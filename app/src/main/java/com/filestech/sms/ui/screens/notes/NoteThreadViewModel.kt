package com.filestech.sms.ui.screens.notes

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.io.LectureBornee
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.data.voice.VoiceRecorder
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.clientmode.NoOpClientSendGateway
import com.filestech.sms.domain.clientmode.NoteSaveAttachment
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.NOTE_TITLE_MAX_LENGTH
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.usecase.ExportNotePdfUseCase
import com.filestech.sms.sync.client.ClientDownloadState
import com.filestech.sms.ui.components.AttachmentKind
import com.filestech.sms.ui.screens.thread.ContactCardSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Notes — the ViewModel for one note's thread.
 *
 * It mirrors [ThreadViewModel]'s shape (bounded message window +
 * load-older paging, draft persistence, staged attachments, voice
 * recording + playback, PDF export) but talks ONLY to the note
 * repository: nothing here touches the SMS paths — no
 * `EnvoyerMessageUseCase`, no `SmsSender`, no `OutgoingMessageMirror`.
 * Saving an entry is a local Room insert + encrypted attachment
 * writes, and the repository updates the list preview.
 *
 * Plaintext discipline: staged files live in `cacheDir/notes_outgoing`
 * (the user's own picks, not yet encrypted — the same staging the
 * thread uses for outgoing MMS) and are deleted after the save, win
 * or lose. Decrypted playback files are tracked and released (see
 * [registerExternalPlaybackFile] and [onCleared]).
 *
 * Round 36 phase 2 — client mode: the thread works off the mirror.
 * [save] sends via the gateway ([ClientSendGateway.saveNoteMessage],
 * awaited — the draft + staged files stay until it answers, so a
 * failed save keeps them for a retry), the pin / archive / rename /
 * edit / delete actions route through the repository (which frames
 * them to the hub), and note attachment downloads flow through the
 * downloader ([noteDownloads], reached via the entry point like
 * [ThreadViewModel]).
 */
@HiltViewModel
class NoteThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val noteRepo: NoteRepository,
    /**
     * Public: the screen hands it to the message items and the
     * details panel, which decrypt previews through it (the VM
     * itself only uses it for playback + save-a-copy).
     */
    val resolver: NoteAttachmentResolver,
    private val exportPdf: ExportNotePdfUseCase,
    private val voiceRecorder: VoiceRecorder,
    private val playbackController: VoicePlaybackController,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    /** Round 36 phase 2 — the client-mode send seam (a no-op in hub mode). */
    private val sendGateway: ClientSendGateway = NoOpClientSendGateway,
) : ViewModel() {

    val noteId: Long = savedStateHandle.get<Long>("noteId") ?: -1L

    /** Staged attachment awaiting the save: a plaintext staging file + its metadata. */
    data class PendingNoteAttachment(
        /** Absolute path of the staging file in `cacheDir/notes_outgoing`. */
        val path: String,
        val mimeType: String,
        val fileName: String?,
        val sizeBytes: Long,
        /** Voice clips only: the recorded duration, stored with the attachment. */
        val durationMs: Long? = null,
    )

    sealed interface VoiceState {
        data object Idle : VoiceState

        /** Recording in progress; [elapsedMs] drives the recording strip. */
        data class Recording(val elapsedMs: Long) : VoiceState
    }

    data class UiState(
        val note: Note? = null,
        /** True until the first note emission lands (the screen pops only after that). */
        val isLoading: Boolean = true,
        /** The tail window, oldest first. */
        val messages: List<NoteMessage> = emptyList(),
        val totalCount: Int = 0,
        val hasMore: Boolean = false,
        val isLoadingOlder: Boolean = false,
        val draft: String = "",
        val pendingAttachments: List<PendingNoteAttachment> = emptyList(),
        val voiceState: VoiceState = VoiceState.Idle,
        val isSaving: Boolean = false,
        val isExporting: Boolean = false,
        /**
         * The note's attachments with their message's date and body
         * attached (the details panel groups and filters on them).
         */
        val panelAttachments: List<Attachment> = emptyList(),
        /** Round 36 phase 2 — true when this device is a linked client (checked once in init). */
        val isClientMode: Boolean = false,
        /** Round 36 phase 2 — client mode: the note download states, keyed by local attachment id. */
        val noteDownloads: Map<Long, ClientDownloadState> = emptyMap(),
    ) {
        /** PDF export needs at least one entry. */
        val canExport: Boolean get() = messages.isNotEmpty()
    }

    sealed interface Event {
        data class ShowSnackbar(val message: String, val isError: Boolean = false) : Event

        /**
         * The PDF is ready: [shareUri] is the opaque `content://`
         * string from the exporter (the domain stays Android-free);
         * the screen re-parses it into a `Uri` for the share intent.
         */
        data class PdfReady(val shareUri: String, val pages: Int) : Event

        /** The note was deleted (from the panel): the screen pops. */
        data object NoteDeleted : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = oneShotEvents<Event>()
    val events = _events

    /** The playback controller's state (the audio bubbles read it via a lambda). */
    val playbackState: StateFlow<VoicePlaybackController.PlaybackState> = playbackController.state

    /** The tail-window limit: 50 first, +200 per "load older" (same as the thread). */
    private val tailLimit = MutableStateFlow(INITIAL_WINDOW_SIZE)

    /** The draft seeds once from `note.draft`; later note emissions must not clobber typing. */
    private var draftSeeded = false

    /**
     * Bumped on every [updateDraft]. [save] captures it before the
     * insert and clears the draft only if it is unchanged after —
     * typing during the save keeps the new text instead of losing it.
     */
    private var draftRevision = 0

    /** The in-app audio playback file, released when another clip starts or on clear. */
    private var activePlaybackFile: File? = null

    /**
     * Playback files handed to EXTERNAL viewers (the opener registers
     * them here): released in [onCleared] — there is no way to know
     * when an external viewer finishes, so the resolver's 24 h stale
     * purge is the backstop for a file that outlives the screen.
     */
    private val externalPlaybackFiles = mutableListOf<File>()

    private var recordingJob: Job? = null

    init {
        viewModelScope.launch {
            noteRepo.observeNote(noteId).collect { note ->
                if (!draftSeeded) {
                    draftSeeded = true
                    _state.update { it.copy(note = note, isLoading = false, draft = note?.draft ?: "") }
                } else {
                    _state.update { it.copy(note = note, isLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            noteRepo.observeMessagesWindow(noteId, tailLimit).collect { window ->
                _state.update {
                    it.copy(
                        messages = window.messages,
                        totalCount = window.totalCount,
                        hasMore = window.hasMore,
                        isLoadingOlder = window.hasMore && window.messages.size < tailLimit.value,
                    )
                }
            }
        }
        // The details panel's attachments: the repository's
        // `Attachment` rows carry no message date/body (those are
        // populated only by the media queries), so pair each
        // attachment with its message here — the panel groups by date
        // and filters stickers on the body. Newest first, like the
        // conversation media queries: `groupByDetailsDate` keeps the
        // input order inside a group.
        viewModelScope.launch {
            noteRepo.observeMessages(noteId).collect { messages ->
                val paired = messages.asReversed().flatMap { msg ->
                    msg.attachments.map { it.copy(messageDate = msg.date, messageBody = msg.body) }
                }
                _state.update { it.copy(panelAttachments = paired) }
            }
        }
        // Round 36 phase 2 — client mode: the mode is checked once
        // (it cannot change while this screen is open — a mode
        // switch restarts the app), and the note download states
        // flow in from the downloader (a missing downloader leaves
        // the map empty, like ThreadViewModel).
        viewModelScope.launch {
            _state.update { it.copy(isClientMode = sendGateway.isClientMode()) }
        }
        viewModelScope.launch {
            val downloader = runCatching { clientAttachmentDownloader() }.getOrNull() ?: return@launch
            downloader.noteStates.collect { states ->
                _state.update { it.copy(noteDownloads = states) }
            }
        }
    }

    /** Widens the tail window by one page (the "load older" row). */
    fun loadOlder() {
        if (!_state.value.hasMore) return
        tailLimit.value += PAGE_SIZE
    }

    /** Persists the draft on every keystroke (see [ThreadViewModel.updateDraft]). */
    fun updateDraft(text: String) {
        draftRevision++
        _state.update { it.copy(draft = text) }
        viewModelScope.launch { noteRepo.setDraft(noteId, text.ifBlank { null }) }
    }

    /**
     * Saves the entry: the text (when non-blank) and/or the staged
     * attachments, as ONE message. The message insert comes first so
     * an attachment failure still leaves the entry saved (with a
     * snackbar); the staging files are deleted either way.
     *
     * No MMS budget, no image compression: notes store originals.
     * Image dimensions are probed (bounds-only) for the sticker
     * filter and the panel; video duration is probed best-effort.
     *
     * Round 36 phase 2 — client mode sends via the hub instead
     * ([saveViaHub]): the save is awaited, and the draft + staged
     * files stay until it answers.
     */
    fun save() {
        val current = _state.value
        if (current.isSaving) return
        val body = current.draft
        val staged = current.pendingAttachments
        if (body.isBlank() && staged.isEmpty()) return
        if (current.isClientMode) {
            saveViaHub(body, staged)
            return
        }
        val revision = draftRevision
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val inserted = runCatching { noteRepo.insertMessage(noteId, body) }.getOrNull()
            if (inserted == null) {
                _state.update { it.copy(isSaving = false) }
                _events.tryEmit(
                    Event.ShowSnackbar(context.getString(R.string.note_save_failed), isError = true),
                )
                return@launch
            }
            var failed = 0
            for (item in staged) {
                val file = File(item.path)
                val bytes = withContext(io) { runCatching { file.readBytes() }.getOrNull() }
                if (bytes == null) {
                    failed++
                    continue
                }
                val dims = if (item.mimeType.startsWith("image/", ignoreCase = true)) {
                    probeImageDimensions(bytes)
                } else {
                    null
                }
                val duration = item.durationMs
                    ?: if (item.mimeType.startsWith("video/", ignoreCase = true)) {
                        probeVideoDuration(file)
                    } else {
                        null
                    }
                when (
                    noteRepo.addAttachment(
                        messageId = inserted.id,
                        mimeType = item.mimeType,
                        fileName = item.fileName,
                        plaintext = bytes,
                        width = dims?.first,
                        height = dims?.second,
                        durationMs = duration,
                    )
                ) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> failed++
                }
            }
            withContext(io) {
                staged.forEach { runCatching { File(it.path).delete() } }
            }
            val draftIntact = draftRevision == revision
            _state.update {
                it.copy(
                    isSaving = false,
                    pendingAttachments = emptyList(),
                    draft = if (draftIntact) "" else it.draft,
                )
            }
            if (draftIntact) noteRepo.setDraft(noteId, null)
            if (failed > 0) {
                _events.tryEmit(
                    Event.ShowSnackbar(context.getString(R.string.note_attachment_failed), isError = true),
                )
            }
        }
    }

    /** Deletes one entry (its attachment rows + files cascade in the repository). */
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch { noteRepo.deleteMessage(messageId) }
    }

    /** Round 36 phase 2 — edits a text entry's body (the repository routes client mode). */
    fun editMessage(messageId: Long, newBody: String) {
        if (newBody.isBlank()) return
        viewModelScope.launch { noteRepo.editMessage(messageId, newBody) }
    }

    /** Round 36 phase 2 — client mode: requests one note attachment's bytes, by local id. */
    fun requestNoteDownload(localAttachmentId: Long) {
        viewModelScope.launch {
            runCatching { clientAttachmentDownloader().requestNote(localAttachmentId) }
        }
    }

    /** Round 36 phase 2 — the attachment downloader, via the entry point (see [ThreadViewModel]). */
    private fun clientAttachmentDownloader(): com.filestech.sms.sync.client.ClientAttachmentDownloader =
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context,
            com.filestech.sms.sync.client.ClientAttachmentDownloaderEntryPoint::class.java,
        ).clientAttachmentDownloader()

    /**
     * Round 36 phase 2 — the client-mode save: the gateway inserts
     * the optimistic row + encrypted attachment copies, uploads
     * each file, sends `save_note_message`, and awaits the ack.
     * Success clears the draft + staged files (as in hub mode);
     * failure KEEPS them — the gateway already deleted the
     * optimistic row + files, so the user retries from the draft
     * and the staged files. A staged file that vanished before the
     * send fails the save before anything is sent.
     */
    private fun saveViaHub(body: String, staged: List<PendingNoteAttachment>) {
        val revision = draftRevision
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val attachments = staged.mapNotNull { item ->
                val file = File(item.path)
                if (!file.exists()) return@mapNotNull null
                val bytes = withContext(io) { runCatching { file.readBytes() }.getOrNull() }
                val dims = if (bytes != null && item.mimeType.startsWith("image/", ignoreCase = true)) {
                    probeImageDimensions(bytes)
                } else {
                    null
                }
                val duration = item.durationMs
                    ?: if (item.mimeType.startsWith("video/", ignoreCase = true)) {
                        probeVideoDuration(file)
                    } else {
                        null
                    }
                NoteSaveAttachment(
                    file = file,
                    mimeType = item.mimeType,
                    fileName = item.fileName,
                    width = dims?.first,
                    height = dims?.second,
                    durationMs = duration,
                )
            }
            if (attachments.size != staged.size) {
                _state.update { it.copy(isSaving = false) }
                _events.tryEmit(
                    Event.ShowSnackbar(context.getString(R.string.note_attachment_failed), isError = true),
                )
                return@launch
            }
            when (val result = sendGateway.saveNoteMessage(noteId, body, attachments)) {
                is Outcome.Success -> {
                    withContext(io) {
                        staged.forEach { runCatching { File(it.path).delete() } }
                    }
                    val draftIntact = draftRevision == revision
                    _state.update {
                        it.copy(
                            isSaving = false,
                            pendingAttachments = emptyList(),
                            draft = if (draftIntact) "" else it.draft,
                        )
                    }
                    if (draftIntact) noteRepo.setDraft(noteId, null)
                }

                is Outcome.Failure -> {
                    _state.update { it.copy(isSaving = false) }
                    val message = when (result.error) {
                        is AppError.ClientOffline ->
                            context.getString(R.string.error_send_client_offline)

                        else -> context.getString(R.string.note_save_failed)
                    }
                    _events.tryEmit(Event.ShowSnackbar(message, isError = true))
                }
            }
        }
    }

    /**
     * Stages a picked attachment: a contact resolves to its vCard
     * (see [ContactCardSource]); anything else copies to
     * `cacheDir/notes_outgoing`, bounded at [STAGING_CAP_BYTES] with
     * the same two guards as the thread (declared size, then
     * [LectureBornee]). The copy is the ORIGINAL — no compression,
     * no MMS budget.
     */
    fun onAttachmentPicked(uri: Uri, kind: AttachmentKind) {
        if (_state.value.note == null) return
        viewModelScope.launch {
            val cr = context.contentResolver
            val carte = if (kind == AttachmentKind.CONTACT) {
                val resolue = withContext(io) { ContactCardSource(cr).resoudre(uri) }
                if (resolue == null) {
                    _events.tryEmit(
                        Event.ShowSnackbar(context.getString(R.string.note_attachment_failed), isError = true),
                    )
                    return@launch
                }
                resolue
            } else {
                null
            }
            val source = carte?.uri ?: uri
            val mime = carte?.mime ?: cr.getType(uri) ?: when (kind) {
                AttachmentKind.PHOTO -> "image/jpeg"
                AttachmentKind.VIDEO -> "video/mp4"
                else -> "application/octet-stream"
            }
            val displayName = carte?.nomDeFichier
                ?: resolveDisplayName(uri)
                ?: context.getString(R.string.attachment_generic_name)
            val file = copyToStaging(source, mime)
            if (file == null) {
                _events.tryEmit(
                    Event.ShowSnackbar(context.getString(R.string.note_attachment_failed), isError = true),
                )
                return@launch
            }
            _state.update {
                it.copy(
                    pendingAttachments = it.pendingAttachments + PendingNoteAttachment(
                        path = file.absolutePath,
                        mimeType = mime,
                        fileName = displayName,
                        sizeBytes = file.length(),
                    ),
                )
            }
        }
    }

    /** Removes a staged attachment and deletes its staging file. */
    fun removePendingAttachment(path: String) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.path == path }) }
        viewModelScope.launch(io) { runCatching { File(path).delete() } }
    }

    // — Voice (the recorder is NOT coupled to MMS sending: the
    // coupling was only in ThreadViewModel.doSendVoice. A finished
    // clip stages as a pending audio chip and saves with the entry;
    // there is no pre-save playback review in v1.)

    /** Starts a recording (the screen checks RECORD_AUDIO first). Max 120 s / 280 KB (MMS-era caps). */
    fun startRecording() {
        if (_state.value.voiceState is VoiceState.Recording) return
        viewModelScope.launch { runCatching { voiceRecorder.pruneOld() } }
        recordingJob = viewModelScope.launch {
            voiceRecorder.record().collect { event ->
                when (event) {
                    is VoiceRecorder.Event.Tick ->
                        _state.update { it.copy(voiceState = VoiceState.Recording(event.elapsedMs)) }

                    is VoiceRecorder.Event.Completed -> {
                        _state.update { it.copy(voiceState = VoiceState.Idle) }
                        stageVoiceClip(event.result)
                    }

                    is VoiceRecorder.Event.Failed -> {
                        _state.update { it.copy(voiceState = VoiceState.Idle) }
                        _events.tryEmit(
                            Event.ShowSnackbar(
                                context.getString(R.string.note_attachment_failed),
                                isError = true,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Stops the recording: the [VoiceRecorder.Event.Completed] flows through the collector. */
    fun stopRecording() {
        voiceRecorder.stop()
    }

    /** Cancels the recording: the recorder deletes its file and the flow closes silently. */
    fun cancelRecording() {
        voiceRecorder.cancel()
        recordingJob?.cancel()
        recordingJob = null
        _state.update { it.copy(voiceState = VoiceState.Idle) }
    }

    /**
     * Stages a finished voice clip: the recorder's file (≤280 KB)
     * copies into `notes_outgoing` and the original is deleted, so no
     * plaintext clip lingers in the recorder's directory.
     */
    private fun stageVoiceClip(result: VoiceRecorder.Result) {
        viewModelScope.launch(io) {
            val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
            val target = File(dir, "voice-${System.currentTimeMillis()}.3gp")
            val copied = runCatching {
                result.file.copyTo(target, overwrite = true)
                runCatching { result.file.delete() }
                true
            }.getOrDefault(false)
            if (!copied) {
                _events.tryEmit(
                    Event.ShowSnackbar(context.getString(R.string.note_attachment_failed), isError = true),
                )
                return@launch
            }
            _state.update {
                it.copy(
                    pendingAttachments = it.pendingAttachments + PendingNoteAttachment(
                        path = target.absolutePath,
                        mimeType = result.mimeType,
                        fileName = context.getString(R.string.thread_pinned_voice),
                        sizeBytes = target.length(),
                        durationMs = result.durationMs,
                    ),
                )
            }
        }
    }

    // — Audio playback (the controller is shared with the thread; the
    // key is the attachment's `localUri`, matching the bubble's
    // `derivedStateOf` key — the URI is an identity here, never a
    // source: the player gets the decrypted playback file's URI.)

    /**
     * Toggles playback of an audio attachment. Same clip: the
     * controller pauses/resumes without touching the file. Another
     * clip: decrypt to a playback file, release the previous clip's
     * file, and hand the new file to the controller.
     */
    fun togglePlayback(attachment: Attachment) {
        val key = attachment.localUri
        if (playbackController.state.value.activeKey == key) {
            playbackController.toggle(key, Uri.EMPTY)
            return
        }
        viewModelScope.launch {
            when (val res = resolver.decryptToPlaybackFile(attachment)) {
                is Outcome.Success -> {
                    activePlaybackFile?.let { resolver.releasePlaybackFile(it) }
                    activePlaybackFile = res.value
                    playbackController.toggle(key, Uri.fromFile(res.value))
                }

                is Outcome.Failure -> {
                    Timber.w("NoteThreadViewModel: decrypt for playback failed")
                    _events.tryEmit(
                        Event.ShowSnackbar(context.getString(R.string.note_open_failed), isError = true),
                    )
                }
            }
        }
    }

    /** Seeks within whichever clip is currently active in the playback controller. */
    fun seekPlaybackTo(positionMs: Int) {
        playbackController.seekTo(positionMs)
    }

    // — Note actions (the panel + the list sheet call these)

    /** Renames the note (blank titles are ignored; the title caps at [NOTE_TITLE_MAX_LENGTH]). */
    fun renameNote(title: String) {
        val clean = title.trim().take(NOTE_TITLE_MAX_LENGTH)
        if (clean.isBlank()) return
        viewModelScope.launch { noteRepo.renameNote(noteId, clean) }
    }

    /** Deletes the note and emits [Event.NoteDeleted] so the screen pops. */
    fun deleteNote() {
        viewModelScope.launch {
            noteRepo.deleteNote(noteId)
            _events.tryEmit(Event.NoteDeleted)
        }
    }

    /** Sets the note's bubble colour (`null` = default brand blue). */
    fun setBubbleColor(colorArgb: Int?) {
        viewModelScope.launch { noteRepo.setBubbleColor(noteId, colorArgb) }
    }

    /** Exports the whole note to PDF (the use case refuses an empty note). */
    fun exportToPdf() {
        if (_state.value.isExporting || !_state.value.canExport) return
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true) }
            when (val res = exportPdf(noteId)) {
                is Outcome.Success -> _events.tryEmit(Event.PdfReady(res.value.shareUri, res.value.pages))
                is Outcome.Failure -> _events.tryEmit(
                    Event.ShowSnackbar(context.getString(R.string.snack_pdf_export_failed), isError = true),
                )
            }
            _state.update { it.copy(isExporting = false) }
        }
    }

    /**
     * Registers a playback file handed to an external viewer (the
     * note opener calls this): it is released in [onCleared] — there
     * is no way to know when an external viewer finishes.
     */
    fun registerExternalPlaybackFile(file: File) {
        externalPlaybackFiles.add(file)
    }

    /**
     * Saves a decrypted copy of an attachment to a user-picked SAF
     * location (the details panel's save-a-copy). The resolver
     * flushes but does not close the stream; the `use` closes it.
     */
    suspend fun saveAttachmentCopy(attachment: Attachment, targetUri: Uri): Boolean =
        withContext(io) {
            runCatching {
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    resolver.decryptToStream(attachment, out) is Outcome.Success
                } ?: false
            }.getOrDefault(false)
        }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        voiceRecorder.cancel()
        playbackController.stop()
        activePlaybackFile?.let { resolver.releasePlaybackFile(it) }
        activePlaybackFile = null
        externalPlaybackFiles.forEach { resolver.releasePlaybackFile(it) }
        externalPlaybackFiles.clear()
    }

    // — Staging helpers (mirroring ThreadViewModel's copy guards)

    private suspend fun copyToStaging(uri: Uri, mime: String): File? = withContext(io) {
        val ext = mime.substringAfter('/', "bin").take(8).replace(Regex("[^A-Za-z0-9]"), "")
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val target = File(
            dir,
            "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}.${ext.ifBlank { "bin" }}",
        )
        val declaredSize = declaredSizeOf(uri)
        if (declaredSize != null && declaredSize > STAGING_CAP_BYTES) return@withContext null
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            val kept = LectureBornee.recopier(input, target, STAGING_CAP_BYTES)
            if (kept) target else null
        }.getOrNull()
    }

    private suspend fun declaredSizeOf(uri: Uri): Long? = withContext(io) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
    }

    private suspend fun resolveDisplayName(uri: Uri): String? = withContext(io) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /**
     * Bounds-only image dimension probe. Fully guarded: `BitmapFactory`
     * throws on plain JVM (unit tests), and a corrupt header yields
     * non-positive dimensions — both read as "unknown", never a crash.
     */
    private fun probeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val w = options.outWidth
        val h = options.outHeight
        if (w > 0 && h > 0) w to h else null
    }.getOrNull()

    /** Best-effort video duration probe (null when the file is unreadable). */
    private fun probeVideoDuration(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    companion object {
        /** First tail window (same as the thread). */
        const val INITIAL_WINDOW_SIZE: Int = 50

        /** Rows added per "load older" (same as the thread). */
        const val PAGE_SIZE: Int = 200

        /** Staging cap per file — the thread's copy cap and the backup per-attachment cap. */
        const val STAGING_CAP_BYTES: Long = 25L * 1024 * 1024

        /** Staging subdirectory of `cacheDir` for pre-encryption picks. */
        const val STAGING_DIR: String = "notes_outgoing"
    }
}
