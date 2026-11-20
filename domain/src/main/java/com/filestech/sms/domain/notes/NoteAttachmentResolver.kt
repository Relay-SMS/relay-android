package com.filestech.sms.domain.notes

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import java.io.File
import java.io.OutputStream

/**
 * The decrypt-on-read seam for note attachments. Note attachment files on disk are
 * ENCRYPTED (AES-256-GCM under the notes master key); the UI must never hand
 * `Attachment.localUri` to Coil, a player or a share intent directly — ciphertext is not an
 * image, and a plaintext file left on disk would weaken the encryption claim.
 *
 * The three read shapes, by consumer:
 * - [decryptToMemory] — images (and thumbnails): decrypted bytes in memory, fed straight
 *   to Coil. Plaintext NEVER touches disk here.
 * - [decryptToPlaybackFile] — video/audio: players need a file/URI, so the plaintext is
 *   decrypted to a cache-dir file on demand. The caller MUST call [releasePlaybackFile]
 *   when playback closes; the file is wiped then (best effort). See the transient-plaintext
 *   note on [decryptToPlaybackFile].
 * - [decryptToStream] — "Save a copy" (SAF) and the notes backup export: decrypted bytes
 *   written to the caller's stream. The exported copy is plaintext, which is correct —
 *   the user explicitly exported it (the backup re-encrypts the whole payload under the
 *   passphrase key).
 *
 * Failure mode: if the notes key is lost or corrupt, or a file is missing/tampered with,
 * every function returns `Outcome.Failure` ([AppError.Crypto] / [AppError.NotFound] /
 * [AppError.Storage]) — the UI surfaces an unreadable-attachment state, never a crash.
 * Note TEXT survives a notes-key loss (different key hierarchy); only attachment bytes
 * become unreadable.
 */
interface NoteAttachmentResolver {

    /**
     * Decrypts the attachment fully into memory. For images and thumbnails (feed the
     * bytes to Coil), and for the notes backup export (which base64s them into the
     * payload). Plaintext never touches disk.
     */
    suspend fun decryptToMemory(attachment: Attachment): Outcome<ByteArray>

    /**
     * Decrypts a video/audio attachment to a cache-dir file for playback and returns it.
     *
     * TRANSIENT-PLAINTEXT WINDOW: the plaintext file exists on disk from this call until
     * [releasePlaybackFile] wipes it. A crash (or a caller that forgets to release) leaves
     * a plaintext file in `cacheDir/notes_playback/` until the stale-file purge (files
     * older than 24 h are deleted opportunistically on the next call) or the OS clears
     * the cache. This is the best available shape — players need a file — and it is
     * strictly narrower than storing plaintext durably: the file lives in cache, never in
     * `filesDir`, and is wiped, not just deleted, on release.
     *
     * The file must NOT be shared outside the app without going through the FileProvider
     * (the UI layer owns the `notes_playback` cache-path entry).
     */
    suspend fun decryptToPlaybackFile(attachment: Attachment): Outcome<File>

    /**
     * Wipes the playback file created by [decryptToPlaybackFile] (best-effort overwrite
     * with zeros, then delete). Only files inside the playback cache dir are touched —
     * any other path is ignored.
     */
    fun releasePlaybackFile(file: File)

    /**
     * Decrypts the attachment and writes the plaintext to [output] in chunks. The stream
     * is flushed but NOT closed — the caller owns it.
     *
     * Note on "streaming": AES-GCM authenticates the WHOLE blob before any plaintext may
     * be trusted, so the tag is verified over the full ciphertext in memory first and
     * only then are bytes written out. The chunking applies to the write-out, not to the
     * decryption — releasing plaintext before the tag verifies would defeat the
     * authentication.
     */
    suspend fun decryptToStream(attachment: Attachment, output: OutputStream): Outcome<Unit>
}
