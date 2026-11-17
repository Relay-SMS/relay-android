package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.clientmode.NoOpClientSendGateway
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
        val mmsDeGroupe = groupe && (settings.hydratedOrNull() ?: AppSettings()).sending.groupMms
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
