package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.clientmode.NoOpClientSendGateway
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

/**
 * v1.25.3 (audit H6) — relance un envoi programmé abandonné, à la demande explicite de
 * l'utilisateur depuis la section « Échoués ».
 *
 * L'ordre compte, et il est l'inverse de [CancelScheduledMessageUseCase] : on remet d'abord la
 * ligne en `PENDING`, **ensuite** on planifie. Le worker refuse tout ce qui n'est pas `PENDING`,
 * donc planifier en premier ouvrirait une fenêtre où il se réveille, voit encore `FAILED` et
 * repart sans rien envoyer.
 *
 * Le replay repart avec un compteur de tentatives neuf (nouveau `WorkRequest`), donc de nouveau
 * [com.filestech.sms.system.scheduler.ScheduledSendAttempt.MAX_ATTEMPTS] essais.
 *
 * À noter : chaque relance qui aboutit crée une nouvelle ligne d'envoi dans le fil, à côté de la
 * ligne en échec de la tentative précédente. C'est assumé — la relance est un geste utilisateur
 * délibéré, et masquer la trace de l'échec serait pire.
 */
class RetryScheduledMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
    /**
     * Client mode C3 — the wire send gateway. Default NoOp keeps the
     * hub tests compiling untouched; Hilt always provides the real one.
     */
    private val clientSend: ClientSendGateway = NoOpClientSendGateway,
    /**
     * Client mode C3 — the schedule use case: a client retry is a
     * FRESH schedule through the wire branch (a new key — the hub's
     * dedup answers a re-sent key with the stored row, and a FAILED
     * stored row never fires again).
     */
    private val scheduleMessage: ScheduleMessageUseCase,
) {
    suspend operator fun invoke(id: Long): Outcome<Unit> {
        // Client mode C3 — a retry is a fresh schedule, not a re-send
        // of the key: the hub's dedup answers a re-sent key with the
        // stored row, and a FAILED stored row never fires again. The
        // new schedule carries a new key; the old row is deleted only
        // once the new one is away (an offline retry keeps the failed
        // row, so the user can try again). The fire time is now + a
        // minute, not now: the hub refuses past times, and the two
        // devices' clocks can disagree by seconds.
        if (clientSend.isClientMode()) {
            val row = repo.findById(id)
                ?: return Outcome.Failure(AppError.NotFound("scheduled message"))
            if (row.clientMessageId == null) {
                return Outcome.Failure(AppError.Validation("this schedule was not created on this device"))
            }
            val sendAt = System.currentTimeMillis() + RETRY_DELAY_MS
            return when (
                val created = scheduleMessage(row.conversationId, row.addresses, row.body, sendAt, row.subId)
            ) {
                is Outcome.Success -> {
                    runCatching { repo.delete(id) }
                    Outcome.Success(Unit)
                }
                is Outcome.Failure -> created
            }
        }
        val now = System.currentTimeMillis()
        repo.rearmPending(id, now)
        scheduler.scheduleAt(id, now)
        return Outcome.Success(Unit)
    }

    companion object {
        /** Client mode C3 — a retry fires a minute from now (covers device clock skew vs the hub). */
        const val RETRY_DELAY_MS: Long = 60_000L
    }
}
