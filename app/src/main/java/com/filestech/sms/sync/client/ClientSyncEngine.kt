package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity
import com.filestech.sms.data.local.db.entity.ClientSyncStateEntity
import com.filestech.sms.data.repository.SyncedConversationResult
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Client mode — the sync phases the UI renders (the desktop's `SyncPhase`). */
enum class ClientSyncPhase {
    /** No run, no prompt: disconnected, or waiting for the first connect. */
    Idle,

    /** First connect to a hub with no stored mode: ask how much to sync. */
    Prompting,

    /** A sync run is streaming. */
    Syncing,

    /** Caught up; live frames keep the cache fresh. */
    Live,

    /** The run failed (`sync_error`, or the request never went out). */
    Error,
}

/**
 * Client mode — everything the UI needs to render the sync state:
 * the phase, the run progress, the error, the hub's default-SMS
 * role, and the connection. One flow, so the list, the prompt and
 * Linked devices never disagree.
 */
data class ClientSyncUiState(
    val phase: ClientSyncPhase = ClientSyncPhase.Idle,
    /** Messages (or conversations, in `new_only`) stored this run. */
    val imported: Long = 0,
    /** The run's expected total, null until `sync_started` announces it. */
    val total: Long? = null,
    /** True while the current run is a delta (a stored cursor), not a first sync. */
    val isDelta: Boolean = false,
    /** True once the run has lasted past [ClientSyncEngine.DELTA_SYNC_DIALOG_DELAY_MS]. */
    val deltaElapsed: Boolean = false,
    /** The `sync_error` code, or [ClientSyncEngine.ERROR_NOT_CONNECTED]. Null when no error. */
    val errorCode: String? = null,
    /** The hub's error message, or null (the UI falls back to its default wording). */
    val errorMessage: String? = null,
    /**
     * Client mode C4 — the last `contacts_error` code, or null.
     * Separate from [errorCode] by design: message sync proceeds
     * regardless, and this never touches the sync progress. Cleared
     * by a completed snapshot, a live contact delta, or a wipe.
     */
    val contactsErrorCode: String? = null,
    /** Client mode C4 — the hub's contacts error message, or null. */
    val contactsErrorMessage: String? = null,
    /** Whether the hub app is the phone's default SMS app; null = unknown. */
    val hubIsDefaultSms: Boolean? = null,
    val connection: ClientConnectionState = ClientConnectionState.Disconnected,
    val hubId: String? = null,
    val hubName: String? = null,
)

/**
 * Client mode — whether the sync progress shows for this UI state.
 * The desktop's `shouldShowSyncDialog`, ported exactly: a first
 * sync shows at once; a delta shows only once the phone has
 * announced real work (`total > 0`, from `sync_started`) or the run
 * has lasted past the delay — a sub-second empty delta, the usual
 * shape of a brief Wi-Fi blip, never flashes the progress row.
 */
fun shouldShowSyncProgress(ui: ClientSyncUiState): Boolean {
    val total = ui.total
    if (ui.phase != ClientSyncPhase.Syncing || total == null) return false
    if (!ui.isDelta) return true
    return ui.deltaElapsed || total > 0
}

/**
 * Client mode — the sync engine: the desktop's `SyncEngine`
 * (`sync.ts`), ported to the Android cache. One [Mutex] serializes
 * frame handling and the public operations, so a live frame can
 * never interleave with a batch write.
 *
 * The rules it lives by (all ported from the desktop, §7 of the
 * design):
 * - **Cursor.** The global cursor is the max hub message id
 *   DURABLY STORED — never the server's `sync_complete` cursor (a
 *   live message could still be in flight past it). It is
 *   persisted at `sync_complete` and after each live `message_new`,
 *   never per batch: in a full run the first batch already holds
 *   the global max, so a per-batch persist would strand the
 *   unstored history after a crash. The old end is guarded by the
 *   per-conversation frontiers + gap-fill, not by the cursor.
 * - **Frontier.** A stream batch advances a conversation's walk
 *   only when it is a gap-fill reply, a fetch-on-open reply, or a
 *   fresh full sync — never for a delta batch (whose
 *   `has_more: false` says nothing about the unfinished walk).
 * - **Snapshot prune.** On `conversations` with `has_more: false`,
 *   local conversations absent from the snapshot are deleted
 *   (deletions included — there is no tombstone feed). Safe in
 *   delta runs too: the hub always sends the FULL snapshot.
 * - **`unread: 0` is the read signal.** A `conversation_updated`
 *   with zero unread marks the cached messages read (via the
 *   mirror).
 * - **`new_only`.** The `sync_started` cursor is saved immediately
 *   as the "now" baseline — there are no batches to advance it —
 *   and no gap-fill runs (history was declined by choice).
 *
 * Started once by [ClientSyncService] before it connects; the
 * engine then follows the connection state (prompt on first
 * connect, delta resume on reconnect) and the frame stream.
 * C4 wires the notifier (a live message notifies) and the contacts
 * sync (snapshot + deltas + lazy avatars); bulk batches still
 * land silently.
 */
@Singleton
class ClientSyncEngine @Inject constructor(
    private val channel: ClientSyncChannel,
    private val mirror: ClientMirror,
    private val stateStore: SyncStateStore,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val linkStore: ClientLinkStore,
    private val wiper: ClientCacheWiper,
    @ApplicationScope private val appScope: CoroutineScope,
    /** Client mode C3 — the send/schedule manager (send acks + schedule acks route here). */
    private val sendManager: ClientSendManager,
    /** Client mode C3 — the write-back sender (`action_result` + `conversation_created` route here). */
    private val actionSender: ClientActionSender,
    /** Client mode C4 — the live-message notifier (only `message_new` notifies). */
    private val notificationRelay: ClientNotificationRelay,
    /** Client mode C4 — contacts snapshot + deltas + lazy avatars. */
    private val contactSync: ClientContactSync,
    /** Client mode C5 — attachment downloads (non-avatar chunks route here). */
    private val downloader: ClientAttachmentDownloader,
    /** Client mode C5 — attachment uploads (`attachment_upload_*` acks route here). */
    private val uploader: ClientAttachmentUploader,
) {

    private val _ui = MutableStateFlow(ClientSyncUiState())
    val ui: StateFlow<ClientSyncUiState> = _ui.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var started = false

    // -- Run state (all guarded by [mutex]) -------------------------

    private var hubId: String? = null
    private var hubName: String? = null

    /** The stored row for the current hub, mirrored in memory. */
    private var syncState: ClientSyncStateEntity? = null

    private var runMode: String? = null
    private var runIsDelta: Boolean = false

    /** Guards the delta-elapsed job against a newer run. */
    private var runToken: Int = 0

    /** The cursor: max hub message id durably stored (in-memory; see the class doc). */
    private var maxStoredId: Long = 0

    private var imported: Long = 0
    private var total: Long? = null

    /** Hub conversation ids seen in this run's snapshot (null outside a run). */
    private var snapshotIds: MutableSet<Long>? = null

    /** Gap-fill: the incomplete conversations, walked one at a time. */
    private val gapFillQueue: ArrayDeque<Long> = ArrayDeque()
    private var gapFillActive: Boolean = false

    /** Fetch-on-open: hub ids with a page in flight / fetched this app session. */
    private val fetchInFlight: MutableSet<Long> = mutableSetOf()
    private val fetchedThisSession: MutableSet<Long> = mutableSetOf()

    /** A pinned message that has not arrived yet, by hub message id. */
    private val pendingPinned: MutableMap<Long, PendingPin> = mutableMapOf()

    /** Replies waiting for their quote target, by hub target id → local reply ids. */
    private val pendingQuotes: MutableMap<Long, MutableList<Long>> = mutableMapOf()

    private var deltaElapsedJob: Job? = null

    /** A pinned message not yet in the cache: which conversation pins it, and when. */
    private data class PendingPin(val hubConversationId: Long, val pinnedAt: Long?)

    /** What one stored batch contributed: its oldest hub id + how many rows landed. */
    private data class BatchStored(val minId: Long?, val count: Int)

    /**
     * Starts the engine: follows the connection state and the frame
     * stream. Idempotent — the service calls it on every start.
     * Also picks up the linked hub id from the link store, so
     * Re-sync and the mode choice work even before the first
     * connect of this run (the prompt itself still waits for the
     * connection — it is the connect that asks the question).
     */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        appScope.launch {
            val link = runCatching { linkStore.snapshot() }.getOrNull()
            mutex.withLock {
                if (hubId == null && link?.hubId != null) {
                    hubId = link.hubId
                    hubName = link.hubName
                    updateUi { it.copy(hubId = hubId, hubName = hubName) }
                }
            }
        }
        appScope.launch {
            channel.state.collect { state -> mutex.withLock { onConnection(state) } }
        }
        appScope.launch {
            channel.frames.collect { frame -> mutex.withLock { onFrame(frame) } }
        }
    }

    /**
     * The user picked a sync mode in the first-connect prompt.
     * Stores the choice per-hub (cursor `"0"`) and starts the sync.
     * Only from [ClientSyncPhase.Prompting] — a later mode change
     * goes through Re-sync.
     */
    suspend fun chooseMode(mode: String) {
        mutex.withLock {
            val hub = hubId ?: return
            if (ui.value.phase != ClientSyncPhase.Prompting) return
            val state = ClientSyncStateEntity(
                hubId = hub,
                mode = mode,
                globalLastMessageId = "0",
                initialSyncDone = false,
                lastSyncAt = null,
            )
            stateStore.saveSyncState(state)
            syncState = state
            startSync(mode, "0")
        }
    }

    /** Re-issues the sync request after an error (the Retry affordance). */
    suspend fun retry() {
        mutex.withLock {
            val stored = syncState ?: return
            if (ui.value.phase != ClientSyncPhase.Error) return
            startSync(stored.mode, stored.globalLastMessageId)
        }
    }

    /**
     * Client mode C4 — the contacts Retry (Linked devices): re-asks
     * the full contact snapshot without touching messages. The error
     * note stays until a snapshot succeeds (or a live delta lands) —
     * a failed send (offline) leaves it in place.
     */
    suspend fun retryContacts() {
        mutex.withLock {
            contactSync.resetSnapshot()
            channel.send(ClientFrames.encodeContactsRequest())
        }
    }

    /**
     * "Re-sync messages": wipes the message cache, forgets the sync
     * state, and re-prompts for the mode. The link + TOFU pin are
     * untouched (this is not an unlink), and the schedules this
     * device created survive (the wiper never touches
     * `scheduled_messages`). Works offline — the prompt waits for
     * the connection, like the first-connect prompt.
     */
    suspend fun resync() {
        mutex.withLock {
            resetRunState()
            wiper.wipeMessageCache()
            syncState = null
            fetchedThisSession.clear()
            pendingPinned.clear()
            pendingQuotes.clear()
            updateUi {
                it.copy(
                    phase = ClientSyncPhase.Prompting,
                    imported = 0,
                    total = null,
                    isDelta = false,
                    deltaElapsed = false,
                    errorCode = null,
                    errorMessage = null,
                    hubIsDefaultSms = null,
                    contactsErrorCode = null,
                    contactsErrorMessage = null,
                )
            }
        }
    }

    /**
     * Fetch-on-open: walks one thread's history from its stored
     * frontier, one page at a time, until complete. The desktop's
     * `fetchThread`: no-op when a fetch is in flight or the thread
     * was fetched this session; a complete thread with cached
     * messages is a no-op; a complete-but-empty thread fetches once
     * per session. [localConversationId] is the local Room id.
     */
    suspend fun fetchThread(localConversationId: Long) {
        mutex.withLock {
            val hubConvId = fetchTarget(localConversationId) ?: return
            val state = stateStore.getConversationState(hubConvId)
            if (state?.historyComplete == true) {
                val cached = messageDao.countForConversation(localConversationId)
                if (cached > 0) return
            }
            fetchInFlight.add(hubConvId)
            requestFetchPage(hubConvId, state?.minSyncedId ?: "0")
        }
    }

    /**
     * The hub id of the thread to fetch, or null when there is
     * nothing to fetch: no hub, unknown thread, already in flight,
     * or already fetched this session.
     */
    private suspend fun fetchTarget(localConversationId: Long): Long? {
        if (hubId == null) return null
        val hubConvId = conversationDao.findById(localConversationId)?.hubConversationId
        if (hubConvId == null || hubConvId in fetchInFlight || hubConvId in fetchedThisSession) {
            return null
        }
        return hubConvId
    }

    // -- Connection -------------------------------------------------

    private suspend fun onConnection(state: ClientConnectionState) {
        updateUi { it.copy(connection = state) }
        when (state) {
            is ClientConnectionState.Connected -> onConnected(state)
            is ClientConnectionState.Removed -> {
                resetRunState()
                hubId = null
                hubName = null
                syncState = null
                fetchedThisSession.clear()
                pendingPinned.clear()
                pendingQuotes.clear()
                // Client mode C3 — a create in flight can never answer now.
                actionSender.onSessionEnded()
                // Client mode C4 — avatar downloads belong to the dead
                // session; the hub's contacts error is moot with it.
                contactSync.onSessionEnded()
                // Client mode C5 — attachment downloads + uploads
                // belong to the dead session too.
                downloader.onSessionEnded()
                uploader.onSessionEnded()
                updateUi {
                    it.copy(
                        phase = ClientSyncPhase.Idle,
                        hubIsDefaultSms = null,
                        hubId = null,
                        hubName = null,
                        contactsErrorCode = null,
                        contactsErrorMessage = null,
                    )
                }
            }
            // Connecting / Failed / Disconnected: a drop mid-sync
            // goes back to idle (the reconnect resumes from the
            // stored cursor); the hub's default-SMS role is unknown
            // again until the next `sync_started` reports it.
            else -> {
                if (ui.value.phase == ClientSyncPhase.Syncing) {
                    resetRunState()
                    updateUi { it.copy(phase = ClientSyncPhase.Idle) }
                }
                updateUi { it.copy(hubIsDefaultSms = null) }
                // Client mode C3 — a create in flight can never answer
                // once the session is down (a reconnect starts a new
                // session; the hub's answer to the old one never
                // arrives). The caller retries the create.
                actionSender.onSessionEnded()
                // Client mode C4 — in-flight avatar downloads go back
                // to the queue; the reconnect resumes them.
                contactSync.onSessionEnded()
                // Client mode C5 — in-flight attachment downloads go
                // back to the queue too; in-flight uploads fail (the
                // send path retries them).
                downloader.onSessionEnded()
                uploader.onSessionEnded()
            }
        }
    }

    /**
     * Client mode — hub change (fix before the push): wipes the
     * message cache when the connecting hub is not the hub the
     * cache belongs to. Two signals, one wipe: the engine already
     * served another hub this session, or the stored sync state
     * belongs to another hub (re-linked after a restart — the link
     * record already says the new hub, so the session check alone
     * would miss it). A same-hub reconnect wipes nothing: the
     * session hub matches and the stored state is that hub's.
     */
    private suspend fun wipeCacheIfHubChanged(newHubId: String) {
        // A blank id is no hub — the link store only ever persists
        // a real hub id or nothing — so it never counts as a change.
        val hubChangedInSession = !hubId.isNullOrBlank() && hubId != newHubId
        val foreignState = stateStore.listHubIds().any { it != newHubId }
        if (hubChangedInSession || foreignState) {
            wiper.wipeMessageCache()
        }
    }

    private suspend fun onConnected(state: ClientConnectionState.Connected) {
        // Client mode — hub change (fix before the push): a
        // different hub clears the cache and re-prompts (§5.2).
        // The check runs on EVERY connect, not just inside the
        // hub-change branch below: after a restart the engine
        // seeds hubId from the link record, which already says
        // the new hub — the branch alone would miss a re-link.
        // The wipe clears every hub's state, so the load below
        // finds nothing and the prompt shows.
        wipeCacheIfHubChanged(state.hubId)
        if (state.hubId != hubId) {
            // A different hub (or the first connect): its state, its
            // prompt. Fetch + pending state belongs to the old hub.
            resetRunState()
            fetchedThisSession.clear()
            pendingPinned.clear()
            pendingQuotes.clear()
            hubId = state.hubId
            hubName = state.hubName
            syncState = stateStore.getSyncState(state.hubId)
            // Client mode C4 — a different hub is a different address
            // book: its downloads drop, and its contacts error (if
            // any) is the new hub's to report.
            contactSync.onHubChanged()
            // Client mode C5 — a different hub is a different
            // attachment store: its downloads drop too.
            downloader.onHubChanged()
            updateUi {
                it.copy(
                    hubId = hubId,
                    hubName = hubName,
                    hubIsDefaultSms = null,
                    contactsErrorCode = null,
                    contactsErrorMessage = null,
                )
            }
        } else if (syncState == null) {
            // Fresh engine, same hub as the link record (hubId was
            // seeded at start, so the branch above never ran): load
            // the stored state, or a restart re-prompts instead of
            // resuming the delta.
            syncState = stateStore.getSyncState(state.hubId)
        }
        // Client mode C4 — connected (again): refused avatars get a
        // fresh start, and the queued downloads resume.
        contactSync.onSessionResumed()
        // Client mode C5 — connected (again): the interrupted
        // attachment downloads resume.
        downloader.onSessionResumed()
        val stored = syncState
        if (stored == null) {
            // Never synced with this hub — ask how much.
            updateUi { it.copy(phase = ClientSyncPhase.Prompting) }
            return
        }
        // Same hub, reconnected after a drop or an error: resume the
        // delta. The phase is usually `live` here — a drop leaves it
        // alone (only a drop mid-sync resets it to `idle`) — so
        // `live` must resume too, or a return home never catches up.
        // `prompting` waits for the user; `syncing` restarts the run
        // (its session died — the disconnect handler normally resets
        // it to idle first, this is the defensive path).
        if (ui.value.phase != ClientSyncPhase.Prompting) {
            startSync(stored.mode, stored.globalLastMessageId)
        }
    }

    // -- Runs ---------------------------------------------------------

    /**
     * Starts (or restarts) a sync run. [sinceMessageId] is the
     * stored cursor (`"0"` on a first sync); a non-zero cursor
     * makes it a delta. The request going out is the only
     * fallible step — a dead session surfaces as [ClientSyncPhase.Error]
     * with [ERROR_NOT_CONNECTED], and the reconnect resumes it.
     */
    private suspend fun startSync(mode: String, sinceMessageId: String) {
        resetRunState()
        // Client mode C4 — a run starts: the next contacts snapshot
        // is a fresh set (its prune must not keep numbers from an
        // interrupted earlier snapshot).
        contactSync.resetSnapshot()
        val token = runToken
        runMode = mode
        runIsDelta = sinceMessageId != "0"
        maxStoredId = sinceMessageId.toLongOrNull() ?: 0L
        snapshotIds = mutableSetOf()
        deltaElapsedJob = appScope.launch {
            delay(DELTA_SYNC_DIALOG_DELAY_MS)
            mutex.withLock {
                if (runToken == token && ui.value.phase == ClientSyncPhase.Syncing) {
                    updateUi { it.copy(deltaElapsed = true) }
                }
            }
        }
        updateUi {
            it.copy(
                phase = ClientSyncPhase.Syncing,
                imported = 0,
                total = null,
                isDelta = runIsDelta,
                deltaElapsed = false,
                errorCode = null,
                errorMessage = null,
            )
        }
        val cursor = sinceMessageId.takeIf { it != "0" }
        val sent = channel.send(ClientFrames.encodeSyncRequest(mode, cursor))
        if (!sent) {
            updateUi {
                it.copy(
                    phase = ClientSyncPhase.Error,
                    errorCode = ERROR_NOT_CONNECTED,
                    errorMessage = null,
                )
            }
        }
    }

    /** Resets the per-run state. The stored cursor seeds [maxStoredId] (never below it). */
    private fun resetRunState() {
        runToken++
        runMode = null
        runIsDelta = false
        imported = 0
        total = null
        maxStoredId = syncState?.globalLastMessageId?.toLongOrNull() ?: 0L
        snapshotIds = null
        gapFillQueue.clear()
        gapFillActive = false
        // In-flight fetches belong to the dead session — their
        // replies will never come. A completed fetch stays done for
        // the app session (only a hub change clears it).
        fetchInFlight.clear()
        deltaElapsedJob?.cancel()
        deltaElapsedJob = null
    }

    /** Persists the cursor after a live message lands (cheap, one row). */
    private suspend fun persistCursor() {
        val stored = syncState ?: return
        val updated = stored.copy(globalLastMessageId = maxStoredId.toString())
        stateStore.saveSyncState(updated)
        syncState = updated
    }

    // -- Frames -----------------------------------------------------

    private suspend fun onFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.SyncStarted -> onSyncStarted(frame.value)
            is ServerFrame.Conversations -> onConversations(frame.value)
            is ServerFrame.MessagesBatch -> onMessagesBatch(frame.value)
            is ServerFrame.SyncComplete -> onSyncComplete()
            is ServerFrame.MessageNew -> onMessageNew(frame.value)
            is ServerFrame.MessageUpdated -> mirror.applyMessageUpdated(frame.value)
            is ServerFrame.ConversationUpdated ->
                upsertConversationFrame(frame.value.conversation)

            is ServerFrame.ConversationDeleted -> onConversationDeleted(frame.value)
            is ServerFrame.ConversationCreated -> {
                // Client mode C3 — upsert FIRST, then notify the
                // sender: the create waiter reads the local row by hub
                // id, and it must exist by then.
                upsertConversationFrame(frame.value.conversation)
                actionSender.onConversationCreated(frame.value)
            }

            // Client mode C3 — send + schedule + write-back acks.
            is ServerFrame.MessageSent -> sendManager.onMessageSent(frame.value)
            is ServerFrame.MessageFailed -> sendManager.onMessageFailed(frame.value)
            is ServerFrame.ActionResult -> actionSender.onActionResult(frame.value)
            is ServerFrame.MessageScheduled -> sendManager.onMessageScheduled(frame.value)
            is ServerFrame.ScheduleCancelled -> sendManager.onScheduleCancelled(frame.value)

            // Client mode C4 — contacts snapshot + deltas + error.
            is ServerFrame.Contacts -> onContacts(frame.value)
            is ServerFrame.ContactUpdated -> onContactUpdated(frame.value)
            is ServerFrame.ContactDeleted -> contactSync.onContactDeleted(frame.value)
            is ServerFrame.ContactsError -> onContactsError(frame.value)
            is ServerFrame.AttachmentChunk -> onAttachmentChunk(frame)
            is ServerFrame.AttachmentError -> onAttachmentError(frame)

            is ServerFrame.SyncError -> onSyncError(frame.value)
            is ServerFrame.HubStatus -> onHubStatus(frame.value)
            else -> onOtherFrame(frame)
        }
    }

    /**
     * Everything else: the upload acks (C5 — ready/done/error route
     * to the uploader) and the auth/heartbeat frames (the transport
     * consumes those; they never reach the engine).
     * Unknown/Malformed are dropped — a newer hub may send types this
     * build does not know yet, and that must never break the sync.
     */
    private suspend fun onOtherFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.AttachmentUploadReady -> uploader.onReady(frame.value)
            is ServerFrame.AttachmentUploadDone -> uploader.onDone(frame.value)
            is ServerFrame.AttachmentUploadError -> uploader.onError(frame.value)
            else -> Timber.d("ClientSyncEngine: ignoring %s", frame::class.simpleName)
        }
    }

    /**
     * Client mode C4 — one `contacts` snapshot batch. A completed
     * snapshot clears the contacts error (the address book is whole
     * again); message sync proceeds regardless either way.
     */
    private suspend fun onContacts(value: SyncProtocol.Contacts) {
        if (contactSync.onContacts(value)) clearContactsError()
    }

    /** Client mode C4 — a live contact delta also clears the contacts error. */
    private suspend fun onContactUpdated(value: SyncProtocol.ContactUpdated) {
        contactSync.onContactUpdated(value)
        clearContactsError()
    }

    /**
     * Client mode C4 — the hub cannot sync contacts right now.
     * Separate from `sync_error` by design: message sync proceeds
     * regardless, and this never touches the sync progress. Linked
     * devices shows the reason with a Retry.
     */
    private fun onContactsError(value: SyncProtocol.ContactsError) {
        updateUi {
            it.copy(
                contactsErrorCode = value.code,
                contactsErrorMessage = value.message.ifBlank { null },
            )
        }
    }

    private fun clearContactsError() {
        updateUi { it.copy(contactsErrorCode = null, contactsErrorMessage = null) }
    }

    /**
     * Client mode C4/C5 — one `attachment_chunk`: avatar chunks
     * (`avatar:<number>`) go to the contact sync; the rest go to the
     * attachment downloader.
     */
    private suspend fun onAttachmentChunk(frame: ServerFrame.AttachmentChunk) {
        if (isAvatarAttachmentId(frame.value.attachmentId)) {
            contactSync.onAvatarChunk(frame.value)
        } else {
            downloader.onChunk(frame.value)
        }
    }

    /** Client mode C4/C5 — one `attachment_error`: same routing as [onAttachmentChunk]. */
    private suspend fun onAttachmentError(frame: ServerFrame.AttachmentError) {
        if (isAvatarAttachmentId(frame.value.attachmentId)) {
            contactSync.onAvatarError(frame.value)
        } else {
            downloader.onError(frame.value)
        }
    }

    private suspend fun onSyncStarted(value: SyncProtocol.SyncStarted) {
        val mode = value.mode
        runMode = mode
        // Full mode counts messages; `new_only` streams no messages,
        // so it counts conversations instead (else the progress would
        // sit at "0 of N messages" and then complete).
        total = if (mode == SyncProtocol.SyncRequest.NEW_ONLY) {
            value.totalConversations.toLong()
        } else {
            value.totalMessages
        }
        imported = 0
        updateUi {
            it.copy(
                phase = ClientSyncPhase.Syncing,
                imported = 0,
                total = total,
                hubIsDefaultSms = value.hubIsDefaultSms,
            )
        }
        if (mode == SyncProtocol.SyncRequest.NEW_ONLY) {
            // The "now" baseline: save the cursor immediately —
            // there are no batches to advance it, and everything
            // before "now" is history we chose not to pull.
            val baseline = value.cursorMessageId.toLongOrNull() ?: 0L
            if (baseline > maxStoredId) maxStoredId = baseline
            persistCursor()
        }
    }

    private suspend fun onConversations(value: SyncProtocol.Conversations) {
        val snapshot = snapshotIds ?: return
        for (wire in value.conversations) {
            upsertConversationFrame(wire)
            val hubConvId = parseHubId(wire.id) ?: continue
            snapshot.add(hubConvId)
            if (runMode == SyncProtocol.SyncRequest.NEW_ONLY) {
                imported++
                updateUi { it.copy(imported = imported) }
            }
        }
        if (!value.hasMore) {
            // Snapshot complete: drop local conversations the hub no
            // longer has. The hub always sends the FULL snapshot
            // (delta runs included), so this is safe on every run.
            val pruned = mirror.pruneSyncedConversations(snapshot)
            if (pruned > 0) {
                Timber.i("ClientSyncEngine: pruned %d conversations absent from the snapshot", pruned)
            }
        }
    }

    private suspend fun onMessagesBatch(value: SyncProtocol.MessagesBatch) {
        val hubConvId = parseHubId(value.conversationId) ?: return
        val isGapFillReply = gapFillActive && gapFillQueue.firstOrNull() == hubConvId
        val isFetchReply = hubConvId in fetchInFlight
        if (value.messages.isEmpty()) {
            onEmptyBatch(hubConvId, isGapFillReply, isFetchReply)
        } else {
            onNonEmptyBatch(value, hubConvId, isGapFillReply, isFetchReply)
        }
    }

    private suspend fun onNonEmptyBatch(
        value: SyncProtocol.MessagesBatch,
        hubConvId: Long,
        isGapFillReply: Boolean,
        isFetchReply: Boolean,
    ) {
        val stored = storeBatchMessages(value.messages)
        // A stream batch only advances the walk when it is a
        // gap-fill reply, a fetch-on-open reply, or a fresh full
        // sync. In a delta run the hub sends only new messages with
        // `has_more: false` — neither the frontier nor the complete
        // flag may move, or an unfinished history would be marked
        // complete. A fetch-on-open reply is an explicit walk from
        // the stored frontier, so it always advances — even in a
        // delta run.
        if (isGapFillReply || isFetchReply || !runIsDelta) {
            setFrontier(hubConvId, stored.minId, complete = !value.hasMore)
        }
        if (runMode != SyncProtocol.SyncRequest.NEW_ONLY) {
            imported += stored.count
            updateUi { it.copy(imported = imported) }
        }
        if (isGapFillReply) advanceGapFill(value, hubConvId, stored.minId)
        if (isFetchReply) advanceFetch(value, hubConvId, stored.minId, isGapFillReply)
    }

    /**
     * An empty page means there is nothing more to walk for this
     * conversation — treat it as complete even if `has_more` is set
     * (a page with rows would have carried them). Except in a delta
     * run, where an empty stream batch says nothing about the
     * unfinished walk — leave the frontier alone and let gap-fill
     * finish it. An empty fetch-on-open reply IS the end of the
     * walk (the hub had nothing before the frontier), so it
     * completes the conversation even in a delta run.
     */
    private suspend fun onEmptyBatch(
        hubConvId: Long,
        isGapFillReply: Boolean,
        isFetchReply: Boolean,
    ) {
        if (isGapFillReply || isFetchReply || !runIsDelta) {
            setFrontier(hubConvId, minSyncedId = null, complete = true)
        }
        if (isGapFillReply) {
            gapFillQueue.removeFirstOrNull()
            pumpGapFill()
        }
        if (isFetchReply) {
            fetchInFlight.remove(hubConvId)
            fetchedThisSession.add(hubConvId)
        }
    }

    /**
     * Stores one batch, oldest id + count out. The cursor advances
     * only after the durable write — per message, as each lands.
     * A message that resolves a pending pin or a pending quote
     * heals it on the spot; a reply whose target has not arrived
     * yet waits in [pendingQuotes].
     */
    private suspend fun storeBatchMessages(messages: List<SyncedMessage>): BatchStored {
        var minId: Long? = null
        var count = 0
        for (wire in messages) {
            val hubMsgId = storeOneMessage(wire) ?: continue
            if (hubMsgId > maxStoredId) maxStoredId = hubMsgId
            if (minId == null || hubMsgId < minId) minId = hubMsgId
            count++
        }
        return BatchStored(minId, count)
    }

    /**
     * Stores one wire message and heals whatever it resolves (a
     * pending pin, a pending quote). Null when the row cannot be
     * stored: the mirror declined, or the id is unparsable.
     */
    private suspend fun storeOneMessage(wire: SyncedMessage): Long? {
        val result = mirror.upsertSyncedMessage(wire) ?: return null
        val hubMsgId = parseHubId(wire.id) ?: return null
        resolvePendingFor(hubMsgId, result.localId)
        result.unresolvedReplyToHubMessageId?.let { target ->
            pendingQuotes.getOrPut(target) { mutableListOf() }.add(result.localId)
        }
        // Client mode C3 — a batch message carrying a client key is a
        // fired schedule (or our own send, already reconciled by the
        // mirror): the schedule's local row goes SENT.
        wire.clientMessageId?.let { sendManager.onLiveMessage(it) }
        return hubMsgId
    }

    /** Gap-fill walks one conversation at a time, oldest page first. */
    private suspend fun advanceGapFill(
        value: SyncProtocol.MessagesBatch,
        hubConvId: Long,
        minId: Long?,
    ) {
        if (value.hasMore) {
            val nextBefore = value.nextBeforeId ?: minId?.toString() ?: "0"
            requestPage(hubConvId, nextBefore)
        } else {
            gapFillQueue.removeFirstOrNull()
            pumpGapFill()
        }
    }

    /**
     * Fetch-on-open walks the same way, but never shares gap-fill's
     * queue. When gap-fill is already walking this conversation its
     * branch requests the next page; the fetch just waits for the
     * final page.
     */
    private suspend fun advanceFetch(
        value: SyncProtocol.MessagesBatch,
        hubConvId: Long,
        minId: Long?,
        isGapFillReply: Boolean,
    ) {
        if (value.hasMore) {
            if (!isGapFillReply) {
                val nextBefore = value.nextBeforeId ?: minId?.toString() ?: "0"
                requestFetchPage(hubConvId, nextBefore)
            }
        } else {
            fetchInFlight.remove(hubConvId)
            fetchedThisSession.add(hubConvId)
        }
    }

    private suspend fun onSyncComplete() {
        // The cursor is the max id WE stored — never the server's
        // cursor (it is deliberately not read: a live message could
        // still be in flight past it, and persisting it would skip
        // that message forever).
        val stored = syncState
        if (stored != null) {
            val updated = stored.copy(
                globalLastMessageId = maxStoredId.toString(),
                initialSyncDone = true,
                lastSyncAt = System.currentTimeMillis(),
            )
            stateStore.saveSyncState(updated)
            syncState = updated
        }
        deltaElapsedJob?.cancel()
        deltaElapsedJob = null
        updateUi {
            it.copy(
                phase = ClientSyncPhase.Live,
                imported = 0,
                total = null,
                isDelta = false,
                deltaElapsed = false,
            )
        }
        // `new_only` fetches no history by choice — there is nothing
        // to gap-fill. Its snapshot rows stay incomplete, so a later
        // FULL run's gap-fill walks them then.
        if (runMode != SyncProtocol.SyncRequest.NEW_ONLY) {
            startGapFill()
        }
    }

    private suspend fun onMessageNew(value: SyncProtocol.MessageNew) {
        val wire = value.message
        val result = mirror.upsertSyncedMessage(wire) ?: return
        val hubMsgId = parseHubId(wire.id) ?: return
        if (hubMsgId > maxStoredId) maxStoredId = hubMsgId
        resolvePendingFor(hubMsgId, result.localId)
        result.unresolvedReplyToHubMessageId?.let { target ->
            pendingQuotes.getOrPut(target) { mutableListOf() }.add(result.localId)
        }
        // Client mode C3 — a live message carrying a client key is a
        // fired schedule (or our own send, already reconciled by the
        // mirror): the schedule's local row goes SENT.
        wire.clientMessageId?.let { sendManager.onLiveMessage(it) }
        persistCursor()
        // Client mode C4 — a live message notifies (bulk batches
        // never do); the relay applies its own gates, the notifier
        // the rest (mute / category / preview).
        notificationRelay.onLiveMessage(wire, result)
    }

    private suspend fun onConversationDeleted(value: SyncProtocol.ConversationDeleted) {
        val hubConvId = parseHubId(value.conversationId) ?: return
        mirror.deleteSyncedConversation(hubConvId)
        gapFillQueue.remove(hubConvId)
        fetchInFlight.remove(hubConvId)
        fetchedThisSession.remove(hubConvId)
        pendingPinned.entries.removeAll { it.value.hubConversationId == hubConvId }
    }

    private suspend fun onSyncError(value: SyncProtocol.SyncError) {
        deltaElapsedJob?.cancel()
        deltaElapsedJob = null
        gapFillActive = false
        gapFillQueue.clear()
        updateUi {
            it.copy(
                phase = ClientSyncPhase.Error,
                imported = 0,
                total = null,
                isDelta = false,
                deltaElapsed = false,
                errorCode = value.code,
                errorMessage = value.message.ifBlank { null },
            )
        }
    }

    private fun onHubStatus(value: SyncProtocol.HubStatus) {
        updateUi { it.copy(hubIsDefaultSms = value.isDefaultSms) }
    }

    // -- Shared frame helpers ---------------------------------------

    /**
     * Upserts one conversation frame (`conversations`,
     * `conversation_updated`, `conversation_created`) and records
     * a pinned message that has not arrived yet.
     */
    private suspend fun upsertConversationFrame(
        wire: SyncedConversation,
    ): SyncedConversationResult? {
        val result = mirror.upsertSyncedConversation(wire) ?: return null
        val unresolvedPin = result.unresolvedPinnedHubMessageId
        if (unresolvedPin != null) {
            val hubConvId = parseHubId(wire.id)
            if (hubConvId != null) {
                pendingPinned[unresolvedPin] = PendingPin(hubConvId, result.pinnedMessageAt)
            }
        }
        return result
    }

    /**
     * A message just landed: pin it if a conversation was waiting
     * for it, and point at it any replies that quoted it before it
     * arrived.
     */
    private suspend fun resolvePendingFor(hubMessageId: Long, localId: Long) {
        pendingPinned.remove(hubMessageId)?.let { pin ->
            val localConvId = conversationDao.findByHubConversationId(pin.hubConversationId)?.id
            if (localConvId != null) {
                conversationDao.setPinnedMessage(localConvId, localId, pin.pinnedAt)
            }
        }
        pendingQuotes.remove(hubMessageId)?.let { replyIds ->
            for (replyId in replyIds) {
                mirror.resolveReplyTarget(replyId, localId)
            }
        }
    }

    /**
     * Moves one conversation's walk frontier. The frontier only ever
     * moves DOWN (older): the min of the stored value and the new
     * page's oldest id — `"0"` (un-walked) takes the new value.
     * An empty page ([minSyncedId] null) keeps the frontier and
     * only flips the complete flag.
     */
    private suspend fun setFrontier(
        hubConversationId: Long,
        minSyncedId: Long?,
        complete: Boolean,
    ) {
        val existing = stateStore.getConversationState(hubConversationId)
        val current = existing?.minSyncedId?.toLongOrNull() ?: 0L
        val next = when {
            minSyncedId == null -> existing?.minSyncedId ?: "0"
            current == 0L -> minSyncedId
            else -> minOf(current, minSyncedId)
        }
        stateStore.saveConversationState(
            ClientConversationStateEntity(
                hubConversationId = hubConversationId,
                minSyncedId = next.toString(),
                historyComplete = complete,
            ),
        )
    }

    // -- Gap-fill -----------------------------------------------------

    /**
     * Gap-fill: any conversation whose history is incomplete gets a
     * `messages_request` from its stored frontier, until complete.
     * This is the resume path after a dropped connection and the
     * repair path for a single conversation — and what makes a
     * crash mid-full-sync correct: the cursor guards the NEW end,
     * the frontiers guard the OLD end.
     */
    private suspend fun startGapFill() {
        val incomplete = stateStore.listIncompleteConversationIds()
        if (incomplete.isEmpty()) return
        gapFillQueue.clear()
        gapFillQueue.addAll(incomplete)
        gapFillActive = true
        pumpGapFill()
    }

    private suspend fun pumpGapFill() {
        val id = gapFillQueue.firstOrNull()
        if (id == null) {
            gapFillActive = false
            return
        }
        val frontier = stateStore.getConversationState(id)?.minSyncedId ?: "0"
        requestPage(id, frontier)
    }

    private suspend fun requestPage(hubConversationId: Long, beforeId: String) {
        val sent = channel.send(
            ClientFrames.encodeMessagesRequest(hubConversationId.toString(), beforeId, GAP_FILL_LIMIT),
        )
        if (!sent) {
            // Session dropped mid-fill: stop quietly — the reconnect
            // resumes from the stored frontiers.
            gapFillActive = false
            gapFillQueue.clear()
        }
    }

    /**
     * One fetch-on-open page. Unlike [requestPage], a failure here
     * touches only the fetch: the conversation leaves the in-flight
     * set (the next open retries) and gap-fill is left alone — a
     * fetch failure is not a gap-fill failure.
     */
    private suspend fun requestFetchPage(hubConversationId: Long, beforeId: String) {
        val sent = channel.send(
            ClientFrames.encodeMessagesRequest(hubConversationId.toString(), beforeId, GAP_FILL_LIMIT),
        )
        if (!sent) {
            fetchInFlight.remove(hubConversationId)
        }
    }

    private inline fun updateUi(transform: (ClientSyncUiState) -> ClientSyncUiState) {
        _ui.update(transform)
    }

    companion object {
        /**
         * How long a delta (reconnect) sync must run before its
         * progress shows when the phone has not announced any work
         * yet — long enough that a sub-second empty catch-up stays
         * invisible, short enough that a real catch-up never feels
         * stuck. (Desktop `DELTA_SYNC_DIALOG_DELAY_MS`.)
         */
        const val DELTA_SYNC_DIALOG_DELAY_MS = 1_000L

        /** Page size for gap-fill / fetch-on-open (the hub caps at 200). */
        const val GAP_FILL_LIMIT = 200

        /** [ClientSyncUiState.errorCode] when the request never went out (dead session). */
        const val ERROR_NOT_CONNECTED = "not_connected"
    }
}

/**
 * Client mode — the Hilt entry point for [ClientSyncEngine], for
 * callers that cannot take it as a constructor parameter
 * ([ThreadViewModel] is at the constructor-parameter cap).
 * Resolved from the injected application context, the same way
 * `RecentReactionsStore` is reached.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClientSyncEngineEntryPoint {
    fun clientSyncEngine(): ClientSyncEngine
}
