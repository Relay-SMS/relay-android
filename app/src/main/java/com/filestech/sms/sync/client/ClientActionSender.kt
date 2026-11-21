package com.filestech.sms.sync.client

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.dao.NoteMessageDao
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
import java.util.UUID
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
 *
 * Round 36 phase 2 — notes follow the same shapes: `create_note`
 * (one at a time, awaited up to 20 s, answered by `note_created`
 * or a refused `action_result`), `delete_note` /
 * `delete_note_message` (awaited up to 20 s, answered by
 * `action_result` — the caller deletes its local row only on
 * success), and the pin / archive / rename / edit actions (optimistic
 * + revert, like the conversation flags). An edit's success is the
 * `note_message_updated` broadcast, which drops the pending revert
 * via [onNoteMessageUpdated] — the `action_result` `ok:true` does
 * the same, whichever arrives first.
 */
@Singleton
class ClientActionSender @Inject constructor(
    private val channel: ClientSyncChannel,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settings: AppSettingsSource,
    private val noteDao: NoteDao,
    private val noteMessageDao: NoteMessageDao,
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
    private val noteCreateMutex = Mutex()
    private var pendingNoteCreate: CompletableDeferred<Outcome<Long>>? = null

    /** Awaited note deletes, keyed like the reverts — several may be in flight at once. */
    private val pendingDeletes = mutableMapOf<ActionKey, CompletableDeferred<Outcome<Unit>>>()

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
     * Round 36 phase 2 — sends `create_note` and awaits the hub's
     * answer (up to 20 s): `note_created` (success) or a refused
     * `action_result`. Returns the LOCAL note id — the engine
     * upserts the note before the answer is delivered, so the row
     * exists by the time this returns. One create at a time (the
     * frame carries a client UUID, but the answer carries no
     * request id, so the next `note_created` is the answer to this
     * one). Offline → [AppError.ClientOffline].
     */
    override suspend fun createNote(title: String): Outcome<Long> {
        if (title.isBlank()) return Outcome.Failure(AppError.Validation("no title"))
        return noteCreateMutex.withLock {
            val deferred = CompletableDeferred<Outcome<Long>>()
            mutex.withLock { pendingNoteCreate = deferred }
            val sent = channel.send(
                ClientFrames.encodeCreateNote(
                    SyncProtocol.CreateNote(UUID.randomUUID().toString(), title),
                ),
            )
            if (!sent) {
                mutex.withLock { if (pendingNoteCreate === deferred) pendingNoteCreate = null }
                return@withLock Outcome.Failure(AppError.ClientOffline)
            }
            val outcome = withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
                ?: Outcome.Failure(AppError.Network())
            mutex.withLock { if (pendingNoteCreate === deferred) pendingNoteCreate = null }
            outcome
        }
    }

    /**
     * Round 36 phase 2 — sends `delete_note` and awaits the hub's
     * `action_result` (up to 20 s). The caller deletes its local
     * row only on success — a deleted row cannot be reverted.
     * Several deletes may be in flight at once (keyed by note);
     * a second delete of the SAME note awaits the first instead
     * of sending a second frame. Offline → [AppError.ClientOffline].
     */
    override suspend fun deleteNote(localNoteId: Long): Outcome<Unit> {
        val hubId = noteDao.findById(localNoteId)?.hubNoteId
            ?: return Outcome.Failure(AppError.Validation("note is not synced with the phone"))
        return awaitDelete(ActionKey(ACTION_DELETE_NOTE, hubId.toString(), null)) {
            ClientFrames.encodeDeleteNote(SyncProtocol.DeleteNote(hubId.toString()))
        }
    }

    /**
     * Round 36 phase 2 — sends `delete_note_message` and awaits
     * the hub's `action_result` (up to 20 s). Same shape as
     * [deleteNote]: the caller deletes its local row only on
     * success.
     */
    override suspend fun deleteNoteMessage(localNoteId: Long, localMessageId: Long): Outcome<Unit> {
        val hubNoteId = noteDao.findById(localNoteId)?.hubNoteId
            ?: return Outcome.Failure(AppError.Validation("note is not synced with the phone"))
        val hubMessageId = noteMessageDao.findById(localMessageId)?.hubNoteMessageId
            ?: return Outcome.Failure(AppError.Validation("entry is not synced with the phone"))
        return awaitDelete(
            ActionKey(ACTION_DELETE_NOTE_MESSAGE, hubNoteId.toString(), hubMessageId.toString()),
        ) {
            ClientFrames.encodeDeleteNoteMessage(
                SyncProtocol.DeleteNoteMessage(hubNoteId.toString(), hubMessageId.toString()),
            )
        }
    }

    /**
     * Round 36 phase 2 — the awaited-delete shape: one deferred
     * per key, the frame sent once, the `action_result` completing
     * it. A second caller for the same key awaits the same
     * deferred instead of sending a second frame.
     */
    private suspend fun awaitDelete(
        key: ActionKey,
        encode: () -> String,
    ): Outcome<Unit> {
        var created = false
        val deferred = mutex.withLock {
            pendingDeletes[key]?.let { return@withLock it }
            CompletableDeferred<Outcome<Unit>>().also {
                pendingDeletes[key] = it
                created = true
            }
        }
        if (created) {
            if (!channel.send(encode())) {
                mutex.withLock { pendingDeletes.remove(key) }
                return Outcome.Failure(AppError.ClientOffline)
            }
        }
        val outcome = withTimeoutOrNull(CREATE_TIMEOUT_MS) { deferred.await() }
            ?: Outcome.Failure(AppError.Network())
        mutex.withLock { if (pendingDeletes[key] === deferred) pendingDeletes.remove(key) }
        return outcome
    }

    /**
     * Client mode — tablet calls: sends `dial_request` and awaits
     * the hub's `dial_result` (up to 10 s). Success means the phone
     * placed the call — the off-hook transition follows as
     * `call_state` `dialing`, and the audio stays on the phone.
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
        when (result.action) {
            ACTION_CREATE_CONVERSATION -> {
                if (!result.ok) {
                    failPendingCreate(
                        AppError.Validation(result.message ?: result.code ?: "create failed"),
                    )
                }
            }
            // Round 36 phase 2 — a refused `create_note` fails the
            // pending note create (a successful one answers with
            // `note_created`, never an `action_result`).
            ACTION_CREATE_NOTE -> {
                if (!result.ok) {
                    failPendingNoteCreate(
                        AppError.Validation(result.message ?: result.code ?: "create failed"),
                    )
                }
            }
            else -> onActionResultForKey(result)
        }
    }

    /**
     * The `action_result` for a write-back keyed by [ActionKey]:
     * an awaited delete completes here (success or refusal — the
     * caller deletes its local row only on success), otherwise the
     * pending revert runs on `ok:false`.
     */
    private suspend fun onActionResultForKey(result: SyncProtocol.ActionResult) {
        val key = ActionKey(result.action, result.conversationId, result.messageId)
        val deleteDeferred = mutex.withLock { pendingDeletes.remove(key) }
        if (deleteDeferred != null) {
            deleteDeferred.complete(
                if (result.ok) {
                    Outcome.Success(Unit)
                } else {
                    Outcome.Failure(
                        AppError.Validation(result.message ?: result.code ?: "delete failed"),
                    )
                },
            )
            return
        }
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

    /**
     * Round 36 phase 2 — the `note_created` answer: completes the
     * pending note create with the LOCAL note id. The engine
     * upserted the note before calling here, so the row exists.
     */
    suspend fun onNoteCreated(value: SyncProtocol.NoteCreated) {
        val deferred = mutex.withLock { pendingNoteCreate } ?: return
        val hubId = parseHubId(value.note.id)
        val localId = hubId?.let { noteDao.findByHubId(it)?.id }
        val outcome = if (localId != null) {
            Outcome.Success(localId)
        } else {
            Outcome.Failure(AppError.NotFound("note"))
        }
        mutex.withLock { if (pendingNoteCreate === deferred) pendingNoteCreate = null }
        deferred.complete(outcome)
    }

    /**
     * Round 36 phase 2 — the `note_message_updated` broadcast for
     * an edit this device sent: the edit landed on the hub, so the
     * pending revert is dropped (not run). The `action_result`
     * `ok:true` does the same — whichever arrives first wins, and
     * the second is a no-op. A broadcast for an edit another
     * device sent has no pending revert and is a no-op too.
     */
    suspend fun onNoteMessageUpdated(value: SyncProtocol.NoteMessageUpdated) {
        val key = ActionKey(
            ACTION_EDIT_NOTE_MESSAGE,
            value.message.noteId,
            value.message.id,
        )
        mutex.withLock { pendingActions.remove(key) }
    }

    /** The session ended (disconnect, revocation): a create or dial in flight can never answer. */
    suspend fun onSessionEnded() {
        failPendingCreate(AppError.ClientOffline)
        failPendingDial(AppError.ClientOffline)
        failPendingNoteCreate(AppError.ClientOffline)
        val deletes = mutex.withLock {
            pendingDeletes.values.toList().also { pendingDeletes.clear() }
        }
        deletes.forEach { it.complete(Outcome.Failure(AppError.ClientOffline)) }
    }

    private suspend fun failPendingCreate(error: AppError) {
        val deferred = mutex.withLock { pendingCreate?.also { pendingCreate = null } } ?: return
        deferred.complete(Outcome.Failure(error))
    }

    private suspend fun failPendingNoteCreate(error: AppError) {
        val deferred = mutex.withLock { pendingNoteCreate?.also { pendingNoteCreate = null } } ?: return
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
        is ClientAction.SetNotePin -> flagNoteAction(
            action.localNoteId,
            action.value,
            ACTION_SET_NOTE_PIN,
        ) { id, v ->
            ClientFrames.encodeSetNotePin(SyncProtocol.SetNotePin(id, v))
        }
        is ClientAction.SetNoteArchived -> flagNoteAction(
            action.localNoteId,
            action.value,
            ACTION_SET_NOTE_ARCHIVED,
        ) { id, v ->
            ClientFrames.encodeSetNoteArchived(SyncProtocol.SetNoteArchived(id, v))
        }
        is ClientAction.RenameNote -> {
            val hubNoteId = hubNoteId(action.localNoteId) ?: return null
            EncodedAction(
                ClientFrames.encodeRenameNote(
                    SyncProtocol.RenameNote(hubNoteId.toString(), action.title),
                ),
                ActionKey(ACTION_RENAME_NOTE, hubNoteId.toString(), null),
            )
        }
        is ClientAction.EditNoteMessage -> {
            val hubNoteId = hubNoteId(action.localNoteId) ?: return null
            val hubMsgId = hubNoteMessageId(action.localMessageId) ?: return null
            EncodedAction(
                ClientFrames.encodeEditNoteMessage(
                    SyncProtocol.EditNoteMessage(
                        hubNoteId.toString(),
                        hubMsgId.toString(),
                        action.body,
                    ),
                ),
                ActionKey(
                    ACTION_EDIT_NOTE_MESSAGE,
                    hubNoteId.toString(),
                    hubMsgId.toString(),
                ),
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

    /** Round 36 phase 2 — the note half of [flagAction]: pin / archive by hub note id. */
    private suspend fun flagNoteAction(
        localNoteId: Long,
        value: Boolean,
        action: String,
        encode: (String, Boolean) -> String,
    ): EncodedAction? {
        val hubId = hubNoteId(localNoteId) ?: return null
        return EncodedAction(encode(hubId.toString(), value), ActionKey(action, hubId.toString(), null))
    }

    private suspend fun hubNoteId(localNoteId: Long): Long? =
        noteDao.findById(localNoteId)?.hubNoteId

    private suspend fun hubNoteMessageId(localMessageId: Long): Long? =
        noteMessageDao.findById(localMessageId)?.hubNoteMessageId

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
        private const val ACTION_CREATE_NOTE = "create_note"
        private const val ACTION_DELETE_NOTE = "delete_note"
        private const val ACTION_DELETE_NOTE_MESSAGE = "delete_note_message"
        private const val ACTION_SET_NOTE_PIN = "set_note_pin"
        private const val ACTION_SET_NOTE_ARCHIVED = "set_note_archived"
        private const val ACTION_RENAME_NOTE = "rename_note"
        private const val ACTION_EDIT_NOTE_MESSAGE = "edit_note_message"
    }
}
