package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.core.ext.stripMmsAddressSuffix
import com.filestech.sms.data.categories.StickyCategoryStore
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.media.probePngDimensions
import com.filestech.sms.data.mms.RelayMediaFilename
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.mms.MediaAttachmentSpec
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction.Kind
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point of truth for inserting an incoming/outgoing message into Room.
 * Keeps the conversation row consistent (lastMessageAt, preview, unreadCount).
 */
@Singleton
class ConversationMirror @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val attachmentDao: AttachmentDao,
    private val contacts: ContactRepository,
    // v1.27.2 (audit Codex, C-07) — le rapprochement de conversation exige la region : neuf
    // chiffres ne portent aucune information de pays.
    private val phoneIdentity: PhoneIdentity,
    // v1.28.7 — la fusion de doublons supprime la conversation victime : ses notifications
    // pointaient ensuite vers une conversation qui n'existe plus.
    private val notifications: com.filestech.sms.domain.notification.ConversationNotificationCanceller,
    @IoDispatcher private val io: CoroutineDispatcher,
    // Round 35 — the sticky category store (receive-time filing). Hilt
    // injects the shared @Singleton; the default keeps the existing
    // unit tests constructing the mirror without it (it builds from the
    // mirror's own DAOs, so a test's fake DAOs still back it).
    private val stickyCategories: StickyCategoryStore = StickyCategoryStore(conversationDao, messageDao),
) : OutgoingMessageMirror {

    /**
     * Process-wide cache of `address → displayName` lookups. Avoids re-querying the contacts
     * provider for the same phone number across multiple receive/send paths.
     *
     * **v1.3.7 (F5 audit)** — passé de `ConcurrentHashMap` non borné à [android.util.LruCache]
     * borné à [DISPLAY_NAME_CACHE_MAX] (1000) entries. Sur les comptes très anciens (10 ans+ de
     * SMS = 50 000+ messages tous opérateurs, banque, livraisons, 2FA, démarchage), la map
     * pouvait croître jusqu'à plusieurs milliers d'entrées et conserver ces strings ad vitam,
     * sans plafond. 1000 entrées couvre largement le nombre de correspondants distincts d'un
     * utilisateur normal (les expéditeurs alphanumériques type "Free", "INFO", "ORANGE" sont
     * partagés). L'éviction LRU drop les expéditeurs les plus anciens d'abord — donc les
     * conversations actives gardent toujours leur nom résolu en cache chaud.
     *
     * **Thread-safety.** Ce `@Singleton` est invoqué concurremment depuis des threads
     * broadcast-receiver (`SmsDeliverReceiver`, `MmsDownloadedReceiver`), des workers
     * WorkManager (`TelephonySyncWorker.runSync → bulkImportFromTelephony`), et des coroutines
     * Hilt-scoped (`SendSmsUseCase → upsertOutgoingSms`). [android.util.LruCache] est
     * thread-safe (synchronized interne sur `get` / `put` / `evictAll`) — équivalent au
     * `ConcurrentHashMap` précédent côté garantie atomicity.
     *
     * `LruCache` rejette les valeurs `null`, donc on encode "no name found" comme chaîne vide
     * et on retraduit en `null` dans [resolveDisplayName] — le cache négatif reste tout aussi
     * précieux (skip de la requête contacts au second tour).
     */
    // Lazy so the mirror stays constructible on plain JVM (unit tests):
    // `LruCache`'s constructor throws Stub! outside Android, and the
    // reaction-fold path never touches the display-name cache. On device
    // the cache is created on first use — identical behavior.
    private val displayNameCache by lazy {
        android.util.LruCache<String, String>(DISPLAY_NAME_CACHE_MAX)
    }

    private suspend fun resolveDisplayName(rawAddress: String): String? {
        displayNameCache.get(rawAddress)?.let { return if (it.isEmpty()) null else it }
        // v1.28.5 — une annulation ne doit pas EMPOISONNER le cache negatif d'un singleton : le
        // correspondant s'affichait par son numero jusqu'au prochain `refreshContactNames()`.
        val name = com.filestech.sms.core.result
            .runCatchingCancellable { contacts.lookupByPhone(rawAddress)?.displayName }
            .getOrNull()
        // Empty string is our sentinel for "looked up, no match" — keeps `LruCache`
        // happy (it rejects nulls) without losing the negative-cache behaviour.
        displayNameCache.put(rawAddress, name.orEmpty())
        return name
    }

    /**
     * v1.28.3 (audit global, X-08 — mesuré sur le S9) — **le titre d'un GROUPE est la liste des
     * noms de ses membres**, pas de ses numéros. `findOrCreate` laissait `displayName` à `null`
     * pour un groupe en affirmant que « le rendu UI joint les noms côté présentation » ; il
     * joignait les numéros bruts. Ici comme là, un membre inconnu garde son numéro ; `null` si
     * aucun membre n'est un contact — l'écran retombe alors sur les numéros, comme avant.
     */
    private suspend fun nomDeGroupe(addresses: List<PhoneAddress>): String? =
        com.filestech.sms.domain.model.GroupTitle.of(addresses.map { a -> a.raw to resolveDisplayName(a.raw) })

    suspend fun upsertIncomingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int? = null,
        /**
         * E2E (schema v22) — `true` when the body is the DECRYPTED text of
         * an encrypted message: the row carries `encrypted = 1` (the
         * ciphertext fragments themselves are never inserted as messages).
         * Défaut `false` = SMS en clair, inchangé.
         */
        encrypted: Boolean = false,
    ): Long = withContext(io) {
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = body,
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = false,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 0,
                encrypted = encrypted,
            )
            // v1.26.1 (audit M11) — on distingue une INSERTION RÉELLE d'un conflit ignoré.
            //
            // L'insert est en `IGNORE` sur l'index `UNIQUE(telephony_uri)` et rend `-1` quand la
            // ligne existe déjà — typiquement quand une synchronisation a miroité le même SMS
            // depuis `content://sms` entre l'écriture système du receveur et cet appel. Le retour
            // était ignoré, avec deux effets : le compteur de non-lus était incrémenté une
            // SECONDE fois pour un seul message, et le receveur, ne retrouvant pas `-1` en base,
            // abandonnait la notification — le SMS arrivait sans être signalé.
            //
            // Le chemin en MASSE avait été corrigé pour ce motif exact en v1.8.0 ; le chemin
            // unitaire ne l'avait pas été.
            val insertedId = messageDao.insert(msg)
            if (insertedId > 0L) {
                // Round 35 — sticky filing from the FULL body, BEFORE the
                // touch: the store's newest-wins guard compares against
                // the pre-touch `lastMessageAt`.
                stickyCategories.onIncomingMessage(convId, body, PhoneAddress.of(address), date)
                touchConversation(convId, date, body, deltaUnread = +1)
                insertedId
            } else {
                // Conflit : on rend l'id de la ligne DÉJÀ présente pour que l'appelant puisse
                // quand même notifier, et on ne touche pas au compteur.
                telephonyUri?.let { messageDao.findByTelephonyUri(it)?.id } ?: -1L
            }
        }
    }

    /**
     * Round 49 — the minus-one group lookup for encrypted groups:
     * [memberAddresses] is the FULL member list (canonical
     * addresses, including ourselves — from a sender-key row), and
     * the stored group holds exactly one member fewer (ourselves
     * excluded). Returns the conversation id, or null when no
     * stored group matches. NEVER creates: a stray distribution
     * or an undecryptable message from an unknown group must not
     * conjure a thread. See [rapprocherGroupeMoinsUn] (the
     * group-MMS path's twin of this rule).
     */
    suspend fun findGroupConversationId(memberAddresses: List<PhoneAddress>): Long? =
        withContext(io) {
            rapprocherGroupeMoinsUn(memberAddresses)?.id
        }

    /**
     * Round 49 — find-or-create for an encrypted group:
     * [threadAddresses] are the thread's addresses (ourselves
     * EXCLUDED — exactly what `Conversation.addresses` holds).
     * The exact group match finds the stored conversation; when
     * none exists, the conversation is created (a first encrypted
     * group message from a group Relay has never seen still needs
     * a thread to land in).
     */
    suspend fun ensureGroupConversation(threadAddresses: List<PhoneAddress>): Long =
        withContext(io) {
            database.withTransaction {
                ensureConversation(threadAddresses)
            }
        }

    /**
     * Round 49 — inserts a decrypted (or undecryptable —
     * placeholder, empty body) group SMS into a KNOWN group
     * conversation. Same row shape as [upsertIncomingSms]
     * (IGNORE insert, unread touch, conflict returns the existing
     * id), but the conversation is the caller's — the group
     * session manager resolved it from the sender-key row's
     * member list, so no address-based resolution happens here.
     * The sender is the message's `address` (the member who sent
     * it), not the conversation's address list.
     */
    suspend fun upsertIncomingGroupSms(
        conversationId: Long,
        senderAddress: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int? = null,
        encrypted: Boolean = false,
    ): Long = withContext(io) {
        database.withTransaction {
            val msg = MessageEntity(
                conversationId = conversationId,
                telephonyUri = telephonyUri,
                address = senderAddress,
                body = body,
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = false,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 0,
                encrypted = encrypted,
            )
            val insertedId = messageDao.insert(msg)
            if (insertedId > 0L) {
                stickyCategories.onIncomingMessage(
                    conversationId,
                    body,
                    PhoneAddress.of(senderAddress),
                    date,
                )
                touchConversation(conversationId, date, body, deltaUnread = +1)
                insertedId
            } else {
                telephonyUri?.let { messageDao.findByTelephonyUri(it)?.id } ?: -1L
            }
        }
    }

    /**
     * v1.4.1 (SEC-01) — drops a "poison-pill" Room row carrying the system [telephonyUri]
     * of an SMS that was already folded into a reaction badge by
     * [applyIncomingReaction] — an incoming tapback, or our own outgoing
     * tapback imported from the sent box. Without this sentinel,
     * [TelephonySyncManager] would later re-import the very same
     * `Reacted ❤️ to «…»` body from `content://sms` as a regular
     * bubble — duplicating the user-visible event (one badge + one phantom text
     * bubble).
     *
     * The sentinel is intentionally invisible :
     *   - `body = ""`        → renders no text in the thread list / bubble
     *   - `read = true`      → never bumps the unread count
     *   - `reaction_emoji = null` → does not paint an extra badge anywhere
     *   - same `telephonyUri` as the system row → unique index in [MessageEntity] makes
     *     the future [TelephonySyncManager] insert a no-op (`OnConflictStrategy.IGNORE`).
     *
     * Idempotent : a second call with the same `telephonyUri` is silently ignored at the
     * DAO level. Safe to call after a successful [applyIncomingReaction] even if the user
     * resends the same reaction (rare carrier replay).
     */
    suspend fun upsertReactionSentinel(
        address: String,
        telephonyUri: String?,
        date: Long,
    ) = withContext(io) {
        if (telephonyUri.isNullOrBlank()) return@withContext
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val sentinel = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = "",
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = true,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = null,
                scheduledAt = null,
                attachmentsCount = 0,
                // v1.28.4 — la sentinelle se DÉCLARE, elle n'est plus devinée à sa forme.
                hidden = true,
            )
            // `OnConflictStrategy.IGNORE` on the insert means a second call with the same
            // telephonyUri is a no-op — idempotent.
            messageDao.insert(sentinel)
            // NB: the sentinel itself never calls `touchConversation` — its
            // body is empty, so bumping here would surface an empty preview.
            // The bump comes from the fold ([applyIncomingReaction]), which
            // touches with the reaction's date and the emoji as preview.
        }
    }

    /**
     * Tries to apply an incoming reaction back to a message in the thread with
     * [address]. Returns the applied target, or `null` when no match exists
     * (caller then falls back to inserting the body as a plain incoming SMS).
     *
     * Lookup strategy depends on [kind] :
     *   - [com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction
     *     .Kind.Tapback] — verbose `Reacted <emoji> [to «…»]`, Apple tapbacks and
     *     Google Messages framing. The target can be ANY message in the thread,
     *     incoming or outgoing: in a group thread people react to each other's
     *     texts, and on iPhone you can react to your own. Match either by body
     *     prefix (when [bodyPrefix] is non-null) or by "most recent message"
     *     (when the remote reacted to a message with no text body, voice / image).
     *   - Own reactions ([isOwnReaction]) — our own outgoing tapback, imported
     *     from the sent box or found by the reparse: the target is one of
     *     THEIR incoming messages FIRST (a preview that also matches a
     *     message we sent must not win); only when no incoming message
     *     matches do our own outgoing messages become candidates — our own
     *     reactions now send for our own texts too, so the sent-box tapback
     *     must fold onto our message or it would surface as a stray bubble.
     *     The conversation is never bumped — reacting is our own action,
     *     not news.
     *   - [com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction
     *     .Kind.EmojiOnly] — bare emoji SMS, ambiguous with a real one-emoji message.
     *     The match requires an outgoing message strictly younger than
     *     [IncomingReactionDecoder.EMOJI_ONLY_REACT_WINDOW_MS]. If none exists, we
     *     give up and the caller stores the body as a regular incoming SMS. This
     *     path stays outgoing-only on purpose: a bare "❤️" must never be folded
     *     onto a message the sender received.
     *
     * When [isRemoval] is true, the target's badge is cleared (only if it
     * currently shows the same emoji — the single-emoji storage cannot tell
     * whose reaction it is) instead of being set. The caller still gets a
     * non-null result when a target was found, so the removal text is
     * suppressed as a bubble even when the badge itself was untouched.
     *
     * The conversation is resolved via exact-CSV match, then the identity
     * fallback (covers the `+33` vs `06` representation drift), then — for a
     * reaction sent by a group participant — any group conversation that
     * contains the sender, most recently active first. A target match is
     * what disambiguates between groups sharing that sender. We do NOT call
     * [ensureConversation] here — a missing conversation is a fast-fail.
     *
     * [reactionDateMs] is the timestamp of the reaction SMS itself ; a target
     * must be dated at most [REACTION_DATE_TOLERANCE_MS] after it (MMS
     * ordering can skew dates). `null` disables the check.
     *
     * On an ADD, the conversation is bumped: [touchConversation] with the
     * reaction's own date and the emoji as preview, so the thread surfaces
     * under "today" instead of being missed (a reaction is activity, not a
     * silent metadata edit). No unread bump — a reaction is not a message
     * waiting to be read. A removal never bumps: clearing a badge is silent.
     * An own reaction never bumps either (see [isOwnReaction] above).
     */
    /**
     * v1.4.1 — outcome of [applyIncomingReaction]. `null` = no matching
     * message was found (caller falls back to inserting the body as a plain incoming
     * SMS). Non-null carries the `conversationId`, the `targetMessageId` (Room id of
     * the message the reaction was glued onto) and the body of that message
     * — all three are consumed by the receiver to post a "<sender> a réagi ❤️ : …"
     * system notification (v1.6.1).
     *
     * `targetMessageId` is required by
     * [com.filestech.sms.system.notifications.IncomingMessageNotifier.notifyIncoming]
     * to derive a stable, non-colliding notification id (one notif per reacted
     * message) and to deep-link the user back to the precise message on tap.
     */
    data class ReactionApplied(
        val conversationId: Long,
        val targetMessageId: Long,
        val targetBody: String,
    )

    suspend fun applyIncomingReaction(
        address: String,
        emoji: String,
        bodyPrefix: String?,
        kind: Kind,
        /**
         * v1.6.2 — `true` quand le wire Tapback contenait le marker `…` (encoder a
         * tronqué). Détermine la stratégie de match :
         *   - `false` (body court non tronqué) → match EXACT après normalisation des
         *     whitespace. Résout l'ambiguïté "Hello" vs "Hello world" : avant on
         *     prenait toujours le plus récent (FAUX si l'utilisateur a réagi à un
         *     ancien message court), maintenant on prend celui dont le body normalisé
         *     EQUALS le prefix.
         *   - `true` (body long, prefix seul connu) → match `STARTS WITH` (l'unique
         *     stratégie possible quand on n'a que les 47 premiers chars). Ambiguïté
         *     résiduelle inhérente au protocole SMS-based Tapback.
         */
        wasTruncated: Boolean = false,
        /** `true` when the remote user removed a reaction: clear the badge. */
        isRemoval: Boolean = false,
        /** Timestamp of the reaction SMS itself, for the date-tolerance check. */
        reactionDateMs: Long? = null,
        /**
         * Feedback round 7 — Room id of a message that must never be picked as
         * the target. Only the retroactive reparse passes it (the reaction row
         * itself, which already sits in Room — see [reparseStoredReactions]).
         * The live receiver leaves it `null`: the reaction row does not exist
         * yet when it folds.
         */
        excludeMessageId: Long? = null,
        /**
         * `true` when the reaction text is OUR OWN outgoing tapback (an
         * imported sent-box row, or a stored outgoing row the reparse
         * found) rather than one received from the correspondent. The badge
         * still lands on the target — one of THEIR incoming messages — but
         * the conversation is never bumped. The live receiver never passes
         * this: it only ever sees incoming SMS.
         */
        isOwnReaction: Boolean = false,
    ): ReactionApplied? = withContext(io) {
        val addr = PhoneAddress.of(address)
        if (addr.normalized.isEmpty()) return@withContext null
        // Avoid creating an empty conversation just to lookup against it — only
        // proceed if a conversation for this address already exists.
        val csv = addr.raw
        // v1.26.1 (audit H13, par cohérence) — même clé que les deux replis d'import : à 8
        // chiffres, une réaction reçue de `0612345678` pouvait se poser sur la conversation de
        // `0712345678`. Laisser ce site sur l'ancienne clé aurait recréé l'asymétrie même que
        // l'audit condamne.
        // v1.27.2 (audit Codex du 2026-08-05, P-11) — même règle qu'aux trois autres replis :
        // `blockKey` seul ferait poser une réaction reçue de `+1 561 234 5678` sur la conversation
        // de `+33 6 12 34 56 78`.
        val ident = phoneIdentity.snapshot()
        val oneToOne = conversationDao.findByAddressesCsv(csv)
            ?: conversationDao.snapshotOneToOneConversations().firstOrNull { conv ->
                val convRaw = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw
                convRaw != null && ident.matches(convRaw, addr.raw)
            }
        // Group threads: the sender is one participant among several. Any group
        // containing them is a candidate; the quoted text disambiguates between
        // groups sharing that sender. Most recently active first.
        val groups = conversationDao.snapshotGroupConversations()
            .filter { conv ->
                PhoneAddress.list(conv.addressesCsv).any { member ->
                    ident.matches(member.raw, addr.raw)
                }
            }
            .sortedByDescending { it.lastMessageAt }
        val candidates = listOfNotNull(oneToOne) + groups
        if (candidates.isEmpty()) return@withContext null
        val maxDateMs = reactionDateMs?.let { it + REACTION_DATE_TOLERANCE_MS }
        for (conv in candidates) {
            val target = findReactionTarget(
                conversationId = conv.id,
                bodyPrefix = bodyPrefix,
                kind = kind,
                wasTruncated = wasTruncated,
                maxDateMs = maxDateMs,
                excludeMessageId = excludeMessageId,
                isOwnReaction = isOwnReaction,
            ) ?: continue
            if (isRemoval) {
                // Single-emoji storage cannot tell whose reaction the badge
                // shows: only clear it when it is the emoji being removed.
                if (target.reactionEmoji == emoji) {
                    messageDao.setReaction(target.id, null)
                }
            } else {
                messageDao.setReaction(target.id, emoji)
                // Own reactions never bump: reacting is our own action,
                // not news — and the live outgoing path never bumps either.
                if (!isOwnReaction) {
                    touchForReaction(
                        conversationId = conv.id,
                        reactorAddress = address,
                        emoji = emoji,
                        dateMs = reactionDateMs ?: System.currentTimeMillis(),
                    )
                }
            }
            return@withContext ReactionApplied(
                conversationId = conv.id,
                targetMessageId = target.id,
                targetBody = target.body,
            )
        }
        return@withContext null
    }

    /**
     * Bumps a conversation for an incoming reaction ADD: date = the reaction
     * SMS's own timestamp, preview = the emoji — in a group, prefixed with
     * the reactor's display name ("Marie ❤️") so the row says who reacted.
     * No unread bump: a reaction is not a message waiting to be read.
     *
     * Relies on [touchConversation]'s newest-wins guard: when a newer real
     * message already sits in the thread, the reaction keeps the date but
     * does not overwrite its preview.
     */
    private suspend fun touchForReaction(
        conversationId: Long,
        reactorAddress: String,
        emoji: String,
        dateMs: Long,
    ) {
        val conv = conversationDao.findById(conversationId) ?: return
        val isGroup = PhoneAddress.list(conv.addressesCsv).size > 1
        val preview = if (isGroup) {
            val name = resolveDisplayName(reactorAddress)
            if (name != null) "$name $emoji" else emoji
        } else {
            emoji
        }
        touchConversation(conversationId, dateMs, preview, deltaUnread = 0)
    }

    /**
     * Feedback round 7 — one-time retroactive fold of reaction texts that
     * arrived BEFORE this build learned to fold them live (or before Relay
     * became the default SMS app and started importing them at all). Old
     * `Reacted ❤️ to «…»` / iOS tapback / French readable rows sitting in Room
     * as plain text bubbles get their badge glued onto the original message,
     * then the text row is replaced by the same sentinel the live path writes
     * ([upsertReactionSentinel]) so the next sync sweep does not re-import the
     * carrier text as a bubble.
     *
     * Runs once per reparse version (gated by
     * `AdvancedSettings.reactionReparseVersion`, set by the caller), on IO,
     * in the background — the user just sees old reaction texts turn into
     * badges. Version 2 added the outgoing fold below, so installs that
     * already ran version 1 run once more.
     *
     * Outgoing rows — our own reactions, imported from the sent box — fold
     * onto their target too (one of THEIR incoming messages first, then one
     * of OUR outgoing messages when no incoming matches — a reaction to one
     * of our own texts), with no conversation bump and no notification: the
     * reaction is our own action, not news. A removal clears our badge the
     * same way.
     *
     * Hard limits (by design, not bugs):
     *   - [Kind.EmojiOnly] is SKIPPED: its 5-minute window is anchored at
     *     `System.currentTimeMillis()` in [findReactionTarget], so a bare-emoji
     *     row from last month would mis-attach to a message sent minutes ago.
     *     A bare "❤️" stays a text bubble — the safe choice.
     *   - Google Messages reactions are UNRECOVERABLE: the receiver strips
     *     the U+200B framing before storing, so the stored body carries no
     *     signal at all. Only Tapback / iOS / French / emoji+quote formats
     *     survive in stored bodies.
     *   - A row with no `telephonyUri` keeps its text (badge still set): with
     *     no sentinel to write, deleting it would let the next sync sweep
     *     re-import the carrier text as a bubble.
     *
     * Crash-safety: the sentinel is written BEFORE the delete, each in its
     * own transaction. A crash between the two leaves sentinel + text row;
     * the next run (flag not yet set) folds it again idempotently. Deleting
     * first would risk the reverse — text gone, no sentinel, phantom bubble
     * back on the next sync.
     */
    data class ReactionReparseResult(
        val scanned: Int,
        val folded: Int,
        val skipped: Int,
        val normalized: Int = 0,
    )

    suspend fun reparseStoredReactions(): ReactionReparseResult = withContext(io) {
        var scanned = 0
        var folded = 0
        var skipped = 0
        for (row in messageDao.findReactionCandidates()) {
            scanned++
            val decoded = IncomingReactionDecoder.decode(row.body)
            if (decoded == null || decoded.kind == Kind.EmojiOnly) {
                skipped++
                continue
            }
            val applied = try {
                applyIncomingReaction(
                    address = row.address,
                    emoji = decoded.emoji,
                    bodyPrefix = decoded.previewPrefix,
                    kind = decoded.kind,
                    wasTruncated = decoded.wasTruncated,
                    isRemoval = decoded.isRemoval,
                    reactionDateMs = row.date,
                    // The reaction row itself already sits in Room: without
                    // this, a no-preview reaction ("Reacted ❤️" to a photo)
                    // would match ITSELF as "most recent message", get a badge,
                    // then be deleted — the reaction lost entirely.
                    excludeMessageId = row.id,
                    isOwnReaction = row.direction == MessageDirection.OUTGOING,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "reparseStoredReactions: fold failed for message #${row.id}")
                null
            }
            if (applied == null) {
                skipped++
                continue
            }
            if (row.telephonyUri.isNullOrBlank()) {
                Timber.w(
                    "reparseStoredReactions: message #${row.id} has no telephonyUri — " +
                        "badge set but text row kept (no sentinel possible)",
                )
                skipped++
                continue
            }
            // Sentinel FIRST (own transaction), then delete + preview refresh:
            // see the crash-safety note on this function.
            upsertReactionSentinel(
                address = row.address,
                telephonyUri = row.telephonyUri,
                date = row.date,
            )
            database.withTransaction {
                messageDao.delete(row.id)
                // The deleted row may have been the conversation's preview —
                // recompute it from the remaining messages.
                messageDao.refreshConversationPreview(applied.conversationId)
            }
            // The refresh above recomputes the row from the remaining
            // messages and wipes the bump [applyIncomingReaction] just wrote
            // — re-apply it AFTER the refresh (adds only; a removal stays
            // silent). The newest-wins guard in [touchConversation] keeps a
            // newer real message's preview when one exists. Own reactions
            // never bump (their fold skipped the touch on purpose), so the
            // re-apply skips them too — otherwise the refresh would be the
            // only place an own reaction bumped the thread.
            if (!decoded.isRemoval && row.direction != MessageDirection.OUTGOING) {
                touchForReaction(
                    conversationId = applied.conversationId,
                    reactorAddress = row.address,
                    emoji = decoded.emoji,
                    dateMs = row.date,
                )
            }
            folded++
        }
        // v3 — normalize legacy multi-emoji reactions: older builds could
        // store several emojis in one reaction ("👍😂😅"); Relay's model is
        // one emoji per message, so keep the first cluster and drop the
        // rest. Idempotent: single-emoji values are untouched, so a
        // re-run (or a v3 install that already ran it) changes nothing.
        var normalized = 0
        for (row in messageDao.findMessagesWithReactions()) {
            val emoji = row.reactionEmoji ?: continue
            val first = IncomingReactionDecoder.firstGraphemeCluster(emoji)
            if (first != emoji) {
                messageDao.setReaction(row.id, first)
                normalized++
            }
        }
        Timber.i(
            "reparseStoredReactions: done — scanned=%d folded=%d skipped=%d normalized=%d",
            scanned,
            folded,
            skipped,
            normalized,
        )
        ReactionReparseResult(
            scanned = scanned,
            folded = folded,
            skipped = skipped,
            normalized = normalized,
        )
    }

    /**
     * Finds the message a reaction targets inside one conversation, most
     * recent first. Tapback kinds match ANY message in the thread (incoming
     * or outgoing); the emoji-only path keeps its outgoing-only, 5-minute
     * window rule. Candidates dated after [maxDateMs] are skipped (a
     * reaction cannot target a message sent after it). For an own reaction
     * ([isOwnReaction]) the target must be one of THEIR incoming messages
     * — a preview that also matches one of ours must never win.
     */
    private suspend fun findReactionTarget(
        conversationId: Long,
        bodyPrefix: String?,
        kind: Kind,
        wasTruncated: Boolean,
        maxDateMs: Long?,
        excludeMessageId: Long? = null,
        isOwnReaction: Boolean = false,
    ): MessageEntity? {
        fun withinTolerance(entity: MessageEntity): Boolean =
            maxDateMs == null || entity.date <= maxDateMs

        // Feedback round 7 — the retroactive reparse passes the reaction row's
        // own id so it can never be picked as its own target (it already sits
        // in Room, unlike in the live path). `null` (live path) excludes
        // nothing: `id != null` is always true.
        fun notExcluded(entity: MessageEntity): Boolean =
            entity.id != excludeMessageId

        // Own tapback: the target is one of THEIR incoming messages FIRST —
        // a preview that also matches a message we sent must not win. Only
        // when no incoming message matches do our own outgoing messages
        // become candidates (second pass, `allowOutgoingTargets`): our own
        // reactions now send for our own texts too, so the sent-box tapback
        // `Liked "our text"` must fold onto our message on re-import, or it
        // would surface as a stray bubble. Incoming tapbacks
        // (`isOwnReaction = false`) match any direction on the first pass.
        var allowOutgoingTargets = false
        fun directionOk(entity: MessageEntity): Boolean =
            !isOwnReaction || allowOutgoingTargets ||
                entity.direction == MessageDirection.INCOMING

        // Local (not a member): this class is at its Detekt function-count
        // limit. The two passes share the search; only the direction guard
        // changes between them.
        suspend fun search(): MessageEntity? = when (kind) {
            Kind.Tapback -> {
                if (bodyPrefix != null) {
                    val normalizedPrefix = bodyPrefix.collapseWhitespace()
                    if (!wasTruncated) {
                        // v1.6.2 — match EXACT (body normalisé == prefix). Élimine
                        // l'ambiguïté quand plusieurs messages partagent un préfixe
                        // court ("Hello" ≠ "Hello world"). Itère le plus récent en
                        // premier ; comme la liste DAO est ORDER BY date DESC, le
                        // firstOrNull retourne le plus récent EXACT match — en pratique
                        // le seul, sauf duplicate identique rare.
                        messageDao.findRecentForConversation(
                            conversationId = conversationId,
                            limit = TAPBACK_FALLBACK_LOOKUP_LIMIT,
                        ).firstOrNull { entity ->
                            notExcluded(entity) &&
                                withinTolerance(entity) &&
                                directionOk(entity) &&
                                entity.body.collapseWhitespace() == normalizedPrefix
                        }
                    } else {
                        // v1.4.1 / v1.6.2 — body tronqué : match préfixe inévitable.
                        // SQL LIKE rapide d'abord (cas mono-ligne), fallback Kotlin
                        // si le body contient des newlines (l'encoder normalise les
                        // whitespace mais pas le DAO).
                        val escaped = escapeForSqlLike(bodyPrefix)
                        messageDao.findMostRecentByBodyPrefix(conversationId, escaped)
                            ?.takeIf { notExcluded(it) && withinTolerance(it) && directionOk(it) }
                            ?: messageDao.findRecentForConversation(
                                conversationId = conversationId,
                                limit = TAPBACK_FALLBACK_LOOKUP_LIMIT,
                            ).firstOrNull { entity ->
                                notExcluded(entity) &&
                                    withinTolerance(entity) &&
                                    directionOk(entity) &&
                                    entity.body.collapseWhitespace()
                                        .startsWith(normalizedPrefix)
                            }
                    }
                } else {
                    if (isOwnReaction || excludeMessageId != null) {
                        // Own reaction, or the reparse: `findMostRecent`
                        // could return one of my own messages, or the
                        // reaction row itself when it is the latest message.
                        // Scan the recent window instead and let the guards
                        // pick — the direction guard for an own reaction,
                        // the exclusion for the reparse.
                        messageDao.findRecentForConversation(
                            conversationId = conversationId,
                            limit = TAPBACK_FALLBACK_LOOKUP_LIMIT,
                        ).firstOrNull { entity ->
                            notExcluded(entity) && withinTolerance(entity) &&
                                directionOk(entity)
                        }
                    } else {
                        messageDao.findMostRecent(conversationId)
                            ?.takeIf { withinTolerance(it) }
                    }
                }
            }
            Kind.EmojiOnly -> {
                val sinceMs = System.currentTimeMillis() -
                    IncomingReactionDecoder.EMOJI_ONLY_REACT_WINDOW_MS
                messageDao.findMostRecentOutgoingAfter(conversationId, sinceMs)
            }
        }
        search()?.let { return it }
        // Own tapback with no incoming match: retry allowing our own
        // outgoing messages (a reaction to one of our own texts). The
        // EmojiOnly branch ignores the direction guard entirely, so a
        // second pass would return the same answer — Tapback only.
        if (!isOwnReaction || kind != Kind.Tapback) return null
        allowOutgoingTargets = true
        return search()
    }

    /**
     * Escapes the three SQL LIKE wildcards (`%`, `_`, `\`) so a body containing them
     * is matched literally. Mirrors the `ESCAPE '\'` clause in the DAO query.
     */
    private fun escapeForSqlLike(input: String): String =
        input
            .replace("""\""", """\\""")
            .replace("""%""", """\%""")
            .replace("""_""", """\_""")

    /**
     * v1.6.2 — collapse de tout whitespace (espaces multiples, tabs, newlines `\n` `\r`,
     * U+2028, U+2029, etc.) en un espace simple + trim. Utilisé pour le fallback de fold
     * Tapback : l'encoder normalise déjà côté envoyeur, on doit le faire aussi côté
     * receveur pour comparer le previewPrefix au body OUTGOING d'origine.
     */
    private fun String.collapseWhitespace(): String =
        this.replace(Regex("\\s+"), " ").trim()

    // v1.6.2 — la constante TAPBACK_FALLBACK_LOOKUP_LIMIT vit dans le companion
    // principal en bas du fichier (Kotlin n'accepte qu'un seul companion par classe).

    override suspend fun upsertOutgoingSms(
        address: String,
        body: String,
        date: Long,
        telephonyUri: String?,
        subId: Int?,
        // v1.16.0 — Type sécurisé (était Int). Default PENDING vit sur le port OutgoingMessageMirror.
        initialStatus: MessageStatus,
        replyToMessageId: Long?,
        /**
         * v1.4.1 — when non-null, overrides what gets stored in the Room row's `body`
         * column (the on-wire SMS body sent via `SmsManager` and mirrored to the
         * system inbox remains [body], unchanged). Used by [com.filestech.sms.domain
         * .usecase.SendReactionUseCase] to silently send a Tapback to the
         * correspondent while keeping its own thread free of a redundant outgoing
         * bubble — the empty `localMirrorBody = ""` row is filtered out at the DAO
         * query level (`observeForConversation` excludes body=''+0 attach+0 reaction).
         * Default `null` = mirror the wire body as-is (regular text SMS).
         */
        localMirrorBody: String?,
        // Slice 3 — clé d'idempotence d'un envoi fait depuis un appareil lié, écrite sur la
        // ligne AVANT la remise au radio (c'est ce qui rend la dédup durable).
        clientMessageId: String?,
        /**
         * E2E (schema v22) — `true` quand le SMS part chiffré : la ligne
         * miroir porte le clair avec `encrypted = 1`. (No default here:
         * an override may not declare one — the interface provides it.)
         */
        encrypted: Boolean,
    ): Long = withContext(io) {
        val mirrorBody = localMirrorBody ?: body
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = mirrorBody,
                type = MessageType.SMS,
                direction = MessageDirection.OUTGOING,
                date = date,
                dateSent = null,
                read = true,
                starred = false,
                status = initialStatus,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 0,
                replyToMessageId = replyToMessageId,
                clientMessageId = clientMessageId,
                encrypted = encrypted,
                // v1.28.4 — la sentinelle de réaction sortante se DÉCLARE, elle n'est plus devinée.
                hidden = localMirrorBody != null && localMirrorBody.isEmpty(),
            )
            val msgId = messageDao.insert(msg)
            // v1.4.1 — when the row is a hidden reaction sentinel (mirrorBody=""),
            // we don't bump `lastMessageAt` / `lastMessagePreview` either, otherwise
            // the conversation list would show a blank preview line and the wrong
            // sort order (sentinel timestamp masking the real last message). For
            // regular SMS, [touchConversation] behaviour is unchanged.
            if (mirrorBody.isNotEmpty()) {
                touchConversation(convId, date, mirrorBody, deltaUnread = 0, resetUnread = true)
            }
            msgId
        }
    }

    // v1.16.0 — Paramètre `status` typé MessageStatus (était Int) — propagation depuis le DAO.
    override suspend fun updateOutgoingStatus(
        localId: Long,
        status: MessageStatus,
        errorCode: Int?,
        attempt: Int?,
    ): Boolean = withContext(io) {
        // v1.26.1 (audit M8) — promotion monotone : le statut d'un envoi ne peut que progresser.
        // Voir [MessageDao.promoteStatusMonotonic] : c'est ce qui empêche l'accusé positif d'une
        // partie d'écraser l'échec d'une autre sur un SMS multi-parties.
        //
        // v1.28.3 (F23) — et `attempt` empêche l'accusé d'une tentative PRÉCÉDENTE de s'appliquer
        // à la ligne relancée, que la rétrogradation vient de ramener au bas de cette échelle.
        messageDao.promoteStatusMonotonic(localId, status, status.rawValue, errorCode, attempt) > 0
    }

    /** v1.28.3 (F05) — voir [OutgoingMessageMirror.outgoingStatus]. */
    override suspend fun outgoingStatus(localId: Long): MessageStatus? = withContext(io) {
        messageDao.findById(localId)?.status
    }

    /** v1.26.1 (audit M8), v1.28.3 (F23) — voir [OutgoingMessageMirror.resetOutgoingForRetry]. */
    override suspend fun resetOutgoingForRetry(localId: Long): Int? = withContext(io) {
        messageDao.openNextSendAttempt(localId)
        messageDao.sendAttemptOf(localId)
    }

    /**
     * Inserts an outgoing MMS row + its single audio attachment in one transaction. Returns the
     * message id so the caller (MmsSender) can correlate the dispatch result.
     *
     * The audio file is referenced by its absolute path (recorded into the app's private cache
     * by [com.filestech.sms.data.voice.VoiceRecorder]); ownership transfers to the row so the
     * file persists for the lifetime of the message (cascade-deleted with it).
     *
     * Preview/body: emoji + duration label so the conversation list shows something meaningful
     * without having to fetch the attachment table.
     */
    override suspend fun upsertOutgoingMms(
        address: String,
        audioFile: File,
        mimeType: String,
        durationMs: Long,
        date: Long,
        subId: Int?,
        encrypted: Boolean,
    ): Long = withContext(io) {
        val durationLabel = formatDurationLabel(durationMs)
        val preview = "🎤 $durationLabel"
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = null,
                address = address,
                body = preview,
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = date,
                dateSent = null,
                read = true,
                starred = false,
                status = MessageStatus.PENDING,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = 1,
                encrypted = encrypted,
            )
            val msgId = messageDao.insert(msg)
            attachmentDao.insert(
                AttachmentEntity(
                    messageId = msgId,
                    mimeType = mimeType,
                    fileName = audioFile.name,
                    sizeBytes = audioFile.length(),
                    localUri = audioFile.absolutePath,
                    width = null,
                    height = null,
                    durationMs = durationMs,
                ),
            )
            touchConversation(convId, date, preview, deltaUnread = 0, resetUnread = true)
            msgId
        }
    }

    /**
     * Generic outgoing-MMS mirror for **non-voice** attachments (photo, video, PDF, contact card,
     * arbitrary file). Same transaction guarantees as [upsertOutgoingMms] but accepts a list of
     * [MediaAttachmentSpec] so a single multipart MMS surfaces as **one** message row in the UI
     * with N attachments — matching what `bulkImportMmsFromTelephony` does on the import side.
     *
     * The preview line is the user's text body if any, otherwise an emoji + filename fallback so
     * the conversation list still shows something meaningful.
     */
    override suspend fun upsertOutgoingMediaMms(
        address: String,
        attachments: List<MediaAttachmentSpec>,
        textBody: String,
        date: Long,
        subId: Int?,
        clientMessageId: String?,
        encrypted: Boolean,
    ): Long = withContext(io) {
        require(attachments.isNotEmpty()) { "upsertOutgoingMediaMms requires at least one attachment" }
        // v1.3.10 — `messages.body` stores the **user caption verbatim** (possibly empty), so
        // MediaAttachmentBubble can decide whether to render a caption line below the icon.
        // `preview` is only used to keep the conversation list informative ("📎 file.pdf"
        // when the user sent the file without a caption).
        val storedBody = textBody.trim()
        val preview = storedBody.ifBlank { mediaPreviewLabel(attachments.first()) }
        database.withTransaction {
            val convId = ensureConversation(listOf(PhoneAddress.of(address)))
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = null,
                address = address,
                body = storedBody,
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = date,
                dateSent = null,
                read = true,
                starred = false,
                status = MessageStatus.PENDING,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = attachments.size,
                clientMessageId = clientMessageId,
                encrypted = encrypted,
            )
            val msgId = messageDao.insert(msg)
            for (a in attachments) {
                attachmentDao.insert(
                    AttachmentEntity(
                        messageId = msgId,
                        mimeType = a.mimeType,
                        fileName = a.file.name,
                        sizeBytes = a.file.length(),
                        localUri = a.file.absolutePath,
                        width = a.width,
                        height = a.height,
                        durationMs = a.durationMs,
                    ),
                )
            }
            touchConversation(convId, date, preview, deltaUnread = 0, resetUnread = true)
            msgId
        }
    }

    /** v1.28.3 (groupes) — voir [OutgoingMessageMirror.upsertGroupEcho]. */
    override suspend fun upsertOutgoingGroupMms(
        addresses: List<PhoneAddress>,
        attachments: List<MediaAttachmentSpec>,
        textBody: String,
        date: Long,
        subId: Int?,
        clientMessageId: String?,
    ): Long = withContext(io) {
        val corps = textBody.trim()
        require(attachments.isNotEmpty() || corps.isNotEmpty()) { "un MMS de groupe sans contenu" }
        val preview = corps.ifBlank { mediaPreviewLabel(attachments.first()) }
        database.withTransaction {
            val convId = ensureConversation(addresses)
            val msgId = messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    telephonyUri = null,
                    address = addresses.joinToString(";") { it.raw },
                    body = corps,
                    type = MessageType.MMS,
                    direction = MessageDirection.OUTGOING,
                    date = date,
                    dateSent = null,
                    read = true,
                    starred = false,
                    status = MessageStatus.PENDING,
                    errorCode = null,
                    subId = subId,
                    scheduledAt = null,
                    attachmentsCount = attachments.size,
                    clientMessageId = clientMessageId,
                ),
            )
            for (a in attachments) {
                attachmentDao.insert(
                    AttachmentEntity(
                        messageId = msgId,
                        mimeType = a.mimeType,
                        fileName = a.file.name,
                        sizeBytes = a.file.length(),
                        localUri = a.file.absolutePath,
                        width = a.width,
                        height = a.height,
                        durationMs = a.durationMs,
                    ),
                )
            }
            touchConversation(convId, date, preview, deltaUnread = 0, resetUnread = true)
            msgId
        }
    }

    override suspend fun upsertGroupEcho(
        addresses: List<PhoneAddress>,
        body: String,
        date: Long,
        subId: Int?,
        status: MessageStatus,
        replyToMessageId: Long?,
        attachments: List<MediaAttachmentSpec>,
        clientMessageId: String?,
        encrypted: Boolean,
    ): Long = withContext(io) {
        val corps = body.trim()
        val preview = corps.ifBlank { attachments.firstOrNull()?.let { mediaPreviewLabel(it) }.orEmpty() }
        database.withTransaction {
            val convId = ensureConversation(addresses)
            val msgId = messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    telephonyUri = null,
                    address = addresses.joinToString(";") { it.raw },
                    body = corps,
                    type = if (attachments.isEmpty()) MessageType.SMS else MessageType.MMS,
                    direction = MessageDirection.OUTGOING,
                    date = date,
                    dateSent = null,
                    read = true,
                    starred = false,
                    status = status,
                    errorCode = null,
                    subId = subId,
                    scheduledAt = null,
                    attachmentsCount = attachments.size,
                    replyToMessageId = replyToMessageId,
                    clientMessageId = clientMessageId,
                    encrypted = encrypted,
                ),
            )
            for (a in attachments) {
                attachmentDao.insert(
                    AttachmentEntity(
                        messageId = msgId,
                        mimeType = a.mimeType,
                        fileName = a.file.name,
                        sizeBytes = a.file.length(),
                        localUri = a.file.absolutePath,
                        width = a.width,
                        height = a.height,
                        durationMs = a.durationMs,
                    ),
                )
            }
            if (preview.isNotEmpty()) touchConversation(convId, date, preview, deltaUnread = 0, resetUnread = true)
            msgId
        }
    }

    private fun mediaPreviewLabel(a: MediaAttachmentSpec): String = when {
        a.mimeType.startsWith("image/") -> "🖼️ " + a.file.name
        a.mimeType.startsWith("video/") -> "🎞️ " + a.file.name
        a.mimeType.startsWith("audio/") -> "🎤 " + a.file.name
        a.mimeType == "text/x-vcard" || a.mimeType == "text/vcard" -> "👤 " + a.file.name
        else -> "📎 " + a.file.name
    }

    /** v1.28.9 (F17) — ce qu'a produit l'inscription d'un MMS entrant : la ligne, et si elle est nouvelle. */
    data class InscriptionMmsRecu(val messageId: Long, val nouveau: Boolean)

    /**
     * Mirrors an incoming MMS retrieved through [com.filestech.sms.pdu.PduParser]. The caller is
     * responsible for having already written the attachment bytes to disk and passed the absolute
     * file path here.
     *
     * **v1.3.10** : `caption` is the raw user text (e.g. the text/plain part of the multipart),
     * stored verbatim in `messages.body` (empty when absent). `previewLabel` is the conversation-
     * list line — derived by the caller from caption / Subject / mime placeholder. Decoupling
     * the two avoids rendering a placeholder emoji as a fake text caption under the attachment
     * bubble.
     *
     * **v1.28.3 (F16)** — accepte **plusieurs** pieces jointes. Le parametre etait un unique
     * `attachmentFile`, et le selecteur du receveur s'appelait `extractFirstMediaPart` : d'un MMS a trois
     * photos, une seule etait conservee, et le PDU — seule copie, aucun MMS entrant n'etant ecrit cote
     * fournisseur systeme — etait ensuite supprime. La table `attachments` savait deja porter plusieurs
     * lignes par message : c'est le chemin SORTANT qui en profitait (`upsertOutgoingMediaMms`), le chemin
     * entrant non. Encore un jumeau asymetrique.
     *
     * **v1.28.4** — [groupMembers] : les membres du groupe reconstitués depuis l'en-tête du PDU
     * (`GroupMmsMembers`), ou `null` pour une conversation ordinaire. La ligne garde [address] =
     * l'expéditeur : c'est lui que la bulle nomme.
     *
     * **v1.28.10** — [inclutSoi] : `true` quand [groupMembers] porte l'utilisateur lui-même
     * (« Mon numéro » non renseigné). La conversation est alors retrouvée par rapprochement
     * « moins un » (ces membres moins un, c'est le groupe) ; s'il n'existe pas encore, on retombe
     * sur la conversation individuelle de l'expéditeur — on ne crée pas un groupe qui compterait
     * l'utilisateur comme membre.
     *
     * **v1.28.9 (F17, septième note d'Andrew sur la MR !38458) — idempotente par clé de transaction.**
     *
     * Le PDU d'un MMS dont le traitement a échoué est gardé, puis repris plus tard. La reprise doit
     * reconnaître un message déjà écrit — traitement mort après le commit, ou média perdu —, sinon elle
     * le dupliquerait. La clé ([com.filestech.sms.data.mms.PdusEnAttente.cle]) est cherchée DANS la
     * transaction d'écriture, avant tout : Room ouvre ses transactions en `BEGIN IMMEDIATE`, un seul
     * écrivain à la fois, donc le receveur et la reprise ne peuvent pas insérer tous deux. L'index
     * UNIQUE sur la colonne tient la même règle en défense en profondeur : `insert` est en `IGNORE`, un
     * conflit rend `-1`, et l'on relit alors l'existant.
     *
     * La clé est cherchée AVANT `ensureConversation` : sinon un message déjà rangé dans un groupe
     * créerait, à la reprise, une conversation vide à son expéditeur.
     *
     * **La porte [encoreVoulu]** est lue dans la même transaction, juste après la clé : `false` abandonne
     * l'écriture et rend `null`. L'effaceur supprime le PDU gardé AVANT son message ([ConversationEraser]) ;
     * une reprise qui avait lu le PDU juste avant ne trouve plus la ligne et, sans cette porte, écrirait à
     * nouveau le message que l'utilisateur venait de supprimer. La transaction est sérialisée avec celle de
     * l'effaceur : ou bien le message est encore là et la clé le trouve, ou bien il est parti, et son PDU
     * avec lui.
     *
     * Remplace `upsertIncomingMms`, dont le receveur était le seul appelant : deux entrées pour la même
     * écriture, l'une sans clé ni porte, auraient fini par diverger.
     */
    @Suppress("LongParameterList")
    suspend fun inscrireMmsRecu(
        address: String,
        pieces: List<IncomingAttachment>,
        caption: String?,
        previewLabel: String,
        date: Long,
        telephonyUri: String? = null,
        subId: Int? = null,
        groupMembers: List<PhoneAddress>? = null,
        inclutSoi: Boolean = false,
        transactionKey: String? = null,
        encoreVoulu: () -> Boolean = { true },
        // Round 13 — the AOSP system thread ID for this MMS (group MMS only, resolved by
        // `IntegrationMmsRecu` before the transaction). `0L` = unknown: routing is exactly
        // as before. When > 0, `ensureConversation` tries it first.
        systemThreadId: Long = 0L,
        // Round 48 — `true` when this is an encrypted-media MMS
        // (1:1 with a ciphertext part, decided by
        // `IntegrationMmsRecu` from the part shape). The row is
        // pending until its key message arrives — pending is
        // DERIVED (encrypted + not failed + no key row), so no
        // other column is set here.
        encrypted: Boolean = false,
    ): InscriptionMmsRecu? = withContext(io) {
        val storedBody = caption?.trim().orEmpty()
        database.withTransaction {
            val deja = transactionKey?.let { messageDao.findIdByTransactionKey(it) }
            if (deja != null) return@withTransaction InscriptionMmsRecu(deja, nouveau = false)
            if (!encoreVoulu()) return@withTransaction null
            val convId = ensureConversation(
                groupMembers ?: listOf(PhoneAddress.of(address)),
                inclutSoi = inclutSoi && groupMembers != null,
                systemThreadId = systemThreadId,
            )
            val msg = MessageEntity(
                conversationId = convId,
                telephonyUri = telephonyUri,
                address = address,
                body = storedBody,
                type = MessageType.MMS,
                direction = MessageDirection.INCOMING,
                date = date,
                dateSent = date,
                read = false,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = subId,
                scheduledAt = null,
                attachmentsCount = pieces.size,
                mmsTransactionKey = transactionKey,
                encrypted = encrypted,
            )
            val msgId = messageDao.insert(msg)
            if (msgId == -1L) {
                // `IGNORE` : un index unique a refusé la ligne. Pour la clé, c'est le message déjà là ;
                // pour tout autre conflit, rien ne permet de rendre un id honnête — on lève, et le
                // receveur garde le PDU.
                //
                // Round 16 — la clé n'est pas le seul index unique : un MMS de groupe écrit dans le
                // fournisseur porte aussi `telephony_uri`, et une synchronisation qui l'aurait
                // importé entre l'écriture fournisseur et cette transaction rendrait la ligne par
                // CET index. Sans ce repli, le conflit lèverait alors que le message est bien là.
                val existant = transactionKey?.let { messageDao.findIdByTransactionKey(it) }
                    ?: telephonyUri?.let { messageDao.findByTelephonyUri(it)?.id }
                    ?: error("MMS entrant refuse par un index unique")
                return@withTransaction InscriptionMmsRecu(existant, nouveau = false)
            }
            if (pieces.isNotEmpty()) {
                attachmentDao.insertAll(
                    pieces.map { piece ->
                        // Round 33 — probe PNG dimensions at import:
                        // the sticker filter needs real dimensions,
                        // and a received sticker's row used to store
                        // null here, so the 512×512 arm could never
                        // fire for it. A 24-byte header read,
                        // fail-soft (`null` when unprobed).
                        val pngSize = if (piece.mimeType.equals("image/png", ignoreCase = true)) {
                            probePngDimensions(piece.file)
                        } else {
                            null
                        }
                        AttachmentEntity(
                            messageId = msgId,
                            mimeType = piece.mimeType,
                            fileName = piece.file.name,
                            sizeBytes = piece.file.length(),
                            localUri = piece.file.absolutePath,
                            width = pngSize?.first,
                            height = pngSize?.second,
                            durationMs = piece.durationMs,
                        )
                    },
                )
            }
            // Round 35 — sticky filing from the caption (the stored body),
            // not the preview label: an MMS caption can carry the promo
            // signal ("Reply STOP to opt out") the label never shows.
            // BEFORE the touch, for the store's newest-wins guard.
            stickyCategories.onIncomingMessage(convId, storedBody, PhoneAddress.of(address), date)
            touchConversation(convId, date, previewLabel, deltaUnread = +1)
            InscriptionMmsRecu(msgId, nouveau = true)
        }
    }

    /**
     * v1.28.9 (F17) — **remplace les pièces jointes d'un MMS reçu** par [pieces], dans une transaction,
     * et rend les `local_uri` des lignes remplacées.
     *
     * C'est la complétion d'un message écrit sans toutes ses pièces (disque plein, renommage refusé) : la
     * reprise du PDU gardé réécrit TOUTES les parties, puis les substitue d'un bloc. Tout remplacer
     * plutôt qu'ajouter la pièce manquante : rien ne dit laquelle a manqué, et l'ordre du PDU est gardé.
     * L'appelant efface ensuite les anciens fichiers par
     * [FichiersDePiecesJointes.effacerSiPlusCites] — jamais par un `delete()` direct : un fichier peut être
     * cité ailleurs.
     *
     * Rend `null`, sans rien écrire, si le message n'existe plus : supprimé entre-temps, il ne doit pas
     * reprendre de pièces.
     */
    suspend fun remplacerPiecesMmsRecu(messageId: Long, pieces: List<IncomingAttachment>): List<String>? =
        withContext(io) {
            database.withTransaction {
                if (messageDao.findById(messageId) == null) return@withTransaction null
                val anciennes = attachmentDao.findForMessage(messageId).map { it.localUri }
                attachmentDao.deleteForMessage(messageId)
                attachmentDao.insertAll(
                    pieces.map { piece ->
                        AttachmentEntity(
                            messageId = messageId,
                            mimeType = piece.mimeType,
                            fileName = piece.file.name,
                            sizeBytes = piece.file.length(),
                            localUri = piece.file.absolutePath,
                            width = null,
                            height = null,
                            durationMs = piece.durationMs,
                        )
                    },
                )
                messageDao.setAttachmentsCount(messageId, pieces.size)
                anciennes
            }
        }

    private fun formatDurationLabel(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    /**
     * One-shot pass that walks every conversation with a missing display name and resolves it via
     * the contacts provider. Called from [ConversationsViewModel] at screen init so newly granted
     * READ_CONTACTS permission immediately updates the list — no reinstall needed.
     */
    suspend fun refreshContactNames() = withContext(io) {
        displayNameCache.evictAll()
        val missing = conversationDao.findMissingDisplayName()
        if (missing.isEmpty()) return@withContext
        database.withTransaction {
            for (conv in missing) {
                val firstAddr = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw ?: continue
                val name = resolveDisplayName(firstAddr) ?: continue
                conversationDao.setDisplayName(conv.id, name)
            }
        }
    }

    /**
     * Mirror every row returned by [com.filestech.sms.data.sms.TelephonyReader.readMmsBatched]
     * into Room. MMS rows live in `content://mms` (separate from `content://sms`), and without
     * this method they vanish from the UI after a re-install — system rows survive, but our
     * SQLCipher mirror is wiped and the SMS-only sync never picks them up.
     *
     * Idempotency: each row's `telephonyUri` is `content://mms/{id}`, indexed UNIQUE in Room,
     * so the `OnConflictStrategy.IGNORE` on `messageDao.insert` short-circuits duplicates.
     * Attachments are inserted only on a fresh row (the `insertedId == -1L` path means the row
     * was a duplicate, so the existing attachments already exist).
     */
    suspend fun bulkImportMmsFromTelephony(rows: List<com.filestech.sms.data.sms.TelephonyReader.MmsImportRow>) = withContext(io) {
        if (rows.isEmpty()) return@withContext
        // v1.2.4 audit P2: group by AOSP `thread_id` so each conversation gets exactly one
        // `findById + update` (= one `touchConversation`) per chunk instead of one per row.
        // For 500 MMS across 20 threads that's 20 SQLCipher updates instead of 500. Mirrors
        // the per-thread aggregation already used by `bulkImportFromTelephony` (SMS path).
        val byThread = rows.groupBy { it.threadId }
        database.withTransaction {
            // v1.2.7 audit P4 — buffer les AttachmentEntity de tout le group, puis 1 seul
            // `insertAll` à la fin pour économiser N-1 IPC SQLCipher. Sur un import 500 MMS
            // × ~1.5 attachments moyens, gain mesuré 400-600 ms cumulés. Le buffer est
            // réutilisé via `.clear()` au début de chaque itération de group.
            val attachmentsBuf = ArrayList<AttachmentEntity>(32)
            for ((_, group) in byThread) {
                val first = group.first()
                val convId = ensureConversationByThread(
                    systemThreadId = first.threadId,
                    addresses = listOf(PhoneAddress.of(first.address)),
                )
                // Round 48 — media encryption is 1:1 only: a group
                // thread's encrypted-looking part stays plain. The
                // conversation row carries the member list
                // (`addresses_csv`); one address = 1:1. Read once
                // per thread group, not once per row.
                val isOneToOne = conversationDao.findById(convId)
                    ?.let { PhoneAddress.list(it.addressesCsv).size == 1 } == true
                var maxDate = 0L
                var lastPreview = ""
                var unreadDelta = 0
                attachmentsBuf.clear()
                for (row in group) {
                    // Round 48 — an encrypted-media part in a 1:1
                    // import: the ciphertext stays in the system
                    // store (it is encrypted — any app with MMS
                    // permission reads only opaque bytes) and the
                    // row is flagged encrypted, pending its key.
                    // An OUTGOING row is failed outright: the
                    // sender keeps no copy of the per-file keys,
                    // and the plaintext original is gone with the
                    // reinstall that triggered this import — the
                    // media can never decrypt again, so the row
                    // renders "couldn't be decrypted" instead of a
                    // file icon that opens garbage.
                    val hasCiphertextPart = isOneToOne &&
                        row.attachments.any {
                            RelayMediaFilename.isEncryptedPart(it.contentType, it.filename)
                        }
                    val msg = MessageEntity(
                        conversationId = convId,
                        telephonyUri = "content://mms/${row.telephonyId}",
                        address = row.address,
                        body = row.textBody,
                        type = MessageType.MMS,
                        direction = row.direction,
                        date = row.dateMs,
                        dateSent = row.dateMs,
                        read = row.read,
                        starred = false,
                        status = row.status,
                        errorCode = null,
                        subId = row.subId,
                        scheduledAt = null,
                        attachmentsCount = row.attachments.size,
                        encrypted = hasCiphertextPart,
                        decryptFailed = hasCiphertextPart &&
                            row.direction == MessageDirection.OUTGOING,
                    )
                    val insertedId = messageDao.insert(msg)
                    // Insert id < 0 → conflict, row already mirrored; skip part insertion.
                    if (insertedId <= 0L) continue
                    for (part in row.attachments) {
                        attachmentsBuf += AttachmentEntity(
                            messageId = insertedId,
                            mimeType = part.contentType,
                            fileName = part.filename ?: "part_${part.partId}",
                            sizeBytes = 0L,
                            // The system keeps the binary payload on disk and serves it through
                            // this URI — no need to copy bytes into our cache. Coil / MediaPlayer
                            // both accept `content://mms/part/…` directly.
                            localUri = "content://mms/part/${part.partId}",
                            width = null,
                            height = null,
                            durationMs = null,
                        )
                    }
                    if (row.dateMs > maxDate) {
                        maxDate = row.dateMs
                        lastPreview = row.textBody.ifBlank { firstAttachmentPreviewLabel(row.attachments) }
                    }
                    if (row.direction == MessageDirection.INCOMING && !row.read) unreadDelta++
                }
                if (attachmentsBuf.isNotEmpty()) {
                    // v1.2.7 audit P4 — batch insert des parts pour ce group avant de passer
                    // au suivant. Buffer réutilisé via `.clear()` en début de boucle group.
                    attachmentDao.insertAll(attachmentsBuf)
                }
                if (maxDate > 0L) {
                    touchConversation(convId, maxDate, lastPreview, deltaUnread = unreadDelta)
                }
            }
        }
    }

    /** Returns a short emoji + type label for the conversation preview when the MMS has no text. */
    private fun firstAttachmentPreviewLabel(parts: List<com.filestech.sms.data.sms.TelephonyReader.MmsPartImport>): String {
        val first = parts.firstOrNull() ?: return ""
        return when {
            first.contentType.startsWith("audio/") -> "🎤 " + (first.filename ?: "Audio")
            first.contentType.startsWith("image/") -> "🖼️ " + (first.filename ?: "Image")
            first.contentType.startsWith("video/") -> "🎞️ " + (first.filename ?: "Vidéo")
            else -> "📎 " + (first.filename ?: first.contentType)
        }
    }

    /**
     * Bulk import: groups inserts + conversation touches into a SINGLE Room transaction so the
     * `messages` / `conversations` tables only invalidate once at commit time. Without this the
     * UI recomposes thousands of times during a 5000-SMS import = the "single row scrolling at
     * full speed" symptom users reported on Samsung One UI 6 / 7.
     *
     * `messages.telephony_uri` has a UNIQUE index + `OnConflictStrategy.IGNORE`, so re-running
     * the import is idempotent: existing rows stay put, only fresh URIs add new rows.
     */
    suspend fun bulkImportFromTelephony(messages: List<MessageEntity>) = withContext(io) {
        if (messages.isEmpty()) return@withContext
        // Audit P-P0-1 bis: the cache is no longer cleared at the start of a bulk import.
        // (1) Now that the underlying store is a `ConcurrentHashMap`, the clear / put race that
        // motivated the previous wipe is gone. (2) Entries are immutable `(number → name)`
        // pairs; if a contact name actually changes, the [com.filestech.sms.data.contacts.ContactsReader]
        // observer will invalidate downstream. (3) Keeping the cache hot across imports means a
        // live SMS arriving mid-sync reuses the resolutions the sync already paid for instead
        // of re-querying the contacts provider for every common correspondent.
        //
        // Imports one system thread's SMS rows. Plain rows insert in bulk;
        // rows shaped like a reaction are decoded and, on a Tapback match,
        // folded onto their target (badge + sentinel) instead of importing
        // as a text bubble — the same outcome as the live receiver, for
        // reactions that arrived while another app was the default SMS app.
        // Outgoing rows fold too: our OWN reactions, imported from the
        // sent box (the sending app mirrors them with an empty body, but
        // the provider holds the full text) — badge on their message, no
        // bump, no unread.
        //
        // The fold runs AFTER the plain insert so a target sitting in the
        // same batch is already in Room (the sweep reads provider ids
        // ascending, so a target from an earlier page is always in too). A
        // tapback that fails to fold falls back to a plain bubble, like the
        // live receiver — nothing is silently lost.
        //
        // A local function (not a member): the importer is the only caller,
        // and this class is at its Detekt function-count limit.
        suspend fun importSmsThreadGroup(systemThreadId: Long, group: List<MessageEntity>) {
            // E2E (design §3.5) — ciphertext fragments in the system store
            // are NOT messages: they are the wire form of an encrypted
            // message, reassembled live by the receive path. The same
            // holds for handshake SMS (round 45): a `RLYH:` text is
            // the wire form of a probe or bundle, handled live by the
            // handshake path. Skipping both here keeps a re-import
            // (fresh install, resync) from surfacing `RLY1:` gibberish
            // or `RLYH:` handshake text as text bubbles — and from
            // letting a fragment or handshake SMS become the
            // conversation preview via `last` below. An all-fragment
            // (or all-handshake) group creates no conversation at
            // all. The literals mirror `E2eWireFormat.MAGIC` /
            // `E2eHandshakeSms.MAGIC`, which live in `:app` and are
            // unreachable from `:data`.
            val rows = group.filterNot {
                it.body.startsWith(E2E_FRAGMENT_PREFIX) || it.body.startsWith(E2E_HANDSHAKE_PREFIX)
            }
            if (rows.isEmpty()) return
            // Local: only this importer folds rows. Decodes the body, and on a
            // Tapback match applies it to its target and writes the sentinel —
            // the text row itself is never inserted. Returns `true` when the row
            // was folded (caller must not insert it); `false` means "plain
            // bubble" (caller inserts the row as-is). EmojiOnly is never folded
            // here: its 5-minute window is anchored at "now", so an old
            // bare-emoji row would mis-attach to a message sent minutes ago.
            // Same rule as [reparseStoredReactions]. Outgoing rows (our own
            // reactions, from the sent box) fold onto their incoming target
            // first, then onto one of our own outgoing messages when no
            // incoming matches (a reaction to one of our own texts) — with
            // no bump either way. See [applyIncomingReaction].
            suspend fun foldImportedTapback(row: MessageEntity): Boolean {
                val decoded = IncomingReactionDecoder.decode(row.body) ?: return false
                if (decoded.kind == Kind.EmojiOnly) return false
                val applied = try {
                    applyIncomingReaction(
                        address = row.address,
                        emoji = decoded.emoji,
                        bodyPrefix = decoded.previewPrefix,
                        kind = decoded.kind,
                        wasTruncated = decoded.wasTruncated,
                        isRemoval = decoded.isRemoval,
                        reactionDateMs = row.date,
                        isOwnReaction = row.direction == MessageDirection.OUTGOING,
                    )
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Timber.w(t, "foldImportedTapback: fold failed for ${row.telephonyUri}")
                    null
                }
                if (applied == null) return false
                // Sentinel takes the tapback's telephonyUri so the next sweep sees
                // the UNIQUE constraint taken and skips the re-import — the same
                // poison-pill the live receiver writes.
                upsertReactionSentinel(
                    address = row.address,
                    telephonyUri = row.telephonyUri,
                    date = row.date,
                )
                return true
            }

            val first = rows.first()
            val convId = ensureConversationByThread(
                systemThreadId = systemThreadId,
                addresses = listOf(PhoneAddress.of(first.address)),
            )
            val withConv = rows.map { it.copy(conversationId = convId) }
            val tapbackRows = ArrayList<MessageEntity>()
            val plainRows = ArrayList<MessageEntity>(withConv.size)
            for (row in withConv) {
                // Both directions: an incoming row is their reaction, an
                // outgoing one is OUR OWN (imported from the sent box —
                // the sending app mirrors it with an empty body, but the
                // full text is what the provider holds).
                if (looksLikeReactionText(row.body)) {
                    tapbackRows += row
                } else {
                    plainRows += row
                }
            }
            // v1.8.0 (bug 2 fix) — Room's @Insert(OnConflictStrategy.IGNORE) returns
            // -1L for each row that conflicted with the UNIQUE (telephony_uri) index
            // (i.e. already mirrored), and the new row id for fresh inserts. Before
            // this fix the unread delta was calculated over the WHOLE group — every
            // re-sync (12h worker or manual pull-to-refresh) re-bumped the badge by
            // the count of incoming-unread rows even when the user had already opened
            // the thread and Room had cleared its unread counter. Now we count ONLY
            // rows that actually inserted, so the badge reflects truly new arrivals.
            //
            // The MMS path above (`bulkImportMmsFromTelephony`, line ~620) is not
            // affected by the same regression because it iterates row-by-row with a
            // `if (insertedId <= 0L) continue` that already skips the delta increment
            // for conflicting rows. Keep both branches symmetric in spirit even if
            // the syntactic shape differs.
            val insertedIds = messageDao.insertAll(plainRows)
            var unreadDelta = plainRows.withIndex().count { (idx, m) ->
                insertedIds.getOrElse(idx) { -1L } > 0L &&
                    m.direction == MessageDirection.INCOMING &&
                    !m.read
            }
            val foldedUris = HashSet<String>()
            for (row in tapbackRows) {
                if (foldImportedTapback(row)) {
                    row.telephonyUri?.let { foldedUris += it }
                } else {
                    val insertedId = messageDao.insert(row)
                    // Incoming only: an outgoing row that failed to fold is
                    // our own sent text — never unread, even if the
                    // provider row carries READ=0.
                    if (insertedId > 0L && row.direction == MessageDirection.INCOMING && !row.read) {
                        unreadDelta++
                    }
                }
            }
            // The touch must never surface a folded tapback's raw text as the
            // preview: `last` is the newest NON-folded row. When every row in the
            // group folded, an incoming fold's own touch already set the date
            // and the emoji preview — and an all-outgoing group needs no touch
            // at all (our own reactions never bump) — so nothing is left to touch.
            val last = withConv
                .filter { it.telephonyUri !in foldedUris }
                .maxByOrNull { it.date }
            if (last != null) {
                touchConversation(convId, last.date, last.body, deltaUnread = unreadDelta)
            }
        }

        database.withTransaction {
            // Group by system thread_id — that's the source of truth for "which conversation".
            // `MessageEntity.conversationId` here actually carries the system Telephony.Sms.THREAD_ID
            // (set by TelephonyReader.toSms before we know our local convId).
            val byThread = HashMap<Long, MutableList<MessageEntity>>(messages.size / 8 + 1)
            for (m in messages) {
                byThread.getOrPut(m.conversationId) { ArrayList() } += m
            }
            for ((systemThreadId, group) in byThread) {
                importSmsThreadGroup(systemThreadId, group)
            }
        }
    }

    /**
     * Ensures a conversation row exists for the given system thread_id and returns our local id.
     * Required for the bulk import path — without it, all conversations collide on `thread_id=0`
     * (the UNIQUE index across the column makes them REPLACE each other).
     */
    private suspend fun ensureConversationByThread(
        systemThreadId: Long,
        addresses: List<PhoneAddress>,
    ): Long {
        val resolved = if (addresses.size == 1) resolveDisplayName(addresses.first().raw) else nomDeGroupe(addresses)
        if (systemThreadId > 0L) {
            conversationDao.findByThreadId(systemThreadId)?.let { existing ->
                if (existing.displayName == null && resolved != null) {
                    conversationDao.update(existing.copy(displayName = resolved))
                }
                return existing.id
            }
        }
        val csv = addresses.sortedBy { it.normalized }.toCsv()

        // v1.3.10 — exact-CSV fallback BEFORE the suffix-8 lookup. Catches the case where
        // [MmsDownloadedReceiver] just created the conversation without a system thread id
        // (round 13 now resolves one for group MMS via `SystemThreadResolver`, but a
        // failed lookup still leaves `0L`) and the subsequent
        // [TelephonySyncManager.bulkImportMmsFromTelephony] is now importing the same MMS
        // with its real system thread id. Without this, we'd insert a second conversation
        // for the same correspondent — user-visible as a duplicate thread.
        (conversationDao.findByAddressesCsv(csv) ?: rapprocherGroupe(addresses))?.let { existing ->
            if (systemThreadId > 0L && existing.threadId != systemThreadId) {
                conversationDao.update(existing.copy(
                    threadId = systemThreadId,
                    displayName = existing.displayName ?: resolved,
                ))
            } else if (existing.displayName == null && resolved != null) {
                conversationDao.update(existing.copy(displayName = resolved))
            }
            return existing.id
        }

        // v1.3.10 — suffix-8 fallback : if the existing conversation was created from a raw
        // PDU address (e.g. "+33617332729") and we're now importing from `content://mms` with
        // the gateway-decorated form (e.g. "+33617332729/TYPE=PLMN"), the exact-CSV match
        // misses but the last 8 digits do agree. Same rationale as [ensureConversation].
        if (addresses.size == 1) {
            // v1.26.1 (audit H13) — rapprochement sur `blockKey()` (9 chiffres
            // significatifs) et non plus `phoneSuffix8()`.
            //
            // Huit chiffres amputaient le chiffre qui distingue un `06…` d'un `07…` :
            // `0612345678` et `0712345678` — DEUX PERSONNES DIFFÉRENTES — partageaient
            // leur clé. À l'import, les messages de l'une étaient rattachés à la
            // conversation de l'autre, sous son nom ; répondre dans ce fil envoyait au
            // mauvais destinataire. Le fichier `StringExt` documente déjà cette raison
            // pour la liste noire, et la passe de déduplication se disait elle-même
            // « PLUS stricte que le suffix-8 de la réception ».
            //
            // ⚠️ `stripMmsAddressSuffix` d'abord : l'en-tête `From:` d'un PDU porte
            // `/TYPE=PLMN`, or `blockKey()` bascule en mode alphanumérique dès qu'il voit
            // une lettre — sans ce nettoyage il rendrait la chaîne entière et casserait
            // précisément le cas que ce repli existe pour couvrir.
            val incomingKey = addresses.first().raw.stripMmsAddressSuffix().blockKey()
            if (incomingKey.length >= CONV_MATCH_MIN_DIGITS && incomingKey.all { it.isDigit() }) {
                val ident = phoneIdentity.snapshot()
                val oneToOne = conversationDao.snapshotOneToOneConversations()
                // v1.27.2 (audit Codex du 2026-08-05, P-11) — le seau `blockKey` ne suffit
                // pas : `+33 6 12 34 56 78` et `+1 561 234 5678` rendent la meme cle de neuf
                // chiffres. [phoneAddressesMatch] ajoute la desambiguisation par indicatif pays.
                val match = oneToOne.firstOrNull { conv ->
                    val convRaw = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw
                    convRaw != null && ident.matches(convRaw, addresses.first().raw)
                }
                if (match != null) {
                    if (systemThreadId > 0L && match.threadId != systemThreadId) {
                        conversationDao.update(match.copy(
                            threadId = systemThreadId,
                            displayName = match.displayName ?: resolved,
                        ))
                    } else if (match.displayName == null && resolved != null) {
                        conversationDao.update(match.copy(displayName = resolved))
                    }
                    return match.id
                }
            }
        }

        return conversationDao.insert(
            ConversationEntity(
                // v1.28.3 — un `thread_id` AOSP est toujours ≥ 1 ; tout le reste vaut « inconnu ».
                // Les appelants d'import passent la valeur lue du fournisseur, qui a déjà été
                // filtrée plus haut, mais l'invariant de la colonne se tient ICI, au seul endroit
                // qui écrit — pas dans la promesse de six appelants.
                threadId = systemThreadId.takeIf { it > 0L },
                addressesCsv = csv,
                displayName = resolved,
                // Sentinel 0L — the **next** [touchConversation] call (which always follows an
                // ensure*) sets `lastMessageAt = maxOf(date, 0)` = the real message date. The
                // previous `System.currentTimeMillis()` default was a bug: importing 6-month-old
                // MMS would silently set lastMessageAt = now via the `maxOf(now, oldDate) = now`
                // clamp, scrambling the conversation list order during bulk import.
                lastMessageAt = 0L,
                lastMessagePreview = null,
                unreadCount = 0,
            ),
        )
    }

    /**
     * v1.28.4 — **un groupe se reconnaît à ses membres, pas à l'écriture de leurs numéros.** Mesuré
     * sur le S24 : le groupe composé en national (`0617…;0698…`) et le même groupe reconstitué
     * depuis un PDU en international (`+33617…;+33698…`) faisaient deux fils. Le jumeau du repli
     * 1-à-1, en second choix après la CSV exacte, aux deux points de rapprochement — cf.
     * [ConversationRepositoryImpl.matchGroupByIdentity].
     */
    /**
     * Round 12 — comparateur indulgent pour les groupes. Le [matches] strict
     * (E.164) échoue quand la région n'est pas établie (GrapheneOS : ni la SIM
     * ni le réseau ne la donnent) et que les deux écritures du même numéro
     * diffèrent — nationale `5551234567` contre internationale `+15551234567`.
     * Le repli compare les 9 derniers chiffres (`blockKey`) : assez longs pour
     * ne pas confondre deux correspondants, exigés des deux côtés et tous
     * chiffres pour écarter les codes courts et les expéditeurs
     * alphanumériques. Sans ce repli, la réponse à un groupe tombait en 1-à-1
     * dès que « Mon numéro » n'était pas renseigné.
     */
    private fun lenientGroupMatches(strict: (String, String) -> Boolean): (String, String) -> Boolean =
        { a, b ->
            strict(a, b) || run {
                val ka = a.stripMmsAddressSuffix().blockKey()
                val kb = b.stripMmsAddressSuffix().blockKey()
                ka.length == 9 && kb.length == 9 &&
                    ka.all { it.isDigit() } && kb.all { it.isDigit() } && ka == kb
            }
        }

    private suspend fun rapprocherGroupe(addresses: List<PhoneAddress>): ConversationEntity? =
        if (addresses.size > 1) {
            ConversationRepositoryImpl.matchGroupByIdentity(
                conversationDao.snapshotGroupConversations(),
                addresses,
                lenientGroupMatches(phoneIdentity.snapshot()::matches),
            )
        } else {
            null
        }

    /**
     * v1.28.10 — le jumeau « moins un » de [rapprocherGroupe] : la liste porte l'utilisateur
     * lui-même (« Mon numéro » non renseigné), donc le groupe cherché a exactement un membre de
     * moins que la liste. Trois adresses ou plus sont exigées : après nous avoir retirés, il
     * doit rester au moins deux membres, sinon ce n'est pas un groupe.
     */
    private suspend fun rapprocherGroupeMoinsUn(addresses: List<PhoneAddress>): ConversationEntity? =
        if (addresses.size > 2) {
            ConversationRepositoryImpl.matchGroupByIdentityMinusOne(
                conversationDao.snapshotGroupConversations(),
                addresses,
                lenientGroupMatches(phoneIdentity.snapshot()::matches),
            )
        } else {
            null
        }

    /**
     * v1.28.10 — résout une liste d'adresses qui porte l'utilisateur (« Mon numéro » non
     * renseigné) : d'abord le groupe « moins un », sinon la conversation individuelle de
     * l'expéditeur (en tête de liste). Ne crée jamais un groupe qui compterait l'utilisateur
     * comme membre — sans groupe connu, on ne sait pas qui retirer.
     *
     * Round 13 — [systemThreadId] is stamped onto the minus-one group when found (it IS
     * that group), so the next message in the thread matches by thread ID. It is NEVER
     * passed to the 1:1 fallback below: the sender's individual conversation must not
     * carry a group thread ID.
     */
    private suspend fun resoudreAvecSoi(addresses: List<PhoneAddress>, systemThreadId: Long = 0L): Long {
        rapprocherGroupeMoinsUn(addresses)?.let { existing ->
            // Le nom vient des membres du groupe retrouvé, pas de la liste brute (qui porte
            // aussi notre propre numéro).
            if (existing.displayName == null) {
                nomDeGroupe(PhoneAddress.list(existing.addressesCsv))?.let { nom ->
                    conversationDao.update(existing.copy(displayName = nom))
                }
            }
            if (systemThreadId > 0L && existing.threadId != systemThreadId) {
                conversationDao.update(existing.copy(threadId = systemThreadId))
            }
            return existing.id
        }
        return ensureConversation(listOf(addresses.first()))
    }

    private suspend fun ensureConversation(
        addresses: List<PhoneAddress>,
        inclutSoi: Boolean = false,
        // Round 13 — the AOSP system thread ID for this MMS (group MMS only; `0L` = unknown).
        // The provider keys a thread by its recipient set, so this is the grouping Android
        // itself uses — it works for a group Relay has never seen, and without our own
        // number. `0L` leaves every existing path exactly as it was.
        systemThreadId: Long = 0L,
    ): Long {
        val csv = addresses.sortedBy { it.normalized }.toCsv()
        val resolved = if (addresses.size == 1) resolveDisplayName(addresses.first().raw) else nomDeGroupe(addresses)

        // 0) + 1) Round 13 — the system thread ID first, then the exact-CSV / group
        //    match. The provider keys a thread by its recipient set, so a thread hit IS
        //    the conversation for this group, whatever the participant list looks like
        //    (carrier-stripped `To`, national vs international spellings, our own number
        //    present or not). A thread ID only ever matches the conversation for that
        //    exact recipient set, so it cannot misroute a 1:1 into a group or vice versa.
        //    When the match came from CSV / group instead, the thread ID is adopted:
        //    the next message in this thread then matches by thread ID even if its
        //    participant list is spelled differently. (For a thread hit the stamp is a
        //    no-op — the stored ID already equals it.)
        val byThread: ConversationEntity? = if (systemThreadId > 0L) {
            conversationDao.findByThreadId(systemThreadId)
        } else {
            null
        }
        (byThread ?: conversationDao.findByAddressesCsv(csv) ?: rapprocherGroupe(addresses))?.let { existing ->
            if (existing.displayName == null && resolved != null) {
                conversationDao.update(existing.copy(displayName = resolved))
            }
            if (systemThreadId > 0L && existing.threadId != systemThreadId) {
                conversationDao.update(existing.copy(threadId = systemThreadId))
            }
            return existing.id
        }

        // 1b) v1.28.10 — « moins un » : la liste porte l'utilisateur lui-même (« Mon numéro »
        //     non renseigné), donc aucun groupe stocké ne peut l'égaler exactement — il a un
        //     membre de moins. Un groupe dont les membres sont ces adresses moins une, c'est
        //     le groupe (l'adresse en trop, c'est nous). Sans ce repli, la réponse à un groupe
        //     existant tombait en conversation individuelle de l'expéditeur.
        if (inclutSoi && addresses.size > 1) return resoudreAvecSoi(addresses, systemThreadId)

        // 2) v1.3.3 — FALLBACK matching par suffix 8 chiffres pour les conversations
        //    1-to-1. Sans ce fallback, un SMS reçu en format national `0612345678` créerait
        //    une 2ᵉ conversation alors qu'une conv existe déjà en format international
        //    `+33612345678` (importée du système). Bug user-visible : liste des
        //    conversations qui se remplit de doublons VIDES à chaque SMS reçu (les
        //    messages tombent dans la conv "broadcast" pendant que la conv "import"
        //    historique reste figée).
        //
        //    Restreint aux conv 1-to-1 (`snapshotOneToOneConversations` filtre déjà les
        //    groupes). Pour les groupes, le matching CSV strict reste la bonne approche :
        //    deux participants partiels ne doivent PAS être confondus avec un autre
        //    groupe via suffix.
        if (addresses.size == 1) {
            // v1.26.1 (audit H13) — rapprochement sur `blockKey()` (9 chiffres
            // significatifs) et non plus `phoneSuffix8()`.
            //
            // Huit chiffres amputaient le chiffre qui distingue un `06…` d'un `07…` :
            // `0612345678` et `0712345678` — DEUX PERSONNES DIFFÉRENTES — partageaient
            // leur clé. À l'import, les messages de l'une étaient rattachés à la
            // conversation de l'autre, sous son nom ; répondre dans ce fil envoyait au
            // mauvais destinataire. Le fichier `StringExt` documente déjà cette raison
            // pour la liste noire, et la passe de déduplication se disait elle-même
            // « PLUS stricte que le suffix-8 de la réception ».
            //
            // ⚠️ `stripMmsAddressSuffix` d'abord : l'en-tête `From:` d'un PDU porte
            // `/TYPE=PLMN`, or `blockKey()` bascule en mode alphanumérique dès qu'il voit
            // une lettre — sans ce nettoyage il rendrait la chaîne entière et casserait
            // précisément le cas que ce repli existe pour couvrir.
            val incomingKey = addresses.first().raw.stripMmsAddressSuffix().blockKey()
            if (incomingKey.length >= CONV_MATCH_MIN_DIGITS && incomingKey.all { it.isDigit() }) {
                val ident = phoneIdentity.snapshot()
                val oneToOne = conversationDao.snapshotOneToOneConversations()
                // v1.27.2 (audit Codex du 2026-08-05, P-11) — le seau `blockKey` ne suffit
                // pas : `+33 6 12 34 56 78` et `+1 561 234 5678` rendent la meme cle de neuf
                // chiffres. [phoneAddressesMatch] ajoute la desambiguisation par indicatif pays.
                val match = oneToOne.firstOrNull { conv ->
                    val convRaw = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw
                    convRaw != null && ident.matches(convRaw, addresses.first().raw)
                }
                if (match != null) {
                    if (match.displayName == null && resolved != null) {
                        conversationDao.update(match.copy(displayName = resolved))
                    }
                    return match.id
                }
            }
        }

        // 3) Vraiment nouvelle conversation — création.
        //
        // v1.28.3 (F01) — `threadId = null` quand aucun fil système n'est connu. Avec la
        // sentinelle `0L` sous un index UNIQUE, deux correspondants inconnus reçus dans le
        // même intervalle se chassaient l'un l'autre : le second insert supprimait la
        // conversation du premier, messages compris.
        //
        // Round 13 — when a system thread ID is known (group MMS), store it: the NEXT
        // message in the same system thread then matches by step 0 (`findByThreadId`),
        // even if its participant list is incomplete. This is what makes a brand-new
        // group work from the second message on — and, since the thread ID comes from
        // the PDU addresses, from the first one too when the group is new to Relay.
        return conversationDao.insert(
            ConversationEntity(
                threadId = systemThreadId.takeIf { it > 0L },
                addressesCsv = csv,
                displayName = resolved,
                // Same sentinel as `ensureConversationByThread` — see comment there.
                lastMessageAt = 0L,
                lastMessagePreview = null,
                unreadCount = 0,
            ),
        )
    }

    /**
     * v1.22.x — dédup « one-heal » des conversations 1-to-1 du MÊME numéro laissées en double par
     * les versions antérieures aux correctifs de threading (fallback suffix-8 à la réception v1.3.3
     * + `findOrCreate` au composer v1.21.1). Ces correctifs empêchent les NOUVEAUX doublons mais ne
     * fusionnent pas ceux déjà en base — cette passe les réunit.
     *
     * Appelée au cold-start ([com.filestech.sms.MainApplication]), en tâche de fond ; idempotente
     * (une 2ᵉ passe ne trouve plus aucun groupe ≥ 2 → no-op).
     *
     * Sûreté (« ne jamais fusionner deux personnes différentes ») :
     *   - clé de fusion = **E.164** (région SIM/réglage résolue une seule fois), PLUS stricte que
     *     le suffix-8 de la réception : deux numéros distincts partageant leurs 8 derniers chiffres
     *     (ex. `06…12345678` vs `07…12345678`) obtiennent des clés différentes → non fusionnés. Un
     *     numéro non normalisable (short code, expéditeur alphanumérique, région inconnue) n'a pas
     *     de clé `+…` → jamais fusionné.
     *   - coffre-fort EXCLU (`in_vault` filtré) : contexte sensible jamais touché.
     *   - reparent des messages AVANT suppression de la conversation source (FK `onDelete = CASCADE`
     *     sur `messages.conversation_id` — supprimer d'abord effacerait les messages).
     *   - fusion dans une transaction unique ; `unread_count` recalculé depuis les messages réels.
     *   - `scheduled_messages.conversation_id` est reparenté vers le survivant (cohérence des
     *     données, même si l'envoi programmé résout aujourd'hui ses destinataires via `addressesCsv`).
     *   - le `threadId` système AOSP d'une victime est repris par le survivant s'il n'en avait pas,
     *     pour ne pas dégrader la propagation `markRead` vers `content://sms|mms`.
     *
     * Retourne `true` si au moins un groupe de doublons a été fusionné, `false` si la base était
     * déjà propre — l'appelant s'en sert pour ne mémoriser la complétion qu'une fois propre.
     */
    suspend fun dedupeSameNumberConversations(): Boolean = withContext(io) {
        val oneToOne = conversationDao.snapshotOneToOneConversations().filterNot { it.inVault }
        if (oneToOne.size < 2) return@withContext false
        // 🔴 v1.27.2 (audit Codex final, F-01 — le JUMEAU) — region HYDRATEE.
        //
        // Cette passe lisait `defaultRegionIso()`, donc `settings.state.value`, dont la valeur
        // initiale est la configuration par defaut. Sur un demarrage a froid, l'override
        // « Indicatif pays par defaut » etait ignore : la dedup ne trouvait alors aucun groupe a
        // fusionner, rendait `false`... et `StartupMigrations` posait DEFINITIVEMENT son drapeau
        // `dedupSameNumberV1230`. Les doublons devenaient permanents, sans rejeu possible.
        //
        // On passe par le meme instantane que le reste de l'identite.
        val ident = phoneIdentity.snapshot()
        val candidates = oneToOne.mapNotNull { conv ->
            val raw = PhoneAddress.list(conv.addressesCsv).singleOrNull()?.raw ?: return@mapNotNull null
            DedupCandidate(id = conv.id, rawAddress = raw, lastMessageAt = conv.lastMessageAt)
        }
        val plans = planSameNumberMerges(candidates) { raw -> ident.canonical(raw) }
        // ⚠️ Sans region fiable, aucune forme nationale ne se canonicalise : ne rien trouver ne
        // prouve alors RIEN. On rend `true` pour que la migration one-shot ne se croie pas
        // terminee et rejoue au prochain demarrage, region hydratee.
        if (plans.isEmpty()) return@withContext !ident.regionKnown
        // v1.28.7 (relecture GPT 5.2) — les victimes REELLEMENT supprimees, relevees dans la
        // transaction. Un plan dont le survivant manque est saute (`continue` ci-dessous) sans rien
        // supprimer : annuler ses notifications apres coup les couperait sur des conversations
        // toujours presentes. Le cas est juge inatteignable, mais la garde doit rester vraie le
        // jour ou l'invariant casserait.
        val victimesSupprimees = mutableListOf<Long>()
        database.withTransaction {
            for (plan in plans) {
                // 1) reparent AVANT toute suppression (FK CASCADE sur messages.conversation_id).
                //    scheduled_messages est aussi reparenté (pas de FK, mais cohérence des données).
                for (victimId in plan.victimIds) {
                    messageDao.reparentMessages(
                        fromConversationId = victimId,
                        toConversationId = plan.survivorId,
                    )
                    scheduledMessageDao.reparentConversationId(
                        fromConversationId = victimId,
                        toConversationId = plan.survivorId,
                    )
                }
                // 2) fusion des métadonnées sur le survivant. Épinglage/muet = OR (on ne perd pas
                //    un flag posé sur l'une des deux) ; nom/apparence/brouillon = 1ʳᵉ valeur non
                //    nulle ; aperçu + date pris sur la conversation la plus récente du groupe.
                val survivor = conversationDao.findById(plan.survivorId)
                if (survivor == null) {
                    // Groupes disjoints + reparent-avant-delete rendent ce cas non atteignable ;
                    // on loggue au cas où une régression future casserait cet invariant.
                    Timber.e("dedupe: survivant %d introuvable, groupe ignoré", plan.survivorId)
                    continue
                }
                val victims = plan.victimIds.mapNotNull { conversationDao.findById(it) }
                val newest = (victims + survivor).maxByOrNull { it.lastMessageAt } ?: survivor
                conversationDao.update(
                    survivor.copy(
                        lastMessageAt = newest.lastMessageAt,
                        lastMessagePreview = newest.lastMessagePreview,
                        pinned = survivor.pinned || victims.any { it.pinned },
                        muted = survivor.muted || victims.any { it.muted },
                        // D1 (audit) — ne reste archivée QUE si TOUTES l'étaient : sinon une conv
                        // active fusionnée pourrait disparaître de la liste principale selon quel
                        // doublon a la date la plus récente (survivant élu).
                        archived = survivor.archived && victims.all { it.archived },
                        displayName = survivor.displayName ?: victims.firstNotNullOfOrNull { it.displayName },
                        avatarUri = survivor.avatarUri ?: victims.firstNotNullOfOrNull { it.avatarUri },
                        bubbleColorArgb = survivor.bubbleColorArgb ?: victims.firstNotNullOfOrNull { it.bubbleColorArgb },
                        // D2 (audit) — concatène les brouillons non-vides (survivant + victimes) au
                        // lieu d'écraser : un texte non envoyé tapé dans un doublon ne doit pas être
                        // perdu silencieusement à la fusion.
                        draft = (listOfNotNull(survivor.draft?.takeIf { it.isNotBlank() }) +
                            victims.mapNotNull { v -> v.draft?.takeIf { it.isNotBlank() } })
                            .distinct()
                            .joinToString("\n\n")
                            .takeIf { it.isNotBlank() },
                    ),
                )
                // 3) suppression des conversations sources (désormais vidées de leurs messages).
                for (victimId in plan.victimIds) {
                    conversationDao.delete(victimId)
                    victimesSupprimees += victimId
                }
                // 3bis) D3 (audit) — reprise du threadId système AOSP. Si le survivant n'en a pas
                //   (créé via ensureConversation → threadId=null) mais qu'une victime en portait un
                //   (créée via ensureConversationByThread à l'import système), on le récupère pour
                //   que markRead continue de propager READ=1 vers content://sms|mms. FAIT APRÈS le
                //   delete des victimes : l'index `conversations.thread_id` est UNIQUE, la victime
                //   détenait encore ce threadId jusqu'à sa suppression.
                if (survivor.threadId == null) {
                    val recovered = victims.firstNotNullOfOrNull { it.threadId?.takeIf { t -> t > 0L } }
                    if (recovered != null) {
                        conversationDao.setThreadId(plan.survivorId, recovered)
                    }
                }
            }
            // 4) recalcule conversations.unread_count depuis les messages réellement non lus.
            conversationDao.recomputeAllUnreadCounts()
        }
        // v1.28.7 — APRÈS la validation, et pour les seules victimes réellement supprimées. Leurs
        // messages vivent désormais sous le survivant, mais leurs notifications portaient le tag de
        // la conversation supprimée : un tap ouvrait un fil qui n'existe plus (`AppRoot` navigue
        // vers l'identifiant sans vérifier qu'il existe). On les retire plutôt que de les laisser
        // mentir ; le compteur de non-lus du survivant, recalculé plus haut, porte toujours ces
        // messages.
        for (victimId in victimesSupprimees) notifications.cancelAllForConversation(victimId)
        Timber.i("dedupeSameNumberConversations: %d groupe(s) de doublons fusionné(s)", plans.size)
        true
    }

    /**
     * Round 12 — [resetUnread] : quand on ENVOIE un message, la conversation n'a plus
     * rien d'inattendu à signaler : répondre, c'est avoir lu. Sans ça, répondre
     * depuis la notification (sans ouvrir le fil) laissait le compteur à 1 alors
     * que l'utilisateur venait d'écrire. Les envois passent `resetUnread = true` ;
     * les réceptions gardent `deltaUnread = +1`.
     *
     * Round 35 — répondre efface aussi la catégorie collante : la
     * conversation redevient classée sur son dernier message (le texte de
     * l'utilisateur, donc Personnel). Seuls les chemins d'envoi passent
     * `resetUnread = true`, donc c'est le seul endroit où l'effacement
     * peut se faire ; la réception ne touche jamais la colonne ici (elle
     * passe par `StickyCategoryStore`, qui a besoin du corps complet).
     */
    private suspend fun touchConversation(
        convId: Long,
        date: Long,
        preview: String,
        deltaUnread: Int,
        resetUnread: Boolean = false,
    ) {
        val current = conversationDao.findById(convId) ?: return
        // v1.27.2 (relecture Gemini du 2026-08-05) — 🔴 L'APERÇU ÉTAIT LE JUMEAU OUBLIÉ DE LA DATE.
        //
        // `lastMessageAt` était protégé par `maxOf` contre un recul dans le temps — un import par
        // lots, ou un message retardé par le réseau, ne devait pas faire remonter une vieille date.
        // La ligne suivante écrasait pourtant `lastMessagePreview` **sans la même garde**.
        //
        // Résultat : la conversation gardait sa date récente, restait donc en haut de la liste,
        // mais affichait le texte d'un message parfois vieux de plusieurs années. Deux champs qui
        // décrivent le MÊME message et qui pouvaient se contredire.
        //
        // On n'écrit désormais l'aperçu que si ce message est bien le plus récent. Le `>=` couvre
        // l'égalité stricte : deux messages à la même milliseconde, le dernier arrivé gagne, ce
        // qui reste le comportement d'avant.
        val isNewest = date >= current.lastMessageAt
        conversationDao.update(
            current.copy(
                lastMessageAt = maxOf(date, current.lastMessageAt),
                lastMessagePreview =
                if (isNewest) preview.take(MAX_PREVIEW) else current.lastMessagePreview,
                unreadCount = if (resetUnread) 0 else (current.unreadCount + deltaUnread).coerceAtLeast(0),
                stickyCategory = if (resetUnread) null else current.stickyCategory,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Client mode (C2) — the client mirror. These two methods are the
    // ONLY writers of synced rows: every `conversations` /
    // `messages_batch` / `message_new` / `message_updated` /
    // `conversation_updated` frame funnels through them (via
    // `sync.client.ClientMirror`). The hub id is the upsert key, so a
    // re-sync is idempotent — the same hub row always lands on the
    // same local row, and the UNIQUE indexes on the `hub_*_id`
    // columns are the backstop. Nothing here touches the system
    // telephony provider: client rows keep `threadId = null` and
    // `telephonyUri = null`, so no system propagation can ever fire
    // for them.
    // ------------------------------------------------------------------

    /**
     * Client mode — upserts one synced conversation (snapshot row or
     * `conversation_updated`). Hub values are authoritative and are
     * applied as-is: preview, date, unread count, pin/mute/archive.
     *
     * The row is found by hub id first. When no row carries this hub
     * id yet, [ensureConversation] matches by address (so a
     * conversation the user already had locally — e.g. from before
     * the switch to client mode — is adopted, not duplicated) and the
     * hub id is stamped on the match — UNLESS the match already
     * carries a DIFFERENT hub id (a false friend: two hub
     * conversations sharing an address set), in which case a fresh
     * row is inserted instead of stealing the match.
     *
     * A pinned message that has not arrived yet is reported back in
     * [SyncedConversationResult.unresolvedPinnedHubMessageId] — the
     * engine keeps it pending and pins it when the message lands.
     * `unread == 0` is the hub's read signal: the cached messages
     * are marked read too, so the thread view agrees with the list.
     *
     * `isBlocked` is intentionally NOT stored: there is no `blocked`
     * column on `ConversationEntity` (blocking is derived at read
     * time from the local block list). Block-list sync needs a design
     * decision; see the C2 report.
     */
    suspend fun upsertSyncedConversation(
        data: SyncedConversationData,
    ): SyncedConversationResult = withContext(io) {
        database.withTransaction {
            val existing = conversationDao.findByHubConversationId(data.hubConversationId)
            val localId = if (existing != null) {
                existing.id
            } else {
                val addresses = data.addresses.map { PhoneAddress.of(it) }
                val matchedId = ensureConversation(addresses)
                val matched = conversationDao.findById(matchedId)
                when {
                    matched == null -> {
                        // ensureConversation always returns a live row;
                        // a null read means the row vanished mid-flight
                        // (wipe raced us) — insert fresh.
                        insertSyncedConversationRow(data)
                    }
                    matched.hubConversationId != null &&
                        matched.hubConversationId != data.hubConversationId -> {
                        // False friend: the address match belongs to
                        // another hub conversation. Insert a fresh row
                        // instead of stealing it.
                        insertSyncedConversationRow(data)
                    }
                    matched.hubConversationId == null -> {
                        // Adopt the match (or the row ensureConversation
                        // just created) and stamp it with the hub id.
                        conversationDao.update(
                            matched.copy(hubConversationId = data.hubConversationId),
                        )
                        matchedId
                    }
                    else -> matchedId
                }
            }
            // One update path for both branches: the hub's values win.
            val row = conversationDao.findById(localId)
                ?: return@withTransaction SyncedConversationResult(localId, existing == null)
            val pinnedLocalId = data.pinnedMessageHubId?.let {
                messageDao.findByHubMessageId(it)?.id
            }
            conversationDao.update(
                row.copy(
                    addressesCsv = data.addresses
                        .map { PhoneAddress.of(it) }
                        .sortedBy { it.normalized }
                        .toCsv(),
                    displayName = data.displayName ?: row.displayName,
                    customName = data.customName,
                    lastMessageAt = data.lastMessageAt,
                    lastMessagePreview = data.lastMessagePreview,
                    unreadCount = data.unreadCount,
                    pinned = data.pinned,
                    archived = data.archived,
                    muted = data.muted,
                    pinnedMessageId = when {
                        data.pinnedMessageHubId == null -> null
                        pinnedLocalId != null -> pinnedLocalId
                        else -> row.pinnedMessageId
                    },
                    pinnedMessageAt = when {
                        data.pinnedMessageHubId == null -> null
                        pinnedLocalId != null -> data.pinnedMessageAt
                        else -> row.pinnedMessageAt
                    },
                ),
            )
            if (data.unreadCount == 0) messageDao.markConversationRead(localId)
            SyncedConversationResult(
                localId = localId,
                created = existing == null,
                unresolvedPinnedHubMessageId =
                if (data.pinnedMessageHubId != null && pinnedLocalId == null) {
                    data.pinnedMessageHubId
                } else {
                    null
                },
                pinnedMessageAt = data.pinnedMessageAt,
            )
        }
    }

    /**
     * Client mode — upserts one synced message (`messages_batch` /
     * `message_new` / `message_updated`). Found by hub id; the update
     * path patches only the live-mutable fields (status, read,
     * starred, reaction, reply target, error code) — body, type,
     * direction, date and address never change after a sync. The E2E
     * flags (`encrypted`, `decrypt_failed`) ride the update too: they
     * are immutable in practice, but a re-sync re-sends the message
     * and the stored row must agree with the frame.
     *
     * When the conversation is unknown (a live message beat its
     * `conversation_updated`, or a gap-fill reply for a pruned
     * thread), a stub conversation is created from the sender so the
     * message has a home; the next conversation frame fills in the
     * real values. The touch passes `deltaUnread = 0`: the hub's
     * unread count is authoritative and is set by the conversation
     * frames, so a message must never bump the counter itself.
     *
     * The reply target resolves only when the target has already
     * arrived; an unresolvable target leaves the stored value alone
     * (a later `message_updated` for the reply, or the target's own
     * arrival followed by a re-sync, heals it).
     */
    suspend fun upsertSyncedMessage(data: SyncedMessageData): SyncedMessageResult =
        withContext(io) {
            database.withTransaction {
                val existing = messageDao.findByHubMessageId(data.hubMessageId)
                if (existing != null) {
                    val replyLocalId = data.replyToHubMessageId?.let {
                        messageDao.findByHubMessageId(it)?.id
                    }
                    messageDao.update(
                        existing.copy(
                            status = data.status,
                            read = data.read,
                            starred = data.starred,
                            reactionEmoji = data.reaction,
                            errorCode = data.errorCode,
                            replyToMessageId = replyLocalId ?: existing.replyToMessageId,
                            clientMessageId = data.clientMessageId ?: existing.clientMessageId,
                            encrypted = data.encrypted,
                            decryptFailed = data.decryptFailed,
                        ),
                    )
                    upsertSyncedAttachments(attachmentDao, existing.id, data.attachments)
                    return@withTransaction SyncedMessageResult(
                        localId = existing.id,
                        inserted = false,
                        unresolvedReplyToHubMessageId =
                        if (data.replyToHubMessageId != null && replyLocalId == null) {
                            data.replyToHubMessageId
                        } else {
                            null
                        },
                    )
                }

                // Client mode C3 — reconcile by `client_message_id`:
                // the hub echoes the key on the sent row, so a live
                // `message_new` for our own send lands on the optimistic
                // row instead of duplicating it. The hub's values win
                // (signed body, server date, status); the local id stays
                // stable, so the thread never flickers. Only a row that
                // has not been reconciled yet (no hub id) matches — a
                // row the `message_sent` ack already stamped is caught
                // by the hub-id branch above.
                if (data.clientMessageId != null) {
                    val optimistic = messageDao.findByClientMessageId(data.clientMessageId)
                    if (optimistic != null && optimistic.hubMessageId == null) {
                        val keyReplyLocalId = data.replyToHubMessageId?.let {
                            messageDao.findByHubMessageId(it)?.id
                        }
                        messageDao.update(
                            optimistic.copy(
                                hubMessageId = data.hubMessageId,
                                body = data.body,
                                date = data.date,
                                dateSent = data.dateSent,
                                status = data.status,
                                read = data.read,
                                starred = data.starred,
                                reactionEmoji = data.reaction,
                                errorCode = data.errorCode,
                                replyToMessageId = keyReplyLocalId ?: optimistic.replyToMessageId,
                                address = data.address,
                                attachmentsCount = data.attachments.size,
                                // The hub encrypted our send (the
                                // optimistic row was written plain) —
                                // the echo carries the real flags.
                                encrypted = data.encrypted,
                                decryptFailed = data.decryptFailed,
                            ),
                        )
                        upsertSyncedAttachments(attachmentDao, optimistic.id, data.attachments)
                        // Round 35 — a hub echo of our own send un-sticks
                        // the thread. The touch here must not reset the
                        // hub-owned unread count, so the clear is
                        // explicit rather than riding `resetUnread`.
                        if (data.direction == MessageDirection.OUTGOING) {
                            stickyCategories.onOutgoingMessage(optimistic.conversationId)
                        }
                        touchConversation(
                            convId = optimistic.conversationId,
                            date = data.date,
                            preview = syncedMessagePreview(data),
                            // The hub's unread count is authoritative —
                            // a message must never bump it itself.
                            deltaUnread = 0,
                        )
                        return@withTransaction SyncedMessageResult(
                            localId = optimistic.id,
                            inserted = false,
                            unresolvedReplyToHubMessageId =
                            if (data.replyToHubMessageId != null && keyReplyLocalId == null) {
                                data.replyToHubMessageId
                            } else {
                                null
                            },
                        )
                    }
                }

                val convId = conversationDao.findByHubConversationId(data.hubConversationId)?.id
                    ?: stubSyncedConversation(data.hubConversationId, data.address)
                val replyLocalId = data.replyToHubMessageId?.let {
                    messageDao.findByHubMessageId(it)?.id
                }
                var localId = messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        telephonyUri = null,
                        address = data.address,
                        body = data.body,
                        type = data.type,
                        direction = data.direction,
                        date = data.date,
                        dateSent = data.dateSent,
                        read = data.read,
                        starred = data.starred,
                        status = data.status,
                        errorCode = data.errorCode,
                        attachmentsCount = data.attachments.size,
                        replyToMessageId = replyLocalId,
                        reactionEmoji = data.reaction,
                        clientMessageId = data.clientMessageId,
                        hubMessageId = data.hubMessageId,
                        encrypted = data.encrypted,
                        decryptFailed = data.decryptFailed,
                    ),
                )
                if (localId == -1L) {
                    // IGNORE conflict: the UNIQUE hub id raced us (the
                    // same message arrived twice in one batch window).
                    // Reconcile by the hub id, like the outgoing path
                    // reconciles by `client_message_id`.
                    localId = messageDao.findByHubMessageId(data.hubMessageId)?.id ?: -1L
                }
                if (localId != -1L) {
                    upsertSyncedAttachments(attachmentDao, localId, data.attachments)
                    // Round 35 — the client computes its sticky category
                    // locally from the frame's full body (no protocol
                    // change): an incoming message files, an outgoing
                    // one un-sticks. BEFORE the touch, for the store's
                    // newest-wins guard.
                    if (data.direction == MessageDirection.OUTGOING) {
                        stickyCategories.onOutgoingMessage(convId)
                    } else {
                        stickyCategories.onIncomingMessage(
                            convId,
                            data.body,
                            PhoneAddress.of(data.address),
                            data.date,
                        )
                    }
                    touchConversation(
                        convId = convId,
                        date = data.date,
                        preview = syncedMessagePreview(data),
                        // The hub's unread count is authoritative —
                        // a message must never bump it itself.
                        deltaUnread = 0,
                    )
                }
                SyncedMessageResult(
                    localId = localId,
                    inserted = localId != -1L,
                    unresolvedReplyToHubMessageId =
                    if (data.replyToHubMessageId != null && replyLocalId == null) {
                        data.replyToHubMessageId
                    } else {
                        null
                    },
                )
            }
        }

    /**
     * Client mode — inserts a fresh synced conversation row (the
     * false-friend branch of [upsertSyncedConversation], and the
     * vanished-match fallback). The hub's values go straight in;
     * `threadId` stays null — the hub's AOSP thread id means nothing
     * on this device.
     */
    private suspend fun insertSyncedConversationRow(data: SyncedConversationData): Long =
        conversationDao.insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = data.addresses
                    .map { PhoneAddress.of(it) }
                    .sortedBy { it.normalized }
                    .toCsv(),
                displayName = data.displayName,
                customName = data.customName,
                lastMessageAt = data.lastMessageAt,
                lastMessagePreview = data.lastMessagePreview,
                unreadCount = data.unreadCount,
                pinned = data.pinned,
                archived = data.archived,
                muted = data.muted,
                hubConversationId = data.hubConversationId,
            ),
        )

    /**
     * Client mode — the stub home for a message whose conversation
     * has not arrived yet: [ensureConversation] by sender address,
     * stamped with the hub id — or a fresh row when the match is a
     * false friend. The next conversation frame fills in the real
     * values (title, preview, unread).
     */
    private suspend fun stubSyncedConversation(hubConversationId: Long, senderAddress: String): Long {
        val stubId = ensureConversation(listOf(PhoneAddress.of(senderAddress)))
        val stub = conversationDao.findById(stubId)
        return when {
            stub == null -> conversationDao.insert(
                ConversationEntity(
                    threadId = null,
                    addressesCsv = listOf(PhoneAddress.of(senderAddress)).toCsv(),
                    displayName = null,
                    lastMessageAt = 0L,
                    lastMessagePreview = null,
                    hubConversationId = hubConversationId,
                ),
            )
            stub.hubConversationId == null -> {
                conversationDao.update(stub.copy(hubConversationId = hubConversationId))
                stubId
            }
            stub.hubConversationId == hubConversationId -> stubId
            // False friend: the sender match belongs to another hub
            // conversation — a fresh stub instead of stealing it.
            else -> conversationDao.insert(
                ConversationEntity(
                    threadId = null,
                    addressesCsv = listOf(PhoneAddress.of(senderAddress)).toCsv(),
                    displayName = null,
                    lastMessageAt = 0L,
                    lastMessagePreview = null,
                    hubConversationId = hubConversationId,
                ),
            )
        }
    }

    private companion object {
        const val MAX_PREVIEW = 240

        /**
         * v1.3.7 (F5 audit) — borne LRU du cache `displayNameCache`. 1000 entrées couvre largement
         * un compte normal (correspondants distincts + senders alphanumériques opérateurs/banques).
         * Au-delà, l'éviction LRU drop les expéditeurs anciens d'abord — les conversations actives
         * restent toujours résolues en cache chaud sans coût ContentProvider.
         */
        const val DISPLAY_NAME_CACHE_MAX = 1000

        /**
         * Borne du fallback fuzzy de fold Tapback. 500 messages couvrent les longs
         * fils (même ordre de grandeur que la limite de 500 de Qik) ; le coût
         * mémoire reste négligeable (~500 × 200 B = 100 KB), une seule query
         * Room supplémentaire grâce au LIMIT côté DAO — déclenchée UNIQUEMENT
         * quand la LIKE rapide a échoué (cas body multi-ligne).
         */
        const val TAPBACK_FALLBACK_LOOKUP_LIMIT = 500

        /**
         * Tolérance de date du matching Tapback : un message ne peut être la
         * cible d'une réaction que s'il est daté d'au plus 60 s APRÈS la
         * réaction (l'ordre MMS peut décaler les dates). Un message plus récent
         * que la réaction ne peut pas être ce à quoi on a réagi.
         */
        const val REACTION_DATE_TOLERANCE_MS: Long = 60_000L

        /**
         * E2E (design §3.2) — magic prefix of a ciphertext fragment SMS.
         * Mirrors `E2eWireFormat.MAGIC`, which lives in `:app` and is
         * unreachable from `:data`; `bulkImportFromTelephony` skips rows
         * starting with it. If the wire format ever changes its magic,
         * this literal must change with it.
         */
        const val E2E_FRAGMENT_PREFIX = "RLY1:"

        /**
         * E2E (round 45) — magic prefix of a handshake SMS (a probe or
         * bundle carried over regular SMS). Mirrors
         * `E2eHandshakeSms.MAGIC`, which lives in `:app` and is
         * unreachable from `:data`; `bulkImportFromTelephony` skips
         * rows starting with it, so a handshake SMS never resurfaces
         * as a bubble. If the wire format ever changes its magic,
         * this literal must change with it.
         */
        const val E2E_HANDSHAKE_PREFIX = "RLYH:"
    }
}

/**
 * v1.26.1 (audit H13) — nombre minimal de chiffres exigé pour rapprocher deux écritures
 * d'un même correspondant.
 *
 * ⚠️ HUIT, et non neuf. Une première version exigeait 9, par symétrie avec
 * `BLOCK_KEY_SIGNIFICANT_DIGITS`. La revue a montré que c'était une RÉGRESSION : comme
 * `blockKey()` PLAFONNE déjà à 9 chiffres, exiger `length >= 9` revenait à exiger
 * exactement 9 — donc à ne plus jamais rapprocher un numéro national à 8 chiffres
 * significatifs (Singapour, Hong Kong…), qui matchait pourtant très bien avant.
 *
 * Le seuil ne sert qu'à écarter les codes courts, qu'on ne doit jamais rapprocher par
 * suffixe. C'est `blockKey()` — et non ce seuil — qui apporte la discrimination : deux
 * numéros français produisent deux clés de 9 chiffres DIFFÉRENTES, donc le défaut
 * `0612345678` / `0712345678` reste corrigé, la comparaison étant une égalité stricte
 * entre chaînes de même longueur.
 *
 * v1.27.2 (audit externe 2026-08-04 #1) — sorti du companion PRIVÉ de [ConversationMirror]
 * en top-level `internal` : le même seuil garde désormais le jumeau composition
 * [ConversationRepositoryImpl.matchOneToOneByBlockKey]. Une valeur dupliquée aurait pu
 * dériver et rouvrir l'asymétrie réception/composition que ce correctif ferme.
 */
internal const val CONV_MATCH_MIN_DIGITS = 8

/**
 * Wire prefixes a reaction text can start with (the six tapback verbs,
 * `Reacted …`, `Removed …`, the French `Réagi …` / `J'ai réagi …` forms) —
 * the prefix arms of `MessageDao.findReactionCandidates`, for the cheap
 * pre-filter in [looksLikeReactionText]. A superset of what the decoder
 * matches; `decode()` re-validates strictly. File scope (not a class
 * member): the check is pure, and `ConversationMirror` is already at its
 * function-count limit.
 */
private val REACTION_TEXT_PREFIXES = listOf(
    "Reacted ",
    "Loved ",
    "Liked ",
    "Disliked ",
    "Laughed at ",
    "Emphasized ",
    "Questioned ",
    "Removed ",
    "Réagi ",
    "J'ai réagi ",
    "J’ai réagi ",
)

/**
 * Cheap pre-filter for the import fold: mirrors the LIKE arms of
 * `MessageDao.findReactionCandidates` (a superset of what the decoder can
 * match) so the full decode only runs on bodies shaped like a reaction.
 * False positives are harmless — `decode()` re-validates strictly.
 */
private fun looksLikeReactionText(body: String): Boolean {
    if (body.isEmpty()) return false
    if (REACTION_TEXT_PREFIXES.any { body.startsWith(it) }) return true
    return body.contains(" a réagi par ") || body.contains('»')
}

/**
 * Client mode — upserts one message's synced attachment metadata by
 * hub id. File scope (not a class member): `ConversationMirror` is
 * already at its function-count limit, and this only needs the DAO.
 *
 * The update path refreshes metadata but NEVER touches `localUri`:
 * that is C5's download, and a re-sync must not drop a file the
 * device already fetched. New rows get `localUri = ""` — the bubble
 * degrades gracefully on a blank URI (placeholder, no crash) until
 * C5 fills it in.
 *
 * Client mode C5 — the reconcile link: when the synced message is
 * this device's own send, its optimistic attachment rows (no hub
 * id) are deleted and their durable files linked onto the synced
 * rows, by position + size (the desktop's `linkStagedFiles` rule:
 * the hub stores the exact uploaded bytes, so an equal size is the
 * same file). A size mismatch links nothing — the bubble falls back
 * to download-on-demand.
 */
private suspend fun upsertSyncedAttachments(
    attachmentDao: AttachmentDao,
    messageId: Long,
    attachments: List<SyncedAttachmentData>,
) {
    // Snapshot the optimistic rows BEFORE the upsert deletes them:
    // they are the only record of where the durable files live.
    val optimistic = attachmentDao.findForMessage(messageId)
        .filter { it.hubAttachmentId == null }
        .sortedBy { it.id }
    if (optimistic.isNotEmpty()) {
        attachmentDao.deleteOptimisticForMessage(messageId)
    }
    for (att in attachments) {
        val existing = attachmentDao.findByHubAttachmentId(att.hubAttachmentId)
        if (existing != null) {
            attachmentDao.update(
                existing.copy(
                    messageId = messageId,
                    mimeType = att.mimeType,
                    fileName = att.fileName,
                    sizeBytes = att.sizeBytes,
                    width = att.width,
                    height = att.height,
                    durationMs = att.durationMs,
                ),
            )
        } else {
            attachmentDao.insert(
                AttachmentEntity(
                    messageId = messageId,
                    mimeType = att.mimeType,
                    fileName = att.fileName,
                    sizeBytes = att.sizeBytes,
                    // No bytes yet — the download is C5. A blank
                    // URI renders as a placeholder, never a crash.
                    localUri = "",
                    width = att.width,
                    height = att.height,
                    durationMs = att.durationMs,
                    hubAttachmentId = att.hubAttachmentId,
                ),
            )
        }
    }
    if (optimistic.isNotEmpty()) {
        linkOptimisticFiles(attachmentDao, messageId, optimistic)
    }
}

/**
 * Client mode C5 — links the optimistic rows' durable files onto
 * the synced rows, by position + size. Only pairs whose sizes match
 * (and whose staged file is non-blank) are linked; the rest keep
 * their blank URI and download on demand.
 */
private suspend fun linkOptimisticFiles(
    attachmentDao: AttachmentDao,
    messageId: Long,
    optimistic: List<AttachmentEntity>,
) {
    val synced = attachmentDao.findForMessage(messageId)
        .filter { it.hubAttachmentId != null }
        .sortedBy { it.id }
    val linked = optimistic.zip(synced).filter { (staged, target) ->
        staged.localUri.isNotBlank() && staged.sizeBytes == target.sizeBytes
    }
    for ((staged, target) in linked) {
        attachmentDao.updateLocalUri(target.id, staged.localUri)
    }
}

/**
 * Client mode — the conversation-list preview for a synced message:
 * the body when it has one, else the media label of the first
 * attachment (same emoji + file-name shape as [ConversationMirror]'s
 * `mediaPreviewLabel`, so a caption-less MMS never blanks the
 * preview the hub's conversation frame already set).
 */
private fun syncedMessagePreview(data: SyncedMessageData): String {
    if (data.body.isNotBlank()) return data.body
    val first = data.attachments.firstOrNull() ?: return ""
    val label = when {
        first.mimeType.startsWith("image/") -> "🖼️"
        first.mimeType.startsWith("video/") -> "🎞️"
        first.mimeType.startsWith("audio/") -> "🎤"
        first.mimeType == "text/x-vcard" || first.mimeType == "text/vcard" -> "👤"
        else -> "📎"
    }
    return if (first.fileName != null) "$label ${first.fileName}" else label
}
