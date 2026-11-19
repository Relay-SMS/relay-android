package com.filestech.sms.sync.client

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.sync.SyncProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode C3 — the write-back sender for a linked device.
 *
 * Callers apply their optimistic change locally, then hand the action
 * here: the sender resolves the local ids to hub ids, sends the frame,
 * and holds the revert until the hub's `action_result` answers — the
 * hub is authoritative, so `ok:false` runs the revert. When the frame
 * cannot go out at all (offline, or an id that has no hub id), the
 * revert runs immediately and the call returns false.
 *
 * One exception to the revert rule, ported from the desktop: a
 * `set_reaction` refused with `send_failed` does NOT revert — the
 * phone sets the reaction locally first and only the SMS failed, so
 * the reaction stays set on the hub.
 *
 * The sender also owns `create_conversation`: one create at a time
 * (the frame carries no request id, so the next
 * `conversation_created` answers it), awaited up to 20 s. The engine
 * upserts the conversation before delivering the answer, so the local
 * row exists by the time the caller reads it. `dial_request` follows
 * the same shape (one dial at a time, awaited up to 10 s) — the
 * tablet asks the hub to place a cellular call, and the audio stays
 * on the phone.
 */
@Singleton
class ClientActionSender @Inject constructor(
    private val channel: ClientSyncChannel,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settings: AppSettingsSource,
) : ClientActionGateway {

    /** The identity an `action_result` answers with: action + hub ids. */
    private data class ActionKey(val action: String, val hubConversationId: String?, val hubMessageId: String?)

    private data class EncodedAction(val text: String, val key: ActionKey)

    private val mutex = Mutex()
    private val pendingActions = mutableMapOf<ActionKey, (suspend () -> Unit)?>()
    private val createMutex = Mutex()
    private var pendingCreate: CompletableDeferred<Outcome<Long>>? = null
    private val dialMutex = Mutex()
    private var pendingDial: CompletableDeferred<Outcome<Unit>>? = null

    override suspend fun isClientMode(): Boolean =
        (settings.hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT

    override suspend fun sendAction(action: ClientAction, revert: (suspend () -> Unit)?): Boolean {
        val encoded = encodeAction(action)
        if (encoded == null) {
            // An id that could not be resolved to a hub id (a
            // hub-mode leftover row, or a message not yet synced):
            // the frame cannot go out, so the optimistic change is
            // undone right here.
            runRevert(revert)
            return false
        }
        if (!channel.send(encoded.text)) {
            runRevert(revert)
            return false
        }
        mutex.withLock { pendingActions[encoded.key] = revert }
        return true
    }

    override suspend fun createConversation(addresses: List<String>, customName: String?): Outcome<Long> {
        if (addresses.isEmpty()) return Outcome.Failure(AppError.Validation("no recipients"))
        val name = customName?.trim()?.takeIf { it.isNotEmpty() }
        // One create at a time: the frame carries no request id, so
        // the next `conversation_created` is the answer to this one.
        return createMutex.withLock {
            val deferred = CompletableDeferred<Outcome<Long>>()
            mutex.withLock { pendingCreate = deferred }
            val sent = channel.send(
                ClientFrames.encodeCreateConversation(SyncProtocol.CreateConversation(addresses, name)),
            )
            if (!sent) {
                mutex.withLock { if (pendingCreate === deferred) pendingCreate = null }
                return@withLock Outcome.Failure(AppError.ClientOffline)
            }
            val outcome = withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
                ?: Outcome.Failure(AppError.Network())
            mutex.withLock { if (pendingCreate === deferred) pendingCreate = null }
            outcome
        }
    }

    /**
     * Client mode — tablet calls: sends `dial_request` and awaits
     * the hub's `dial_result` (up to 10 s). Success means the phone
     * placed the call — the off-hook transition follows as
     * `call_state` `active`, and the audio stays on the phone.
     * Failure carries the hub's short error code
     * (`permission_denied`, `no_number`, `failed`) as the
     * [AppError.Validation] message, so the caller can explain it.
     * One dial at a time (the frame carries no request id, so the
     * next `dial_result` answers it); a dial and a create may be in
     * flight together — different frames, different answers.
     */
    suspend fun dial(number: String): Outcome<Unit> {
        if (number.isBlank()) return Outcome.Failure(AppError.Validation("no number"))
        return dialMutex.withLock {
            val deferred = CompletableDeferred<Outcome<Unit>>()
            mutex.withLock { pendingDial = deferred }
            val sent = channel.send(
                ClientFrames.encodeDialRequest(SyncProtocol.DialRequest(number)),
            )
            if (!sent) {
                mutex.withLock { if (pendingDial === deferred) pendingDial = null }
                return@withLock Outcome.Failure(AppError.ClientOffline)
            }
            val outcome = withTimeoutOrNull(DIAL_TIMEOUT_MS) { deferred.await() }
                ?: Outcome.Failure(AppError.Network())
            mutex.withLock { if (pendingDial === deferred) pendingDial = null }
            outcome
        }
    }

    /**
     * The `dial_result` answer: completes the pending dial. A
     * refused dial fails with the hub's error code — the engine
     * clears the outgoing call state it set for the dial.
     */
    suspend fun onDialResult(result: SyncProtocol.DialResult) {
        val deferred = mutex.withLock { pendingDial?.also { pendingDial = null } } ?: return
        val outcome = if (result.ok) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError.Validation(result.error ?: SyncProtocol.DialResult.FAILED))
        }
        deferred.complete(outcome)
    }

    /** The `action_result` for a write-back (or a refused create): revert on `ok:false`. */
    suspend fun onActionResult(result: SyncProtocol.ActionResult) {
        if (result.action == ACTION_CREATE_CONVERSATION) {
            if (!result.ok) {
                failPendingCreate(
                    AppError.Validation(result.message ?: result.code ?: "create failed"),
                )
            }
            return
        }
        val key = ActionKey(result.action, result.conversationId, result.messageId)
        val revert = mutex.withLock { pendingActions.remove(key) } ?: return
        if (result.ok) return
        // A reaction whose SMS didn't go out stays set on the phone
        // (the phone's own behavior) — nothing to revert.
        if (result.action == ACTION_SET_REACTION && result.code == SyncProtocol.MessageFailed.SEND_FAILED) return
        Timber.w("ClientActionSender: %s refused (%s) — reverting", result.action, result.code)
        runRevert(revert)
    }

    /**
     * The `conversation_created` answer: completes the pending create
     * with the LOCAL conversation id. The engine upserted the
     * conversation before calling here, so the row exists.
     */
    suspend fun onConversationCreated(value: SyncProtocol.ConversationCreated) {
        val deferred = mutex.withLock { pendingCreate } ?: return
        val hubId = parseHubId(value.conversation.id)
        val localId = hubId?.let { conversationDao.findByHubConversationId(it)?.id }
        val outcome = if (localId != null) {
            Outcome.Success(localId)
        } else {
            Outcome.Failure(AppError.NotFound("conversation"))
        }
        mutex.withLock { if (pendingCreate === deferred) pendingCreate = null }
        deferred.complete(outcome)
    }

    /** The session ended (disconnect, revocation): a create or dial in flight can never answer. */
    suspend fun onSessionEnded() {
        failPendingCreate(AppError.ClientOffline)
        failPendingDial(AppError.ClientOffline)
    }

    private suspend fun failPendingCreate(error: AppError) {
        val deferred = mutex.withLock { pendingCreate?.also { pendingCreate = null } } ?: return
        deferred.complete(Outcome.Failure(error))
    }

    private suspend fun failPendingDial(error: AppError) {
        val deferred = mutex.withLock { pendingDial?.also { pendingDial = null } } ?: return
        deferred.complete(Outcome.Failure(error))
    }

    private suspend fun runRevert(revert: (suspend () -> Unit)?) {
        if (revert == null) return
        runCatching { revert() }.onFailure { Timber.w(it, "ClientActionSender: revert failed") }
    }

    private suspend fun hubConversationId(localConversationId: Long): Long? =
        conversationDao.findById(localConversationId)?.hubConversationId

    private suspend fun encodeAction(action: ClientAction): EncodedAction? = when (action) {
        is ClientAction.MarkRead -> {
            val hubId = hubConversationId(action.localConversationId) ?: return null
            EncodedAction(
                ClientFrames.encodeMarkRead(SyncProtocol.MarkRead(hubId.toString())),
                ActionKey(ACTION_MARK_READ, hubId.toString(), null),
            )
        }
        ClientAction.MarkAllRead -> EncodedAction(
            ClientFrames.encodeMarkAllRead(),
            ActionKey(ACTION_MARK_ALL_READ, null, null),
        )
        is ClientAction.SetPin -> flagAction(action.localConversationId, action.value, ACTION_SET_PIN) { id, v ->
            ClientFrames.encodeSetPin(SyncProtocol.SetPin(id, v))
        }
        is ClientAction.SetArchived -> flagAction(
            action.localConversationId,
            action.value,
            ACTION_SET_ARCHIVED,
        ) { id, v ->
            ClientFrames.encodeSetArchived(SyncProtocol.SetArchived(id, v))
        }
        is ClientAction.SetMuted -> flagAction(action.localConversationId, action.value, ACTION_SET_MUTED) { id, v ->
            ClientFrames.encodeSetMuted(SyncProtocol.SetMuted(id, v))
        }
        is ClientAction.SetBlocked -> flagAction(
            action.localConversationId,
            action.value,
            ACTION_SET_BLOCKED,
        ) { id, v ->
            ClientFrames.encodeSetBlocked(SyncProtocol.SetBlocked(id, v))
        }
        is ClientAction.SetReaction -> {
            val hubConvId = hubConversationId(action.localConversationId) ?: return null
            val hubMsgId = messageDao.findById(action.localMessageId)?.hubMessageId ?: return null
            EncodedAction(
                ClientFrames.encodeSetReaction(
                    SyncProtocol.SetReaction(hubConvId.toString(), hubMsgId.toString(), action.emoji),
                ),
                ActionKey(ACTION_SET_REACTION, hubConvId.toString(), hubMsgId.toString()),
            )
        }
        is ClientAction.SetPinnedMessage -> {
            val hubConvId = hubConversationId(action.localConversationId) ?: return null
            val hubMsgId = action.localMessageId?.let { messageDao.findById(it)?.hubMessageId }
            if (action.localMessageId != null && hubMsgId == null) return null
            EncodedAction(
                ClientFrames.encodeSetPinnedMessage(
                    SyncProtocol.SetPinnedMessage(hubConvId.toString(), hubMsgId?.toString()),
                ),
                ActionKey(ACTION_SET_PINNED_MESSAGE, hubConvId.toString(), hubMsgId?.toString()),
            )
        }
    }

    private suspend fun flagAction(
        localConversationId: Long,
        value: Boolean,
        action: String,
        encode: (String, Boolean) -> String,
    ): EncodedAction? {
        val hubId = hubConversationId(localConversationId) ?: return null
        return EncodedAction(encode(hubId.toString(), value), ActionKey(action, hubId.toString(), null))
    }

    companion object {
        /** A create that hears nothing back in 20 s fails (same as the desktop). */
        const val CREATE_TIMEOUT_MS: Long = 20_000L

        /** A dial that hears no `dial_result` in 10 s fails (the phone answers at once). */
        const val DIAL_TIMEOUT_MS: Long = 10_000L

        private const val ACTION_MARK_READ = "mark_read"
        private const val ACTION_MARK_ALL_READ = "mark_all_read"
        private const val ACTION_SET_PIN = "set_pin"
        private const val ACTION_SET_ARCHIVED = "set_archived"
        private const val ACTION_SET_MUTED = "set_muted"
        private const val ACTION_SET_BLOCKED = "set_blocked"
        private const val ACTION_SET_REACTION = "set_reaction"
        private const val ACTION_SET_PINNED_MESSAGE = "set_pinned_message"
        private const val ACTION_CREATE_CONVERSATION = "create_conversation"
    }
}
