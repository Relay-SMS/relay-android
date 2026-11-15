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
    ): SendResult {
        sendError?.let { throw it }
        sends += SendCall(clientMessageId, conversationId, body, replyToMessageId)
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
)

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
