package com.filestech.sms.system.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.sync.ActionResult
import com.filestech.sms.sync.MessageState
import com.filestech.sms.sync.Readiness
import com.filestech.sms.sync.SendResult
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.domain.model.MessageState as DomainMessageState

/**
 * Slice 2 glue (SYNC_PROTOCOL.md, glue point 4): the
 * [SyncMessageSource] implementation — the one place that knows both
 * the sync wire DTOs and the app's repositories, so the sync package
 * itself keeps importing no app code.
 *
 * It lives in the app module, not the data module, on purpose: the
 * data module cannot see the app's sync package (module layering
 * runs app → data, never the reverse), and the sync package cannot
 * move into the data module without dragging the transport along.
 * App-module glue, next to the other system seams.
 *
 * Slice 3 adds the send + write-back surface. Sends are serialized
 * by one mutex at this singleton's level (not per session — two
 * linked devices could otherwise race each other) and deduped on
 * the durable client key stamped on the message row, so a retry
 * after a lost ack never sends the same text twice.
 */
@Singleton
class RepositorySyncMessageSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ConversationRepository,
    private val defaultSmsAppChecker: DefaultSmsAppChecker,
    private val envoyerMessage: EnvoyerMessageUseCase,
    private val retrySend: RetrySendUseCase,
    private val sendReaction: SendReactionUseCase,
    private val settings: AppSettingsSource,
) : SyncMessageSource {

    /** Serializes all send handling — see the class KDoc. */
    private val sendMutex = Mutex()

    /** Last reaction SMS dispatched per message, for the phone's 60 s same-emoji dedup. */
    private val recentReactionDispatches = mutableMapOf<Long, ReactionDispatch>()

    override suspend fun conversations(): List<SyncedConversation> {
        val counts = repository.messageCountsByConversation()
        return allConversations().first()
            .sortedByDescending { it.lastMessageAt }
            .map { it.toSynced(counts[it.id] ?: 0) }
    }

    override fun observeConversations(): Flow<List<SyncedConversation>> =
        allConversations().map { list ->
            val counts = repository.messageCountsByConversation()
            list.sortedByDescending { it.lastMessageAt }
                .map { it.toSynced(counts[it.id] ?: 0) }
        }

    /**
     * `observeAll`'s parameter means "archived ONLY", not "include
     * archived" — both halves are needed for the whole list. Vault
     * conversations are excluded by the DAO itself (`in_vault = 0`),
     * so sync can never leak them, by construction.
     */
    private fun allConversations(): Flow<List<Conversation>> =
        combine(
            repository.observeAll(false),
            repository.observeAll(true),
        ) { active, archived -> active + archived }

    override suspend fun messagesBefore(
        conversationId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedMessage> =
        repository.messagesBefore(conversationId, beforeId, limit).map { it.toSynced() }

    override suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage> =
        repository.messagesAfter(conversationId, afterId).map { it.toSynced() }

    override suspend fun totalMessageCount(): Long = repository.totalMessageCount()

    override suspend fun messageCountsByConversation(): Map<Long, Int> =
        repository.messageCountsByConversation()

    override suspend fun maxMessageId(): Long = repository.maxMessageId()

    override fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        return repository.observeMessageStates(ids).map { states ->
            states.map { it.toSynced() }
        }
    }

    override suspend fun readiness(): Readiness {
        val smsGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (!smsGranted) return Readiness.NoSmsPermission
        if (repository.totalMessageCount() == 0L) return Readiness.MirrorEmpty
        return Readiness.Ready
    }

    override suspend fun isDefaultSmsApp(): Boolean = defaultSmsAppChecker.isDefault()

    // -- Slice 3: send + write-back -----------------------------------

    override suspend fun sendMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        replyToMessageId: Long?,
    ): SendResult {
        if (body.isBlank()) {
            // The desktop refuses blank bodies client-side, so this is
            // a client bug, not a user error — the generic code fits.
            return SendResult.Failed(
                SyncProtocol.MessageFailed.INTERNAL,
                "The message could not be sent.",
            )
        }
        if (body.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_SEND_BODY_BYTES) {
            return SendResult.Failed(
                SyncProtocol.MessageFailed.MESSAGE_TOO_LONG,
                "The message is too long to send.",
            )
        }
        val conversation = resolveActionTarget(conversationId)
            ?: return SendResult.Failed(
                SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                "Conversation not found.",
            )
        return sendMutex.withLock {
            val existing = repository.findMessageByClientMessageId(clientMessageId)
            if (existing != null) {
                return@withLock storedResult(existing)
            }
            // Non-cancellable: a dropped session mid-send must not
            // strand the message between the mirror write and the
            // radio dispatch. The send completes, the ack is lost,
            // and the desktop's reconnect retry hits the dedup above.
            val outcome = withContext(NonCancellable) {
                envoyerMessage(
                    recipients = conversation.addresses,
                    body = body,
                    replyToMessageId = replyToMessageId,
                    clientMessageId = clientMessageId,
                )
            }
            mapSendOutcome(outcome)
        }
    }

    /**
     * The dedup answer for a key this phone has already seen. Pending
     * / sent / delivered → the stored result, never a re-send. Failed
     * → re-dispatch the existing row via the phone's own retry path
     * (it opens a numbered attempt, so late receipts from the first
     * try can't corrupt the second).
     */
    private suspend fun storedResult(existing: Message): SendResult {
        if (existing.status != Message.Status.FAILED) {
            return SendResult.Sent(existing.id, existing.status.name.lowercase())
        }
        return when (val retried = retrySend(existing.id)) {
            is Outcome.Success -> SendResult.Sent(existing.id, Message.Status.PENDING.name.lowercase())
            is Outcome.Failure -> when (retried.error) {
                AppError.RecipientBlocked -> SendResult.Failed(
                    SyncProtocol.MessageFailed.BLOCKED_RECIPIENT,
                    "The recipient is blocked.",
                )
                // A group MMS row or a group echo can't be
                // re-dispatched — the phone can't retry those either
                // (tap-to-retry is SMS-only). The message stays
                // failed; the user sends it again from the phone.
                AppError.MmsRetryUnsupported, AppError.GroupEchoRetryUnsupported -> SendResult.Failed(
                    SyncProtocol.MessageFailed.SEND_FAILED,
                    "This message can't be sent again automatically. Send it again from the phone.",
                )
                else -> SendResult.Failed(
                    SyncProtocol.MessageFailed.SEND_FAILED,
                    "The message could not be sent.",
                )
            }
        }
    }

    private suspend fun mapSendOutcome(outcome: Outcome<SendReport>): SendResult {
        if (outcome is Outcome.Failure) {
            return when (outcome.error) {
                AppError.NotDefaultSmsApp -> SendResult.Failed(
                    SyncProtocol.MessageFailed.HUB_NOT_DEFAULT_SMS,
                    "Relay on the phone isn't your default messaging app.",
                )
                AppError.RecipientBlocked -> SendResult.Failed(
                    SyncProtocol.MessageFailed.BLOCKED_RECIPIENT,
                    "The recipient is blocked.",
                )
                else -> SendResult.Failed(
                    SyncProtocol.MessageFailed.SEND_FAILED,
                    "The message could not be sent.",
                )
            }
        }
        val report = (outcome as Outcome.Success).value
        // The row the desktop shows: the group echo for a broadcast
        // group send, else the single dispatched row (a group MMS
        // dispatches exactly one row — the group row).
        val messageId = report.groupEchoId ?: report.dispatched.firstOrNull()
            ?: return SendResult.Failed(
                SyncProtocol.MessageFailed.SEND_FAILED,
                "The message could not be sent.",
            )
        // The ack status is the row's status at ack time: normally
        // PENDING, but a broadcast group echo is frozen SENT (all
        // dispatched) or FAILED (some failed) at write time.
        val status = repository.findMessageById(messageId)?.status?.name?.lowercase()
            ?: Message.Status.PENDING.name.lowercase()
        return SendResult.Sent(messageId, status, report.blocked.size)
    }

    override suspend fun markRead(conversationId: Long): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.markRead(conversationId)
        return ActionResult(ok = true)
    }

    override suspend fun markAllRead(): ActionResult {
        repository.markAllRead()
        return ActionResult(ok = true)
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.setPinned(conversationId, pinned)
        return ActionResult(ok = true)
    }

    override suspend fun setArchived(conversationId: Long, archived: Boolean): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.setArchived(conversationId, archived)
        return ActionResult(ok = true)
    }

    override suspend fun setMuted(conversationId: Long, muted: Boolean): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.setMuted(conversationId, muted)
        return ActionResult(ok = true)
    }

    override suspend fun setReaction(conversationId: Long, messageId: Long, emoji: String?): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        // The message must belong to the named conversation: without
        // this, a stale or malicious client could set a reaction on a
        // message in a vault conversation (`setReaction` itself has no
        // vault guard — it is a local-state write, not a read).
        val message = repository.findMessageById(messageId)
        if (message == null || message.conversationId != conversationId) {
            return ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Message not found.",
            )
        }
        val previousEmoji = message.reactionEmoji
        // The phone's order: local state first, then the SMS. If the
        // SMS fails, the local reaction stays set. No dispatch is
        // needed when nothing changed (Noop) or when a removal has no
        // previous emoji to name in the removal SMS.
        val dispatch = when (val result = repository.setReaction(messageId, emoji)) {
            is SetReactionResult.First -> ReactionDispatch(result.messageId, result.emoji, false)
            is SetReactionResult.Changed -> ReactionDispatch(messageId, result.to, false)
            SetReactionResult.Removed ->
                previousEmoji?.let { ReactionDispatch(messageId, it, true) }
            SetReactionResult.Noop -> null
        }
        return dispatch?.let { dispatchReactionSms(it, message) } ?: ActionResult(ok = true)
    }

    /**
     * The phone's reaction-dispatch rules, in the same order the
     * thread screen applies them: no SMS for our own messages, no SMS
     * when the user turned reactions-to-recipient off, no re-send of
     * the same reaction within 60 s, and no removal SMS when a
     * reaction came back between the set and the dispatch. The local
     * reaction is already set in every case — only the SMS is gated.
     */
    private suspend fun dispatchReactionSms(dispatch: ReactionDispatch, message: Message): ActionResult {
        // Our own messages first: no SMS, and no settings read either.
        if (message.isOutgoing) return ActionResult(ok = true)
        val sending = (settings.hydratedOrNull() ?: AppSettings()).sending
        // The `when` short-circuits in order, so the anti-race re-read
        // only happens when the earlier gates passed.
        val skipSms = when {
            !sending.sendReactionsToRecipient -> true
            isRecentDuplicate(dispatch) -> true
            dispatch.isRemoval ->
                repository.findMessageById(dispatch.messageId)?.reactionEmoji != null
            else -> false
        }
        if (skipSms) return ActionResult(ok = true)
        recentReactionDispatches[dispatch.messageId] =
            dispatch.copy(timestamp = System.currentTimeMillis())
        return when (
            sendReaction(dispatch.messageId, dispatch.emoji, sending.reactionFormat, dispatch.isRemoval)
        ) {
            is Outcome.Success -> ActionResult(ok = true)
            is Outcome.Failure -> ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.SEND_FAILED,
                message = "The reaction was saved on the phone but could not be sent.",
            )
        }
    }

    /** The 60 s same-emoji dedup, mirroring the thread screen's burst guard. */
    private fun isRecentDuplicate(dispatch: ReactionDispatch): Boolean {
        val now = System.currentTimeMillis()
        recentReactionDispatches.entries.removeAll { now - it.value.timestamp > REACTION_DEDUP_WINDOW_MS }
        val last = recentReactionDispatches[dispatch.messageId]
        return last != null && last.emoji == dispatch.emoji && last.isRemoval == dispatch.isRemoval &&
            now - last.timestamp < REACTION_DEDUP_WINDOW_MS
    }

    override suspend fun setPinnedMessage(conversationId: Long, messageId: Long?): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        if (messageId != null) {
            // Same membership guard as `setReaction`: only a message
            // of this thread can be pinned in it.
            val message = repository.findMessageById(messageId)
            if (message == null || message.conversationId != conversationId) {
                return ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                    message = "Message not found.",
                )
            }
            repository.setPinnedMessage(conversationId, messageId, System.currentTimeMillis())
        } else {
            repository.setPinnedMessage(conversationId, null, null)
        }
        return ActionResult(ok = true)
    }

    /** The conversation an action may touch: existing and never vault. */
    private suspend fun resolveActionTarget(conversationId: Long): Conversation? =
        repository.findById(conversationId)?.takeIf { !it.inVault }

    private fun unknownConversation(): ActionResult = ActionResult(
        ok = false,
        code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
        message = "Conversation not found.",
    )

    companion object {
        /** Same window as the thread screen's reaction burst guard (60 s). */
        private const val REACTION_DEDUP_WINDOW_MS = 60_000L
    }
}

/** One reaction SMS dispatch, as recorded for the 60 s dedup. */
private data class ReactionDispatch(
    val messageId: Long,
    val emoji: String,
    val isRemoval: Boolean,
    val timestamp: Long = 0L,
)

/** Domain → wire mapping. Ids are decimal strings of the Room ids. */
private fun Conversation.toSynced(messageCount: Int): SyncedConversation = SyncedConversation(
    id = id.toString(),
    threadId = threadId,
    addresses = addresses.map { it.raw },
    displayName = displayName,
    customName = customName,
    title = title,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    unread = unreadCount,
    pinned = pinned,
    archived = archived,
    muted = muted,
    blocked = blocked,
    messageCount = messageCount,
    pinnedMessageId = pinnedMessageId?.toString(),
    pinnedMessageAt = pinnedMessageAt,
)

private fun Message.toSynced(): SyncedMessage = SyncedMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    address = address,
    body = body,
    type = type.name.lowercase(),
    direction = direction.name.lowercase(),
    date = date,
    dateSent = dateSent,
    read = read,
    starred = starred,
    status = status.name.lowercase(),
    errorCode = errorCode,
    replyToMessageId = replyToMessageId?.toString(),
    reaction = reactionEmoji,
    attachments = attachments.map { it.toSynced() },
    clientMessageId = clientMessageId,
)

private fun Attachment.toSynced(): SyncedAttachment = SyncedAttachment(
    id = id.toString(),
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
)

private fun DomainMessageState.toSynced(): MessageState = MessageState(
    id = id,
    conversationId = conversationId,
    status = status.name.lowercase(),
    reaction = reactionEmoji,
    read = read,
    starred = starred,
)
