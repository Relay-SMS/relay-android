package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
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
 */
class EnvoyerMessageUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val sendMediaMms: SendMediaMmsUseCase,
    private val settings: AppSettingsSource,
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
    ): Outcome<SendReport> {
        val groupe = recipients.size > 1
        val mmsDeGroupe = groupe && (settings.hydratedOrNull() ?: AppSettings()).sending.groupMms
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
