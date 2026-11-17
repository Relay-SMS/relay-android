package com.filestech.sms.sync.client

import android.content.Context
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.sync.SyncProtocol
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode C5 — the durable attachment root (`filesDir`), shared
 * with the inbound writer and the outgoing store. MUST stay in sync
 * with the `<files-path name="attachments" .../>` entry in
 * `res/xml/file_provider_paths.xml` and with
 * `OutgoingAttachmentStoreImpl.ATTACHMENTS_DIR` (internal to the data
 * module, so this is our own copy of the name).
 */
internal const val MMS_ATTACHMENTS_DIR_NAME = "mms_attachments"

/**
 * Client mode C5 — the download state of one attachment, keyed by its
 * LOCAL attachment id (the only id the UI knows). The downloader owns
 * the map; the thread + gallery observe it and render the matching
 * tile state.
 */
sealed interface ClientDownloadState {
    /** Not downloaded yet — the bubble offers tap-to-download (and auto-requests on render). */
    data object NotDownloaded : ClientDownloadState

    /** Bytes are coming in. [totalBytes] is the metadata size, 0 when unknown. */
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : ClientDownloadState

    /** Bytes verified + on disk (`localUri` set). */
    data object Downloaded : ClientDownloadState

    /** Failed for good — the bubble offers tap-to-retry. */
    data object Failed : ClientDownloadState

    /** A verified transfer of zero bytes: the phone's file is genuinely empty. Not a failure. */
    data object Empty : ClientDownloadState
}

/**
 * Client mode C5 — the attachment downloader: the desktop's
 * `AttachmentDownloader` (`attachments.ts`), ported to the Android
 * cache. Lazy only — a download starts when the UI asks for it (a
 * bubble renders, a gallery tile renders, or the user taps retry),
 * never eagerly: opening a thread fetches its visible attachments,
 * nothing is prefetched.
 *
 * Rules, ported from the desktop:
 * - **Queue.** At most [MAX_CONCURRENT_DOWNLOADS] run at once, the
 *   rest queue FIFO. A download is one `attachment_request`
 *   (`attachment_id` + the membership-guard `message_id`), then
 *   64 KiB `attachment_chunk` frames, sequential offsets, base64url.
 * - **Verify.** The final chunk's SHA-256 + size are checked
 *   ([verifyTransferBytes]); when the metadata size is known
 *   (non-zero — size-0 metadata means "unknown", the desktop rule)
 *   the received count must match it too — the final chunk's size +
 *   digest only prove the transfer wasn't corrupted in flight, not
 *   that the phone sent the whole file.
 * - **Empty.** A verified transfer of zero bytes means the phone's
 *   file is genuinely empty — it reports [ClientDownloadState.Empty]
 *   instead of failing, so the UI can say "this file is empty"
 *   rather than "couldn't download" (and no retry is attempted: an
 *   empty file won't grow). The id still lands in the failed set,
 *   so auto-requests don't re-download it in a loop; a manual retry
 *   still re-checks, in case the file has grown since.
 * - **Stall + retry.** No chunk for 30 s is a stall: one automatic
 *   retry, then failed. An integrity mismatch retries the same way.
 *   A protocol error (offset gap, bad base64, oversized total) or a
 *   phone refusal (`attachment_error`) fails at once, no auto-retry.
 * - **Disconnect.** In-flight + queued downloads become
 *   interrupted (bytes dropped — no resume in v1) and re-queue on
 *   reconnect.
 * - **Cache.** A set `localUri` with a file of the expected size
 *   means the bytes are already here. A stale path (file gone, or
 *   the wrong size) is cleared and the download starts. Files land
 *   as `<hubAttachmentId>.<ext>` in `filesDir/mms_attachments/`
 *   (flat, like the hub's own files), written to `.part` + renamed
 *   — a crash mid-write leaves a `.part` the render path ignores.
 *   Nothing unverified ever enters the cache.
 *
 * The frame handlers never throw: a bad chunk logs and fails its
 * download instead of killing the engine's frame loop. One [Mutex]
 * serializes the transfer state — the engine calls the frame
 * handlers, the UI calls [request]/[retry], the stall timers fire
 * from [scope], and the three never interleave. Private helpers run
 * with the mutex held; only the public entry points lock (the mutex
 * is not reentrant).
 */
@Singleton
class ClientAttachmentDownloader @Inject constructor(
    private val channel: ClientSyncChannel,
    private val attachmentDao: AttachmentDao,
    private val messageDao: MessageDao,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** One attachment the UI asked for: the wire ids + the metadata the verify needs. */
    private data class KnownDownload(
        val hubId: String,
        val localId: Long,
        val hubMessageId: String,
        val mimeType: String,
        val sizeBytes: Long,
        val attempts: Int = 0,
    )

    /** One download in flight: the bytes so far + their count + the stall timer. */
    private class ActiveDownload(
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) {
        var received: Long = 0
        var stallJob: Job? = null
        var lastProgressAt: Long = 0
    }

    /** A resolved `request()`: the row + the wire ids the request frame needs. */
    private data class Target(
        val localId: Long,
        val hubAttachmentId: Long,
        val hubMessageId: Long,
        val mimeType: String,
        val sizeBytes: Long,
        val localUri: String,
    )

    private val mutex = Mutex()

    private val _states = MutableStateFlow<Map<Long, ClientDownloadState>>(emptyMap())

    /** The download state per local attachment id (empty while nothing has been asked for). */
    val states: StateFlow<Map<Long, ClientDownloadState>> = _states.asStateFlow()

    /** Downloads the UI asked for, by hub attachment id (wire string). */
    private val known = mutableMapOf<String, KnownDownload>()

    /** Downloads with chunks in flight, by hub attachment id. */
    private val active = mutableMapOf<String, ActiveDownload>()

    /** Hub attachment ids waiting for a download slot. */
    private val queued = ArrayDeque<String>()

    /** Hub attachment ids that failed for good (or are confirmed empty) — only `retry` revives them. */
    private val failed = mutableSetOf<String>()

    /** Hub attachment ids the socket drop interrupted — re-queued on reconnect. */
    private val interrupted = mutableSetOf<String>()

    private val attachmentsDir: File get() = File(context.filesDir, MMS_ATTACHMENTS_DIR_NAME)

    /**
     * Asks for one attachment's bytes, by LOCAL attachment id.
     * Dedupes (in-flight / queued / failed are no-ops — failed ones
     * only come back via [retry]), and checks the cache first: a set
     * `localUri` with a file of the expected size means the bytes are
     * already here. A hub-mode row (no hub id) or an unknown id is a
     * no-op — the hub path never downloads.
     */
    suspend fun request(localAttachmentId: Long) {
        mutex.withLock {
            val target = lookupTarget(localAttachmentId) ?: return
            val hubId = target.hubAttachmentId.toString()
            if (hubId in active || hubId in queued || hubId in failed) return
            interrupted.remove(hubId)
            val previous = known[hubId]
            known[hubId] = KnownDownload(
                hubId = hubId,
                localId = target.localId,
                hubMessageId = target.hubMessageId.toString(),
                mimeType = target.mimeType,
                sizeBytes = target.sizeBytes,
                attempts = previous?.attempts ?: 0,
            )
            if (checkCache(target)) {
                emitState(target.localId, ClientDownloadState.Downloaded)
                return
            }
            queued.add(hubId)
            pumpLocked()
        }
    }

    /** Tap-to-retry a failed download. Unknown or not-failed ids are no-ops. */
    suspend fun retry(localAttachmentId: Long) {
        mutex.withLock {
            val hubId = known.entries.firstOrNull { it.value.localId == localAttachmentId }?.key
                ?: return
            if (hubId !in failed) return
            failed.remove(hubId)
            // A manual retry gets its own automatic retry, like a fresh request.
            known[hubId]?.let { known[hubId] = it.copy(attempts = 0) }
            queued.add(hubId)
            pumpLocked()
        }
    }

    /** One `attachment_chunk` frame. Unknown ids are late chunks — ignored. */
    suspend fun onChunk(chunk: SyncProtocol.AttachmentChunk) {
        runCatching {
            mutex.withLock { handleChunkLocked(chunk) }
        }.onFailure { Timber.w(it, "ClientAttachmentDownloader: chunk failed") }
    }

    /** One `attachment_error` frame: the phone refused — no auto-retry. */
    suspend fun onError(error: SyncProtocol.AttachmentError) {
        mutex.withLock {
            val hubId = error.attachmentId
            val wasQueued = queued.remove(hubId)
            if (!active.containsKey(hubId) && !wasQueued) return
            failLocked(hubId, interrupted = false)
        }
    }

    /** The socket dropped: in-flight + queued downloads become interrupted. */
    suspend fun onSessionEnded() {
        mutex.withLock {
            for ((hubId, state) in active) {
                clearStallTimer(state)
                interrupted.add(hubId)
                known[hubId]?.let { emitState(it.localId, ClientDownloadState.NotDownloaded) }
            }
            for (hubId in queued) interrupted.add(hubId)
            active.clear()
            queued.clear()
        }
    }

    /** Reconnected: re-queue everything the drop interrupted. */
    suspend fun onSessionResumed() {
        mutex.withLock {
            if (interrupted.isEmpty()) return
            for (hubId in interrupted) {
                if (known.containsKey(hubId)) queued.add(hubId)
            }
            interrupted.clear()
            pumpLocked()
        }
    }

    /** A different hub: its attachments replace this one's — drop every transfer. */
    suspend fun onHubChanged() {
        mutex.withLock { resetLocked() }
    }

    /** The cache was wiped (Re-sync / Unlink / mode switch): every transfer goes. */
    suspend fun onCacheWiped() {
        mutex.withLock { resetLocked() }
    }

    // -- Locked helpers (the mutex is held) ---------------------------

    /** The row + wire ids for a `request()`, or null when there is nothing to download. */
    private suspend fun lookupTarget(localAttachmentId: Long): Target? {
        val entity = attachmentDao.findById(localAttachmentId) ?: return null
        val hubAttachmentId = entity.hubAttachmentId ?: return null
        val hubMessageId = messageDao.findById(entity.messageId)?.hubMessageId ?: return null
        return Target(
            localId = localAttachmentId,
            hubAttachmentId = hubAttachmentId,
            hubMessageId = hubMessageId,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            localUri = entity.localUri,
        )
    }

    /**
     * True when the bytes are already on disk. A size-0 metadata
     * means "unknown" (imported attachments carry no size), so any
     * non-empty file counts and a 0-byte file is stale, never
     * cached. A stale path (file gone, or the wrong size) is cleared
     * — the file deleted, the row's `localUri` blanked — and the
     * download starts.
     */
    private suspend fun checkCache(target: Target): Boolean {
        val path = target.localUri
        if (path.isBlank()) return false
        val file = File(path)
        if (cachedFileOk(file, target.sizeBytes)) return true
        runCatching { file.delete() }
        attachmentDao.updateLocalUri(target.localId, "")
        return false
    }

    private fun cachedFileOk(file: File, expectedSize: Long): Boolean {
        if (!file.exists()) return false
        return if (expectedSize == 0L) file.length() > 0 else file.length() == expectedSize
    }

    /** Starts queued downloads while a slot is free (at most the cap runs at once). */
    private suspend fun pumpLocked() {
        while (active.size < MAX_CONCURRENT_DOWNLOADS && queued.isNotEmpty()) {
            val hubId = queued.removeFirst()
            val entry = known[hubId]
            if (entry == null) {
                // Unknown id (wiped mid-queue) — skip it, keep pumping.
            } else {
                startDownloadLocked(hubId, entry)
            }
        }
    }

    private suspend fun startDownloadLocked(hubId: String, entry: KnownDownload) {
        val state = ActiveDownload()
        active[hubId] = state
        resetStallTimerLocked(hubId, state)
        emitState(entry.localId, ClientDownloadState.Downloading(0, entry.sizeBytes))
        val sent = runCatching {
            channel.send(
                ClientFrames.encodeAttachmentRequest(
                    SyncProtocol.AttachmentRequest(hubId, entry.hubMessageId),
                ),
            )
        }.getOrDefault(false)
        if (!sent) {
            // Offline (or the session died between the request and
            // the send): interrupted — the reconnect resumes it.
            clearStallTimer(state)
            active.remove(hubId)
            interrupted.add(hubId)
            emitState(entry.localId, ClientDownloadState.NotDownloaded)
        }
    }

    private suspend fun handleChunkLocked(chunk: SyncProtocol.AttachmentChunk) {
        val state = active[chunk.attachmentId] ?: return
        val entry = known[chunk.attachmentId] ?: return
        val bytes = takeChunk(state, chunk)
        if (bytes == null || state.received + bytes.size > SyncProtocol.MAX_ATTACHMENT_DOWNLOAD_BYTES) {
            failLocked(chunk.attachmentId, interrupted = false)
            return
        }
        state.bytes.write(bytes)
        state.received += bytes.size
        resetStallTimerLocked(chunk.attachmentId, state)
        emitProgressLocked(state, entry, final = chunk.isFinal)
        if (chunk.isFinal) finishLocked(chunk.attachmentId, chunk.sha256, chunk.sizeBytes)
    }

    /**
     * The chunk's bytes, or null when it is not the next one
     * (chunks are sequential per attachment — a gap is a protocol
     * error) or its base64url is malformed.
     */
    private fun takeChunk(state: ActiveDownload, chunk: SyncProtocol.AttachmentChunk): ByteArray? {
        if (chunk.offset != state.received) return null
        return Base64Url.decode(chunk.dataBase64Url)
    }

    private fun emitProgressLocked(
        state: ActiveDownload,
        entry: KnownDownload,
        final: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (!final && now - state.lastProgressAt < PROGRESS_THROTTLE_MS) return
        state.lastProgressAt = now
        emitState(entry.localId, ClientDownloadState.Downloading(state.received, entry.sizeBytes))
    }

    private suspend fun finishLocked(hubId: String, sha256: String?, sizeBytes: Long?) {
        val state = active[hubId] ?: return
        val entry = known[hubId] ?: return
        clearStallTimer(state)
        active.remove(hubId)
        val data = state.bytes.toByteArray()
        if (isVerifiedEmpty(data, entry, sha256, sizeBytes)) {
            failed.add(hubId)
            emitState(entry.localId, ClientDownloadState.Empty)
        } else if (!isVerified(data, entry, sha256, sizeBytes)) {
            retryOrFailLocked(hubId)
            return
        } else if (storeDownload(entry, data)) {
            emitState(entry.localId, ClientDownloadState.Downloaded)
        } else {
            // A disk error won't heal on retry — fail, tap-to-retry.
            failed.add(hubId)
            emitState(entry.localId, ClientDownloadState.Failed)
        }
        pumpLocked()
    }

    /**
     * The final chunk's size + digest, plus the metadata
     * cross-check: when the metadata size is known (non-zero), the
     * received count must match it — the final chunk's size +
     * digest only prove the transfer wasn't corrupted in flight,
     * not that the phone sent the whole file.
     */
    private fun isVerified(
        data: ByteArray,
        entry: KnownDownload,
        sha256: String?,
        sizeBytes: Long?,
    ): Boolean {
        if (!verifyTransferBytes(data, sha256, sizeBytes)) return false
        return entry.sizeBytes <= 0 || data.size.toLong() == entry.sizeBytes
    }

    private fun isVerifiedEmpty(
        data: ByteArray,
        entry: KnownDownload,
        sha256: String?,
        sizeBytes: Long?,
    ): Boolean = data.isEmpty() && isVerified(data, entry, sha256, sizeBytes)

    /**
     * Writes the verified bytes (`.part` + rename) and points the
     * row at them. The row may have been wiped mid-download — never
     * orphan a file for it. False when the row is gone or the disk
     * write failed.
     */
    private suspend fun storeDownload(entry: KnownDownload, data: ByteArray): Boolean {
        if (attachmentDao.findById(entry.localId) == null) return false
        val file = downloadFile(entry.hubId, entry.mimeType)
        val written = runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeBytes(data)
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
            true
        }.getOrDefault(false)
        if (!written) return false
        attachmentDao.updateLocalUri(entry.localId, file.absolutePath)
        return true
    }

    /** The cache file for one attachment: `<hubAttachmentId>.<ext>` in `mms_attachments/`. */
    private fun downloadFile(hubId: String, mimeType: String): File =
        File(attachmentsDir, "$hubId.${extensionForMime(mimeType)}")

    /** A bad transfer: one automatic retry, then failed (tap-to-retry). */
    private suspend fun retryOrFailLocked(hubId: String) {
        val entry = known[hubId] ?: return
        val attempts = entry.attempts + 1
        known[hubId] = entry.copy(attempts = attempts)
        if (attempts < MAX_DOWNLOAD_ATTEMPTS) {
            queued.add(hubId)
        } else {
            failed.add(hubId)
            emitState(entry.localId, ClientDownloadState.Failed)
        }
        pumpLocked()
    }

    /** Fail a download: interrupted (silent, auto re-queue) or failed (tap-to-retry). */
    private suspend fun failLocked(hubId: String, interrupted: Boolean) {
        val state = active.remove(hubId)
        if (state != null) clearStallTimer(state)
        queued.remove(hubId)
        val entry = known[hubId] ?: return
        if (interrupted) {
            this.interrupted.add(hubId)
            emitState(entry.localId, ClientDownloadState.NotDownloaded)
        } else {
            failed.add(hubId)
            emitState(entry.localId, ClientDownloadState.Failed)
        }
        pumpLocked()
    }

    private fun resetStallTimerLocked(hubId: String, state: ActiveDownload) {
        clearStallTimer(state)
        state.stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            mutex.withLock { onStallLocked(hubId) }
        }
    }

    private fun clearStallTimer(state: ActiveDownload) {
        state.stallJob?.cancel()
        state.stallJob = null
    }

    /**
     * A stall is an interruption, not a refusal — one automatic
     * retry now (no render pass is triggered by a stall, so waiting
     * for one would strand the placeholder), then failed.
     */
    private suspend fun onStallLocked(hubId: String) {
        val state = active[hubId] ?: return
        clearStallTimer(state)
        active.remove(hubId)
        retryOrFailLocked(hubId)
    }

    private fun resetLocked() {
        for (state in active.values) clearStallTimer(state)
        active.clear()
        queued.clear()
        failed.clear()
        interrupted.clear()
        known.clear()
        _states.value = emptyMap()
    }

    private fun emitState(localId: Long, state: ClientDownloadState) {
        _states.update { it + (localId to state) }
    }

    companion object {
        /** At most this many downloads run at once; the rest queue FIFO (the desktop's cap). */
        private const val MAX_CONCURRENT_DOWNLOADS = 3

        /** No chunk for this long fails the download as stalled (the desktop's timeout). */
        private const val STALL_TIMEOUT_MS = 30_000L

        /** First try + one automatic retry (integrity mismatch or stall). */
        private const val MAX_DOWNLOAD_ATTEMPTS = 2

        /**
         * Progress emissions are throttled to one per download per
         * this long (the desktop's throttle): every emission
         * re-renders the thread, and the spinner does not need
         * per-chunk precision. The final chunk always reports.
         */
        private const val PROGRESS_THROTTLE_MS = 250L
    }
}

/**
 * Client mode C5 — the Hilt entry point for
 * [ClientAttachmentDownloader], for callers that cannot take it as a
 * constructor parameter ([ThreadViewModel] is at the
 * constructor-parameter cap). Resolved from the injected application
 * context, the same way `ClientSendManager` is reached.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClientAttachmentDownloaderEntryPoint {
    fun clientAttachmentDownloader(): ClientAttachmentDownloader
}

/** File extension for a MIME type — known types from the table, else the sanitized subtype, else `bin`. */
internal fun extensionForMime(mimeType: String): String {
    val known = MIME_EXTENSIONS[mimeType.lowercase()]
    if (known != null) return known
    val subtype = mimeType.substringAfter('/', "")
    val clean = subtype.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        .lowercase().take(10)
    return clean.ifEmpty { "bin" }
}

/** The desktop's MIME → extension table (the extension lets the system viewer pick the right app). */
private val MIME_EXTENSIONS = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/bmp" to "bmp",
    "image/svg+xml" to "svg",
    "video/mp4" to "mp4",
    "video/3gpp" to "3gp",
    "video/webm" to "webm",
    "video/quicktime" to "mov",
    "audio/3gpp" to "3gp",
    "audio/amr" to "amr",
    "audio/mpeg" to "mp3",
    "audio/ogg" to "ogg",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/mp4" to "m4a",
    "audio/webm" to "webm",
    "application/pdf" to "pdf",
    "text/plain" to "txt",
    "text/html" to "html",
    "text/vcard" to "vcf",
    "application/zip" to "zip",
)
