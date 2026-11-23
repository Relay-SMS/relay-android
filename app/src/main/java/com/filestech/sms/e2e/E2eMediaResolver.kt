package com.filestech.sms.e2e

import android.content.Context
import android.net.Uri
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.mms.RelayMediaFilename
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.e2e.E2eMediaKeyMessage.MediaKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 48 — the decrypt-on-view seam for encrypted MMS media,
 * mirroring [com.filestech.sms.domain.notes.NoteAttachmentResolver].
 *
 * Ciphertext stays at rest (in `filesDir/mms_attachments/`, or in
 * the system store for bulk-imported parts at
 * `content://mms/part/…`); the UI must never hand
 * [Attachment.localUri] to Coil, a player or a viewer directly.
 * The three read shapes, by consumer:
 * - [decryptToMemory] — images (and GIFs): decrypted bytes in
 *   memory, fed straight to Coil. Plaintext NEVER touches disk.
 * - [decryptToPlaybackFile] — video/audio + tap-to-open: players
 *   and external viewers need a file, so the plaintext is
 *   decrypted to a cache-dir file on demand. See the
 *   transient-plaintext note on [decryptToPlaybackFile].
 * - The sync path ([com.filestech.sms.system.sync.RepositorySyncMessageSource])
 *   decrypts via [decryptToMemory] and streams the plaintext to
 *   the linked device over the already-encrypted device link.
 *
 * Verification: the plaintext is checked against the key metadata
 * (SHA-256 + size) before anything renders — a carrier that
 * re-encoded the ciphertext part fails loudly instead of showing
 * garbage (the GCM tag fails first; the hash is the second check,
 * and the one that names the cause). A failure marks the message
 * failed ([MessageDao.markMediaDecryptFailed]) on first view
 * attempt, so the thread flips to the "couldn't be decrypted"
 * row — never garbage, never the ciphertext URI. No re-probe: a
 * media key that never arrived will not arrive on a re-probe.
 *
 * Pending is DERIVED, not stored: a relay-media attachment with
 * no key row is pending ([keyFor] returns null). The caller
 * checks that before decrypting; [decryptToMemory] /
 * [decryptToPlaybackFile] on a keyless attachment fail without
 * marking (there is nothing to mark failed — the key may still
 * arrive; the 24-hour prune owns that timeout).
 *
 * Key bytes are wiped after use (the copy handed to the cipher);
 * the [MediaKey] itself is short-lived, built fresh from the DAO
 * read on every call.
 */
@Singleton
class E2eMediaResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaKeys: E2eMediaKeyStore,
    private val cipher: AeadCipher,
    private val messageDao: MessageDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** The stored key for an attachment's file id, or null (pending / not a media part). */
    suspend fun keyFor(attachment: Attachment): MediaKey? {
        val fileId = RelayMediaFilename.parse(attachment.fileName) ?: return null
        return mediaKeys.get(fileId)
    }

    /** Live view of one file's key (null = no key yet) — the pending → decryptable flip. */
    fun observeKey(fileId: String): Flow<MediaKey?> = mediaKeys.observe(fileId)

    /**
     * Live views of several files' keys at once, as a map from
     * file id to key (absent = no key yet). One flow for the
     * thread wrapper: a message's attachments flip together when
     * their keys land.
     */
    fun observeKeys(fileIds: List<String>): Flow<Map<String, MediaKey?>> {
        if (fileIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyMap())
        return combine(fileIds.map { mediaKeys.observe(it) }) { keys ->
            fileIds.zip(keys).toMap()
        }
    }

    /**
     * Decrypts the attachment fully into memory. For images and
     * thumbnails (feed the bytes to Coil) and for the sync path
     * (streamed to the linked device). Plaintext never touches
     * disk here.
     *
     * Failure ([Outcome.Failure]) marks the message failed —
     * except a missing key, which is "pending", not failed (see
     * the class KDoc). Never returns ciphertext bytes.
     */
    suspend fun decryptToMemory(attachment: Attachment): Outcome<ByteArray> =
        withContext(io) { decryptVerified(attachment) }

    /**
     * Decrypts a video/audio attachment to a cache-dir file for
     * playback (and for tap-to-open in an external viewer) and
     * returns it.
     *
     * TRANSIENT-PLAINTEXT WINDOW: the plaintext file exists on
     * disk from this call until [releasePlaybackFile] wipes it.
     * A crash (or a caller that forgets to release) leaves a
     * plaintext file in `cacheDir/e2e_media_playback/` until the
     * stale-file purge (files older than 24 h are deleted
     * opportunistically on the next call) or the OS clears the
     * cache. This is the best available shape — players need a
     * file — and it is strictly narrower than storing plaintext
     * durably: the file lives in cache, never in `filesDir`, and
     * is wiped, not just deleted, on release. The thread wrapper
     * does not release on dispose (playback may outlive the
     * bubble on screen); the purge is the backstop.
     */
    suspend fun decryptToPlaybackFile(attachment: Attachment): Outcome<File> =
        withContext(io) {
            purgeStalePlaybackFiles()
            when (val decrypted = decryptVerified(attachment)) {
                is Outcome.Failure -> decrypted
                is Outcome.Success -> {
                    val plaintext = decrypted.value
                    try {
                        val dir = playbackDir()
                        val target = File(
                            dir,
                            "e2e-playback-${attachment.id}-${UUID.randomUUID().toString().take(8)}",
                        )
                        target.outputStream().use { it.write(plaintext) }
                        Outcome.Success(target)
                    } catch (t: Throwable) {
                        Timber.w(t, "E2E media: playback file write failed")
                        Outcome.Failure(AppError.Storage(t))
                    } finally {
                        plaintext.fill(0)
                    }
                }
            }
        }

    /**
     * Wipes the playback file created by [decryptToPlaybackFile]
     * (best-effort overwrite with zeros, then delete). Only files
     * inside the playback cache dir are touched — any other path
     * is ignored.
     */
    fun releasePlaybackFile(file: File) {
        val dir = runCatching { playbackDir().canonicalPath }.getOrNull() ?: return
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (!canonical.startsWith(dir + File.separator)) {
            Timber.w("E2E media: refusing to release file outside playback dir")
            return
        }
        wipeFileBestEffort(file)
    }

    /**
     * Reads the ciphertext, decrypts it under the per-file key,
     * and verifies the plaintext against the key metadata
     * (SHA-256 + size). Any failure after the key lookup marks
     * the message failed — a bad GCM tag, a hash/size mismatch,
     * or unreadable ciphertext bytes are all terminal for this
     * message (the bytes are gone or the carrier re-encoded
     * them). A missing key is NOT a failure: the message is
     * pending, and the 24-hour prune owns the timeout.
     */
    private suspend fun decryptVerified(attachment: Attachment): Outcome<ByteArray> {
        val key = keyFor(attachment)
            ?: return Outcome.Failure(AppError.Crypto("no media key for this file"))
        val ciphertext = readCiphertext(attachment)
        if (ciphertext == null) {
            markFailed(attachment)
            return Outcome.Failure(AppError.Storage())
        }
        val keyCopy = key.key.copyOf()
        try {
            return when (val decrypted = cipher.decryptRaw(keyCopy, ciphertext)) {
                is Outcome.Failure -> {
                    // Bad GCM tag (or a mangled envelope): the
                    // carrier re-encoded the part, or the key is
                    // for another file. Terminal.
                    Timber.i("E2E media: decrypt failed for attachment %d", attachment.id)
                    markFailed(attachment)
                    Outcome.Failure(AppError.Crypto("media decrypt failed"))
                }
                is Outcome.Success -> {
                    val plaintext = decrypted.value
                    if (plaintext.size.toLong() != key.sizeBytes ||
                        sha256Hex(plaintext) != key.sha256Hex
                    ) {
                        // The tag passed but the metadata
                        // disagrees — a key/file mismatch, not a
                        // transport error.
                        Timber.i("E2E media: plaintext verification failed for attachment %d", attachment.id)
                        plaintext.fill(0)
                        markFailed(attachment)
                        Outcome.Failure(AppError.Crypto("media verification failed"))
                    } else {
                        Outcome.Success(plaintext)
                    }
                }
            }
        } finally {
            keyCopy.wipe()
        }
    }

    /**
     * The ciphertext bytes: a live MMS file under
     * `filesDir/mms_attachments/`, or an imported part at
     * `content://mms/part/{id}` — the same two branches as the
     * sync path's `openAttachment`. Null when the bytes cannot
     * be read (file gone, part URI dead).
     */
    private fun readCiphertext(attachment: Attachment): ByteArray? = runCatching {
        if (attachment.localUri.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(attachment.localUri))
                ?.use { it.readBytes() }
        } else {
            File(attachment.localUri).readBytes()
        }
    }.getOrNull()

    /** Marks the carrying message failed (best effort — a DAO failure must not mask the outcome). */
    private suspend fun markFailed(attachment: Attachment) {
        runCatching { messageDao.markMediaDecryptFailed(attachment.messageId) }
            .onFailure { Timber.w(it, "E2E media: could not mark message %d failed", attachment.messageId) }
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
        }.onFailure { Timber.w(it, "E2E media: stale playback purge failed") }
    }

    /** Best-effort overwrite-with-zeros, then delete. Never throws. */
    private fun wipeFileBestEffort(file: File) {
        runCatching {
            if (file.exists() && file.isFile) {
                FileOutputStream(file, false).use { out ->
                    val zeros = ByteArray(64 * 1024)
                    var remaining = file.length()
                    while (remaining > 0) {
                        val n = minOf(zeros.size.toLong(), remaining).toInt()
                        out.write(zeros, 0, n)
                        remaining -= n
                    }
                    out.flush()
                }
            }
            file.delete()
        }.onFailure { Timber.w(it, "E2E media: could not wipe %s", file.name) }
    }

    companion object {
        /** Subdirectory of `cacheDir` holding transient plaintext playback files. */
        const val PLAYBACK_DIR = "e2e_media_playback"

        /** A playback file older than this is stale and purged on the next playback call. */
        const val PLAYBACK_STALE_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * Round 48 — the EFFECTIVE attachment: the stored row describes
 * the ciphertext part (`application/octet-stream`,
 * `relay-media-<id>.bin`, envelope size), but the bubble dispatch
 * keys off the MIME — an octet-stream part would render as a
 * generic file icon, and an encrypted voice note would miss the
 * audio bubble. Substitute the ORIGINAL mime/name/size from the
 * key metadata before dispatch; the DB row keeps the ciphertext
 * metadata, and [Attachment.localUri] still points at the
 * ciphertext file (the resolver decrypts from it — never hand it
 * to Coil or a viewer). Shared by the thread wrapper and the
 * desktop sync mapping.
 */
fun Attachment.withMediaKey(key: MediaKey): Attachment = copy(
    mimeType = key.mime,
    fileName = key.fileName ?: fileName,
    sizeBytes = key.sizeBytes,
)

/** Lowercase hex SHA-256 of [bytes] (the key metadata's hash domain). */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
