package com.filestech.sms.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Slice 2 — a controllable [SyncMessageSource] for engine + handler
 * tests. The conversation flow is a SharedFlow (not a StateFlow) so
 * a test can re-emit an EQUAL list and prove the engine's diff stays
 * silent; a StateFlow would swallow the re-emission.
 */
internal class FakeSyncMessageSource : SyncMessageSource {
    var readiness: Readiness = Readiness.Ready
    var isDefaultSms: Boolean = true
    var snapshot: List<SyncedConversation> = emptyList()
    val conversationsFlow = MutableSharedFlow<List<SyncedConversation>>(replay = 1)
    val messagesByConversation = mutableMapOf<Long, MutableList<SyncedMessage>>()
    val statesFlow = MutableStateFlow<Map<Long, MessageState>>(emptyMap())
    var maxId: Long = 0L

    /** When set, [conversations] suspends on this gate until the test completes it. */
    var conversationsGate: CompletableDeferred<Unit>? = null

    init {
        conversationsFlow.tryEmit(emptyList())
    }

    override suspend fun conversations(): List<SyncedConversation> {
        conversationsGate?.await()
        return snapshot
    }

    override fun observeConversations(): Flow<List<SyncedConversation>> = conversationsFlow

    override suspend fun messagesBefore(
        conversationId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedMessage> {
        val all = messagesByConversation[conversationId].orEmpty()
        return all.filter { beforeId == 0L || it.id.toLong() < beforeId }
            .sortedByDescending { it.id.toLong() }
            .take(limit)
    }

    override suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage> =
        messagesByConversation[conversationId].orEmpty()
            .filter { it.id.toLong() > afterId }
            .sortedBy { it.id.toLong() }

    override suspend fun totalMessageCount(): Long =
        messagesByConversation.values.sumOf { it.size }.toLong()

    override suspend fun messageCountsByConversation(): Map<Long, Int> =
        messagesByConversation.mapValues { it.value.size }

    override suspend fun maxMessageId(): Long = maxId

    override fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>> =
        statesFlow.map { states -> ids.mapNotNull { states[it] } }

    override suspend fun readiness(): Readiness = readiness

    override suspend fun isDefaultSmsApp(): Boolean = isDefaultSms

    // -- Slice 3: send + write-back -----------------------------------
    // Each records its call and answers from a configurable result, so
    // engine tests can pin the routing without the real send path.

    val sends = mutableListOf<SendCall>()
    var sendResult: SendResult = SendResult.Sent(messageId = 999, status = "pending")

    /** When set, [sendMessage] throws this instead of answering. */
    var sendError: Throwable? = null

    override suspend fun sendMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        replyToMessageId: Long?,
        attachments: List<SendAttachmentInput>?,
    ): SendResult {
        sendError?.let { throw it }
        sends += SendCall(clientMessageId, conversationId, body, replyToMessageId, attachments)
        return sendResult
    }

    val actions = mutableListOf<ActionCall>()
    var actionResult: ActionResult = ActionResult(ok = true)

    /** When set, every action throws this instead of answering. */
    var actionError: Throwable? = null

    override suspend fun markRead(conversationId: Long): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("mark_read", conversationId)
        return actionResult
    }

    override suspend fun markAllRead(): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("mark_all_read")
        return actionResult
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("set_pin", conversationId, value = pinned)
        return actionResult
    }

    override suspend fun setArchived(conversationId: Long, archived: Boolean): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("set_archived", conversationId, value = archived)
        return actionResult
    }

    override suspend fun setMuted(conversationId: Long, muted: Boolean): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("set_muted", conversationId, value = muted)
        return actionResult
    }

    override suspend fun setBlocked(conversationId: Long, blocked: Boolean): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("set_blocked", conversationId, value = blocked)
        return actionResult
    }

    override suspend fun setReaction(conversationId: Long, messageId: Long, emoji: String?): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("set_reaction", conversationId, messageId = messageId, emoji = emoji)
        return actionResult
    }

    override suspend fun setPinnedMessage(conversationId: Long, messageId: Long?): ActionResult {
        actionError?.let { throw it }
        actions += ActionCall("set_pinned_message", conversationId, messageId = messageId)
        return actionResult
    }

    // -- Slice 4: attachment bytes --------------------------------------
    // Same shape as the send surface: each records its call and
    // answers from a configurable result.

    val attachmentRequests = mutableListOf<Pair<Long, Long>>()
    var openAttachmentResult: AttachmentOpenResult = AttachmentOpenResult.Unknown

    /** When set, [openAttachment] throws this instead of answering. */
    var openAttachmentError: Throwable? = null

    override suspend fun openAttachment(
        attachmentId: Long,
        messageId: Long,
    ): AttachmentOpenResult {
        openAttachmentError?.let { throw it }
        attachmentRequests += attachmentId to messageId
        return openAttachmentResult
    }

    val uploadStarts = mutableListOf<UploadStartCall>()
    var uploadStartResult: UploadResult = UploadResult.Ok

    /** When set, [beginUpload] throws this instead of answering. */
    var uploadStartError: Throwable? = null

    override suspend fun beginUpload(
        clientMessageId: String,
        index: Int,
        mimeType: String,
        fileName: String?,
        sizeBytes: Long,
        sha256: String,
    ): UploadResult {
        uploadStartError?.let { throw it }
        uploadStarts += UploadStartCall(clientMessageId, index, mimeType, fileName, sizeBytes, sha256)
        return uploadStartResult
    }

    val uploadChunks = mutableListOf<UploadChunkCall>()
    var uploadChunkResult: UploadResult = UploadResult.Ok

    /** When set, [appendUploadChunk] throws this instead of answering. */
    var uploadChunkError: Throwable? = null

    override suspend fun appendUploadChunk(
        clientMessageId: String,
        index: Int,
        offset: Long,
        bytes: ByteArray,
        final: Boolean,
    ): UploadResult {
        uploadChunkError?.let { throw it }
        uploadChunks += UploadChunkCall(clientMessageId, index, offset, bytes.copyOf(), final)
        return uploadChunkResult
    }

    // -- Phase 2: scheduled sends --------------------------------------
    // Same shape as the send surface: records its call and answers
    // from a configurable result.

    val schedules = mutableListOf<ScheduleCall>()
    var scheduleResult: ScheduleResult = ScheduleResult.Scheduled(messageId = 4242, sendAt = 1_800_000_000_000L)

    /** When set, [scheduleMessage] throws this instead of answering. */
    var scheduleError: Throwable? = null

    override suspend fun scheduleMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        sendAt: Long,
    ): ScheduleResult {
        scheduleError?.let { throw it }
        schedules += ScheduleCall(clientMessageId, conversationId, body, sendAt)
        return scheduleResult
    }

    val cancels = mutableListOf<String>()
    var cancelResult: CancelResult = CancelResult.Cancelled

    /** When set, [cancelScheduledMessage] throws this instead of answering. */
    var cancelError: Throwable? = null

    override suspend fun cancelScheduledMessage(clientMessageId: String): CancelResult {
        cancelError?.let { throw it }
        cancels += clientMessageId
        return cancelResult
    }

    // -- Create conversation from the desktop ---------------------------
    // Same shape as the send surface: records its call and answers
    // from a configurable result.

    val creates = mutableListOf<CreateCall>()
    var createResult: CreateConversationResult = CreateConversationResult.Created(
        sampleConversation(id = 123, title = "Family"),
    )

    /** When set, [createConversation] throws this instead of answering. */
    var createError: Throwable? = null

    override suspend fun createConversation(
        addresses: List<String>,
        customName: String?,
    ): CreateConversationResult {
        createError?.let { throw it }
        creates += CreateCall(addresses, customName)
        return createResult
    }

    // -- Slice 5: contact sync ----------------------------------------
    // Same shape as the send surface: the snapshot is a configurable
    // list, the avatar answers from a configurable result.

    var contactsSnapshot: List<SyncedContact> = emptyList()
    var contactsReadiness: ContactReadiness = ContactReadiness.Ready

    /** When set, [contacts] throws this instead of answering. */
    var contactsError: Throwable? = null

    /** How many times [contacts] was called — the no-double-read pin. */
    var contactsCalls: Int = 0
        private set

    override suspend fun contacts(): List<SyncedContact> {
        contactsError?.let { throw it }
        contactsCalls++
        return contactsSnapshot
    }

    val avatarRequests = mutableListOf<String>()
    var openAvatarResult: AvatarOpenResult = AvatarOpenResult.Unknown

    /** When set, [openAvatar] throws this instead of answering. */
    var openAvatarError: Throwable? = null

    override suspend fun openAvatar(address: String): AvatarOpenResult {
        openAvatarError?.let { throw it }
        avatarRequests += address
        return openAvatarResult
    }

    override suspend fun contactsReadiness(): ContactReadiness = contactsReadiness

    // -- Round 3: contact photo ---------------------------------------
    // Same shape as the send surface: records its call and answers
    // from a configurable result.

    val photoSets = mutableListOf<PhotoSetCall>()
    var photoSetResult: ActionResult = ActionResult(ok = true)

    /** When set, [setContactPhoto] throws this instead of answering. */
    var photoSetError: Throwable? = null

    override suspend fun setContactPhoto(
        address: String,
        photoBytes: ByteArray,
    ): ActionResult {
        photoSetError?.let { throw it }
        photoSets += PhotoSetCall(address, photoBytes.copyOf())
        return photoSetResult
    }

    /** Adds [message] to its conversation's store and advances the max id. */
    fun store(message: SyncedMessage) {
        val list = messagesByConversation.getOrPut(message.conversationId.toLong()) { mutableListOf() }
        list += message
        maxId = maxOf(maxId, message.id.toLong())
    }
}

/** One recorded `sendMessage` call on the fake. */
internal data class SendCall(
    val clientMessageId: String,
    val conversationId: Long,
    val body: String,
    val replyToMessageId: Long?,
    val attachments: List<SendAttachmentInput>? = null,
)

/** One recorded `scheduleMessage` call on the fake. */
internal data class ScheduleCall(
    val clientMessageId: String,
    val conversationId: Long,
    val body: String,
    val sendAt: Long,
)

/** One recorded `createConversation` call on the fake. */
internal data class CreateCall(
    val addresses: List<String>,
    val customName: String?,
)

/**
 * One recorded `setContactPhoto` call on the fake. The bytes are a
 * copy (the caller keeps its own array); compare them with Truth's
 * byte-array subject, which compares by content.
 */
internal data class PhotoSetCall(
    val address: String,
    val photoBytes: ByteArray,
)

/** One recorded `beginUpload` call on the fake. */
internal data class UploadStartCall(
    val clientMessageId: String,
    val index: Int,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long,
    val sha256: String,
)

/** One recorded `appendUploadChunk` call on the fake. */
internal data class UploadChunkCall(
    val clientMessageId: String,
    val index: Int,
    val offset: Long,
    val bytes: ByteArray,
    val final: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadChunkCall) return false
        return clientMessageId == other.clientMessageId &&
            index == other.index &&
            offset == other.offset &&
            bytes.contentEquals(other.bytes) &&
            final == other.final
    }

    override fun hashCode(): Int {
        var result = clientMessageId.hashCode()
        result = 31 * result + index
        result = 31 * result + offset.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + final.hashCode()
        return result
    }
}

/** One recorded action call on the fake. */
internal data class ActionCall(
    val action: String,
    val conversationId: Long? = null,
    val messageId: Long? = null,
    val value: Boolean? = null,
    val emoji: String? = null,
)

/** A minimal conversation for tests; override what the test cares about. */
internal fun sampleConversation(
    id: Long,
    lastMessageAt: Long = 1_700_000_000_000L,
    lastMessagePreview: String? = "hello",
    unread: Int = 0,
    messageCount: Int = 0,
    title: String = "Marie",
): SyncedConversation = SyncedConversation(
    id = id.toString(),
    threadId = null,
    addresses = listOf("+15551234567"),
    displayName = title,
    customName = null,
    title = title,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    unread = unread,
    pinned = false,
    archived = false,
    muted = false,
    blocked = false,
    messageCount = messageCount,
    pinnedMessageId = null,
    pinnedMessageAt = null,
)

/** A minimal message for tests. */
internal fun sampleMessage(
    id: Long,
    conversationId: Long,
    body: String = "msg $id",
    date: Long = 1_700_000_000_000L + id,
    status: String = "received",
    clientMessageId: String? = null,
): SyncedMessage = SyncedMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    address = "+15551234567",
    body = body,
    type = "sms",
    direction = "incoming",
    date = date,
    dateSent = null,
    read = false,
    starred = false,
    status = status,
    errorCode = null,
    replyToMessageId = null,
    reaction = null,
    attachments = emptyList(),
    clientMessageId = clientMessageId,
)

/** A minimal contact for tests; override what the test cares about. */
internal fun sampleContact(
    id: Long,
    displayName: String = "Marie",
    phones: List<String> = listOf("+15551234567"),
    hasPhoto: Boolean = false,
    photoUpdatedAt: Long? = null,
): SyncedContact = SyncedContact(
    displayName = displayName,
    phones = phones,
    hasPhoto = hasPhoto,
    photoUpdatedAt = photoUpdatedAt,
    contactId = id,
)
