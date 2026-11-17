package com.filestech.sms.sync.client

import com.filestech.sms.sync.SyncProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode C5 — the attachment uploader: the desktop's
 * `AttachmentUploader` (`attachments.ts`), ported to the Android
 * send path. One upload per attachment, sequential: `start` (awaits
 * `attachment_upload_ready`), stream the 64 KiB chunks (no per-chunk
 * ack — TCP is the flow control), await `attachment_upload_done`.
 *
 * The caller awaits [upload]; the acks arrive via the engine
 * ([onReady]/[onDone]/[onError]) and resolve the pending wait. A
 * disconnect fails every pending upload at once (the send fails
 * with it — no offline outbox). An ack for an unknown key/index is
 * ignored — a late ack for a finished (or timed-out) upload.
 *
 * One [Mutex] guards the pending map: the engine's ack handlers and
 * the uploading coroutine never interleave. The handlers only
 * complete deferreds — completing one resumes its waiter
 * asynchronously, so no lock ordering issue arises with the engine
 * mutex (the engine calls these while holding its own).
 */
@Singleton
class ClientAttachmentUploader @Inject constructor(
    private val channel: ClientSyncChannel,
) {

    /** An upload failure, carrying the wire code so the send can report it. */
    class UploadError(val code: String, message: String) : Exception(message)

    private enum class Phase { Ready, Done }

    private class PendingUpload(
        var phase: Phase = Phase.Ready,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val mutex = Mutex()
    private val pending = mutableMapOf<String, PendingUpload>()

    /**
     * Uploads one prepared file: start (awaits `ready`), stream the
     * 64 KiB chunks, await `done`. Throws [UploadError] on a phone
     * refusal, a timeout, or a dropped session. An empty file still
     * sends one final (empty) chunk — the phone waits for `final`
     * before it verifies.
     */
    suspend fun upload(
        clientMessageId: String,
        index: Int,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ) {
        val digest = sha256Hex(bytes)
        val key = "$clientMessageId:$index"
        val entry = PendingUpload()
        mutex.withLock { pending[key] = entry }
        try {
            val startSent = channel.send(
                ClientFrames.encodeAttachmentUploadStart(
                    SyncProtocol.AttachmentUploadStart(
                        clientMessageId = clientMessageId,
                        index = index,
                        mimeType = mimeType,
                        fileName = fileName,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = digest,
                    ),
                ),
            )
            if (!startSent) throw UploadError(NOT_CONNECTED, "Couldn't reach your phone.")
            awaitPhase(entry.ready)
            mutex.withLock { entry.phase = Phase.Done }
            streamChunks(clientMessageId, index, bytes)
            awaitPhase(entry.done)
        } finally {
            mutex.withLock { pending.remove(key) }
        }
    }

    /** One `attachment_upload_ready` frame. */
    suspend fun onReady(value: SyncProtocol.AttachmentUploadReady) {
        mutex.withLock {
            val entry = pending["${value.clientMessageId}:${value.index}"] ?: return
            if (entry.phase == Phase.Ready) entry.ready.complete(Unit)
        }
    }

    /** One `attachment_upload_done` frame. */
    suspend fun onDone(value: SyncProtocol.AttachmentUploadDone) {
        mutex.withLock {
            val entry = pending["${value.clientMessageId}:${value.index}"] ?: return
            if (entry.phase == Phase.Done) entry.done.complete(Unit)
        }
    }

    /** One `attachment_upload_error` frame: the phone refused this upload. */
    suspend fun onError(value: SyncProtocol.AttachmentUploadError) {
        mutex.withLock {
            val entry = pending["${value.clientMessageId}:${value.index}"] ?: return
            val error = UploadError(value.code, value.message)
            entry.ready.completeExceptionally(error)
            entry.done.completeExceptionally(error)
        }
    }

    /** The socket dropped: every pending upload fails at once. */
    suspend fun onSessionEnded() {
        mutex.withLock {
            for (entry in pending.values) {
                val error = UploadError(NOT_CONNECTED, "Couldn't reach your phone.")
                entry.ready.completeExceptionally(error)
                entry.done.completeExceptionally(error)
            }
            pending.clear()
        }
    }

    private suspend fun streamChunks(clientMessageId: String, index: Int, bytes: ByteArray) {
        val chunkBytes = SyncProtocol.ATTACHMENT_CHUNK_BYTES
        val chunkCount = maxOf(1, (bytes.size + chunkBytes - 1) / chunkBytes)
        for (i in 0 until chunkCount) {
            val offset = i * SyncProtocol.ATTACHMENT_CHUNK_BYTES
            val slice = bytes.copyOfRange(
                offset,
                minOf(offset + SyncProtocol.ATTACHMENT_CHUNK_BYTES, bytes.size),
            )
            val sent = channel.send(
                ClientFrames.encodeAttachmentUploadChunk(
                    SyncProtocol.AttachmentUploadChunk(
                        clientMessageId = clientMessageId,
                        index = index,
                        offset = offset.toLong(),
                        dataBase64Url = Base64Url.encode(slice),
                        isFinal = i == chunkCount - 1,
                    ),
                ),
            )
            if (!sent) throw UploadError(NOT_CONNECTED, "Couldn't reach your phone.")
        }
    }

    /** Awaits one ack phase, or throws a timeout [UploadError] after [UPLOAD_ACK_TIMEOUT_MS]. */
    private suspend fun awaitPhase(deferred: CompletableDeferred<Unit>) {
        val arrived = withTimeoutOrNull(UPLOAD_ACK_TIMEOUT_MS) { deferred.await() }
        if (arrived == null) {
            throw UploadError(TIMEOUT, "Your phone didn't answer the upload in time.")
        }
    }

    companion object {
        /** No `ready` / `done` within this long of the last frame fails the upload (the desktop's timeout). */
        private const val UPLOAD_ACK_TIMEOUT_MS = 30_000L

        /** The send never left this device (no live session). */
        const val NOT_CONNECTED = "not_connected"

        /** The phone did not answer the upload in time. */
        const val TIMEOUT = "timeout"
    }
}
