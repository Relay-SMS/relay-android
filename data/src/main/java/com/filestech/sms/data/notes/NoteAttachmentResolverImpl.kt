package com.filestech.sms.data.notes

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The decrypt-on-read seam for note attachments (see [NoteAttachmentResolver] for the
 * contract and the failure mode).
 *
 * Playback files live under `cacheDir/notes_playback/` — cache, never `filesDir`, so the
 * OS may purge them and they are never backed up. Each call mints a fresh file
 * (`note-playback-<attachmentId>-<random8>`); stale files (older than
 * [PLAYBACK_STALE_AGE_MS]) are deleted opportunistically on every call, so a crash that
 * strands a plaintext file does not leave it there forever.
 */
@Singleton
class NoteAttachmentResolverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: NoteAttachmentCrypto,
    @IoDispatcher private val io: CoroutineDispatcher,
) : NoteAttachmentResolver {

    override suspend fun decryptToMemory(attachment: Attachment): Outcome<ByteArray> =
        withContext(io) {
            crypto.decryptFile(File(attachment.localUri))
        }

    override suspend fun decryptToPlaybackFile(attachment: Attachment): Outcome<File> =
        withContext(io) {
            purgeStalePlaybackFiles()
            when (val decrypted = crypto.decryptFile(File(attachment.localUri))) {
                is Outcome.Failure -> decrypted
                is Outcome.Success -> {
                    val plaintext = decrypted.value
                    try {
                        val dir = playbackDir()
                        val target = File(
                            dir,
                            "note-playback-${attachment.id}-${UUID.randomUUID().toString().take(8)}",
                        )
                        target.outputStream().use { it.write(plaintext) }
                        Outcome.Success(target)
                    } catch (t: Throwable) {
                        Timber.w(t, "NoteAttachmentResolver: playback file write failed")
                        Outcome.Failure(AppError.Storage(t))
                    } finally {
                        plaintext.fill(0)
                    }
                }
            }
        }

    override fun releasePlaybackFile(file: File) {
        // Only touch files inside the playback cache dir — never wipe an arbitrary path
        // a caller hands us.
        val dir = runCatching { playbackDir().canonicalPath }.getOrNull() ?: return
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (!canonical.startsWith(dir + File.separator)) {
            Timber.w("NoteAttachmentResolver: refusing to release file outside playback dir")
            return
        }
        wipeFileBestEffort(file)
    }

    override suspend fun decryptToStream(
        attachment: Attachment,
        output: OutputStream,
    ): Outcome<Unit> = withContext(io) {
        when (val decrypted = crypto.decryptFile(File(attachment.localUri))) {
            is Outcome.Failure -> decrypted
            is Outcome.Success -> {
                val plaintext = decrypted.value
                try {
                    // AES-GCM authenticates the whole blob before any plaintext may be
                    // trusted, so the tag was already verified inside `decryptFile` — the
                    // chunking below only bounds the write-out, it is not streaming
                    // decryption.
                    var offset = 0
                    while (offset < plaintext.size) {
                        val end = minOf(offset + STREAM_CHUNK_BYTES, plaintext.size)
                        output.write(plaintext, offset, end - offset)
                        offset = end
                    }
                    output.flush()
                    Outcome.Success(Unit)
                } catch (t: Throwable) {
                    Timber.w(t, "NoteAttachmentResolver: decryptToStream write failed")
                    Outcome.Failure(AppError.Storage(t))
                } finally {
                    plaintext.fill(0)
                }
            }
        }
    }

    private fun playbackDir(): File =
        File(context.cacheDir, PLAYBACK_DIR).apply { if (!exists()) mkdirs() }

    /** Best-effort delete of playback files older than [PLAYBACK_STALE_AGE_MS]. */
    private fun purgeStalePlaybackFiles() {
        val dir = File(context.cacheDir, PLAYBACK_DIR)
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - PLAYBACK_STALE_AGE_MS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    wipeFileBestEffort(file)
                }
            }
        }.onFailure { Timber.w(it, "NoteAttachmentResolver: stale playback purge failed") }
    }

    /** Best-effort overwrite-with-zeros, then delete. Never throws. */
    private fun wipeFileBestEffort(file: File) {
        runCatching {
            if (file.exists() && file.isFile) {
                FileOutputStreamWiper.wipe(file)
            }
            file.delete()
        }.onFailure { Timber.w(it, "NoteAttachmentResolver: could not wipe %s", file.name) }
    }

    companion object {
        /** Subdirectory of `cacheDir` holding transient plaintext playback files. */
        const val PLAYBACK_DIR = "notes_playback"

        /** A playback file older than this is stale and purged on the next playback call. */
        const val PLAYBACK_STALE_AGE_MS = 24 * 60 * 60 * 1000L

        /** Write-out chunk size for [decryptToStream]. */
        private const val STREAM_CHUNK_BYTES = 64 * 1024
    }
}

/**
 * Overwrites a file with zeros (best effort — flash wear-levelling may keep the old blocks
 * recoverable to forensic tools, which is why the plaintext window is documented on the
 * interface rather than claimed away).
 */
private object FileOutputStreamWiper {
    fun wipe(file: File) {
        val length = file.length()
        if (length <= 0L) return
        FileOutputStream(file, false).use { out ->
            val zeros = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = minOf(zeros.size.toLong(), remaining).toInt()
                out.write(zeros, 0, n)
                remaining -= n
            }
            out.flush()
        }
    }
}
