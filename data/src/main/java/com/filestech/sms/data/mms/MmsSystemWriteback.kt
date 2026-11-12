package com.filestech.sms.data.mms

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.mms.MmsAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes MMS rows + addresses + parts into the **system content provider**
 * (`content://mms`), so an MMS sent or received through Relay survives a re-install (system rows
 * persist while our local SQLCipher mirror is wiped) and is also visible to other SMS apps
 * the user might switch to later.
 *
 * Background: `SmsManager.sendMultimediaMessage` only dispatches the PDU — it does **not**
 * automatically mirror the outgoing message into `Telephony.Mms`. Most third-party Android
 * SMS apps (including Google Messages on AOSP) write the row themselves. The pattern is:
 *
 *  1. **`insertOutbox(...)`** before dispatch — row appears with `msg_box = OUTBOX (4)`.
 *  2. The OS `MmsService` takes over and runs the MMSC POST.
 *  3. On the result callback (`MmsSentReceiver`), call **`markSent(id)`** (→ `msg_box = SENT (2)`)
 *     or **`delete(id)`** on failure so we don't leave a stale outbox row.
 *
 * Round 16 adds the incoming side: **`insertInbox(...)`** writes a received group MMS
 * into the inbox box and returns the thread Android assigned to it, so group replies
 * are grouped by the system thread instead of by Relay's own reconstruction from the
 * PDU header (which needs our own number — blank on GrapheneOS).
 *
 * Reading + writing `content://mms` requires being the default SMS app. Calls return null /
 * false silently when that's not the case — the caller logs but doesn't crash.
 */
@Singleton
class MmsSystemWriteback @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    private inline fun <T> safe(label: String, block: () -> T): T? =
        runCatching(block).onFailure {
            Timber.w(it, "MmsSystemWriteback.%s failed", label)
        }.getOrNull()

    /**
     * Strips whitespace, dashes and parens so callers passing `"+33 6 12 34 56 78"` and
     * `"+33612345678"` resolve to the same canonical-addresses row inside `getOrCreateThreadId`.
     * Without this, Samsung One UI's `canonical_addresses` table can index the two forms as
     * distinct entries, producing duplicate threads after a reinstall.
     */
    private fun canonicalRecipients(raw: Collection<String>): Set<String> =
        raw.asSequence()
            // v1.2.7 audit S2 : strip d'abord les chars bidi/zero-width (RLO, LRE, BOM…)
            // pour ne pas hit un thread_id distinct côté Samsung canonical_addresses pour le
            // même destinataire visuel — sinon un caller fournissant `"+33‮6 12…"` peut
            // créer une conversation fantôme.
            .map { it.stripInvisibleChars() }
            .map { it.replace(Regex("[\\s\\-()]"), "") }
            .filter { it.isNotBlank() }
            .toHashSet()

    /**
     * Rejects mime types that fail a strict shape check OR attachment files that escape the
     * app's private cache. Both are defense-in-depth — today's callers only stage files under
     * `cache/voice_mms/` or `cache/media_outgoing/` and pick mime types from
     * `ContentResolver.getType`, but a future feature (share-from-other-app, ACTION_SEND) could
     * pass arbitrary input. Without these guards an exotic mime string can crash Samsung's
     * `SemMmsProvider` and a `File("/data/data/<other>/secret")` would be streamed into our row.
     */
    private fun attachmentsAreSafe(attachments: List<MmsAttachment>): Boolean {
        val mimeRegex = Regex("^[a-zA-Z0-9.+/-]{3,80}$")
        val sandboxRoots = listOf(
            runCatching { context.cacheDir.canonicalPath }.getOrNull(),
            runCatching { context.filesDir.canonicalPath }.getOrNull(),
        ).filterNotNull()
        for (a in attachments) {
            if (!mimeRegex.matches(a.mimeType)) {
                Timber.w("MmsSystemWriteback: rejecting suspicious mimeType '%s'", a.mimeType)
                return false
            }
            val canonical = runCatching { a.file.canonicalPath }.getOrNull() ?: run {
                Timber.w("MmsSystemWriteback: cannot canonicalise attachment path '%s'", a.file.path)
                return false
            }
            // v1.27.2 (audit externe Gemini 2026-08-04) — comparaison par SEGMENT, pas par
            // préfixe de chaîne. `startsWith` nu acceptait aussi un dossier FRÈRE dont le nom
            // commence par celui de la racine (« …/cache_autre » passait la garde de
            // « …/cache »). Les deux autres gardes sandbox du dépôt le font déjà correctement
            // — `MmsDownloadedReceiver` via `Path.startsWith`, `OutgoingAttachmentStoreImpl` en
            // concaténant le séparateur ; celle-ci était la seule des trois à comparer des
            // chaînes nues. Non exploitable aujourd'hui (seule l'application peut écrire dans
            // son propre répertoire de données), comme le dit déjà le KDoc : c'est une défense
            // en profondeur, on la rend cohérente avec ses jumelles.
            if (sandboxRoots.none { canonical == it || canonical.startsWith(it + File.separator) }) {
                Timber.w("MmsSystemWriteback: attachment escapes sandbox: %s", canonical)
                return false
            }
        }
        return true
    }

    /**
     * Mirrors an outgoing MMS into `content://mms` *before* dispatch, returning the system
     * `_id` so the receiver can flip it to SENT or delete it on failure. Returns `null` if we
     * are not the default SMS app (provider refuses the insert).
     *
     * **Why** : `SmsManager.sendMultimediaMessage` does not writeback to `content://mms` on
     * Samsung One UI — without this, sent MMS disappear at reinstall and other SMS apps never
     * see them. Inline AOSP conventions in the code:
     *  - date in seconds, msg_box = OUTBOX (4)
     *  - attachment bytes streamed via `openOutputStream` (Android 10+ rejects `_data`)
     *  - text body added as a `text/plain` part alongside binary parts.
     */
    suspend fun insertOutbox(
        recipients: List<String>,
        attachments: List<MmsAttachment>,
        textBody: String?,
    ): Long? = withContext(io) {
        if (recipients.isEmpty()) return@withContext null
        if (!attachmentsAreSafe(attachments)) return@withContext null

        val nowSec = System.currentTimeMillis() / 1000L

        // Resolve (or create) the AOSP thread_id for this recipient set. Without this, the row
        // ends up in a phantom thread separate from the SMS conversation, so after a reinstall
        // our reimport pipeline groups the sent MMS into a new "conversation" duplicate of the
        // existing one. We canonicalise the recipients (strip whitespace/dashes/parens) before
        // resolving so `"+33 6 12 34 56 78"` and `"+33612345678"` hit the same canonical-address
        // row — Samsung One UI's `canonical_addresses` is sensitive to exact string form.
        val systemThreadId: Long = safe("getOrCreateThreadId") {
            Telephony.Threads.getOrCreateThreadId(context, canonicalRecipients(recipients))
        } ?: 0L

        // 1. Insert the main MMS row in /outbox.
        val mmsValues = ContentValues().apply {
            put(Telephony.Mms.DATE, nowSec)
            put(Telephony.Mms.DATE_SENT, nowSec)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, MSG_TYPE_SEND_REQ)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_0)
            put(Telephony.Mms.PRIORITY, PRIORITY_NORMAL)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            if (systemThreadId > 0L) put(Telephony.Mms.THREAD_ID, systemThreadId)
        }
        val mmsUri = safe("insertOutbox.row") {
            resolver.insert(Telephony.Mms.Outbox.CONTENT_URI, mmsValues)
        } ?: return@withContext null
        val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: run {
            Timber.w("MmsSystemWriteback: cannot parse mmsId from %s", mmsUri)
            return@withContext null
        }

        // 2. Insert addresses — each insert in its own safe() so a single failure (e.g. FROM
        // rejected) does not silently skip the remaining TO rows. Without `addr` rows, the MMS
        // shows up in other SMS apps without any recipient label.
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        safe("addr.from") {
            resolver.insert(
                addrUri,
                ContentValues().apply {
                    put("address", "insert-address-token")
                    put("type", ADDR_TYPE_FROM)
                    put("charset", CHARSET_UTF8)
                },
            )
        }
        for (to in recipients) {
            safe("addr.to") {
                resolver.insert(
                    addrUri,
                    ContentValues().apply {
                        put("address", to)
                        put("type", ADDR_TYPE_TO)
                        put("charset", CHARSET_UTF8)
                    },
                )
            }
        }

        // 3. Insert parts. Optional text caption first, then each attachment.
        insertParts(mmsId, textBody, attachments)

        Timber.i("MmsSystemWriteback: inserted outbox row id=%d (%d recipient(s), %d attachment(s))", mmsId, recipients.size, attachments.size)
        mmsId
    }

    /**
     * Round 16 — mirrors an INCOMING group MMS into `content://mms` (inbox box),
     * the way the outgoing path mirrors sent MMS into the outbox, and returns
     * the provider row id together with the thread Android assigned to it.
     *
     * This is the Qik-shaped fix for group replies landing in 1:1 threads:
     * Relay used to keep incoming MMS only in its own database and compute the
     * group thread itself from the PDU header — which needs our own number to
     * exclude it from the recipient set, and that number is blank on
     * GrapheneOS. Writing the message into the system store and reading the
     * thread back off the row lets Android own the grouping, exactly as it
     * does for the sent side (same recipient set, same thread). A second
     * benefit: received group MMS now survive an uninstall like SMS does, and
     * other SMS apps can see them.
     *
     * The thread is computed from [threadRecipients] — the group members with
     * ourselves already excluded when our number is known (the caller passes
     * the list [com.filestech.sms.domain.mms.GroupMmsMembers] built) — via the
     * same canonicalisation as [insertOutbox], so sent and received sides of
     * one group resolve to the same thread. The address rows, by contrast,
     * stay faithful to the PDU: `To` still lists us, as AOSP's own persister
     * writes it. After the insert, the thread is READ BACK from the row
     * rather than trusted from the computation, so whatever thread the
     * provider actually assigned is the one the caller routes on.
     *
     * Returns `null` when we are not the default SMS app (the provider
     * refuses the insert), when the thread cannot be resolved, or on any
     * failure — the caller then falls back to the previous routing. Never
     * throws. The row is written UNREAD (`READ = 0`, `SEEN = 0`); Relay's own
     * unread badge still comes from its Room mirror.
     */
    suspend fun insertInbox(
        sender: String,
        to: List<String>,
        cc: List<String>,
        threadRecipients: Collection<String>,
        attachments: List<MmsAttachment>,
        textBody: String?,
        dateMs: Long,
    ): InboxWrite? = withContext(io) {
        if (sender.isBlank()) return@withContext null
        if (!attachmentsAreSafe(attachments)) return@withContext null

        val systemThreadId: Long = safe("getOrCreateThreadId") {
            Telephony.Threads.getOrCreateThreadId(context, canonicalRecipients(threadRecipients))
        } ?: 0L
        // No thread, no row: a thread-less inbox row would group nowhere and
        // the caller has nothing to route on — it falls back instead.
        if (systemThreadId <= 0L) return@withContext null

        val dateSec = dateMs / 1000L
        val mmsValues = ContentValues().apply {
            put(Telephony.Mms.DATE, dateSec)
            put(Telephony.Mms.DATE_SENT, dateSec)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, MSG_TYPE_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_0)
            put(Telephony.Mms.PRIORITY, PRIORITY_NORMAL)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.THREAD_ID, systemThreadId)
        }
        val mmsUri = safe("insertInbox.row") {
            resolver.insert(Telephony.Mms.Inbox.CONTENT_URI, mmsValues)
        } ?: return@withContext null
        val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: run {
            Timber.w("MmsSystemWriteback: cannot parse mmsId from %s", mmsUri)
            return@withContext null
        }

        // Addresses, faithful to the PDU — each insert in its own safe() so
        // one failure does not skip the rest (same as insertOutbox).
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        safe("addr.from") {
            resolver.insert(
                addrUri,
                ContentValues().apply {
                    put("address", sender)
                    put("type", ADDR_TYPE_FROM)
                    put("charset", CHARSET_UTF8)
                },
            )
        }
        for (recipient in to) {
            safe("addr.to") {
                resolver.insert(
                    addrUri,
                    ContentValues().apply {
                        put("address", recipient)
                        put("type", ADDR_TYPE_TO)
                        put("charset", CHARSET_UTF8)
                    },
                )
            }
        }
        for (recipient in cc) {
            safe("addr.cc") {
                resolver.insert(
                    addrUri,
                    ContentValues().apply {
                        put("address", recipient)
                        put("type", ADDR_TYPE_CC)
                        put("charset", CHARSET_UTF8)
                    },
                )
            }
        }

        insertParts(mmsId, textBody, attachments)

        // Read the thread back off the row itself: whatever Android assigned
        // is the grouping to use. Falls back to the computed thread when the
        // read fails — the row still carries it.
        val threadId = readThreadId(mmsId) ?: systemThreadId
        Timber.i(
            "MmsSystemWriteback: inserted inbox row id=%d thread=%d (%d attachment(s))",
            mmsId,
            threadId,
            attachments.size,
        )
        InboxWrite(mmsId, threadId)
    }

    /** The provider row id and the thread Android assigned to it (see [insertInbox]). */
    data class InboxWrite(val mmsId: Long, val threadId: Long)

    /**
     * Inserts the text caption (first) and the binary parts of an MMS row.
     * Shared by [insertOutbox] and [insertInbox]: same part layout, same
     * streaming (Android 10+ rejects `_data`, so bytes go through
     * `openOutputStream`). Each part insert is its own `safe()` so one bad
     * part does not skip the rest.
     */
    private fun insertParts(mmsId: Long, textBody: String?, attachments: List<MmsAttachment>) {
        val partsUri = Uri.parse("content://mms/$mmsId/part")
        if (!textBody.isNullOrBlank()) {
            safe("part.text") {
                resolver.insert(
                    partsUri,
                    ContentValues().apply {
                        put("mid", mmsId)
                        put("ct", "text/plain")
                        put("chset", CHARSET_UTF8)
                        put("cid", "<text>")
                        put("cl", "text.txt")
                        put("text", textBody)
                    },
                )
            }
        }
        attachments.forEachIndexed { index, att ->
            val partValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", att.mimeType)
                put("cid", "<part$index>")
                put("cl", att.file.name)
                put("name", att.file.name)
            }
            val partUri = safe("part.bin#$index") {
                resolver.insert(partsUri, partValues)
            } ?: return@forEachIndexed
            // Stream the file bytes into the part — `_data` is forbidden on Android 10+.
            // `use { copyTo }` flushes + closes both streams on exit.
            safe("part.stream#$index") {
                resolver.openOutputStream(partUri)?.use { os ->
                    att.file.inputStream().use { it.copyTo(os) }
                }
            }
        }
    }

    /** Reads `THREAD_ID` back off a provider row, or `null` when it cannot be read. */
    private fun readThreadId(mmsId: Long): Long? =
        safe("readThreadId") {
            val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
            resolver.query(uri, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        }

    /** Promotes a previously inserted outbox row to SENT (`msg_box = 2`). */
    suspend fun markSent(mmsSystemId: Long): Boolean = withContext(io) {
        if (mmsSystemId <= 0L) return@withContext false
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsSystemId)
        val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
            put(Telephony.Mms.DATE_SENT, System.currentTimeMillis() / 1000L)
        }
        safe("markSent($mmsSystemId)") {
            resolver.update(uri, values, null, null) > 0
        } ?: false
    }

    /** Deletes the outbox row on dispatch failure. */
    suspend fun delete(mmsSystemId: Long): Boolean = withContext(io) {
        if (mmsSystemId <= 0L) return@withContext false
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsSystemId)
        safe("delete($mmsSystemId)") {
            resolver.delete(uri, null, null) > 0
        } ?: false
    }

    /**
     * v1.2.6 audit F4 — remplace le placeholder `"insert-address-token"` posé par
     * [insertOutbox] sur la row FROM par le vrai MSISDN du SIM, dès qu'on a une valeur
     * fiable (passée par le caller, typiquement le receiver sur `RESULT_OK` après que le
     * système ait potentiellement complété la row). Sur les ROM (Samsung One UI surtout)
     * qui n'écrasent pas le placeholder elles-mêmes, ça évite que la chaîne `addr` de la
     * MMS sortante affiche littéralement `"insert-address-token"` côté autres apps SMS.
     *
     * No-op silencieux si `msisdn` est null/blank, ou si la row n'a plus de FROM placeholder
     * (l'OS l'a déjà remplacé). Pas fatal — la convention AOSP de skipper le token à
     * l'import (voir `TelephonyReader.readMmsAddress`) couvre déjà le cas dégradé.
     */
    suspend fun finalizeFromAddress(mmsSystemId: Long, msisdn: String?): Boolean = withContext(io) {
        if (mmsSystemId <= 0L || msisdn.isNullOrBlank()) return@withContext false
        val addrUri = Uri.parse("content://mms/$mmsSystemId/addr")
        val values = ContentValues().apply { put("address", msisdn) }
        // v1.2.7 audit S1+Q14 : defense-in-depth.
        //  - `AND mid=?` : double sécurité au cas où l'URI scoping serveur-side serait laxiste
        //    sur certaines ROM (l'URI `content://mms/{id}/addr` est censée scoper par mid mais
        //    on ne lui fait pas confiance aveugle).
        //  - Premier essai : on ne touche que les rows portant exactement le placeholder.
        //    Deuxième essai (Free Mobile FR, certaines MVNO) : si l'OS a remplacé le
        //    placeholder par `NULL` au lieu de notre MSISDN, on accepte ce cas dégradé aussi —
        //    la sélection reste bornée à `type=FROM AND mid=this row`, donc on ne risque pas
        //    d'écraser une vraie adresse d'une autre MMS.
        safe("finalizeFromAddress($mmsSystemId)") {
            val updated = resolver.update(
                addrUri,
                values,
                "type=? AND mid=? AND address=?",
                arrayOf(ADDR_TYPE_FROM.toString(), mmsSystemId.toString(), "insert-address-token"),
            )
            if (updated > 0) {
                true
            } else {
                // Fallback : OS a déjà nullé le placeholder. Tenter d'écrire si address IS NULL.
                resolver.update(
                    addrUri,
                    values,
                    "type=? AND mid=? AND address IS NULL",
                    arrayOf(ADDR_TYPE_FROM.toString(), mmsSystemId.toString()),
                ) > 0
            }
        } ?: false
    }

    /**
     * Watchdog: deletes MMS rows **written by Relay** that are stuck in `msg_box = OUTBOX`
     * past [olderThanMs]. Used by [TelephonySyncWorker] to clean up after a [MmsSentReceiver]
     * that never fired (process killed mid-dispatch, force-stop, Doze + reboot). Returns the
     * number of rows deleted, or 0 if we are not default SMS app (provider returns 0 silently).
     *
     * # v1.28.3 (F18) — la suppression exige desormais une PREUVE DE PROPRIETE
     *
     * Le selecteur ne portait que sur `msg_box = OUTBOX` et sur l'age. Il ne demandait jamais
     * qui avait cree la ligne, et supprimait donc **toute** ligne du fournisseur systeme en
     * OUTBOX vieille de plus de quinze minutes — y compris celles d'une autre application :
     * une application constructeur cohabitante, ou celle utilisee avant Relay, dont un MMS
     * avait echoue. Le chien de garde tournait toutes les douze heures.
     *
     * C'est le seul defaut de la relecture externe qui detruise la donnee d'un TIERS, et
     * l'identifiant qui manquait existait deja : `messages.mms_system_id`, que
     * [MmsSentReceiver] consulte precisement pour refuser d'agir sur une ligne qui n'est pas la
     * sienne. La purge etait le seul chemin destructeur du depot a ne pas faire ce controle,
     * alors qu'elle est le plus large.
     *
     * [ownedIds] vient de `MessageDao.ownedMmsSystemIds`. Vide, on ne supprime RIEN : une liste
     * vide signifie « aucune ligne prouvee a nous », pas « tout est a nous ». C'est le sens
     * d'echec qui compte ici — un repli sur l'ancien comportement redonnerait au chien de garde
     * le pouvoir qu'on lui retire.
     *
     * Le decoupage en lots de [SQLITE_MAX_VARIABLES] respecte la limite de parametres de
     * SQLite : un utilisateur ancien peut porter des milliers de MMS, et un `IN (…)` trop long
     * ferait echouer la requete entiere — donc ne purgerait plus rien, en silence.
     */
    suspend fun purgeStaleOutbox(olderThanMs: Long, ownedIds: List<Long>): Int = withContext(io) {
        if (ownedIds.isEmpty()) return@withContext 0
        val cutoffSec = (System.currentTimeMillis() - olderThanMs) / 1000L
        var supprimees = 0
        for (lot in ownedIds.chunked(SQLITE_MAX_VARIABLES)) {
            val (selection, args) = selecteurOutboxPossedee(cutoffSec, lot)
            supprimees += safe("purgeStaleOutbox") {
                resolver.delete(Telephony.Mms.CONTENT_URI, selection, args)
            } ?: 0
        }
        supprimees
    }

    private companion object {
        /**
         * v1.28.3 (F18) — plafond de parametres d'une requete SQLite. La valeur reelle est 999
         * avant SQLite 3.32 et 32766 ensuite ; on s'aligne sur la plus basse, la version
         * embarquee variant selon l'appareil. Depasser ferait echouer la requete ENTIERE, donc
         * ne purgerait plus rien — en silence, le `safe` avalant l'exception.
         */
        const val SQLITE_MAX_VARIABLES: Int = 900

        // PduHeaders constants — kept inline (hidden in the framework, can't import).
        const val MSG_TYPE_SEND_REQ: Int = 128
        const val MSG_TYPE_RETRIEVE_CONF: Int = 132
        const val MMS_VERSION_1_0: Int = 16
        const val PRIORITY_NORMAL: Int = 129
        const val ADDR_TYPE_FROM: Int = 137
        const val ADDR_TYPE_TO: Int = 151
        const val ADDR_TYPE_CC: Int = 130
        const val CHARSET_UTF8: Int = 106
    }
}

/**
 * v1.28.3 (F18) — construit le sélecteur du chien de garde de l'OUTBOX système.
 *
 * Extrait de [MmsSystemWriteback.purgeStaleOutbox] pour être **testable sans appareil**. Le
 * défaut que la relecture externe a trouvé était entièrement dans ce sélecteur : il ne portait
 * que sur `msg_box` et sur la date, donc Relay détruisait les MMS en échec laissés par
 * d'autres applications. La classe, elle, dépend d'un `Context` et exigerait d'être application
 * SMS par défaut pour être exercée — ce qui aurait laissé le défaut sans garde-fou.
 *
 * Le troisième critère, `_id IN (…)`, est celui qui porte la preuve de propriété. Un correctif
 * qui le retirerait ferait tomber `MmsOutboxPurgeSelectorTest`.
 */
internal fun selecteurOutboxPossedee(
    cutoffSec: Long,
    lot: List<Long>,
): Pair<String, Array<String>> {
    require(lot.isNotEmpty()) { "un lot vide ne doit jamais atteindre le selecteur" }
    val placeholders = lot.joinToString(",") { "?" }
    val selection = "${Telephony.Mms.MESSAGE_BOX}=? AND ${Telephony.Mms.DATE}<? AND " +
        "${Telephony.Mms._ID} IN ($placeholders)"
    val args = arrayOf(
        Telephony.Mms.MESSAGE_BOX_OUTBOX.toString(),
        cutoffSec.toString(),
    ) + lot.map { it.toString() }
    return selection to args
}
