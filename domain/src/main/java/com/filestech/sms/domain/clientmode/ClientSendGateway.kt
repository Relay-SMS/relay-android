package com.filestech.sms.domain.clientmode

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport

/**
 * Client mode C3 — the domain-facing send gateway: everything a linked
 * device sends over the wire (text sends, schedule create/cancel) goes
 * through here, so the domain use cases never see the wire layer.
 *
 * The real implementation is [com.filestech.sms.sync.client.ClientSendManager]
 * (app module — the domain layer cannot import app classes, and the gateway
 * is the seam). Bound in `ClientSyncModule`; Hilt always provides the real
 * one in production, so [NoOpClientSendGateway] only exists as a default
 * constructor value so existing tests keep compiling untouched — it must
 * never be reached in client mode, and it fails loudly if it is.
 */
interface ClientSendGateway {

    /** True when the device is a linked device (not the hub phone). */
    suspend fun isClientMode(): Boolean

    /**
     * Sends a text message via the `send_message` frame: inserts the
     * optimistic row, sends the frame with [clientMessageId] (generated
     * when null), and returns the [SendReport] the UI expects.
     * Never touches telephony. Offline → [AppError.ClientOffline].
     */
    suspend fun sendText(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport>

    /**
     * Client mode C5 — sends a media message via the wire: prepares
     * the attachments against the phone's budget (photos
     * recompressed, video never transcoded), inserts the optimistic
     * MMS row + attachment rows, uploads each file in turn, then
     * sends `send_message` with the `attachments` array. A budget
     * refusal ([AppError.ClientMmsCapReached]) or an undecodable
     * photo ([AppError.ClientPhotoPrepareFailed]) returns BEFORE
     * any optimistic row exists; once the row exists, a later
     * failure (upload refused, session dropped) fails the row and
     * the return is still a success — the failed bubble offers the
     * retry, like the desktop. Never touches telephony.
     */
    suspend fun sendMedia(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload>,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport>

    /**
     * Sends the `schedule_message` frame for a local row the caller
     * already created. Returns false when the frame could not be sent
     * (offline) — the caller deletes the row and fails.
     */
    suspend fun scheduleMessage(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        sendAt: Long,
        clientMessageId: String,
    ): Boolean

    /**
     * Sends the `cancel_scheduled` frame for a schedule identified by
     * its client key. Returns false when the frame could not be sent
     * (offline) — the caller keeps the row PENDING.
     */
    suspend fun cancelScheduled(clientMessageId: String): Boolean
}

/**
 * The no-op [ClientSendGateway] used as a default constructor value.
 * Reports hub mode, so every client branch that consults it keeps the
 * hub path — and fails loudly if a client send is ever attempted.
 */
object NoOpClientSendGateway : ClientSendGateway {
    override suspend fun isClientMode(): Boolean = false

    override suspend fun sendText(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport> =
        Outcome.Failure(AppError.Validation("client send gateway not provided"))

    override suspend fun sendMedia(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload>,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport> =
        Outcome.Failure(AppError.Validation("client send gateway not provided"))

    override suspend fun scheduleMessage(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        sendAt: Long,
        clientMessageId: String,
    ): Boolean = false

    override suspend fun cancelScheduled(clientMessageId: String): Boolean = false
}
