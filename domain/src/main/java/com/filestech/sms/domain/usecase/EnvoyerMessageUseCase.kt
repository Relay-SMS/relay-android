package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.clientmode.NoOpClientSendGateway
import com.filestech.sms.domain.e2e.E2eGroupSendGate
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.ownnumber.OwnNumberEnsurer
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import javax.inject.Inject

/**
 * v1.28.4 — **l'aiguillage d'un message sortant, écrit UNE fois.**
 *
 * Le fil de conversation et l'envoi programmé décidaient chacun, avec leurs propres mots, si un
 * message part en SMS, en MMS, ou en MMS de groupe. Ils ont divergé : l'envoi programmé depuis un
 * groupe ignorait le réglage « MMS de groupe » que l'envoi immédiat appliquait. La règle vit
 * désormais ici, et lit elle-même le réglage — un appelant ne peut plus l'oublier.
 *
 * La règle : plusieurs destinataires et réglage « MMS de groupe » actif → un seul MMS à tous,
 * texte seul compris ; sinon des pièces jointes → un MMS par destinataire ; sinon un SMS par
 * destinataire. Le message vocal garde son use case propre (charge utile différente).
 *
 * Group-send own-number fix — avant de router un MMS de groupe, le
 * numéro propre est vérifié juste à temps ([OwnNumberEnsurer]) : un
 * envoi de groupe avec un numéro vide est resté bloqué sur
 * « envoi… » sur l'appareil de l'utilisatrice, donc un numéro
 * toujours vide après détection échoue IMMÉDIATEMENT
 * ([AppError.OwnNumberMissing]) — avant toute ligne miroir, pour
 * que rien ne reste coincé en « envoi… ». La voie vocale, qui
 * contourne ce routeur, porte la même garde dans
 * [SendVoiceMmsUseCase].
 *
 * Round 49 (E2E groups, design §13) — an encrypted group TEXT
 * always travels as per-member SMS fragments: the group MMS path
 * has no encrypted-text channel, so the group-MMS setting never
 * applies to it. The router consults the group gate for group
 * texts and routes Encrypted to the SMS path; the decision is
 * re-taken in [SendSmsUseCase] with the final (signed) body, so
 * this pre-check only routes (and refuses an over-cap body before
 * any row is written). Media in groups stays plain — the gate is
 * never consulted when there are attachments.
 */
class EnvoyerMessageUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val sendMediaMms: SendMediaMmsUseCase,
    private val settings: AppSettingsSource,
    private val ownNumberEnsurer: OwnNumberEnsurer,
    /**
     * Client mode C3 — the wire send gateway. Default NoOp keeps the
     * hub tests compiling untouched; Hilt always provides the real one
     * (a missing binding fails the build).
     */
    private val clientSend: ClientSendGateway = NoOpClientSendGateway,
    /**
     * Round 49 — the encrypted-group send gate. Default
     * [E2eGroupSendGate.NoOp] keeps the existing tests compiling
     * untouched (every group routes as before); Hilt always
     * provides the real gate (bound in `E2eModule`).
     */
    private val groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
    /**
     * Round 49 — the 1:1 send gate, for the background probe on a
     * plain group send: the members' exchanges start here (the
     * group-MMS transport never reaches the send hook, so the
     * router probes — [SendSmsUseCase] probes too, for its own
     * path, and the probe manager gates the duplicate). Default
     * [E2eSendGate.NoOp].
     */
    private val e2eGate: E2eSendGate = E2eSendGate.NoOp,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload> = emptyList(),
        subId: Int? = null,
        /** Cible d'une réponse citée — portée par le SMS seulement, comme avant. */
        replyToMessageId: Long? = null,
        /**
         * Slice 3 — clé d'idempotence d'un envoi fait depuis un appareil lié. Transmise telle
         * quelle aux deux branches : une seule ligne la porte — celle que l'appareil affiche
         * (la ligne 1-à-1, la ligne de MMS de groupe, ou l'écho de groupe).
         */
        clientMessageId: String? = null,
        /**
         * Client mode C3 — the local conversation id, when the caller
         * has one. The wire branch resolves the hub conversation from
         * it (falling back to the recipients' canonical CSV); hub mode
         * ignores it.
         */
        conversationId: Long? = null,
    ): Outcome<SendReport> {
        // Client mode C3 — the wire branch sits ABOVE the hub router AND
        // above the own-number guard: a linked device sends via the hub
        // phone, never via telephony, and the guard (a hub-only concern
        // — the ensurer returns true in client mode) must not be
        // reachable from here even by accident. Group text sends take
        // the same branch: the hub owns group MMS, the client just
        // sends to the conversation.
        if ((settings.hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT) {
            // Client mode C5 — media sends take the wire branch too:
            // the gateway prepares, uploads, then sends with the
            // `attachments` array (the C3 fail-fast is gone).
            return if (attachments.isNotEmpty()) {
                clientSend.sendMedia(conversationId, recipients, body, attachments, replyToMessageId, clientMessageId)
            } else {
                clientSend.sendText(conversationId, recipients, body, replyToMessageId, clientMessageId)
            }
        }
        val groupe = recipients.size > 1
        // Round 49 — the encrypted-group decision for a group TEXT
        // send (media in groups stays plain, design §13.7 — the
        // gate is never consulted when there are attachments). An
        // encrypted group routes to the SMS fan-out regardless of
        // the group-MMS setting; a plain group probes its members
        // in the background (sending to the thread is intent to
        // talk — the exchange that flips the group starts here).
        // The decision is re-taken in [SendSmsUseCase] with the
        // final (signed) body, so this pre-check only routes — but
        // an over-cap body is refused here, before any row is
        // written: routing it to group MMS instead would silently
        // send an encrypted group's message in the clear.
        val groupDecision = if (groupe && attachments.isEmpty()) {
            groupGate.decideGroupSend(recipients.map { it.raw }, body)
        } else {
            null
        }
        if (groupDecision == E2eGroupSendGate.Decision.Plain) {
            recipients.forEach { e2eGate.probeForSend(it.raw, subId) }
        }
        val mmsDeGroupe = groupe &&
            groupDecision != E2eGroupSendGate.Decision.Encrypted &&
            (settings.hydratedOrNull() ?: AppSettings()).sending.groupMms
        // Group-send own-number fix — just-in-time detection, then
        // fail-fast: the check sits BEFORE the dispatch, so a
        // still-blank number creates no PENDING mirror row and the
        // user is told to set their number instead of watching a
        // message hang on "sending". 1:1 sends and group SMS (setting
        // off) never reach this guard.
        if (mmsDeGroupe && !ownNumberEnsurer.ensureForGroupSend()) {
            return Outcome.Failure(AppError.OwnNumberMissing)
        }
        return when {
            groupDecision == E2eGroupSendGate.Decision.RefuseOverCap -> Outcome.Failure(
                AppError.Validation("message too long to send encrypted"),
            )
            mmsDeGroupe -> sendMediaMms.invoke(
                recipients,
                attachments,
                body,
                subId,
                groupMms = true,
                clientMessageId = clientMessageId,
            )
            attachments.isNotEmpty() -> sendMediaMms.invoke(
                recipients,
                attachments,
                body,
                subId,
                echoInGroup = groupe,
                clientMessageId = clientMessageId,
            )
            else -> sendSms.invoke(
                recipients,
                body,
                subId,
                replyToMessageId = replyToMessageId,
                echoInGroup = groupe,
                clientMessageId = clientMessageId,
            )
        }
    }
}
