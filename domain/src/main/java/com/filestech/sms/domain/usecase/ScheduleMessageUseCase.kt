package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.clientmode.NoOpClientSendGateway
import com.filestech.sms.domain.mms.OutgoingAttachmentStore
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import java.util.UUID
import javax.inject.Inject

class ScheduleMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
    private val attachmentStore: OutgoingAttachmentStore,
    /**
     * Client mode C3 — the wire send gateway. Default NoOp keeps the
     * hub tests compiling untouched; Hilt always provides the real one.
     */
    private val clientSend: ClientSendGateway = NoOpClientSendGateway,
) {
    /**
     * v1.26.0 — [attachments] non vide programme un **MMS** au lieu d'un SMS.
     *
     * Jusqu'ici la programmation ne transportait que du texte : on pouvait joindre une photo,
     * programmer l'envoi, et seul le texte partait à l'heure dite. La colonne `attachments_json`
     * existait en base sans jamais être écrite ni lue.
     *
     * ## Les fichiers sont rendus durables ICI, pas au moment de l'envoi
     *
     * C'est tout l'enjeu du différé. Le fichier que l'interface prépare vit dans le cache de
     * staging, que [com.filestech.sms.security.AutoLockObserver] vide **à chaque verrouillage** et
     * que le système peut purger quand il veut. Un envoi peut attendre des heures : à l'échéance,
     * le fichier aurait disparu et le MMS serait parti amputé — ou pas du tout.
     *
     * On les promeut donc vers `filesDir/mms_attachments/` dès la programmation, exactement comme
     * [SendMediaMmsUseCase] le fait juste avant un envoi immédiat. La promotion est idempotente :
     * un fichier déjà durable est renvoyé tel quel, et la rejouer à l'échéance est sans effet.
     *
     * ## Le plafond de taille est vérifié maintenant, pas à l'échéance
     *
     * [SendMediaMmsUseCase] refuse au-delà de son plafond. Le laisser découvrir ça des heures plus
     * tard transformerait une erreur de saisie en échec constaté trop tard, quand il n'est plus
     * temps de réagir. On applique donc la même limite ici, pendant que l'utilisateur est devant
     * son écran et peut retirer une image.
     */
    suspend operator fun invoke(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        whenEpochMillis: Long,
        subId: Int?,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload> = emptyList(),
        /**
         * Phase 2 — clé d'idempotence d'un envoi programmé depuis un appareil lié. Stockée
         * sur la ligne, transmise au routeur à l'échéance. `null` pour un envoi programmé
         * depuis le téléphone.
         */
        clientMessageId: String? = null,
    ): Outcome<Long> {
        if (clientSend.isClientMode()) {
            return scheduleFromClient(
                conversationId,
                addresses,
                body,
                whenEpochMillis,
                subId,
                attachments,
                clientMessageId,
            )
        }
        for (a in attachments) {
            if (!a.file.exists() || a.file.length() == 0L) {
                return Outcome.Failure(AppError.Validation("attachment file missing or empty"))
            }
        }
        val totalBytes = attachments.sumOf { it.file.length() }
        if (totalBytes > SendMediaMmsUseCase.MAX_PAYLOAD_BYTES) {
            return Outcome.Failure(AppError.Validation("payload exceeds cap ($totalBytes B)"))
        }

        val durable = attachments.map { a -> a.copy(file = attachmentStore.promoteToDurable(a.file)) }

        val scheduled = repo.schedule(
            conversationId,
            addresses,
            body,
            whenEpochMillis,
            subId,
            durable,
            clientMessageId,
        )
        return when (scheduled) {
            is Outcome.Success -> {
                scheduler.scheduleAt(scheduled.value, whenEpochMillis)
                scheduled
            }
            is Outcome.Failure -> scheduled
        }
    }

    /**
     * Client mode C3 — the schedule lives on the hub phone, which
     * owns the scheduler: the local row is only a record (never
     * armed with WorkManager — see the ScheduledSendAttempt gate),
     * and the `schedule_message` frame carries the key. A media
     * schedule fails here: media sends arrive in C5.
     */
    private suspend fun scheduleFromClient(
        conversationId: Long?,
        addresses: List<PhoneAddress>,
        body: String,
        whenEpochMillis: Long,
        subId: Int?,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
        clientMessageId: String?,
    ): Outcome<Long> {
        // Media first, then blank body — the same validation order
        // the inline branch had before the extraction.
        val invalid = when {
            attachments.isNotEmpty() -> AppError.ClientMediaUnsupported
            body.isBlank() -> AppError.Validation("empty message body")
            else -> null
        }
        if (invalid != null) return Outcome.Failure(invalid)
        val key = clientMessageId ?: UUID.randomUUID().toString()
        val created = repo.schedule(conversationId, addresses, body, whenEpochMillis, subId, emptyList(), key)
        if (created is Outcome.Failure) return created
        val rowId = (created as Outcome.Success).value
        // Offline: no record. A local row with no wire schedule
        // would sit in the list pretending the phone will fire it.
        if (!clientSend.scheduleMessage(conversationId, addresses, body, whenEpochMillis, key)) {
            runCatching { repo.delete(rowId) }
            return Outcome.Failure(AppError.ClientOffline)
        }
        return Outcome.Success(rowId)
    }
}
