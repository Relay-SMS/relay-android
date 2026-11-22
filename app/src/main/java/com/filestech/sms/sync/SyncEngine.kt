package com.filestech.sms.sync

import com.filestech.sms.sync.calls.CallDialer
import com.filestech.sms.sync.calls.DialOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Slice 2 — one session's sync engine: request handling, the initial
 * stream, and the live collectors. One engine per WebSocket session,
 * created by [SyncServer] and cancelled when the session ends.
 *
 * The engine never blocks the session's frame loop: [onSyncRequest]
 * and [onMessagesRequest] only start jobs; the stream runs in the
 * engine's own scope. Live push runs from [start] (auth) onwards,
 * concurrently with the initial stream — upserts by id make
 * duplicates harmless, so no mode switch is needed.
 *
 * Slice 3 adds the send + write-back handlers ([onSendMessage] and
 * the action handlers), in the same shape: each runs in its own job
 * on the engine scope, so a slow send never blocks the frame loop,
 * the heartbeat, or an in-progress sync stream. The engine itself
 * holds no send state — the dedup lives in the source (§3 of the
 * Slice 3 design), so a dead session loses nothing.
 *
 * Slice 4 adds the attachment-byte handlers ([onAttachmentRequest],
 * [onUploadStart], [onUploadChunk]) in the same shape. The engine
 * owns the chunking: it reads the download stream in 64 KiB slices
 * on `Dispatchers.IO`, hashes incrementally, and sends the frames —
 * the source only opens the stream. A cancelled job (session end)
 * closes the stream — no leak.
 *
 * Phase 2 adds the scheduled-send handler ([onScheduleMessage]) in
 * the same shape; the runner is file-scope like the Slice 4 ones.
 * The scheduled-cancel handler ([onCancelScheduled]) follows it.
 *
 * Create conversation adds the new-conversation handler
 * ([onCreateConversation]) in the same shape; the runner is
 * file-scope like the Slice 4 ones.
 *
 * Round 3 adds the contact-photo handler ([onSetContactPhoto]) in
 * the same shape; the runner is file-scope like the Slice 4 ones.
 *
 * Live calls add the dial handler ([onDialRequest]) in the same
 * shape; the runner is file-scope like the Slice 4 ones. The
 * inbound call frames (`incoming_call`, `call_state`) are NOT an
 * engine concern — the server broadcasts them from the shared
 * observer (see [SyncServer]), so every session hears them.
 *
 * Category mutes add the `set_category_muted` handler
 * ([onSetCategoryMuted]) — through [runAction], like the other
 * flag actions, with the category echoed on `action_result` —
 * plus a third live collector ([collectCategoryMutes]) that
 * pushes the full muted set whenever it changes on the phone,
 * and the same full set after every snapshot in [runStream].
 * A new conversation's first message also pushes here now: the
 * new-conversation arm of the conversation diff calls
 * [pushNewMessages], so a first text from a new number notifies
 * on the desktop like any other (the desktop notifies only from
 * `message_new`).
 *
 * Slice 5 adds the contact-sync handlers ([onContactsRequest],
 * [onAvatarRequest]) in the same shape, plus the contact snapshot
 * inside [runStream] (read once before `sync_started`, streamed
 * after it, before the conversations). Contacts failing never fails
 * message sync: a contacts failure rides as `contacts_error` and
 * the stream proceeds. Debug logging here is counts only — contact
 * names and numbers never reach the log.
 *
 * Round 42 adds the E2E safety-number + notice sync: the
 * `set_e2e_verified` / `retry_e2e_probe` handlers
 * ([onSetE2eVerified], [onRetryE2eProbe]) — through [runAction],
 * like the other flag actions, with the address echoed on
 * `action_result` — plus two live collectors
 * ([collectE2eSafetyNumbers], [collectE2eNotices]) that push the
 * full sets whenever they change on the phone, and the same full
 * sets after every snapshot in [runStream]. The E2E sync is gated
 * on the [e2eSource] being present (null in tests): without it,
 * no E2E frame is sent and the commands answer `ok: false`.
 * There is no E2E on/off setting to gate on — E2E itself is
 * always on (lazy identity), and the only encryption setting
 * (auto-discovery) governs outbound probes, not sync.
 *
 * The engine holds no state beyond the session: cursors live on the
 * desktop. The per-conversation "max sent id" tracking exists only
 * to keep the live path from re-pushing whole threads.
 *
 * Sync pause/resume — the stream holds the [SyncWakelock] while it
 * runs, so a brief screen-off doesn't freeze the sync. The lock is
 * taken when a stream starts and released in a `finally` when it
 * ends, errors, or is cancelled — and only for sync streams: never
 * for attachment downloads/uploads, live push, or anything else.
 * A superseded stream must not release the lock under its
 * replacement (see [onSyncRequest]), so the engine tracks which
 * stream job owns the lock.
 *
 * @param source the message store, behind the [SyncMessageSource] seam.
 * @param parentScope the session's scope; the engine's jobs are
 * children of it, so a dead socket takes the engine with it.
 * @param send carries one outbound text frame to the client.
 * @param wakelock the bounded partial wakelock, held only while a
 * sync stream runs (see [onSyncRequest]).
 * @param callDialer the dial executor for `dial_request`, or null
 * when the session has none (tests): a dial then answers
 * `dial_result` `failed` instead of crashing.
 * @param noteSource the note store seam, or null when the session
 * has none (tests): the notes phase is skipped and note frames are
 * ignored.
 * @param e2eSource the E2E store seam, or null when the session
 * has none (tests): no E2E frame is sent and the E2E commands
 * answer `ok: false` instead of crashing.
 */
class SyncEngine(
    private val source: SyncMessageSource,
    parentScope: CoroutineScope,
    private val send: suspend (String) -> Unit,
    private val wakelock: SyncWakelock,
    private val callDialer: CallDialer? = null,
    private val noteSource: NoteSyncSource? = null,
    private val e2eSource: E2eSyncSource? = null,
) {
    private val engineJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + engineJob)

    /**
     * Round 36 phase 2 — the note engine for this session, or null
     * when the session has no note source. Public so the session
     * handler can dispatch note frames through it.
     */
    val noteEngine: NoteSyncEngine? =
        noteSource?.let { NoteSyncEngine(it, parentScope, send) }

    private var streamJob: Job? = null

    private var started = false

    /** Last conversation snapshot the live collector saw (by id). */
    private var lastConversations: Map<String, SyncedConversation> = emptyMap()

    /** Per-conversation max message id pushed this session (live path). */
    private val maxSentByConversation = ConcurrentHashMap<Long, Long>()

    /** Baseline max id at collector start — live push only covers newer messages. */
    private var liveBaselineId: Long = 0L

    /** Ids of the most recent messages pushed this session (states watch). */
    private val watchedIds = MutableStateFlow<List<Long>>(emptyList())

    /** Last states the watch saw, by message id. */
    private var lastStates: Map<Long, MessageState> = emptyMap()

    /**
     * Starts the live collectors. Called once, when the session
     * authenticates. Idempotent — a second call is a no-op.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { collectConversations() }
        scope.launch { collectMessageStates() }
        scope.launch { collectCategoryMutes() }
        scope.launch { collectE2eSafetyNumbers() }
        scope.launch { collectE2eNotices() }
        noteEngine?.start()
    }

    /**
     * Handles a `sync_request`: cancels any stream in progress and
     * starts a fresh one. Returns immediately — the stream runs in
     * the engine's scope, never on the frame loop. A failure mid-stream
     * answers `sync_error` (generic message, never a stack trace);
     * the socket stays up.
     *
     * The stream holds the [wakelock] from start to finish — taken
     * when the stream job starts, released in the `finally` when it
     * ends, errors, or is cancelled — so a brief screen-off doesn't
     * freeze the sync. The holder is reference-counted, so a
     * superseded stream's `finally` only drops its own count and
     * can never release the lock under its replacement, in either
     * interleaving.
     */
    fun onSyncRequest(request: SyncProtocol.SyncRequest) {
        streamJob?.cancel()
        streamJob = scope.launch {
            wakelock.acquire()
            try {
                runStream(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: sync stream failed")
                runCatching { send(syncErrorInternal()) }
            } finally {
                wakelock.release()
            }
        }
    }

    /** Handles a `messages_request`: one page fetch, answered with one `messages_batch`. */
    fun onMessagesRequest(request: SyncProtocol.MessagesRequest) {
        scope.launch {
            try {
                runMessagesRequest(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: messages request failed")
                runCatching { send(syncErrorInternal()) }
            }
        }
    }

    // -- Slice 3: send + write-back -----------------------------------
    // Same shape as the read handlers: each starts a job and returns
    // immediately, so a slow send never blocks the frame loop.

    /** Handles a `send_message`: answered with `message_sent` or `message_failed`. */
    fun onSendMessage(request: SyncProtocol.SendMessage) {
        scope.launch {
            try {
                runSendMessage(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: send failed")
                runCatching { send(sendFailedInternal(request)) }
            }
        }
    }

    /** Handles a `mark_read`, answered with `action_result`. */
    fun onMarkRead(request: SyncProtocol.MarkRead) {
        scope.launch {
            runAction("mark_read", request.conversationId, null) { source.markRead(it) }
        }
    }

    /** Handles a `mark_all_read`, answered with `action_result`. */
    fun onMarkAllRead() {
        scope.launch {
            runAction("mark_all_read", null, null) { source.markAllRead() }
        }
    }

    /** Handles a `set_pin`, answered with `action_result`. */
    fun onSetPin(request: SyncProtocol.SetPin) {
        scope.launch {
            runAction("set_pin", request.conversationId, null) { source.setPinned(it, request.value) }
        }
    }

    /** Handles a `set_archived`, answered with `action_result`. */
    fun onSetArchived(request: SyncProtocol.SetArchived) {
        scope.launch {
            runAction("set_archived", request.conversationId, null) { source.setArchived(it, request.value) }
        }
    }

    /** Handles a `set_muted`, answered with `action_result`. */
    fun onSetMuted(request: SyncProtocol.SetMuted) {
        scope.launch {
            runAction("set_muted", request.conversationId, null) { source.setMuted(it, request.value) }
        }
    }

    /** Handles a `set_category_muted`, answered with `action_result` (the category echoed). */
    fun onSetCategoryMuted(request: SyncProtocol.SetCategoryMuted) {
        scope.launch {
            runAction("set_category_muted", null, null, request.category) {
                source.setCategoryMuted(request.category, request.muted)
            }
        }
    }

    /** Handles a `set_blocked`, answered with `action_result`. */
    fun onSetBlocked(request: SyncProtocol.SetBlocked) {
        scope.launch {
            runAction("set_blocked", request.conversationId, null) { source.setBlocked(it, request.value) }
        }
    }

    /** Handles a `set_reaction`, answered with `action_result`. */
    fun onSetReaction(request: SyncProtocol.SetReaction) {
        scope.launch {
            runAction("set_reaction", request.conversationId, request.messageId) { conversationId ->
                val messageId = request.messageId.toLongOrNull()
                    ?: return@runAction ActionResult(
                        ok = false,
                        code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                        message = "Message not found.",
                    )
                source.setReaction(conversationId, messageId, request.emoji)
            }
        }
    }

    /** Handles a `set_pinned_message`, answered with `action_result`. */
    fun onSetPinnedMessage(request: SyncProtocol.SetPinnedMessage) {
        scope.launch {
            runAction("set_pinned_message", request.conversationId, request.messageId) { conversationId ->
                val messageId = request.messageId?.toLongOrNull()
                if (request.messageId != null && messageId == null) {
                    return@runAction ActionResult(
                        ok = false,
                        code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                        message = "Message not found.",
                    )
                }
                source.setPinnedMessage(conversationId, messageId)
            }
        }
    }

    // -- Slice 4: attachment bytes --------------------------------------
    // Same shape as the other handlers: each starts a job and returns
    // immediately, so a slow download never blocks the frame loop.

    /**
     * Handles an `attachment_request`: streams the bytes in 64 KiB
     * chunks, or answers `attachment_error`. Downloads run
     * concurrently (one job each); chunks for different attachments
     * interleave on the wire, each tagged with its `attachment_id`.
     */
    fun onAttachmentRequest(request: SyncProtocol.AttachmentRequest) {
        scope.launch {
            try {
                // Round 36 phase 2 — a note download carries the
                // `"note:"` prefix on BOTH ids; the note engine
                // streams it (no note engine = no note source, so
                // the id is unknown — same answer as a missing file).
                if (request.attachmentId.startsWith(SyncProtocol.NOTE_ATTACHMENT_ID_PREFIX)) {
                    val engine = noteEngine
                    if (engine == null) {
                        send(
                            attachmentError(
                                request.attachmentId,
                                SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
                                "This attachment couldn't be found.",
                            ),
                        )
                    } else {
                        engine.runNoteAttachmentRequest(request)
                    }
                    return@launch
                }
                runAttachmentRequest(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: attachment request failed")
                runCatching { send(attachmentErrorInternal(request.attachmentId)) }
            }
        }
    }

    /** Handles an `attachment_upload_start`, answered with `attachment_upload_ready` or `attachment_upload_error`. */
    fun onUploadStart(request: SyncProtocol.AttachmentUploadStart) {
        scope.launch {
            try {
                runUploadStart(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: upload start failed")
                runCatching {
                    // Val-extracted (was a nested `send(uploadError(...))`): keeps
                    // the class under the LargeClass limit after the wrapping fixes.
                    val errorFrame = uploadError(
                        request.clientMessageId,
                        request.index,
                        SyncProtocol.AttachmentUploadError.INTERNAL,
                        "This file couldn't be uploaded.",
                    )
                    send(errorFrame)
                }
            }
        }
    }

    /**
     * Handles an `attachment_upload_chunk`: answered with
     * `attachment_upload_done` on the final chunk, or
     * `attachment_upload_error`. Non-final chunks get no ack — TCP is
     * the flow control.
     */
    fun onUploadChunk(request: SyncProtocol.AttachmentUploadChunk) {
        scope.launch {
            try {
                runUploadChunk(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: upload chunk failed")
                runCatching {
                    // Val-extracted (was a nested `send(uploadError(...))`): keeps
                    // the class under the LargeClass limit after the wrapping fixes.
                    val errorFrame = uploadError(
                        request.clientMessageId,
                        request.index,
                        SyncProtocol.AttachmentUploadError.INTERNAL,
                        "This file couldn't be uploaded.",
                    )
                    send(errorFrame)
                }
            }
        }
    }

    // -- Phase 2: scheduled sends --------------------------------------
    // Same shape as the other handlers: starts a job and returns
    // immediately, so a slow schedule never blocks the frame loop.

    /** Handles a `schedule_message`: answered with `message_scheduled` or `message_failed`. */
    fun onScheduleMessage(request: SyncProtocol.ScheduleMessage) {
        scope.launch {
            try {
                runScheduleMessage(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: schedule failed")
                runCatching { send(scheduleFailedInternal(request)) }
            }
        }
    }

    /** Handles a `cancel_scheduled`: answered with `schedule_cancelled` or `message_failed`. */
    fun onCancelScheduled(request: SyncProtocol.CancelScheduled) {
        scope.launch {
            try {
                runCancelScheduled(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: cancel scheduled failed")
                runCatching { send(cancelFailedInternal(request)) }
            }
        }
    }

    // -- Create conversation -------------------------------------------
    // Same shape as the other handlers: starts a job and returns
    // immediately, so a slow create never blocks the frame loop.

    /**
     * Handles a `create_conversation`: answered with
     * `conversation_created` or `action_result` (`ok: false` — no
     * `conversation_id`, none exists yet).
     */
    fun onCreateConversation(request: SyncProtocol.CreateConversation) {
        scope.launch {
            try {
                runCreateConversation(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: create conversation failed")
                runCatching { send(createFailedInternal()) }
            }
        }
    }

    // -- Slice 5: contact sync -----------------------------------------
    // Same shape as the other handlers: each starts a job and returns
    // immediately, so a slow contacts read never blocks the frame loop.

    /**
     * Handles a `contacts_request`: re-streams the full snapshot, or
     * answers `contacts_error`. Never touches messages.
     */
    fun onContactsRequest() {
        scope.launch {
            try {
                runContactsRequest(source, send)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: contacts request failed")
                runCatching { send(contactsErrorInternal()) }
            }
        }
    }

    /**
     * Handles an `avatar_request`: streams the photo through the
     * Slice 4 chunk sender under the `avatar:<number>` id, or
     * answers `attachment_error`.
     */
    fun onAvatarRequest(request: SyncProtocol.AvatarRequest) {
        scope.launch {
            try {
                runAvatarRequest(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: avatar request failed")
                runCatching { send(attachmentErrorInternal(avatarId(request.address))) }
            }
        }
    }

    // -- Round 3: contact photo ----------------------------------------
    // Same shape as the other handlers: starts a job and returns
    // immediately, so a slow photo write never blocks the frame loop.

    /**
     * Handles a `set_contact_photo`: answered with `action_result`
     * (`ok: true` on success, `ok: false` with a code on refusal —
     * the address is echoed either way, so the desktop attributes
     * the reply to one pick).
     */
    fun onSetContactPhoto(request: SyncProtocol.SetContactPhoto) {
        scope.launch {
            try {
                runSetContactPhoto(source, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: set contact photo failed")
                runCatching { send(setContactPhotoFailedInternal(request.address)) }
            }
        }
    }

    // -- Live calls: dial ----------------------------------------------
    // Same shape as the other handlers: starts a job and returns
    // immediately, so a slow dial never blocks the frame loop.

    /**
     * Handles a `dial_request`: answered with `dial_result`
     * (`ok: true` when the call was placed, `ok: false` with a
     * snake_case code on refusal — see [SyncProtocol.DialResult]).
     */
    fun onDialRequest(request: SyncProtocol.DialRequest) {
        scope.launch {
            try {
                runDialRequest(callDialer, send, request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: dial request failed")
                runCatching { send(dialResultInternal()) }
            }
        }
    }

    // -- Round 42: E2E safety numbers + notices ------------------------
    // Same shape as the other handlers: starts a job and returns
    // immediately, so a slow write never blocks the frame loop.
    // Answered with `action_result` (the address echoed); the
    // collector's re-send of the full set is the confirmation.

    /** Handles a `set_e2e_verified`, answered with `action_result`. */
    fun onSetE2eVerified(request: SyncProtocol.SetE2eVerified) {
        scope.launch {
            runAction("set_e2e_verified", null, null, address = request.address) {
                e2eSource?.setVerified(request.address, request.verified)
                    ?: ActionResult(
                        ok = false,
                        code = SyncProtocol.MessageFailed.INTERNAL,
                        message = "Encryption sync isn't available on this phone.",
                    )
            }
        }
    }

    /** Handles a `retry_e2e_probe`, answered with `action_result`. */
    fun onRetryE2eProbe(request: SyncProtocol.RetryE2eProbe) {
        scope.launch {
            runAction("retry_e2e_probe", null, null, address = request.address) {
                e2eSource?.retryProbe(request.address)
                    ?: ActionResult(
                        ok = false,
                        code = SyncProtocol.MessageFailed.INTERNAL,
                        message = "Encryption sync isn't available on this phone.",
                    )
            }
        }
    }

    /** Cancels the stream and both collectors. Called when the session ends. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        noteEngine?.stop()
        engineJob.cancel()
    }

    private suspend fun runStream(request: SyncProtocol.SyncRequest) {
        if (request.mode != SyncProtocol.SyncRequest.FULL &&
            request.mode != SyncProtocol.SyncRequest.NEW_ONLY
        ) {
            send(SyncProtocol.encodeError("unknown sync mode: ${request.mode}"))
            return
        }
        when (source.readiness()) {
            Readiness.NoSmsPermission -> {
                send(noSmsAccessError())
                return
            }
            Readiness.MirrorEmpty -> {
                send(mirrorEmptyError())
                return
            }
            Readiness.Ready -> Unit
        }
        // Slice 5 — contacts are read ONCE, before `sync_started`:
        // the count feeds `total_contacts`, and the same list is
        // streamed right after `sync_started`, before the
        // conversations — no double read. A contacts failure never
        // fails message sync: it rides as `contacts_error` after
        // `sync_started` and the stream proceeds regardless.
        val contactsRead = readContacts(source)
        val totalContacts = (contactsRead as? ContactsRead.Ok)?.contacts?.size ?: 0
        val sinceId = request.sinceMessageId?.toLongOrNull() ?: 0L
        val conversations = source.conversations()
        val totalMessages = source.totalMessageCount()
        val cursor = source.maxMessageId()
        // Round 36 phase 2 — the notes snapshot is read once, before
        // `sync_started`: the count + cursor feed the frame, and the
        // same list streams right after the conversations. A session
        // without a note source streams no notes at all.
        val notes = noteEngine?.notesForStream().orEmpty()
        val totalNotes = noteEngine?.totalNotes() ?: 0
        val noteCursor = noteEngine?.maxNoteMessageId() ?: 0L
        send(
            SyncProtocol.encodeSyncStarted(
                SyncProtocol.SyncStarted(
                    mode = request.mode,
                    totalConversations = conversations.size,
                    totalMessages = totalMessages,
                    cursorMessageId = cursor.toString(),
                    hubIsDefaultSms = source.isDefaultSmsApp(),
                    hubTunnelIp = source.tunnelIp(),
                    totalContacts = totalContacts,
                    cursorNoteMessageId = noteCursor.toString(),
                    totalNotes = totalNotes,
                ),
            ),
        )
        when (contactsRead) {
            is ContactsRead.Ok -> streamContacts(send, contactsRead.contacts)
            is ContactsRead.Error -> send(contactsError(contactsRead.code, contactsRead.message))
        }
        streamConversations(send, conversations)
        noteEngine?.streamNotesPhase(request, notes)
        if (request.mode == SyncProtocol.SyncRequest.FULL) {
            if (sinceId > 0L) streamDelta(conversations, sinceId) else streamAllMessages(conversations)
        }
        val endCursor = source.maxMessageId()
        val endNoteCursor = noteEngine?.maxNoteMessageId() ?: 0L
        // Val-extracted (was a 5-line nested `send(encode(...))`): keeps the
        // class under the LargeClass limit after the wrapping fixes above.
        val complete = SyncProtocol.SyncComplete(
            cursorMessageId = endCursor.toString(),
            cursorNoteMessageId = endNoteCursor.toString(),
        )
        send(SyncProtocol.encodeSyncComplete(complete))
        // Category mutes — the full set rides right after the
        // snapshot, so a fresh connection learns it without waiting
        // for a change. A null read (settings unreadable) skips the
        // frame — an empty set would wrongly unmute everything —
        // and the collector sends the set on the next change.
        source.mutedCategories()?.let {
            send(SyncProtocol.encodeCategoryMutes(SyncProtocol.CategoryMutes(it.sorted())))
        }
        // Round 42 — the E2E sets ride right after the snapshot,
        // next to the category mutes, so a fresh connection learns
        // the safety numbers, verified flags and in-thread notices
        // without waiting for a change.
        sendE2eSnapshot()
    }

    /**
     * Round 42 — sends the full E2E sets after the snapshot. A
     * failed read skips that frame (an empty set would wrongly
     * clear the desktop's state) — and the collectors send the
     * sets on the next change. A failure here must never fail the
     * message sync: the identity-key read can fail on a device
     * whose Keystore is unhappy (round 39). A session without an
     * E2E source sends nothing.
     */
    private suspend fun sendE2eSnapshot() {
        val e2e = e2eSource ?: return
        try {
            val peers = e2e.safetyNumbers()
            send(SyncProtocol.encodeE2eSafetyNumbers(SyncProtocol.E2eSafetyNumbers(peers)))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "SyncEngine: e2e safety numbers unreadable, skipping")
        }
        try {
            val notices = e2e.notices()
            send(SyncProtocol.encodeE2eNotices(SyncProtocol.E2eNotices(notices)))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "SyncEngine: e2e notices unreadable, skipping")
        }
    }

    /** Full mode: every conversation's messages, newest first, most-recent conversation first. */
    private suspend fun streamAllMessages(conversations: List<SyncedConversation>) {
        for (conversation in conversations) {
            streamOneConversation(conversation)
        }
    }

    private suspend fun streamOneConversation(conversation: SyncedConversation) {
        val conversationId = conversation.id.toLongOrNull() ?: return
        var beforeId: Long? = 0L
        while (beforeId != null) {
            beforeId = streamOnePage(conversation, conversationId, beforeId)
        }
    }

    /**
     * Streams one page of [conversation] below [beforeId]. Returns the
     * next `before_id`, or null when the thread is exhausted.
     */
    private suspend fun streamOnePage(
        conversation: SyncedConversation,
        conversationId: Long,
        beforeId: Long,
    ): Long? {
        val page = source.messagesBefore(conversationId, beforeId, MESSAGE_BATCH_SIZE)
        if (page.isEmpty()) return null
        val hasMore = page.size == MESSAGE_BATCH_SIZE
        val lastId = page.last().id
        send(
            SyncProtocol.encodeMessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = conversation.id,
                    messages = page,
                    hasMore = hasMore,
                    nextBeforeId = lastId.takeIf { hasMore },
                ),
            ),
        )
        notePushed(conversationId, page)
        return lastId.toLongOrNull().takeIf { hasMore }
    }

    /**
     * Reconnect delta: only the messages above [sinceId], per
     * conversation, oldest first. The batches carry `has_more: false`
     * — there is no `before_id` continuation for a delta range; the
     * desktop gap-fills older history via `messages_request` instead.
     */
    private suspend fun streamDelta(conversations: List<SyncedConversation>, sinceId: Long) {
        for (conversation in conversations) {
            streamConversationDelta(conversation, sinceId)
        }
    }

    private suspend fun streamConversationDelta(conversation: SyncedConversation, sinceId: Long) {
        val conversationId = conversation.id.toLongOrNull() ?: return
        val delta = source.messagesAfter(conversationId, sinceId)
        if (delta.isEmpty()) return
        delta.chunked(MESSAGE_BATCH_SIZE).forEach { chunk ->
            send(
                SyncProtocol.encodeMessagesBatch(
                    SyncProtocol.MessagesBatch(
                        conversationId = conversation.id,
                        messages = chunk,
                        hasMore = false,
                    ),
                ),
            )
        }
        notePushed(conversationId, delta)
    }

    private suspend fun runMessagesRequest(request: SyncProtocol.MessagesRequest) {
        val conversationId = request.conversationId.toLongOrNull()
        if (conversationId == null) {
            send(SyncProtocol.encodeError("unknown conversation: ${request.conversationId}"))
            return
        }
        val known = source.conversations().any { it.id == request.conversationId }
        if (!known) {
            send(SyncProtocol.encodeError("unknown conversation: ${request.conversationId}"))
            return
        }
        val beforeId = request.beforeId?.toLongOrNull() ?: 0L
        val limit = (request.limit ?: MESSAGE_BATCH_SIZE).coerceIn(1, MESSAGE_BATCH_SIZE)
        val page = source.messagesBefore(conversationId, beforeId, limit)
        val hasMore = page.size == limit
        val lastId = page.lastOrNull()?.id
        send(
            SyncProtocol.encodeMessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = request.conversationId,
                    messages = page,
                    hasMore = hasMore,
                    nextBeforeId = lastId.takeIf { hasMore },
                ),
            ),
        )
        notePushed(conversationId, page)
    }

    private suspend fun runSendMessage(request: SyncProtocol.SendMessage) {
        val conversationId = request.conversationId.toLongOrNull()
        if (conversationId == null) {
            send(
                SyncProtocol.encodeMessageFailed(
                    SyncProtocol.MessageFailed(
                        clientMessageId = request.clientMessageId,
                        conversationId = request.conversationId,
                        code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                        message = "Conversation not found.",
                    ),
                ),
            )
            return
        }
        // An unparseable reply id is tolerated like a dangling one
        // (the phone's own send path renders a placeholder quote) —
        // the reply is dropped, the message still goes out.
        val replyToMessageId = request.replyToMessageId?.toLongOrNull()
        val result = source.sendMessage(
            request.clientMessageId,
            conversationId,
            request.body,
            replyToMessageId,
            request.attachments?.map { it.toInput() },
        )
        when (result) {
            is SendResult.Sent -> send(
                SyncProtocol.encodeMessageSent(
                    SyncProtocol.MessageSent(
                        clientMessageId = request.clientMessageId,
                        messageId = result.messageId.toString(),
                        conversationId = request.conversationId,
                        status = result.status,
                        blockedCount = result.blockedCount,
                    ),
                ),
            )
            is SendResult.Failed -> send(
                SyncProtocol.encodeMessageFailed(
                    SyncProtocol.MessageFailed(
                        clientMessageId = request.clientMessageId,
                        conversationId = request.conversationId,
                        code = result.code,
                        message = result.message,
                    ),
                ),
            )
        }
    }

    /**
     * Runs one write-back action and answers `action_result`.
     * [conversationId] is the raw wire string (parsed here; an
     * unparseable id answers `unknown_conversation`, never a crash).
     * `mark_all_read` passes null and its [call] ignores the id. A
     * thrown error answers `ok: false` with `internal` — the socket
     * stays up, and the desktop reverts its optimistic change.
     * [category] echoes back on `action_result` for category
     * actions (only `set_category_muted` sets it). [address]
     * echoes back on `action_result` for address actions (only
     * `set_e2e_verified` / `retry_e2e_probe` set it).
     */
    private suspend fun runAction(
        action: String,
        conversationId: String?,
        messageId: String?,
        category: String? = null,
        address: String? = null,
        call: suspend (conversationId: Long) -> ActionResult,
    ) {
        val result = try {
            val id = conversationId?.toLongOrNull()
            if (conversationId != null && id == null) {
                ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                    message = "Conversation not found.",
                )
            } else {
                call(id ?: 0L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "SyncEngine: action $action failed")
            ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The action could not be applied.",
            )
        }
        runCatching {
            send(
                SyncProtocol.encodeActionResult(
                    SyncProtocol.ActionResult(
                        action = action,
                        conversationId = conversationId,
                        messageId = messageId,
                        address = address,
                        category = category,
                        ok = result.ok,
                        code = result.code,
                        message = result.message,
                    ),
                ),
            )
        }
    }

    /**
     * Records pushed messages: advances the live path's
     * per-conversation max id (so the conversation collector never
     * re-pushes them) and slides the states watch over the most
     * recent ids.
     */
    private fun notePushed(conversationId: Long, messages: List<SyncedMessage>) {
        if (messages.isEmpty()) return
        val ids = messages.mapNotNull { it.id.toLongOrNull() }
        val maxId = ids.maxOrNull() ?: return
        maxSentByConversation.compute(conversationId) { _, previous ->
            maxOf(previous ?: liveBaselineId, maxId)
        }
        watchedIds.update { current -> (current + ids).distinct().takeLast(WATCHED_STATES_LIMIT) }
    }

    /**
     * Conversation collector: diffs each list emission against the
     * last snapshot and pushes what changed. The first emission is
     * the baseline — silent, it only seeds the per-conversation max
     * ids (the initial stream sends the snapshot itself).
     *
     * The diff compares whole DTOs: draft keystrokes and appearance
     * changes rewrite the conversation row and re-emit the Flow, but
     * neither field is in the DTO, so equal DTOs mean nothing
     * sync-relevant changed and nothing is pushed.
     */
    private suspend fun collectConversations() {
        liveBaselineId = runCatching { source.maxMessageId() }.getOrDefault(0L)
        var firstEmission = true
        source.observeConversations().collect { list ->
            val snapshot = list.associateBy { it.id }
            if (firstEmission) {
                firstEmission = false
                baselineMaxSent(list)
            } else {
                pushConversationDiff(lastConversations, snapshot, list)
            }
            lastConversations = snapshot
        }
    }

    /** Seeds the live path's per-conversation max ids at the session baseline. */
    private fun baselineMaxSent(list: List<SyncedConversation>) {
        for (conversation in list) {
            conversation.id.toLongOrNull()?.let { id ->
                maxSentByConversation.putIfAbsent(id, liveBaselineId)
            }
        }
    }

    private suspend fun pushConversationDiff(
        previous: Map<String, SyncedConversation>,
        snapshot: Map<String, SyncedConversation>,
        list: List<SyncedConversation>,
    ) {
        for (id in previous.keys - snapshot.keys) {
            send(
                SyncProtocol.encodeConversationDeleted(
                    SyncProtocol.ConversationDeleted(conversationId = id),
                ),
            )
            id.toLongOrNull()?.let { maxSentByConversation.remove(it) }
        }
        for (conversation in list) {
            val old = previous[conversation.id]
            val conversationId = conversation.id.toLongOrNull()
            when {
                old == null -> {
                    if (conversationId != null) {
                        maxSentByConversation.putIfAbsent(conversationId, liveBaselineId)
                    }
                    send(
                        SyncProtocol.encodeConversationUpdated(
                            SyncProtocol.ConversationUpdated(conversation),
                        ),
                    )
                    // A new conversation arrives with its first
                    // message(s) — push them, or the desktop shows
                    // the thread but never notifies for it (it
                    // notifies only from `message_new`). The baseline
                    // seeded above keeps older messages out.
                    conversationId?.let { pushNewMessages(it) }
                }
                conversation != old -> {
                    send(
                        SyncProtocol.encodeConversationUpdated(
                            SyncProtocol.ConversationUpdated(conversation),
                        ),
                    )
                    val hasNewMessages = conversation.lastMessageAt > old.lastMessageAt ||
                        conversation.messageCount > old.messageCount
                    if (conversationId != null && hasNewMessages) {
                        pushNewMessages(conversationId)
                    }
                }
            }
        }
    }

    /** Pushes the messages that arrived in [conversationId] since the last push, oldest first. */
    private suspend fun pushNewMessages(conversationId: Long) {
        val afterId = maxSentByConversation[conversationId] ?: liveBaselineId
        val fresh = source.messagesAfter(conversationId, afterId)
        if (fresh.isEmpty()) return
        for (message in fresh) {
            send(SyncProtocol.encodeMessageNew(SyncProtocol.MessageNew(message)))
        }
        notePushed(conversationId, fresh)
    }

    /**
     * States watch: a bounded Flow over the ids of the most recent
     * messages pushed this session, re-subscribed via `flatMapLatest`
     * as the id set slides. Any emission differing from the last-sent
     * states (status, reaction, read, starred) pushes a
     * `message_updated` with only the changed fields. A first sighting
     * of an id is the baseline — recorded, not pushed.
     */
    private suspend fun collectMessageStates() {
        watchedIds.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else source.observeMessageStates(ids)
        }.collect { states ->
            for (state in states) {
                val old = lastStates[state.id]
                if (old != null && old != state) {
                    send(buildMessageUpdated(old, state))
                }
            }
            lastStates = states.associateBy { it.id }
        }
    }

    /**
     * Category-mute collector: the first emission is the baseline —
     * silent (the snapshot sends the full set itself) — and every
     * later change pushes a `category_mutes` with the full set, so
     * the desktop replaces its list wholesale.
     * `distinctUntilChanged` swallows the re-emissions an unrelated
     * settings write causes upstream (the flow re-emits on every
     * write, muted or not).
     */
    private suspend fun collectCategoryMutes() {
        source.observeMutedCategories().drop(1).distinctUntilChanged().collect { muted ->
            send(SyncProtocol.encodeCategoryMutes(SyncProtocol.CategoryMutes(muted.sorted())))
        }
    }

    /**
     * Round 42 — safety-number collector: the first emission is
     * the baseline — silent (the snapshot sends the full set
     * itself) — and every later change pushes an
     * `e2e_safety_numbers` with the full set, so the desktop
     * replaces its list wholesale. `distinctUntilChanged`
     * swallows the re-emissions an unrelated peer-table write
     * causes upstream (Room re-emits on every write). A session
     * without an E2E source collects nothing.
     */
    private suspend fun collectE2eSafetyNumbers() {
        val e2e = e2eSource ?: return
        e2e.observeSafetyNumbers().drop(1).distinctUntilChanged().collect { peers ->
            send(SyncProtocol.encodeE2eSafetyNumbers(SyncProtocol.E2eSafetyNumbers(peers)))
        }
    }

    /**
     * Round 42 — notice collector: the first emission is the
     * baseline — silent (the snapshot sends the full set itself)
     * — and every later change pushes an `e2e_notices` with the
     * full set, so the desktop replaces its list wholesale.
     * `distinctUntilChanged` swallows the re-emissions an
     * unrelated notice-table write causes upstream. A session
     * without an E2E source collects nothing.
     */
    private suspend fun collectE2eNotices() {
        val e2e = e2eSource ?: return
        e2e.observeNotices().drop(1).distinctUntilChanged().collect { notices ->
            send(SyncProtocol.encodeE2eNotices(SyncProtocol.E2eNotices(notices)))
        }
    }

    companion object {
        /** Conversations per `conversations` batch. */
        const val CONVERSATION_BATCH_SIZE = 100

        /** Contacts per `contacts` batch. */
        const val CONTACT_BATCH_SIZE = 100

        /** Messages per `messages_batch` page. */
        const val MESSAGE_BATCH_SIZE = 200

        /** How many recent message ids the states watch follows. */
        const val WATCHED_STATES_LIMIT = 200
    }
}

/** Wire attachment entry → the source's send input; the fields map 1:1. */
private fun SyncProtocol.SendAttachment.toInput(): SendAttachmentInput = SendAttachmentInput(
    uploadIndex = uploadIndex,
    stickerEmoji = stickerEmoji,
    mimeType = mimeType,
    fileName = fileName,
    width = width,
    height = height,
    durationMs = durationMs,
)

/** Lowercase hex, no separators — the wire's SHA-256 shape. */
private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        chars[index * 2] = digits[value shr 4]
        chars[index * 2 + 1] = digits[value and 0x0F]
    }
    return chars.concatToString()
}

/**
 * Streams the conversation snapshot in batches of 100. Even with
 * nothing to sync, the desktop needs the frame: `has_more: false`
 * is what tells it the list is complete. File scope, not a class
 * member: it touches no engine state beyond the send lambda, and
 * the class is at the Detekt function limit.
 */
private suspend fun streamConversations(
    send: suspend (String) -> Unit,
    conversations: List<SyncedConversation>,
) {
    if (conversations.isEmpty()) {
        send(
            SyncProtocol.encodeConversations(
                SyncProtocol.Conversations(conversations = emptyList(), hasMore = false),
            ),
        )
        return
    }
    val batches = conversations.chunked(SyncEngine.CONVERSATION_BATCH_SIZE)
    batches.forEachIndexed { index, batch ->
        send(
            SyncProtocol.encodeConversations(
                SyncProtocol.Conversations(
                    conversations = batch,
                    hasMore = index < batches.lastIndex,
                ),
            ),
        )
    }
}

/** Builds the partial `message_updated` frame: only changed fields are present. */
private fun buildMessageUpdated(old: MessageState, new: MessageState): String {
    val updated = SyncProtocol.MessageUpdated(
        messageId = new.id.toString(),
        conversationId = new.conversationId.toString(),
        status = new.status.takeIf { it != old.status },
        reaction = new.reaction.takeIf { it != old.reaction },
        reactionRemoved = old.reaction != null && new.reaction == null,
        read = new.read.takeIf { it != old.read },
        starred = new.starred.takeIf { it != old.starred },
    )
    return SyncProtocol.encodeMessageUpdated(updated)
}

private fun noSmsAccessError(): String =
    SyncProtocol.encodeSyncError(
        SyncProtocol.SyncError(
            code = SyncProtocol.SyncError.HUB_NO_SMS_ACCESS,
            message = "Relay on the phone needs SMS permission to sync messages.",
        ),
    )

private fun mirrorEmptyError(): String =
    SyncProtocol.encodeSyncError(
        SyncProtocol.SyncError(
            code = SyncProtocol.SyncError.HUB_MIRROR_EMPTY,
            message = "Relay on the phone hasn't imported any messages yet.",
        ),
    )

private fun syncErrorInternal(): String =
    SyncProtocol.encodeSyncError(
        SyncProtocol.SyncError(
            code = SyncProtocol.SyncError.INTERNAL,
            message = "Sync failed on the phone. Try again.",
        ),
    )

private fun sendFailedInternal(request: SyncProtocol.SendMessage): String =
    SyncProtocol.encodeMessageFailed(
        SyncProtocol.MessageFailed(
            clientMessageId = request.clientMessageId,
            conversationId = request.conversationId,
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The message could not be sent.",
        ),
    )

// -- Phase 2: scheduled sends (file scope, like the Slice 4 runners:
// the runner touches no engine state beyond the source and the send
// lambda, and the class is at the Detekt function limit) -------------

/** Runs one `schedule_message`: answered with `message_scheduled` or `message_failed`. */
private suspend fun runScheduleMessage(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.ScheduleMessage,
) {
    val conversationId = request.conversationId.toLongOrNull()
    if (conversationId == null) {
        send(
            SyncProtocol.encodeMessageFailed(
                SyncProtocol.MessageFailed(
                    clientMessageId = request.clientMessageId,
                    conversationId = request.conversationId,
                    code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                    message = "Conversation not found.",
                ),
            ),
        )
        return
    }
    when (
        val result = source.scheduleMessage(
            request.clientMessageId,
            conversationId,
            request.body,
            request.sendAt,
        )
    ) {
        is ScheduleResult.Scheduled -> send(
            SyncProtocol.encodeMessageScheduled(
                SyncProtocol.MessageScheduled(
                    clientMessageId = request.clientMessageId,
                    messageId = result.messageId.toString(),
                    conversationId = request.conversationId,
                    sendAt = result.sendAt,
                ),
            ),
        )
        is ScheduleResult.Failed -> send(
            SyncProtocol.encodeMessageFailed(
                SyncProtocol.MessageFailed(
                    clientMessageId = request.clientMessageId,
                    conversationId = request.conversationId,
                    code = result.code,
                    message = result.message,
                ),
            ),
        )
    }
}

private fun scheduleFailedInternal(request: SyncProtocol.ScheduleMessage): String =
    SyncProtocol.encodeMessageFailed(
        SyncProtocol.MessageFailed(
            clientMessageId = request.clientMessageId,
            conversationId = request.conversationId,
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The message could not be scheduled.",
        ),
    )

/** Runs one `cancel_scheduled`: answered with `schedule_cancelled` or `message_failed`. */
private suspend fun runCancelScheduled(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.CancelScheduled,
) {
    when (val result = source.cancelScheduledMessage(request.clientMessageId)) {
        CancelResult.Cancelled -> send(
            SyncProtocol.encodeScheduleCancelled(
                SyncProtocol.ScheduleCancelled(clientMessageId = request.clientMessageId),
            ),
        )
        is CancelResult.Failed -> send(
            SyncProtocol.encodeMessageFailed(
                SyncProtocol.MessageFailed(
                    clientMessageId = request.clientMessageId,
                    // A cancel carries no conversation — the desktop
                    // matches a cancel failure by client_message_id.
                    conversationId = "",
                    code = result.code,
                    message = result.message,
                ),
            ),
        )
    }
}

private fun cancelFailedInternal(request: SyncProtocol.CancelScheduled): String =
    SyncProtocol.encodeMessageFailed(
        SyncProtocol.MessageFailed(
            clientMessageId = request.clientMessageId,
            conversationId = "",
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The scheduled message could not be cancelled.",
        ),
    )

// -- Create conversation (file scope, like the Slice 4 runners: the
// runner touches no engine state beyond the source and the send
// lambda, and the class is at the Detekt function limit) -------------

/**
 * Runs one `create_conversation`: answered with
 * `conversation_created` (the full conversation, the same shape as
 * one entry in the `conversations` batches) or `action_result`
 * (`ok: false` — no `conversation_id`, none exists yet).
 */
private suspend fun runCreateConversation(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.CreateConversation,
) {
    when (val result = source.createConversation(request.addresses, request.customName)) {
        is CreateConversationResult.Created -> send(
            SyncProtocol.encodeConversationCreated(
                SyncProtocol.ConversationCreated(result.conversation),
            ),
        )
        is CreateConversationResult.Failed -> send(
            SyncProtocol.encodeActionResult(
                SyncProtocol.ActionResult(
                    action = "create_conversation",
                    ok = false,
                    code = result.code,
                    message = result.message,
                ),
            ),
        )
    }
}

private fun createFailedInternal(): String =
    SyncProtocol.encodeActionResult(
        SyncProtocol.ActionResult(
            action = "create_conversation",
            ok = false,
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The conversation could not be created.",
        ),
    )

// -- Round 3: contact photo (file scope, like the Slice 4 runners:
// the runner touches no engine state beyond the source and the send
// lambda, and the class is at the Detekt function limit) -------------

/**
 * Runs one `set_contact_photo`: the photo decodes (base64url, the
 * protocol's byte alphabet), the size is checked against
 * [SyncProtocol.MAX_CONTACT_PHOTO_BYTES], and the source writes it
 * to the contact — answered with `action_result` either way (the
 * address echoed, so the desktop attributes the reply to one pick).
 * Undecodable or empty bytes are `invalid_request`; an oversized
 * photo is `photo_too_large`; the source's own failures (no
 * contact, no write permission) ride through as-is.
 */
private suspend fun runSetContactPhoto(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.SetContactPhoto,
) {
    val bytes = runCatching {
        Base64.getUrlDecoder().decode(QrPairing.padBase64(request.photoBase64Url))
    }.getOrNull()
    val result = when {
        bytes == null || bytes.isEmpty() -> ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.INVALID_REQUEST,
            message = "That photo couldn't be read.",
        )
        bytes.size > SyncProtocol.MAX_CONTACT_PHOTO_BYTES -> ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.PHOTO_TOO_LARGE,
            message = "That photo is too big.",
        )
        else -> source.setContactPhoto(request.address, bytes)
    }
    send(
        SyncProtocol.encodeActionResult(
            SyncProtocol.ActionResult(
                action = "set_contact_photo",
                address = request.address,
                ok = result.ok,
                code = result.code,
                message = result.message,
            ),
        ),
    )
}

private fun setContactPhotoFailedInternal(address: String): String =
    SyncProtocol.encodeActionResult(
        SyncProtocol.ActionResult(
            action = "set_contact_photo",
            address = address,
            ok = false,
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The photo couldn't be saved on the phone.",
        ),
    )

// -- Live calls: dial (file scope, not a class member: the runner
// touches no engine state beyond the dialer and the send lambda,
// and the class is at the Detekt function limit) ----------------------

/**
 * Runs one `dial_request`: the dialer places the call (or refuses
 * it), and the outcome maps 1:1 onto the `dial_result` frame — a
 * session without a dialer (tests) answers `failed`. A refused
 * dial is never a protocol violation: the socket stays up, and
 * the desktop shows the reason.
 */
private suspend fun runDialRequest(
    dialer: CallDialer?,
    send: suspend (String) -> Unit,
    request: SyncProtocol.DialRequest,
) {
    val outcome = dialer?.dial(request.number) ?: DialOutcome.Failed
    val result = when (outcome) {
        DialOutcome.Success -> SyncProtocol.DialResult(ok = true)
        DialOutcome.PermissionDenied -> SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.PERMISSION_DENIED,
        )
        DialOutcome.NoNumber -> SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.NO_NUMBER,
        )
        DialOutcome.Failed -> SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.FAILED,
        )
    }
    send(SyncProtocol.encodeDialResult(result))
}

private fun dialResultInternal(): String =
    SyncProtocol.encodeDialResult(
        SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.FAILED,
        ),
    )

// -- Slice 4: attachment bytes (file scope, not class members: these
// runners touch no engine state beyond the source and the send lambda,
// and the class is at the Detekt function limit) ----------------------

private suspend fun runAttachmentRequest(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.AttachmentRequest,
) {
    val attachmentId = request.attachmentId.toLongOrNull()
    val messageId = request.messageId.toLongOrNull()
    if (attachmentId == null || messageId == null) {
        // An unparseable id is not a known attachment — same
        // answer as a missing one, so ids can't be probed.
        send(
            attachmentError(
                request.attachmentId,
                SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
                "This attachment couldn't be found.",
            ),
        )
        return
    }
    when (val opened = source.openAttachment(attachmentId, messageId)) {
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
                "This photo couldn't be read on the phone.",
            ),
        )
        is AttachmentOpenResult.Opened ->
            streamAttachment(request.attachmentId, opened.data, send)
    }
}

/**
 * Streams one attachment in 64 KiB chunks, hashing incrementally
 * — one read pass, no extra round trip. The final chunk carries
 * the digest + the actual byte count; the desktop verifies both
 * before the file enters its cache. A failure mid-stream answers
 * `attachment_error` (the desktop discards the partial bytes);
 * the socket stays up. The stream is always closed, including on
 * cancellation (session end).
 */
/** Shared with [NoteSyncEngine]: the note download streams through the same chunker. */
internal suspend fun streamAttachment(
    attachmentId: String,
    data: AttachmentData,
    send: suspend (String) -> Unit,
) {
    if (data.sizeBytes > SyncProtocol.MAX_ATTACHMENT_DOWNLOAD_BYTES) {
        runCatching { data.stream.close() }
        send(
            attachmentError(
                attachmentId,
                SyncProtocol.AttachmentError.ATTACHMENT_TOO_LARGE,
                "This file is too big to download.",
            ),
        )
        return
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(SyncProtocol.ATTACHMENT_CHUNK_BYTES)
    var offset = 0L
    try {
        withContext(Dispatchers.IO) {
            while (true) {
                val read = readChunk(data.stream, buffer)
                // A short read is the end of the stream — and a
                // file whose size is an exact multiple of the
                // chunk size ends with one empty final chunk
                // (the read after the last full chunk returns 0).
                val isFinal = read < buffer.size
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    offset += read
                    if (offset > SyncProtocol.MAX_ATTACHMENT_DOWNLOAD_BYTES) {
                        // The row claimed a small file but the
                        // stream keeps going — stop, don't stream
                        // gigabytes. The desktop discards the
                        // partial bytes on the error frame.
                        send(
                            attachmentError(
                                attachmentId,
                                SyncProtocol.AttachmentError.ATTACHMENT_TOO_LARGE,
                                "This file is too big to download.",
                            ),
                        )
                        return@withContext
                    }
                }
                send(
                    SyncProtocol.encodeAttachmentChunk(
                        SyncProtocol.AttachmentChunk(
                            attachmentId = attachmentId,
                            offset = offset - read,
                            dataBase64Url = base64Url(buffer, read),
                            isFinal = isFinal,
                            sha256 = if (isFinal) digest.digest().toHex() else null,
                            sizeBytes = if (isFinal) offset else null,
                        ),
                    ),
                )
                if (isFinal) break
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "SyncEngine: attachment stream failed")
        runCatching {
            send(
                attachmentError(
                    attachmentId,
                    SyncProtocol.AttachmentError.ATTACHMENT_UNREADABLE,
                    "This photo couldn't be read on the phone.",
                ),
            )
        }
    } finally {
        runCatching { data.stream.close() }
    }
}

/** Fills [buffer] from [stream] (one `read` may return short); returns the bytes read, 0 at end of stream. */
private fun readChunk(stream: InputStream, buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = stream.read(buffer, filled, buffer.size - filled)
        if (read == -1) break
        filled += read
    }
    return filled
}

/** Base64url, no padding — the wire's chunk encoding. Only the first [length] bytes are encoded. */
private fun base64Url(bytes: ByteArray, length: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf(length))

private suspend fun runUploadStart(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.AttachmentUploadStart,
) {
    when (
        val result = source.beginUpload(
            request.clientMessageId,
            request.index,
            request.mimeType,
            request.fileName,
            request.sizeBytes,
            request.sha256,
            request.kind,
        )
    ) {
        UploadResult.Ok -> send(
            SyncProtocol.encodeAttachmentUploadReady(
                SyncProtocol.AttachmentUploadReady(request.clientMessageId, request.index),
            ),
        )
        is UploadResult.Failed -> send(
            uploadError(request.clientMessageId, request.index, result.code, result.message),
        )
    }
}

private suspend fun runUploadChunk(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.AttachmentUploadChunk,
) {
    val bytes = runCatching {
        Base64.getUrlDecoder().decode(QrPairing.padBase64(request.dataBase64Url))
    }.getOrNull()
    if (bytes == null) {
        send(
            uploadError(
                request.clientMessageId,
                request.index,
                SyncProtocol.AttachmentUploadError.INTERNAL,
                "This file couldn't be uploaded.",
            ),
        )
        return
    }
    when (
        val result = source.appendUploadChunk(
            request.clientMessageId,
            request.index,
            request.offset,
            bytes,
            request.isFinal,
        )
    ) {
        UploadResult.Ok -> {
            if (request.isFinal) {
                send(
                    SyncProtocol.encodeAttachmentUploadDone(
                        SyncProtocol.AttachmentUploadDone(request.clientMessageId, request.index),
                    ),
                )
            }
        }
        is UploadResult.Failed -> send(
            uploadError(request.clientMessageId, request.index, result.code, result.message),
        )
    }
}

/** Shared with [NoteSyncEngine]: the note download answers the same errors. */
internal fun attachmentError(attachmentId: String, code: String, message: String): String =
    SyncProtocol.encodeAttachmentError(
        SyncProtocol.AttachmentError(attachmentId, code, message),
    )

private fun attachmentErrorInternal(attachmentId: String): String =
    attachmentError(
        attachmentId,
        SyncProtocol.AttachmentError.INTERNAL,
        "This attachment couldn't be downloaded.",
    )

private fun uploadError(clientMessageId: String, index: Int, code: String, message: String): String =
    SyncProtocol.encodeAttachmentUploadError(
        SyncProtocol.AttachmentUploadError(clientMessageId, index, code, message),
    )

// -- Slice 5: contact sync (file scope, like the Slice 4 runners:
// the runners touch no engine state beyond the source and the send
// lambda, and the class is at the Detekt function limit) -------------

/**
 * Reads the contact snapshot, or the `contacts_error` that explains
 * why it can't be read. A contacts failure is never a stream
 * failure: the caller sends the error frame and proceeds with
 * message sync. Debug logging here is counts only — contact names
 * and numbers never reach the log.
 */
private suspend fun readContacts(source: SyncMessageSource): ContactsRead {
    return when (source.contactsReadiness()) {
        ContactReadiness.NoContactsPermission -> ContactsRead.Error(
            SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS,
            NO_CONTACTS_ACCESS_MESSAGE,
        )
        ContactReadiness.Ready -> try {
            ContactsRead.Ok(source.contacts())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // The permission was revoked between the readiness check
            // and the read — same answer as never having it.
            Timber.d(e, "SyncEngine: contacts read refused")
            ContactsRead.Error(
                SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS,
                NO_CONTACTS_ACCESS_MESSAGE,
            )
        } catch (t: Throwable) {
            Timber.w(t, "SyncEngine: contacts read failed")
            ContactsRead.Error(
                SyncProtocol.ContactsError.INTERNAL,
                CONTACTS_INTERNAL_MESSAGE,
            )
        }
    }
}

/** What [readContacts] found: the snapshot, or the error that replaces it. */
private sealed interface ContactsRead {
    data class Ok(val contacts: List<SyncedContact>) : ContactsRead
    data class Error(val code: String, val message: String) : ContactsRead
}

/** Runs one `contacts_request`: the full snapshot, or `contacts_error`. */
private suspend fun runContactsRequest(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
) {
    when (val read = readContacts(source)) {
        is ContactsRead.Ok -> streamContacts(send, read.contacts)
        is ContactsRead.Error -> send(contactsError(read.code, read.message))
    }
}

/**
 * Streams the contact snapshot in batches of 100, display-name
 * order. The last batch carries `has_more: false` — that is the
 * desktop's prune signal (it deletes local numbers absent from the
 * snapshot). An empty snapshot still sends one empty batch, so the
 * desktop prunes everything and knows the snapshot is complete.
 */
private suspend fun streamContacts(
    send: suspend (String) -> Unit,
    contacts: List<SyncedContact>,
) {
    Timber.d("SyncEngine: streaming %d contacts", contacts.size)
    if (contacts.isEmpty()) {
        send(
            SyncProtocol.encodeContacts(
                SyncProtocol.Contacts(contacts = emptyList(), hasMore = false),
            ),
        )
        return
    }
    val batches = contacts.chunked(SyncEngine.CONTACT_BATCH_SIZE)
    batches.forEachIndexed { index, batch ->
        send(
            SyncProtocol.encodeContacts(
                SyncProtocol.Contacts(
                    contacts = batch,
                    hasMore = index < batches.lastIndex,
                ),
            ),
        )
    }
}

/**
 * Runs one `avatar_request`: the photo streams through the Slice 4
 * chunk sender under the `avatar:<number>` id — no membership guard
 * (there is no message and no vault concept for contacts). No
 * contact, no photo, or an unreadable stream all answer
 * `unknown_attachment`: one code for all three, and the paired
 * desktop already knows `has_photo` from the CONTACT payload, so
 * the error reveals nothing new.
 */
private suspend fun runAvatarRequest(
    source: SyncMessageSource,
    send: suspend (String) -> Unit,
    request: SyncProtocol.AvatarRequest,
) {
    val id = avatarId(request.address)
    when (val opened = source.openAvatar(request.address)) {
        AvatarOpenResult.Unknown -> send(
            attachmentError(
                id,
                SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
                "This photo couldn't be found.",
            ),
        )
        is AvatarOpenResult.Opened -> streamAttachment(
            id,
            AttachmentData(sizeBytes = UNKNOWN_STREAM_SIZE, stream = opened.stream),
            send,
        )
    }
}

/** The chunk id for an avatar download: `avatar:<normalized-number>`. */
private fun avatarId(address: String): String = "avatar:$address"

/**
 * The size of a stream whose length is unknown up front (avatar
 * photo streams — the provider gives no length). The chunk sender
 * caps the stream while reading, and the final chunk carries the
 * actual byte count.
 */
private const val UNKNOWN_STREAM_SIZE = -1L

/** The `contacts_error` message when the phone lacks contacts permission (the spec's words). */
private const val NO_CONTACTS_ACCESS_MESSAGE =
    "Relay on the phone needs contacts permission to sync names and photos."

/** The `contacts_error` message for anything else — generic, never a stack trace. */
private const val CONTACTS_INTERNAL_MESSAGE = "Contacts couldn't be synced right now."

private fun contactsError(code: String, message: String): String =
    SyncProtocol.encodeContactsError(
        SyncProtocol.ContactsError(code, message),
    )

private fun contactsErrorInternal(): String =
    contactsError(
        SyncProtocol.ContactsError.INTERNAL,
        CONTACTS_INTERNAL_MESSAGE,
    )
