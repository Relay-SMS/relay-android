package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.filestech.sms.R
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.e2e.E2eHandshakeHandler
import com.filestech.sms.e2e.E2eHandshakeSmsGate
import com.filestech.sms.e2e.E2eReceiveManager
import com.filestech.sms.e2e.E2eWireFormat
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Fires when an SMS is delivered to this app (as the default SMS app).
 *
 * Responsibility:
 *  1. Reconstruct SmsMessage(s) from the PDU array
 *  2. Drop if the sender is in our blocklist
 *  3. Insert into the system inbox (the OS doesn't do it for us anymore — default SMS app's job)
 *  4. Mirror to Room
 *  5. Trigger a notification
 */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {

    // v1.24.0 SEC-CRIT — tous ces collaborateurs atteignent un DAO, donc `AppDatabase`, donc la
    // réparation zéro-clé. L'injection de champ Hilt a lieu AVANT le corps de `onReceive`, sur le
    // main thread : les résoudre en eager y exécutait plusieurs secondes de reconstruction de base,
    // avec un timeout ANR de broadcast de 10 s — et le SMS entrant perdu. C'est le chemin de
    // démarrage dominant d'une app SMS (le processus est mort la plupart du temps), donc le plus
    // probable pour le lancement de réparation. Résolution différée dans la coroutine ci-dessous.
    @Inject lateinit var telephonyReaderLazy: dagger.Lazy<TelephonyReader>

    @Inject lateinit var mirrorLazy: dagger.Lazy<ConversationMirror>

    @Inject lateinit var notifierLazy: dagger.Lazy<IncomingMessageNotifier>

    /**
     * v1.6.1 (audit QUAL-10) — passe désormais par le Repository plutôt que d'accéder
     * directement au DAO. Garde la couche system décorrélée de la couche data/db et
     * profite des invariants Repository (mapper Entity → Domain) au lieu de manipuler
     * une row Room nue.
     */
    @Inject lateinit var conversationRepoLazy: dagger.Lazy<ConversationRepository>

    /**
     * v1.28.3 (F26) — remplace l'injection directe de `BlockedNumberRepository` : les deux
     * regles d'ecartement — liste noire et « numero inconnu » — vivent desormais au meme
     * endroit, et ce receveur n'a plus a en connaitre le detail.
     */
    @Inject lateinit var blockPolicyLazy: dagger.Lazy<IncomingBlockPolicy>

    /**
     * E2E — the ciphertext fragment path. Resolved lazily like the rest:
     * it reaches the Room database through the fragment store.
     */
    @Inject lateinit var e2eReceiveManagerLazy: dagger.Lazy<E2eReceiveManager>

    /**
     * E2E (round 45) — the handshake-over-SMS path. Resolved lazily
     * like the rest: it reaches the Room database through the peer
     * store.
     */
    @Inject lateinit var handshakeHandlerLazy: dagger.Lazy<E2eHandshakeHandler>

    /**
     * E2E (round 45) — the client-mode gate for the handshake-SMS
     * branch (see [handleE2eHandshakeSms]). Resolved lazily like the
     * rest; the settings read itself is a suspend call.
     */
    @Inject lateinit var settingsLazy: dagger.Lazy<AppSettingsSource>

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @Inject
    @com.filestech.sms.di.IoDispatcher
    lateinit var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        // v1.22.0 (double SIM) — SIM d'arrivée du SMS. Alimente `messages.sub_id` (Room) et
        // `SUBSCRIPTION_ID` côté provider système, ce qui permet d'afficher un tag SIM par
        // message et fiabilise les accusés de réception sur la SIM secondaire. `null` =
        // comportement historique (aucune colonne SIM écrite).
        val subId = intent.extractIncomingSubId()
        val pending = goAsync()
        scope.launch {
            // v1.27.2 (audit externe 2026-08-04 #2) — état partagé avec le `catch` pour le filet
            // de dernier recours : tant que le SMS n'a été ni écrit dans la boîte système ni
            // écarté volontairement par la liste noire, une exception ne doit pas consommer le
            // broadcast en silence. L'app est gestionnaire SMS par défaut : un broadcast consommé
            // sans persistance est un message définitivement perdu, nulle part.
            var salvageAddress: String? = null
            var salvageBody: String? = null
            var salvageTs = 0L
            var persistedToSystem = false
            var droppedByBlocklist = false
            try {
                // v1.27.2 (audit externe 2026-08-04 #2) — le PDU est décodé AVANT la résolution
                // des collaborateurs : le décodage ne touche ni Hilt ni la base. Si la
                // construction de la base échoue plus bas (réparation zéro-clé, stockage
                // indisponible), le `catch` connaît donc déjà l'adresse et le corps et peut
                // écrire le SMS dans la boîte système en dernier recours.
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: emptyArray()
                // One combined guard (was two early exits): keeps the launch
                // block to four early exits — the reaction-removal exit is
                // folded into the applied branch, and the fragment exit
                // takes its place.
                val address = messages.firstOrNull()?.displayOriginatingAddress
                    ?.stripInvisibleChars()
                if (messages.isEmpty() || address == null) return@launch
                val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                // Audit F22: strip bidi overrides + zero-width chars that would let a spam SMS
                // spoof its visible origin or sneak past content moderation.
                // Keep the RAW body too: Google Messages reactions are framed with
                // U+200A/U+200B, and stripping removes U+200B — the framing the
                // decoder needs. The raw body is only used for that one decode.
                val rawBody = buildString {
                    messages.forEach { sm ->
                        append(sm.displayMessageBody.orEmpty())
                    }
                }
                val body = rawBody.stripInvisibleChars()
                salvageAddress = address
                salvageBody = body
                salvageTs = ts
                // Résolution ici, DANS le `try` : un échec doit passer par le `finally` qui appelle
                // `pending.finish()`, sinon le broadcast n'est jamais libéré (ANR) et l'exception
                // remonte au scope applicatif. Et sur le dispatcher IO : c'est `mirrorLazy` qui
                // construit `AppDatabase` (`telephonyReader` ne prend qu'un Context), et le scope
                // tourne sur `Dispatchers.Default` — y bloquer un worker pendant la réparation
                // affamerait le pool CPU au lancement précis où tout en a besoin.
                val deps = withContext(ioDispatcher) {
                    Collaborators(
                        telephonyReader = telephonyReaderLazy.get(),
                        mirror = mirrorLazy.get(),
                        notifier = notifierLazy.get(),
                        conversationRepo = conversationRepoLazy.get(),
                        blockPolicy = blockPolicyLazy.get(),
                        e2eReceiveManager = e2eReceiveManagerLazy.get(),
                        handshakeHandler = handshakeHandlerLazy.get(),
                        settings = settingsLazy.get(),
                    )
                }
                val telephonyReader = deps.telephonyReader
                val mirror = deps.mirror
                val notifier = deps.notifier
                val conversationRepo = deps.conversationRepo
                // v1.27.2 (audit externe 2026-08-04 #2) — repli OUVERT via [isBlockedFailOpen] :
                // une erreur de consultation (Room/SQLCipher) court-circuitait `insertInboxSms`
                // plus bas et le SMS n'était écrit nulle part. Désormais l'erreur laisse passer
                // le message ; seul un `true` franc écarte.
                // v1.28.3 (F26) — la politique couvre desormais AUSSI « bloquer les numeros
                // inconnus », un reglage affiche et persiste que rien n'appliquait. Cf.
                // [IncomingBlockPolicy] pour le sens de l'echec, qui est ici l'essentiel : une
                // permission contacts revoquee ne doit pas se traduire par « tout le monde est
                // inconnu », donc par un telephone qui ne recoit plus rien.
                if (deps.blockPolicy.doitEcarter(address)) {
                    droppedByBlocklist = true
                    Timber.i("Dropping incoming SMS from blocked sender")
                    return@launch
                }
                // E2E — a ciphertext fragment is never a visible message: it
                // enters the fragment path (reassembly + decrypt), which
                // inserts ONE message only once the set is complete and
                // decrypted. The fragment SMS is still written to the system
                // inbox (a real SMS on the air, and the default-SMS-app
                // duty); the bulk import skips `RLY1:` bodies, so it never
                // re-surfaces as a bubble.
                if (handleE2eFragment(deps, address, body, ts, subId)) {
                    persistedToSystem = true
                    return@launch
                }
                // E2E (round 45) — a handshake SMS is never a visible
                // message either: it enters the handshake path (probe
                // → bundle reply, bundle → session), which stores
                // nothing as a message. Like a fragment, it is still
                // written to the system inbox (the default-SMS-app
                // duty); the bulk import skips `RLYH:` bodies, so it
                // never re-surfaces as a bubble. The magics are
                // distinct (`RLYH:` vs `RLY1:`), so the order of the
                // two branches cannot matter — the fragment branch
                // runs first because it is the older path.
                if (handleE2eHandshakeSms(deps, address, body, ts, subId)) {
                    persistedToSystem = true
                    return@launch
                }
                // v1.4.1 — if the body looks like a Tapback reaction back from another
                // Relay / iMessage / Google Messages, try to fold it onto the
                // original message instead of inserting a noisy "Reacted ❤️
                // to «…»" text bubble. The decoder is intentionally strict (a real
                // one-emoji SMS like "❤️" is left alone). On miss, fall through to
                // the standard insert path so legitimate text SMS are never swallowed.
                // Google Messages reactions are decoded from the RAW body first:
                // their U+200A/U+200B framing does not survive `stripInvisibleChars`.
                val decoded = IncomingReactionDecoder.decodeGoogleFormat(rawBody)
                    ?: IncomingReactionDecoder.decode(body)
                if (decoded != null) {
                    // v1.27.2 (audit externe 2026-08-04 #2) — un échec du fold (base
                    // indisponible) retombe sur le chemin d'insert standard au lieu de remonter
                    // au `catch` : ce chemin s'exécute AVANT `insertInboxSms`, son exception
                    // perdait donc le SMS entier alors qu'un Tapback non replié n'est qu'une
                    // bulle de texte en trop. `CancellationException` relancée, comme partout.
                    val applied = try {
                        mirror.applyIncomingReaction(
                            address = address,
                            emoji = decoded.emoji,
                            bodyPrefix = decoded.previewPrefix,
                            kind = decoded.kind,
                            // v1.6.2 — propage le flag de troncature pour que le matcher
                            // choisisse entre exact match (court) et préfixe (long tronqué).
                            wasTruncated = decoded.wasTruncated,
                            isRemoval = decoded.isRemoval,
                            reactionDateMs = ts,
                        )
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Timber.w(t, "applyIncomingReaction failed — falling back to the standard insert path")
                        null
                    }
                    if (applied != null) {
                        // Still write the row to the system inbox so other SMS apps on
                        // the device see the message in their history (legal duty as
                        // default SMS app).
                        val sysUri = telephonyReader.insertInboxSms(address, body, ts, subId)
                        persistedToSystem = true
                        // v1.4.1 (SEC-01) — drop a poison-pill Room row carrying the
                        // same `telephonyUri` so the next [TelephonySyncManager] sweep
                        // sees the UNIQUE constraint already taken and skips the
                        // re-import — otherwise the user would see a phantom text
                        // bubble "Reacted ❤️ to «…»" duplicating the badge.
                        mirror.upsertReactionSentinel(
                            address = address,
                            telephonyUri = sysUri?.toString(),
                            date = ts,
                        )
                        // A removal only clears the badge — there is nothing to
                        // notify about, and the carrier text must still not
                        // surface as a bubble.
                        if (!decoded.isRemoval) {
                            // v1.6.1 — fix : poste une notification système pour que
                            // l'expéditeur du message d'origine sache qu'on a réagi à son
                            // message (parité iMessage / Google Messages). Avant v1.6.1 le
                            // badge se mettait à jour silencieusement, ce qui faisait
                            // croire que la fonction "envoyer ma réaction" ne marchait pas.
                            // Le body de la notif est localisé via [R.string
                            // .reaction_notif_body_with_preview] / `_no_preview` selon que
                            // le message ciblé avait un texte ou non (cas voice MMS / image
                            // sans légende). `previewMode` côté [IncomingMessageNotifier]
                            // s'occupe encore de masquer le contenu si l'utilisateur a
                            // choisi de cacher les aperçus.
                            // v1.6.1 (audit SEC-07) — strip Bidi/RLO/ZWSP du body OUTGOING
                            // avant injection dans la string de notif. Le body d'un message
                            // sortant n'est PAS passé par `stripInvisibleChars` à l'écriture
                            // (contrairement au body entrant) car il vient de l'utilisateur ;
                            // mais un copier-coller depuis le web peut contenir des contrôles
                            // BiDi qui inverseraient visuellement la notif.
                            val targetPreview = applied.targetBody.stripInvisibleChars().trim().take(80)
                            val notifBody = if (targetPreview.isEmpty()) {
                                context.getString(
                                    R.string.reaction_notif_body_no_preview,
                                    decoded.emoji,
                                )
                            } else {
                                context.getString(
                                    R.string.reaction_notif_body_with_preview,
                                    decoded.emoji,
                                    targetPreview,
                                )
                            }
                            notifier.notifyIncoming(
                                address = address,
                                body = notifBody,
                                messageId = applied.targetMessageId,
                                conversationId = applied.conversationId,
                            )
                        }
                        return@launch
                    }
                    // Decoded but no matching outgoing message (the user removed it, or
                    // the reaction came from a third party we never wrote to). Fall
                    // through and store the body verbatim so nothing is silently lost.
                    // v1.4.1 (SEC-04) — phone address dropped from the log line so a
                    // debug-build logcat capture cannot leak PII (consistent with the
                    // blocklist-drop log just above, which also omits the address).
                    Timber.i("Tapback decoded but no matching outgoing message found")
                }
                val uri = telephonyReader.insertInboxSms(address, body, ts, subId)
                persistedToSystem = true
                val msgId = mirror.upsertIncomingSms(
                    address = address,
                    body = body,
                    date = ts,
                    telephonyUri = uri?.toString(),
                    subId = subId,
                )
                // v1.3.3 bug #6 — la notification doit porter le conversationId pour que
                // [IncomingMessageNotifier.cancelAllForConversation] (appelée à l'ouverture
                // du thread) puisse l'effacer en utilisant le groupe. Lookup O(1) sur PK,
                // négligeable face au I/O télémetrie déjà fait juste avant.
                //
                // v1.28.3 (audit global, X-09 — mesure sur le S9) — relecture SANS le masque du
                // coffre. `findMessageById` rend `null` pour un message dont la conversation est
                // au coffre (SEC-V1) : chaque SMS recu dans une conversation du coffre tombait
                // donc dans la branche « bug de coherence » ci-dessous, avec son avertissement —
                // et n'etait pas notifie par ACCIDENT, alors que c'est au notificateur de decider
                // de se taire (il le fait, sur `conv.inVault`). Vu en direct des que X-01 a mis
                // les 1-a-1 des membres d'un groupe au coffre.
                val convId = conversationRepo.findMessageForResend(msgId)?.conversationId
                if (convId != null) {
                    notifier.notifyIncoming(
                        address = address,
                        body = body,
                        messageId = msgId,
                        conversationId = convId,
                    )
                    // Round 41 — peer-deleted-Relay fallback: a plain
                    // SMS text from an ENCRYPTED peer means they lost
                    // Relay (downgrade + re-probe + notice). Never
                    // throws into the receiver — the message is
                    // already stored, and a fallback failure must not
                    // lose it. RLY1 fragments and reactions return
                    // above, so only a real text message reaches here.
                    runCatching {
                        deps.e2eReceiveManager.onPlainSmsReceived(address, convId, subId)
                    }.onFailure { Timber.w(it, "E2E: peer-left fallback failed") }
                } else {
                    // Garde théorique : si la row vient d'être insérée elle DOIT exister.
                    // Si on tombe ici, c'est un bug de cohérence — on logue et on skip.
                    Timber.w("SmsDeliverReceiver: message %d not found after insert", msgId)
                }
            } catch (t: Throwable) {
                Timber.w(t, "SmsDeliverReceiver failed")
                // v1.27.2 (audit externe 2026-08-04 #2) — filet de dernier recours. Avant : toute
                // exception levée avant `insertInboxSms` consommait le broadcast sans avoir rien
                // écrit, et le SMS n'existait nulle part. `TelephonyReader` ne dépend que du
                // Context : il reste utilisable même quand Room/SQLCipher est mort. La ligne
                // système sera ré-importée en Room par [TelephonySyncManager] à la prochaine
                // synchro.
                //
                // ⚠️ v1.27.2 (relecture Codex 2026-08-04) — la version précédente de ce
                // commentaire affirmait « pas de doublon possible ». C'était FAUX. Si le
                // fournisseur système valide l'insertion puis que la liaison Binder échoue avant
                // le retour, l'appel lève alors que la ligne EXISTE : `persistedToSystem` reste
                // faux et ce filet insère une seconde fois. Sémantique « au moins une fois »,
                // pas « exactement une fois ».
                //
                // C'est le compromis VOULU : un doublon se voit et s'efface, une perte est
                // définitive. Mais il devait être écrit tel qu'il est, pas nié.
                if (!persistedToSystem && !droppedByBlocklist) {
                    val addr = salvageAddress
                    val text = salvageBody
                    if (addr != null && text != null) {
                        try {
                            val salvaged = telephonyReaderLazy.get()
                                .insertInboxSms(addr, text, salvageTs, subId)
                            Timber.w("SmsDeliverReceiver: SMS salvaged into the system inbox (uri=%s)", salvaged)
                        } catch (t2: Throwable) {
                            Timber.e(t2, "SmsDeliverReceiver: salvage insert failed — the incoming SMS is lost")
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The E2E fragment branch: `true` when the body parsed as a
     * ciphertext fragment (handled — the caller returns). The fragment
     * is written to the system inbox first, like every SMS; a failure
     * inside the fragment path is logged, not rethrown (except
     * `CancellationException`), so one bad fragment cannot kill the
     * receiver — the sender's retry re-sends the set.
     */
    private suspend fun handleE2eFragment(
        deps: Collaborators,
        address: String,
        body: String,
        ts: Long,
        subId: Int?,
    ): Boolean {
        val fragment = E2eWireFormat.parseFragment(body) ?: return false
        val uri = deps.telephonyReader.insertInboxSms(address, body, ts, subId)
        try {
            deps.e2eReceiveManager.handleFragment(
                address = address,
                fragment = fragment,
                telephonyUri = uri?.toString(),
                dateMs = ts,
                subId = subId,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "E2E: fragment handling failed")
        }
        return true
    }

    /**
     * The E2E handshake-SMS branch (round 45): `true` when the body
     * parsed as a handshake SMS (handled — the caller returns).
     * Thin glue: the decision lives in [E2eHandshakeSmsGate], which
     * is testable on plain JVM (see [com.filestech.sms.e2e
     * .E2eDataSmsGate], the same extraction for the data-SMS
     * receiver).
     */
    private suspend fun handleE2eHandshakeSms(
        deps: Collaborators,
        address: String,
        body: String,
        ts: Long,
        subId: Int?,
    ): Boolean =
        E2eHandshakeSmsGate(deps.telephonyReader, deps.settings, deps.handshakeHandler)
            .handle(address, body, ts, subId)
}

/** Carrier so the collaborators are resolved in a single hop onto the IO dispatcher. */
private class Collaborators(
    val telephonyReader: TelephonyReader,
    val mirror: ConversationMirror,
    val notifier: IncomingMessageNotifier,
    val conversationRepo: ConversationRepository,
    // v1.28.3 (F26) — porte les DEUX regles d'ecartement, liste noire et « numero inconnu ».
    val blockPolicy: IncomingBlockPolicy,
    // E2E — the ciphertext fragment path.
    val e2eReceiveManager: E2eReceiveManager,
    // E2E (round 45) — the handshake-over-SMS path.
    val handshakeHandler: E2eHandshakeHandler,
    // E2E (round 45) — the client-mode gate for the handshake branch.
    val settings: AppSettingsSource,
)
