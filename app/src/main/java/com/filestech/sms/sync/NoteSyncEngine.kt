package com.filestech.sms.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 36 phase 2 — the hub-side note sync engine: one per session,
 * owned by [SyncEngine] (which passes it the session scope and the
 * send lambda). A separate class on purpose: the message engine is
 * at the Detekt class-size limit, and notes carry their own snapshot
 * phase, their own live collectors, and nine frame handlers.
 *
 * The stream phase runs inside the message engine's `runStream`
 * (contacts → conversations → notes → note messages →
 * `sync_complete`); the live collectors run in this engine's own
 * scope and start with the session.
 *
 * Live push dedups by upsert-by-id on the client, so a message that
 * arrives both in the stream and in a live push is stored once.
 * The `note_new` skip-set is best-effort (see
 * [onCreateNote]): a duplicate `note_new` to the requester is
 * harmless for the same reason.
 *
 * @param source the note store seam.
 * @param parentScope the session's scope; the engine's jobs are
 * children of it, so a dead socket takes the engine with it.
 * @param send carries one outbound text frame to the client.
 */
class NoteSyncEngine(
    private val source: NoteSyncSource,
    parentScope: CoroutineScope,
    private val send: suspend (String) -> Unit,
) {
    private val engineJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + engineJob)

    private var started = false

    /** Last note snapshot the live collector saw (by id). */
    private var lastNotes: Map<String, SyncedNote> = emptyMap()

    /** Per-note max entry id pushed this session (live path). */
    private val maxSentByNote = ConcurrentHashMap<Long, Long>()

    /** Baseline max note-message id at collector start — live push only covers newer entries. */
    private var liveBaselineNoteMessageId: Long = 0L

    /** Last edited bodies the edits collector saw, by entry id. */
    private var lastEditedBodies: Map<Long, String> = emptyMap()

    /**
     * Hub ids created by THIS session's `create_note` frames: the
     * collector's `note_new` arm skips them — the requester already
     * got `note_created`. Best-effort: a collector emission between
     * the DB insert and the recording can still push a duplicate
     * `note_new` to the requester, which upserts harmlessly.
     */
    private val skipNewNotes = Collections.synchronizedSet(mutableSetOf<Long>())

    /**
     * Starts the live collectors. Called once, when the session
     * authenticates (via [SyncEngine.start]). Idempotent — a second
     * call is a no-op.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { collectNotes() }
        scope.launch { collectNoteMessageEdits() }
    }

    /** Cancels the collectors. Called when the session ends (via [SyncEngine.stop]). */
    fun stop() {
        engineJob.cancel()
    }

    // -- Stream phase (runs inside SyncEngine.runStream) ----------------

    /** The note list for `sync_started` / the stream phase. */
    suspend fun notesForStream(): List<SyncedNote> = source.notes()

    /** The note count for `sync_started`. */
    suspend fun totalNotes(): Int = source.totalNotes()

    /** The note cursor — read at stream start and again at stream end. */
    suspend fun maxNoteMessageId(): Long = source.maxNoteMessageId()

    /**
     * Streams the notes phase: the note snapshot in 100-note
     * batches, then — in FULL mode — the per-note message pages
     * (all of them, or the delta above the client's note cursor).
     * NEW_ONLY streams the list only, like the message phase.
     */
    suspend fun streamNotesPhase(request: SyncProtocol.SyncRequest, notes: List<SyncedNote>) {
        streamNotes(send, notes)
        if (request.mode != SyncProtocol.SyncRequest.FULL) return
        val sinceNoteId = request.sinceNoteMessageId?.toLongOrNull() ?: 0L
        if (sinceNoteId > 0L) {
            streamNoteDelta(notes, sinceNoteId)
        } else {
            streamAllNoteMessages(notes)
        }
    }

    /** Full mode, no cursor: every note's entries, newest first, most-recent note first. */
    private suspend fun streamAllNoteMessages(notes: List<SyncedNote>) {
        for (note in notes) {
            streamOneNote(note)
        }
    }

    private suspend fun streamOneNote(note: SyncedNote) {
        val noteId = note.id.toLongOrNull() ?: return
        var beforeId: Long? = 0L
        while (beforeId != null) {
            beforeId = streamOneNotePage(note, noteId, beforeId)
        }
    }

    /**
     * Streams one page of [note] below [beforeId]. Returns the next
     * `before_id`, or null when the note is exhausted.
     */
    private suspend fun streamOneNotePage(
        note: SyncedNote,
        noteId: Long,
        beforeId: Long,
    ): Long? {
        val page = source.noteMessagesBefore(noteId, beforeId, NOTE_MESSAGE_BATCH_SIZE)
        if (page.isEmpty()) return null
        val hasMore = page.size == NOTE_MESSAGE_BATCH_SIZE
        val lastId = page.last().id
        send(
            SyncProtocol.encodeNoteMessagesBatch(
                SyncProtocol.NoteMessagesBatch(
                    noteId = note.id,
                    messages = page,
                    hasMore = hasMore,
                    nextBeforeId = lastId.takeIf { hasMore },
                ),
            ),
        )
        notePushed(noteId, page)
        return lastId.toLongOrNull().takeIf { hasMore }
    }

    /**
     * Reconnect delta: only the entries above [sinceId], per note,
     * oldest first. The batches carry `has_more: false` — there is
     * no `before_id` continuation for a delta range; the client
     * gap-fills older history via `note_messages_request` instead.
     */
    private suspend fun streamNoteDelta(notes: List<SyncedNote>, sinceId: Long) {
        for (note in notes) {
            val noteId = note.id.toLongOrNull()
            val delta = noteId?.let { source.noteMessagesAfter(it, sinceId) }.orEmpty()
            if (delta.isEmpty()) continue
            delta.chunked(NOTE_MESSAGE_BATCH_SIZE).forEach { chunk ->
                send(
                    SyncProtocol.encodeNoteMessagesBatch(
                        SyncProtocol.NoteMessagesBatch(
                            noteId = note.id,
                            messages = chunk,
                            hasMore = false,
                        ),
                    ),
                )
            }
            if (noteId != null) notePushed(noteId, delta)
        }
    }

    // -- Frame handlers ---------------------------------------------------
    // Same shape as the message engine's: each starts a job and
    // returns immediately, so a slow save never blocks the frame loop.

    /** Handles a `create_note`: answered with `note_created` or `action_result` `ok: false`. */
    fun onCreateNote(request: SyncProtocol.CreateNote) {
        scope.launch {
            try {
                when (val result = source.createNote(request.clientNoteId, request.title)) {
                    is CreateNoteResult.Created -> {
                        result.note.id.toLongOrNull()?.let { skipNewNotes.add(it) }
                        send(
                            SyncProtocol.encodeNoteCreated(
                                SyncProtocol.NoteCreated(result.note),
                            ),
                        )
                    }
                    is CreateNoteResult.Failed -> send(
                        SyncProtocol.encodeActionResult(
                            SyncProtocol.ActionResult(
                                action = "create_note",
                                ok = false,
                                code = result.code,
                                message = result.message,
                            ),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "NoteSyncEngine: create note failed")
                runCatching {
                    send(
                        SyncProtocol.encodeActionResult(
                            SyncProtocol.ActionResult(
                                action = "create_note",
                                ok = false,
                                code = SyncProtocol.NoteMessageFailed.INTERNAL,
                                message = "This note couldn't be created.",
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** Handles a `save_note_message`: answered with `note_message_saved` or `note_message_failed`. */
    fun onSaveNoteMessage(request: SyncProtocol.SaveNoteMessage) {
        scope.launch {
            try {
                runSaveNoteMessage(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "NoteSyncEngine: save note message failed")
                runCatching { send(saveFailedInternal(request)) }
            }
        }
    }

    private suspend fun runSaveNoteMessage(request: SyncProtocol.SaveNoteMessage) {
        val noteId = request.noteId.toLongOrNull()
        if (noteId == null) {
            send(
                SyncProtocol.encodeNoteMessageFailed(
                    SyncProtocol.NoteMessageFailed(
                        clientMessageId = request.clientMessageId,
                        noteId = request.noteId,
                        code = SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE,
                        message = "This note doesn't exist.",
                    ),
                ),
            )
            return
        }
        val result = source.saveNoteMessage(
            request.clientMessageId,
            noteId,
            request.body,
            request.attachments,
        )
        when (result) {
            is SaveNoteMessageResult.Saved -> send(
                SyncProtocol.encodeNoteMessageSaved(
                    SyncProtocol.NoteMessageSaved(
                        clientMessageId = request.clientMessageId,
                        messageId = result.messageId.toString(),
                        noteId = request.noteId,
                    ),
                ),
            )
            is SaveNoteMessageResult.Failed -> send(
                SyncProtocol.encodeNoteMessageFailed(
                    SyncProtocol.NoteMessageFailed(
                        clientMessageId = request.clientMessageId,
                        noteId = request.noteId,
                        code = result.code,
                        message = result.message,
                    ),
                ),
            )
        }
    }

    /**
     * Handles an `edit_note_message`: answered with `action_result`
     * (like every other write-back action) — and, on success, the
     * edits collector pushes `note_message_updated` to ALL sessions,
     * which is how the new body reaches the other devices.
     */
    fun onEditNoteMessage(request: SyncProtocol.EditNoteMessage) {
        scope.launch {
            runNoteAction("edit_note_message", request.noteId, request.messageId) { noteId ->
                val messageId = request.messageId.toLongOrNull()
                    ?: return@runNoteAction ActionResult(
                        ok = false,
                        code = SyncProtocol.NoteMessageFailed.UNKNOWN_MESSAGE,
                    )
                source.editNoteMessage(noteId, messageId, request.body)
            }
        }
    }

    /** Handles a `rename_note`, answered with `action_result`. */
    fun onRenameNote(request: SyncProtocol.RenameNote) {
        scope.launch {
            runNoteAction("rename_note", request.noteId, null) { noteId ->
                source.renameNote(noteId, request.title)
            }
        }
    }

    /** Handles a `delete_note`, answered with `action_result`. */
    fun onDeleteNote(request: SyncProtocol.DeleteNote) {
        scope.launch {
            runNoteAction("delete_note", request.noteId, null) { noteId ->
                source.deleteNote(noteId)
            }
        }
    }

    /** Handles a `delete_note_message`, answered with `action_result`. */
    fun onDeleteNoteMessage(request: SyncProtocol.DeleteNoteMessage) {
        scope.launch {
            runNoteAction("delete_note_message", request.noteId, request.messageId) { noteId ->
                val messageId = request.messageId.toLongOrNull()
                    ?: return@runNoteAction ActionResult(
                        ok = false,
                        code = SyncProtocol.NoteMessageFailed.UNKNOWN_MESSAGE,
                    )
                source.deleteNoteMessage(noteId, messageId)
            }
        }
    }

    /** Handles a `set_note_pin`, answered with `action_result`. */
    fun onSetNotePin(request: SyncProtocol.SetNotePin) {
        scope.launch {
            runNoteAction("set_note_pin", request.noteId, null) { noteId ->
                source.setNotePinned(noteId, request.value)
            }
        }
    }

    /** Handles a `set_note_archived`, answered with `action_result`. */
    fun onSetNoteArchived(request: SyncProtocol.SetNoteArchived) {
        scope.launch {
            runNoteAction("set_note_archived", request.noteId, null) { noteId ->
                source.setNoteArchived(noteId, request.value)
            }
        }
    }

    /** Handles a `note_messages_request`: one page fetch, answered with one `note_messages_batch`. */
    fun onNoteMessagesRequest(request: SyncProtocol.NoteMessagesRequest) {
        scope.launch {
            try {
                runNoteMessagesRequest(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "NoteSyncEngine: note messages request failed")
                runCatching { send(SyncProtocol.encodeError("note messages request failed")) }
            }
        }
    }

    private suspend fun runNoteMessagesRequest(request: SyncProtocol.NoteMessagesRequest) {
        val noteId = request.noteId.toLongOrNull()
        if (noteId == null) {
            send(SyncProtocol.encodeError("unknown note: ${request.noteId}"))
            return
        }
        val known = source.notes().any { it.id == request.noteId }
        if (!known) {
            send(SyncProtocol.encodeError("unknown note: ${request.noteId}"))
            return
        }
        val beforeId = request.beforeId?.toLongOrNull() ?: 0L
        val limit = (request.limit ?: NOTE_MESSAGE_BATCH_SIZE).coerceIn(1, NOTE_MESSAGE_BATCH_SIZE)
        val page = source.noteMessagesBefore(noteId, beforeId, limit)
        val hasMore = page.size == limit
        val lastId = page.lastOrNull()?.id
        send(
            SyncProtocol.encodeNoteMessagesBatch(
                SyncProtocol.NoteMessagesBatch(
                    noteId = request.noteId,
                    messages = page,
                    hasMore = hasMore,
                    nextBeforeId = lastId.takeIf { hasMore },
                ),
            ),
        )
        notePushed(noteId, page)
    }

    /**
     * Streams one note attachment's bytes: the ids carry the
     * `"note:"` prefix (stripped here), and the bytes come
     * decrypted from the hub's note envelope — the chunk frames
     * still echo the prefixed id, so the client routes them back
     * to the note download. Same probing rule as the MMS path:
     * every failure looks like a missing attachment. Called from
     * [SyncEngine.onAttachmentRequest] (the prefix branch).
     */
    suspend fun runNoteAttachmentRequest(request: SyncProtocol.AttachmentRequest) {
        val attachmentId = request.attachmentId
            .removePrefix(SyncProtocol.NOTE_ATTACHMENT_ID_PREFIX).toLongOrNull()
        val messageId = request.messageId
            .removePrefix(SyncProtocol.NOTE_ATTACHMENT_ID_PREFIX).toLongOrNull()
        if (attachmentId == null || messageId == null) {
            send(
                attachmentError(
                    request.attachmentId,
                    SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
                    "This attachment couldn't be found.",
                ),
            )
            return
        }
        when (val opened = source.openNoteAttachment(attachmentId, messageId)) {
            AttachmentOpenResult.Unknown -> send(
                attachmentError(
                    request.attachmentId,
                    SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
                    "This attachment couldn't be found.",
                ),
            )
            AttachmentOpenResult.Unreadable -> send(
                attachmentError(
                    request.attachmentId,
                    SyncProtocol.AttachmentError.ATTACHMENT_UNREADABLE,
                    "This file couldn't be read on the phone.",
                ),
            )
            is AttachmentOpenResult.Opened ->
                streamAttachment(request.attachmentId, opened.data, send)
        }
    }

    /**
     * Runs one note write-back action and answers `action_result`.
     * [noteId] is the raw wire string (parsed here; an unparseable
     * id answers `unknown_note`, never a crash). A thrown error
     * answers `ok: false` with `internal` — the socket stays up,
     * and the client reverts its optimistic change.
     */
    private suspend fun runNoteAction(
        action: String,
        noteId: String,
        messageId: String?,
        call: suspend (noteId: Long) -> ActionResult,
    ) {
        val result = try {
            val id = noteId.toLongOrNull()
            if (id == null) {
                ActionResult(
                    ok = false,
                    code = SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE,
                )
            } else {
                call(id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "NoteSyncEngine: action $action failed")
            ActionResult(
                ok = false,
                code = SyncProtocol.NoteMessageFailed.INTERNAL,
            )
        }
        runCatching {
            send(
                SyncProtocol.encodeActionResult(
                    SyncProtocol.ActionResult(
                        action = action,
                        conversationId = noteId,
                        messageId = messageId,
                        ok = result.ok,
                        code = result.code,
                        message = result.message,
                    ),
                ),
            )
        }
    }

    /**
     * Records pushed entries: advances the live path's per-note max
     * id (so the notes collector never re-pushes them).
     */
    private fun notePushed(noteId: Long, messages: List<SyncedNoteMessage>) {
        if (messages.isEmpty()) return
        val maxId = messages.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: return
        maxSentByNote.compute(noteId) { _, previous ->
            maxOf(previous ?: liveBaselineNoteMessageId, maxId)
        }
    }

    /**
     * Notes collector: diffs each list emission against the last
     * snapshot and pushes what changed. The first emission is the
     * baseline — silent, it only seeds the live baseline (the
     * initial stream sends the snapshot itself).
     *
     * The diff compares whole DTOs: draft keystrokes rewrite the
     * note row and re-emit the Flow, but the draft is not in the
     * DTO, so equal DTOs mean nothing sync-relevant changed and
     * nothing is pushed.
     */
    private suspend fun collectNotes() {
        liveBaselineNoteMessageId = runCatching { source.maxNoteMessageId() }.getOrDefault(0L)
        var firstEmission = true
        source.observeNotes().collect { list ->
            val snapshot = list.associateBy { it.id }
            if (firstEmission) {
                firstEmission = false
            } else {
                pushNoteDiff(lastNotes, snapshot, list)
            }
            lastNotes = snapshot
        }
    }

    private suspend fun pushNoteDiff(
        previous: Map<String, SyncedNote>,
        snapshot: Map<String, SyncedNote>,
        list: List<SyncedNote>,
    ) {
        for (id in previous.keys - snapshot.keys) {
            send(
                SyncProtocol.encodeNoteDeleted(
                    SyncProtocol.NoteDeleted(noteId = id),
                ),
            )
            id.toLongOrNull()?.let { maxSentByNote.remove(it) }
        }
        for (note in list) {
            val old = previous[note.id]
            val noteId = note.id.toLongOrNull()
            when {
                old == null -> {
                    // A note this session created already got its
                    // `note_created` — the other sessions learn
                    // about it via `note_new`.
                    if (noteId != null && skipNewNotes.remove(noteId)) {
                        maxSentByNote.putIfAbsent(noteId, liveBaselineNoteMessageId)
                    } else {
                        if (noteId != null) {
                            maxSentByNote.putIfAbsent(noteId, liveBaselineNoteMessageId)
                        }
                        send(
                            SyncProtocol.encodeNoteNew(
                                SyncProtocol.NoteNew(note),
                            ),
                        )
                        // A note created by an import arrives with
                        // its entries — push them, or the client
                        // shows the note but never its content.
                        noteId?.let { pushNewNoteMessages(it) }
                    }
                }
                note != old -> {
                    send(
                        SyncProtocol.encodeNoteUpdated(
                            SyncProtocol.NoteUpdated(note),
                        ),
                    )
                    val hasNewMessages = (note.lastMessageAt ?: 0L) > (old.lastMessageAt ?: 0L) ||
                        note.messageCount > old.messageCount
                    if (noteId != null && hasNewMessages) {
                        pushNewNoteMessages(noteId)
                    }
                }
            }
        }
    }

    /** Pushes the entries that arrived in [noteId] since the last push, oldest first. */
    private suspend fun pushNewNoteMessages(noteId: Long) {
        val afterId = maxSentByNote[noteId] ?: liveBaselineNoteMessageId
        val fresh = source.noteMessagesAfter(noteId, afterId)
        if (fresh.isEmpty()) return
        for (message in fresh) {
            send(SyncProtocol.encodeNoteMessageNew(SyncProtocol.NoteMessageNew(message)))
        }
        notePushed(noteId, fresh)
    }

    /**
     * Edits collector: watches the edited rows and pushes
     * `note_message_updated` (the full row) whenever a body
     * changes. The first emission is the baseline — recorded, not
     * pushed. A row that APPEARS in a later emission was just
     * edited for the first time (it was not edited before, so it
     * was not in the Flow) and IS pushed — that is the first edit,
     * not a baseline sighting.
     */
    private suspend fun collectNoteMessageEdits() {
        var firstEmission = true
        source.observeEditedMessages().collect { rows ->
            if (firstEmission) {
                firstEmission = false
            } else {
                for (row in rows) {
                    if (lastEditedBodies[row.id] != row.body) {
                        source.noteMessageById(row.id)?.let { message ->
                            send(
                                SyncProtocol.encodeNoteMessageUpdated(
                                    SyncProtocol.NoteMessageUpdated(message),
                                ),
                            )
                        }
                    }
                }
            }
            lastEditedBodies = rows.associate { it.id to it.body }
        }
    }

    companion object {
        /** Notes per `notes` batch. */
        const val NOTES_BATCH_SIZE = 100

        /** Entries per `note_messages_batch` page. */
        const val NOTE_MESSAGE_BATCH_SIZE = 200
    }
}

/** Streams the note snapshot in 100-note batches (an empty list still sends one frame). */
private suspend fun streamNotes(
    send: suspend (String) -> Unit,
    notes: List<SyncedNote>,
) {
    if (notes.isEmpty()) {
        send(
            SyncProtocol.encodeNotes(
                SyncProtocol.Notes(notes = emptyList(), hasMore = false),
            ),
        )
        return
    }
    val batches = notes.chunked(NoteSyncEngine.NOTES_BATCH_SIZE)
    batches.forEachIndexed { index, batch ->
        send(
            SyncProtocol.encodeNotes(
                SyncProtocol.Notes(
                    notes = batch,
                    hasMore = index < batches.lastIndex,
                ),
            ),
        )
    }
}

/** The `note_message_failed` for a save that threw after parsing (generic, never a stack trace). */
private fun saveFailedInternal(request: SyncProtocol.SaveNoteMessage): String =
    SyncProtocol.encodeNoteMessageFailed(
        SyncProtocol.NoteMessageFailed(
            clientMessageId = request.clientMessageId,
            noteId = request.noteId,
            code = SyncProtocol.NoteMessageFailed.INTERNAL,
            message = "This entry couldn't be saved.",
        ),
    )
