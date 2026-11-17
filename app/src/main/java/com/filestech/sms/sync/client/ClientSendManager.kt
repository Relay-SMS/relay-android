package com.filestech.sms.sync.client

import android.content.Context
import androidx.room.withTransaction
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.PhoneAddress.Companion.toCsv
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.domain.usecase.SendMediaMmsUseCase
import com.filestech.sms.sync.SyncProtocol
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode C3 — sends + schedules from a linked device.
 *
 * A linked device never touches telephony: every send is an optimistic
 * local row + a `send_message` frame to the hub phone, reconciled by
 * `client_message_id` when the hub echoes it (the ack, or the live
 * `message_new` — the mirror reconciles by key, so the row is never
 * duplicated). A retry reuses the SAME key: the hub dedups on it, so a
 * retry can never double-send. Offline, the send fails fast — there is
 * no queue in C3.
 *
 * Client mode C5 — media sends follow the same shape, with an upload
 * step first ([sendMedia]): prepare against the phone's budget
 * ([MediaBudget]), insert the optimistic MMS row + attachment rows
 * (pointing at durable COPIES of the staged files — the staged
 * originals stay put, so the composer can retry from them), upload
 * each file in turn ([ClientAttachmentUploader]), then send
 * `send_message` with the `attachments` array. A budget refusal
 * returns before any row exists; once the row exists, a later failure
 * fails the row and the return is still a success — the failed bubble
 * offers the retry, like the desktop. An `upload_expired` failure
 * re-uploads + re-sends once, automatically (the desktop rule).
 *
 * The manager also tracks the client-created schedules: a schedule is
 * `pending` from the frame until the `message_scheduled` ack confirms
 * it, and a cancel is tracked until `schedule_cancelled` (or a failed
 * cancel, which restores the local row — the hub still holds the
 * schedule). A schedule whose message arrives live is marked SENT.
 */
@Singleton
class ClientSendManager @Inject constructor(
    private val channel: ClientSyncChannel,
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val scheduledRepo: ScheduledMessageRepository,
    private val settings: AppSettingsSource,
    /** Client mode C5 — the media uploader (start → chunks → done per attachment). */
    private val uploader: ClientAttachmentUploader,
    /** Client mode C5 — the optimistic attachment rows + the media retry lookup. */
    private val attachmentDao: AttachmentDao,
    /** Client mode C5 — the durable copies land in `filesDir/mms_attachments/`. */
    @ApplicationContext private val context: Context,
    /**
     * Client mode C5 — the `upload_expired` re-upload runs here, off
     * the engine's frame loop (the failure handler runs under the
     * engine mutex, and an upload waits on acks the engine routes).
     */
    @ApplicationScope private val scope: CoroutineScope,
) : ClientSendGateway {

    /** A schedule awaiting its `message_scheduled` ack (or already confirmed). */
    private data class TrackedSchedule(val localConversationId: Long, val body: String, val sendAt: Long)

    /** A cancel awaiting its `schedule_cancelled` ack — the row id + fire time restore it on failure. */
    private data class PendingCancel(val localRowId: Long, val scheduledAt: Long)

    private val mutex = Mutex()
    private val pendingSends = mutableMapOf<String, Long>()

    /** Client mode C5 — `upload_expired` re-uploads spent per `client_message_id` (max one). */
    private val uploadExpiredRetries = mutableMapOf<String, Int>()
    private val pendingSchedules = mutableMapOf<String, TrackedSchedule>()
    private val confirmedSchedules = mutableMapOf<String, TrackedSchedule>()
    private val pendingCancels = mutableMapOf<String, PendingCancel>()

    override suspend fun isClientMode(): Boolean =
        (settings.hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT

    override suspend fun sendText(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport> {
        if (body.isBlank()) return Outcome.Failure(AppError.Validation("empty message body"))
        val (conv, hubConvId) = when (val resolved = resolveSendTarget(localConversationId, recipients)) {
            is Outcome.Success -> resolved.value
            is Outcome.Failure -> return resolved
        }
        val key = clientMessageId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // A reply target without a hub id is OMITTED, never sent as a
        // local id: local ids share the hub's number space, so a local
        // id could quote a different message on the phone.
        val replyHubId = replyToLocalMessageId?.let { messageDao.findById(it)?.hubMessageId }
        val localId = insertOptimistic(conv, body, now, replyToLocalMessageId, key)
        mutex.withLock { pendingSends[key] = localId }
        val sent = channel.send(
            ClientFrames.encodeSendMessage(
                SyncProtocol.SendMessage(key, hubConvId.toString(), body, replyHubId?.toString()),
            ),
        )
        if (!sent) {
            failRow(localId, SendErrorCode.SYNCHRONOUS)
            mutex.withLock { pendingSends.remove(key) }
            return Outcome.Failure(AppError.ClientOffline)
        }
        // Dispatched, from the UI's point of view: the row is in the
        // thread, so the composer clears its draft.
        return Outcome.Success(SendReport(dispatched = listOf(localId), failed = emptyList(), blocked = emptyList()))
    }

    /**
     * Client mode C5 — a media send from a linked device: prepares
     * the attachments against the phone's budget, inserts the
     * optimistic MMS row + attachment rows, uploads each file, then
     * sends `send_message` with the `attachments` array.
     *
     * A budget refusal ([AppError.ClientMmsCapReached]) or an
     * undecodable photo ([AppError.ClientPhotoPrepareFailed])
     * returns BEFORE any optimistic row exists — the composer shows
     * the refusal and the staged items stay put. Once the row
     * exists, a later failure (upload refused, session dropped)
     * fails the row and the return is still a success — the composer
     * clears and the failed bubble offers the retry, like the
     * desktop. The wire order is fixed: [MediaBudget] never
     * reorders, so the `upload_index` of each wire entry matches the
     * upload it belongs to.
     */
    override suspend fun sendMedia(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport> {
        if (attachments.isEmpty()) {
            return sendText(localConversationId, recipients, body, replyToLocalMessageId, clientMessageId)
        }
        val (conv, hubConvId) = when (val resolved = resolveSendTarget(localConversationId, recipients)) {
            is Outcome.Success -> resolved.value
            is Outcome.Failure -> return resolved
        }
        val key = clientMessageId ?: UUID.randomUUID().toString()
        val prepared = when (val prep = MediaBudget.prepare(attachments, body)) {
            is MediaBudget.PrepareResult.Ready -> prep.items
            is MediaBudget.PrepareResult.Refused -> return Outcome.Failure(prep.error)
        }
        val now = System.currentTimeMillis()
        val replyHubId = replyToLocalMessageId?.let { messageDao.findById(it)?.hubMessageId }
        // Durable COPIES (not moves): the staged originals stay put,
        // so the composer can retry from them after a failure.
        val durable = prepared.map { it.copy(file = copyToDurable(it.file)) }
        val localId = insertOptimisticMedia(conv, body, now, replyToLocalMessageId, key, durable)
        mutex.withLock { pendingSends[key] = localId }
        return dispatchMedia(key, hubConvId, body, replyHubId, localId, durable)
    }

    /**
     * Client mode C5 — the upload + send tail of a media send, for
     * an optimistic row that already exists. An upload failure fails
     * the row and still succeeds (the composer clears); an offline
     * send fails the row and reports offline.
     */
    private suspend fun dispatchMedia(
        key: String,
        hubConvId: Long,
        body: String,
        replyHubId: Long?,
        localId: Long,
        durable: List<MediaBudget.PreparedItem>,
    ): Outcome<SendReport> {
        if (!uploadAll(key, durable)) {
            failPendingSend(key, localId)
            return Outcome.Success(
                SendReport(dispatched = listOf(localId), failed = emptyList(), blocked = emptyList()),
            )
        }
        val sent = channel.send(
            ClientFrames.encodeSendMessage(
                SyncProtocol.SendMessage(
                    key,
                    hubConvId.toString(),
                    body,
                    replyHubId?.toString(),
                    wireEntries(durable),
                ),
            ),
        )
        if (!sent) {
            failPendingSend(key, localId)
            return Outcome.Failure(AppError.ClientOffline)
        }
        return Outcome.Success(
            SendReport(dispatched = listOf(localId), failed = emptyList(), blocked = emptyList()),
        )
    }

    /** Client mode C5 — uploads every prepared item in turn. False on the first failure. */
    private suspend fun uploadAll(key: String, items: List<MediaBudget.PreparedItem>): Boolean =
        items.withIndex().all { (index, item) ->
            val bytes = runCatching { item.file.readBytes() }.getOrNull() ?: return@all false
            runCatching { uploader.upload(key, index, bytes, item.mimeType, item.fileName) }.isSuccess
        }

    /**
     * Client mode C5 — COPIES a staged file into durable storage
     * (`filesDir/mms_attachments/`, the outgoing store's naming)
     * and returns the durable file. A copy, not the store's move:
     * the staged original stays put, so the composer can retry from
     * it after a failure. Idempotent for files already inside the
     * durable root. Fail-open: the staged file itself on any IO
     * failure, so the send still proceeds.
     */
    private fun copyToDurable(staged: File): File {
        val dir = File(context.filesDir, MMS_ATTACHMENTS_DIR_NAME)
        val canonicalDir = runCatching { dir.canonicalPath }.getOrNull()
        val canonicalStaged = runCatching { staged.canonicalPath }.getOrNull()
        if (canonicalDir != null && canonicalStaged != null &&
            canonicalStaged.startsWith(canonicalDir + File.separator)
        ) {
            return staged
        }
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val ext = staged.extension.ifBlank { "bin" }
            val target = File(
                dir,
                "out-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$ext",
            )
            staged.copyTo(target, overwrite = false)
            target
        }.getOrElse { staged }
    }

    /**
     * Retries a failed wire send with the SAME client key — the hub
     * dedups on it, so a retry can never double-send. Called from the
     * thread (tap a failed bubble), via the entry point: the
     * ViewModel is at its constructor-param cap.
     */
    suspend fun resend(localMessageId: Long): Outcome<Unit> {
        val (row, key) = when (val resolved = resolveResendRow(localMessageId)) {
            is Outcome.Success -> resolved.value
            is Outcome.Failure -> return resolved
        }
        val hubConvId = when (val resolved = resolveHubConversationId(row.conversationId)) {
            is Outcome.Success -> resolved.value
            is Outcome.Failure -> return resolved
        }
        // Back to PENDING first — a direct update, not the monotonic
        // status path: a retry deliberately moves backwards, like the
        // mirror's `resetOutgoingForRetry`.
        messageDao.update(row.copy(status = MessageStatus.PENDING, errorCode = null))
        mutex.withLock { pendingSends[key] = localMessageId }
        val replyHubId = row.replyToMessageId?.let { messageDao.findById(it)?.hubMessageId }
        // Client mode C5 — a media retry re-sends the wire
        // attachments array with the same key (no re-upload first:
        // the phone finds its staged upload, or answers
        // `upload_expired` and the failure handler re-uploads — the
        // desktop rule). A text retry sends a null array, byte-
        // identical to before.
        val wire = wireEntriesForRows(attachmentDao.findForMessage(localMessageId)).takeIf { it.isNotEmpty() }
        val sent = channel.send(
            ClientFrames.encodeSendMessage(
                SyncProtocol.SendMessage(key, hubConvId.toString(), row.body, replyHubId?.toString(), wire),
            ),
        )
        if (!sent) {
            failRow(localMessageId, SendErrorCode.SYNCHRONOUS)
            mutex.withLock { pendingSends.remove(key) }
            return Outcome.Failure(AppError.ClientOffline)
        }
        return Outcome.Success(Unit)
    }

    override suspend fun scheduleMessage(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        sendAt: Long,
        clientMessageId: String,
    ): Boolean {
        val conv = resolveConversation(localConversationId, recipients) ?: return false
        val hubConvId = conv.hubConversationId ?: return false
        mutex.withLock {
            pendingSchedules[clientMessageId] = TrackedSchedule(conv.id, body, sendAt)
        }
        val sent = channel.send(
            ClientFrames.encodeScheduleMessage(
                SyncProtocol.ScheduleMessage(clientMessageId, hubConvId.toString(), body, sendAt),
            ),
        )
        if (!sent) mutex.withLock { pendingSchedules.remove(clientMessageId) }
        return sent
    }

    override suspend fun cancelScheduled(clientMessageId: String): Boolean {
        val sent = channel.send(
            ClientFrames.encodeCancelScheduled(SyncProtocol.CancelScheduled(clientMessageId)),
        )
        if (sent) {
            // The row is still PENDING here (the caller cancels it
            // locally only after this returns) — its id + fire time
            // restore it if the hub answers `message_failed`.
            val row = scheduledRepo.findByClientMessageId(clientMessageId)
            if (row != null) {
                mutex.withLock {
                    pendingCancels[clientMessageId] = PendingCancel(row.id, row.scheduledAt)
                }
            }
        }
        return sent
    }

    /** The `message_sent` ack: the optimistic row gets its hub id + the hub's status. */
    suspend fun onMessageSent(value: SyncProtocol.MessageSent) {
        val row = messageDao.findByClientMessageId(value.clientMessageId) ?: return
        val hubId = parseHubId(value.messageId) ?: return
        messageDao.update(
            row.copy(
                hubMessageId = hubId,
                status = parseMessageStatus(value.status, MessageDirection.OUTGOING),
            ),
        )
        mutex.withLock {
            pendingSends.remove(value.clientMessageId)
            uploadExpiredRetries.remove(value.clientMessageId)
        }
    }

    /**
     * A `message_failed`: the hub refused or failed a send, a
     * schedule, or a cancel — the key decides which. A failed cancel
     * restores the local row (the hub still holds the schedule); a
     * failed schedule fails the local record; a failed send fails the
     * optimistic row with the hub's reason.
     */
    suspend fun onMessageFailed(value: SyncProtocol.MessageFailed) {
        val key = value.clientMessageId
        if (handleFailedCancelOrSchedule(key, value)) return
        // Client mode C5 — `upload_expired`: the staged upload is
        // gone (TTL sweep, cache clear). One automatic re-upload +
        // re-send with the same key (the desktop rule); the row stays
        // PENDING while it runs, and only fails if the re-upload
        // itself fails.
        if (value.code == SyncProtocol.MessageFailed.UPLOAD_EXPIRED && tryReupload(key)) return
        val row = messageDao.findByClientMessageId(key) ?: return
        mutex.withLock {
            pendingSends.remove(key)
            uploadExpiredRetries.remove(key)
        }
        messageDao.update(row.copy(status = MessageStatus.FAILED, errorCode = failureErrorCode(value.code)))
        Timber.w("ClientSendManager: send %s failed (%s): %s", key, value.code, value.message)
    }

    /**
     * A `message_failed` for a cancel restores the local row (the hub
     * still holds the schedule); for a schedule — tracked, confirmed,
     * or already failed — it fails the local record, so the user can
     * retry it instead of watching a PENDING row that never fires.
     * True when the key belonged to a cancel or a schedule.
     */
    private suspend fun handleFailedCancelOrSchedule(key: String, value: SyncProtocol.MessageFailed): Boolean {
        val cancel = mutex.withLock { pendingCancels.remove(key) }
        if (cancel != null) {
            runCatching { scheduledRepo.rearmPending(cancel.localRowId, cancel.scheduledAt) }
            Timber.w("ClientSendManager: cancel of %s failed (%s) — schedule restored", key, value.code)
            return true
        }
        val trackedSchedule = mutex.withLock {
            pendingSchedules.remove(key) ?: confirmedSchedules.remove(key)
        }
        if (trackedSchedule != null) {
            // The hub refused the schedule (or the fire failed): the
            // local record fails too, so the user can retry it — a
            // PENDING row here would never fire and never surface.
            failScheduledRow(key)
            return true
        }
        return failScheduledRow(key)
    }

    /**
     * Client mode C5 — starts one automatic re-upload + re-send for
     * an `upload_expired` failure. True when the re-upload started
     * (the row stays PENDING — the failure is handled); false when
     * there is nothing to re-upload (a text send, a missing row, the
     * retry already spent) and the caller must fail the row. The
     * re-upload runs in the application scope, off the engine's frame
     * loop: this handler runs under the engine mutex, and an upload
     * waits on acks the engine routes.
     */
    private suspend fun tryReupload(key: String): Boolean {
        val row = messageDao.findByClientMessageId(key) ?: return false
        val attachments = attachmentDao.findForMessage(row.id)
        if (attachments.isEmpty() || !takeUploadExpiredRetry(key)) return false
        scope.launch { reuploadAndResend(key, row, attachments) }
        return true
    }

    /** Client mode C5 — spends the one `upload_expired` retry for [key] (false when spent). */
    private suspend fun takeUploadExpiredRetry(key: String): Boolean = mutex.withLock {
        val count = uploadExpiredRetries[key] ?: 0
        if (count >= 1) {
            false
        } else {
            uploadExpiredRetries[key] = count + 1
            true
        }
    }

    /**
     * Client mode C5 — re-uploads the optimistic row's attachments
     * from their durable copies and re-sends `send_message` with the
     * same key. Any failure here fails the row (the retry was already
     * spent — there is no second automatic attempt). On success the
     * row stays PENDING until the ack / live echo reconciles it.
     */
    private suspend fun reuploadAndResend(
        key: String,
        row: MessageEntity,
        attachments: List<AttachmentEntity>,
    ) {
        val hubConvId = conversationDao.findById(row.conversationId)?.hubConversationId
        if (hubConvId == null) {
            failPendingSend(key, row.id)
            return
        }
        val uploaded = attachments.withIndex().all { (index, att) ->
            val bytes = runCatching { File(att.localUri).readBytes() }.getOrNull() ?: return@all false
            runCatching { uploader.upload(key, index, bytes, att.mimeType, att.fileName) }.isSuccess
        }
        if (!uploaded) {
            failPendingSend(key, row.id)
            return
        }
        val replyHubId = row.replyToMessageId?.let { messageDao.findById(it)?.hubMessageId }
        val sent = channel.send(
            ClientFrames.encodeSendMessage(
                SyncProtocol.SendMessage(
                    key,
                    hubConvId.toString(),
                    row.body,
                    replyHubId?.toString(),
                    wireEntriesForRows(attachments),
                ),
            ),
        )
        if (!sent) failPendingSend(key, row.id)
    }

    /**
     * Client mode C5 — fails a pending row + drops its tracking (the
     * shared media-failure tail). The `upload_expired` retry counter
     * goes too: a manual resend after this failure is a new attempt
     * with its own automatic retry (still no loop — each retry needs
     * a fresh `upload_expired` frame, and a re-upload that succeeds
     * keeps its spent counter until the ack).
     */
    private suspend fun failPendingSend(key: String, localId: Long) {
        failRow(localId, SendErrorCode.SYNCHRONOUS)
        mutex.withLock {
            pendingSends.remove(key)
            uploadExpiredRetries.remove(key)
        }
    }

    /** The `message_scheduled` ack: a pending schedule is confirmed on the hub. */
    suspend fun onMessageScheduled(value: SyncProtocol.MessageScheduled) {
        val pending = mutex.withLock { pendingSchedules.remove(value.clientMessageId) } ?: return
        mutex.withLock { confirmedSchedules[value.clientMessageId] = pending }
    }

    /** The `schedule_cancelled` ack: all tracking for the key is dropped. */
    suspend fun onScheduleCancelled(value: SyncProtocol.ScheduleCancelled) {
        mutex.withLock {
            pendingSchedules.remove(value.clientMessageId)
            confirmedSchedules.remove(value.clientMessageId)
            pendingCancels.remove(value.clientMessageId)
        }
    }

    /**
     * A live message carrying a client key: if a PENDING scheduled row
     * carries it, the schedule fired on the hub — mark it SENT. (The
     * mirror already reconciled an own-send's optimistic row by the
     * same key.)
     */
    suspend fun onLiveMessage(clientMessageId: String) {
        mutex.withLock {
            pendingSchedules.remove(clientMessageId)
            confirmedSchedules.remove(clientMessageId)
        }
        val row = scheduledRepo.findByClientMessageId(clientMessageId) ?: return
        if (row.state == ScheduledMessage.State.PENDING) scheduledRepo.markSent(row.id)
    }

    /**
     * Fails the scheduled row keyed by [key] (a schedule from before
     * a restart has no in-memory tracking — the local row still
     * carries the key). Returns true when a row was found.
     */
    private suspend fun failScheduledRow(key: String): Boolean {
        val row = scheduledRepo.findByClientMessageId(key) ?: return false
        if (row.state == ScheduledMessage.State.PENDING) scheduledRepo.markFailed(row.id)
        return true
    }

    private fun failureErrorCode(code: String): Int = when (code) {
        SyncProtocol.MessageFailed.HUB_NOT_DEFAULT_SMS -> SendErrorCode.CLIENT_HUB_NOT_DEFAULT_SMS
        SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION -> SendErrorCode.CLIENT_UNKNOWN_CONVERSATION
        SyncProtocol.MessageFailed.BLOCKED_RECIPIENT -> SendErrorCode.RECIPIENT_BLOCKED
        SyncProtocol.MessageFailed.MESSAGE_TOO_LONG -> SendErrorCode.CLIENT_MESSAGE_TOO_LONG
        else -> SendErrorCode.SYNCHRONOUS
    }

    /**
     * The resend's row + key: the row must exist, carry a client
     * key (only this device's own sends can be retried), and be
     * outgoing — checked in that order, as the guards they were.
     */
    private suspend fun resolveResendRow(localMessageId: Long): Outcome<Pair<MessageEntity, String>> {
        val row = messageDao.findById(localMessageId)
            ?: return Outcome.Failure(AppError.NotFound("message"))
        val key = row.clientMessageId
            ?: return Outcome.Failure(AppError.Validation("this message was not sent from this device"))
        if (row.direction != MessageDirection.OUTGOING) {
            return Outcome.Failure(AppError.Validation("only outgoing messages can be retried"))
        }
        return Outcome.Success(row to key)
    }

    /** The hub conversation id for a resend's local conversation. */
    private suspend fun resolveHubConversationId(localConversationId: Long): Outcome<Long> {
        val conv = conversationDao.findById(localConversationId)
            ?: return Outcome.Failure(AppError.NotFound("conversation"))
        val hubConvId = conv.hubConversationId
            ?: return Outcome.Failure(AppError.Validation("conversation is not synced with the phone"))
        return Outcome.Success(hubConvId)
    }

    /**
     * The local conversation for a send: by id when the caller has
     * one, else by the recipients' canonical CSV (notification inline
     * replies and the headless send service have no id).
     */
    private suspend fun resolveConversation(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
    ): ConversationEntity? {
        localConversationId?.let { conversationDao.findById(it)?.let { row -> return row } }
        if (recipients.isEmpty()) return null
        val csv = recipients.sortedBy { it.normalized }.toCsv()
        return conversationDao.findByAddressesCsv(csv)
    }

    /**
     * Client mode C5 — the send target shared by [sendText] and
     * [sendMedia]: the local conversation + its hub id, or the
     * validation failure the caller returns.
     */
    private suspend fun resolveSendTarget(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
    ): Outcome<Pair<ConversationEntity, Long>> {
        val conv = resolveConversation(localConversationId, recipients)
        val hubConvId = conv?.hubConversationId
        if (conv == null || hubConvId == null) {
            val reason = if (conv == null) {
                "conversation not found"
            } else {
                "conversation is not synced with the phone"
            }
            return Outcome.Failure(AppError.Validation(reason))
        }
        return Outcome.Success(conv to hubConvId)
    }

    /**
     * The optimistic row: no system uri, PENDING, carrying the key —
     * plus the conversation touch (the preview only moves when this
     * message is the newest), in one transaction.
     */
    private suspend fun insertOptimistic(
        conv: ConversationEntity,
        body: String,
        now: Long,
        replyToLocalMessageId: Long?,
        key: String,
    ): Long = database.withTransaction {
        val localId = messageDao.insert(
            MessageEntity(
                conversationId = conv.id,
                telephonyUri = null,
                address = conv.addressesCsv,
                body = body,
                type = MessageType.SMS,
                direction = MessageDirection.OUTGOING,
                date = now,
                dateSent = null,
                read = true,
                status = MessageStatus.PENDING,
                replyToMessageId = replyToLocalMessageId,
                clientMessageId = key,
            ),
        )
        touchForOptimistic(conv, now, body)
        localId
    }

    /**
     * Client mode C5 — the optimistic MMS row + its attachment rows:
     * no system uri, PENDING, carrying the key, the attachment rows
     * pointing at the durable copies — plus the conversation touch,
     * in one transaction. The attachment rows carry no hub id yet:
     * the mirror deletes them and links the durable files onto the
     * synced attachments when the hub echoes the send.
     */
    private suspend fun insertOptimisticMedia(
        conv: ConversationEntity,
        body: String,
        now: Long,
        replyToLocalMessageId: Long?,
        key: String,
        items: List<MediaBudget.PreparedItem>,
    ): Long = database.withTransaction {
        val localId = messageDao.insert(
            MessageEntity(
                conversationId = conv.id,
                telephonyUri = null,
                address = conv.addressesCsv,
                body = body,
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = now,
                dateSent = null,
                read = true,
                status = MessageStatus.PENDING,
                attachmentsCount = items.size,
                replyToMessageId = replyToLocalMessageId,
                clientMessageId = key,
            ),
        )
        attachmentDao.insertAll(
            items.map {
                AttachmentEntity(
                    messageId = localId,
                    mimeType = it.mimeType,
                    fileName = it.fileName,
                    sizeBytes = it.file.length(),
                    localUri = it.file.absolutePath,
                    width = it.width,
                    height = it.height,
                    durationMs = it.durationMs,
                )
            },
        )
        touchForOptimistic(conv, now, mediaPreviewLabel(body, items))
        localId
    }

    /**
     * Client mode C5 — the conversation touch shared by the text +
     * media optimistic inserts: the preview only moves when this
     * message is the newest. Runs inside the caller's transaction.
     */
    private suspend fun touchForOptimistic(conv: ConversationEntity, now: Long, preview: String) {
        val current = conversationDao.findById(conv.id) ?: return
        val isNewest = now >= current.lastMessageAt
        conversationDao.update(
            current.copy(
                lastMessageAt = maxOf(now, current.lastMessageAt),
                lastMessagePreview = if (isNewest) preview.take(MAX_PREVIEW) else current.lastMessagePreview,
                unreadCount = 0,
            ),
        )
    }

    /**
     * Client mode C5 — the conversation preview for an optimistic
     * media send: the body when it has one, else the media label of
     * the first attachment (same emoji + file-name shape as the
     * mirror's `syncedMessagePreview`, so a caption-less MMS never
     * blanks the preview before the hub's echo corrects it).
     */
    private fun mediaPreviewLabel(body: String, items: List<MediaBudget.PreparedItem>): String {
        if (body.isNotBlank()) return body
        val first = items.firstOrNull() ?: return ""
        val label = when {
            first.mimeType.startsWith("image/") -> "🖼️"
            first.mimeType.startsWith("video/") -> "🎞️"
            first.mimeType.startsWith("audio/") -> "🎤"
            first.mimeType == "text/x-vcard" || first.mimeType == "text/vcard" -> "👤"
            else -> "📎"
        }
        return "$label ${first.fileName}"
    }

    /** Client mode C5 — the `send_message` attachments array for prepared items. */
    private fun wireEntries(items: List<MediaBudget.PreparedItem>): List<SyncProtocol.SendAttachment> =
        items.mapIndexed { index, item ->
            SyncProtocol.SendAttachment(
                uploadIndex = index,
                mimeType = item.mimeType,
                fileName = item.fileName,
                width = item.width,
                height = item.height,
                durationMs = item.durationMs,
            )
        }

    /**
     * Client mode C5 — the `send_message` attachments array for
     * optimistic attachment rows. A separate name (not an overload
     * of [wireEntries]): the two list types erase to the same JVM
     * signature.
     */
    private fun wireEntriesForRows(attachments: List<AttachmentEntity>): List<SyncProtocol.SendAttachment> =
        attachments.mapIndexed { index, att ->
            SyncProtocol.SendAttachment(
                uploadIndex = index,
                mimeType = att.mimeType,
                fileName = att.fileName,
                width = att.width,
                height = att.height,
                durationMs = att.durationMs,
            )
        }

    private suspend fun failRow(localId: Long, errorCode: Int) {
        val row = messageDao.findById(localId) ?: return
        messageDao.update(row.copy(status = MessageStatus.FAILED, errorCode = errorCode))
    }

    companion object {
        /** Same preview cap as [com.filestech.sms.data.repository.ConversationMirror]. */
        private const val MAX_PREVIEW: Int = 240
    }
}

/**
 * Client mode C3 — the Hilt entry point for [ClientSendManager], for
 * the thread's retry path: `ThreadViewModel` is at its constructor
 * cap, so it reaches the manager this way (same pattern as
 * [ClientSyncEngineEntryPoint]).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClientSendManagerEntryPoint {
    fun clientSendManager(): ClientSendManager
}
