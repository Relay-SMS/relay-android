package com.filestech.sms.data.notes

import android.content.Context
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.NotesKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file envelope for note attachments: AES-256-GCM ([AeadCipher]) under the notes
 * master key ([NotesKeyManager]).
 *
 * File format, one file = one encryption with a random IV:
 * `[version:1][iv:12][ciphertext+tag]` — exactly `AeadCipher.encryptRaw`'s output, so a
 * ciphertext file is 29 bytes larger than its plaintext (`CIPHER_OVERHEAD_BYTES`). The DB
 * records the PLAINTEXT size (`size_bytes`); this class is where the two sizes meet.
 *
 * Encrypted files live at `<files>/notes/attachments/<uuid>` — the attachment UUID is the
 * file name, so a file is findable from its row and never collides. Writes are atomic
 * (`.tmp` + rename, same pattern as the inbound MMS writer): a crash mid-write leaves
 * either the old file or nothing, never a truncated ciphertext.
 *
 * Key hygiene: the master key is fetched as a per-call copy and wiped in a `finally` after
 * every use. A [NotesKeyManager.Failure] maps to [AppError.Crypto] (key lost/corrupt) or
 * [AppError.Storage] (key-file I/O) — never a throw, so callers can surface an
 * unreadable-attachment state.
 */
@Singleton
class NoteAttachmentCrypto @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notesKeyManager: NotesKeyManager,
    private val aead: AeadCipher,
) {

    /** Durable root for encrypted note attachment files; created on first use. */
    fun attachmentsDir(): File =
        File(File(context.filesDir, NotesKeyManager.NOTES_DIR), ATTACHMENTS_DIR)
            .apply { if (!exists()) mkdirs() }

    /** The encrypted file for the attachment with this UUID. */
    fun fileForUuid(uuid: String): File = File(attachmentsDir(), uuid)

    /** Encrypts [plaintext] with the notes master key. */
    fun encrypt(plaintext: ByteArray): Outcome<ByteArray> =
        withMasterKey { key -> aead.encryptRaw(key, plaintext) }

    /** Decrypts an [AeadCipher] blob produced by [encrypt]. */
    fun decrypt(blob: ByteArray): Outcome<ByteArray> =
        withMasterKey { key -> aead.decryptRaw(key, blob) }

    /**
     * Encrypts [plaintext] and writes the ciphertext to [target] atomically (`.tmp` +
     * rename). Returns the target file on success. On failure nothing is left behind:
     * the temp file is deleted and a pre-existing target is untouched.
     */
    fun encryptToFile(plaintext: ByteArray, target: File): Outcome<File> {
        val ciphertext = when (val r = encrypt(plaintext)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
            tmp.outputStream().use { it.write(ciphertext) }
            if (!tmp.renameTo(target)) {
                // Same-partition rename should succeed; fall back to copy + delete.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            ciphertext.wipe()
            Outcome.Success(target)
        } catch (t: Throwable) {
            Timber.w(t, "NoteAttachmentCrypto: encryptToFile failed for %s", target.name)
            runCatching { File(target.parentFile, target.name + TMP_SUFFIX).delete() }
            ciphertext.wipe()
            Outcome.Failure(AppError.Storage(t))
        }
    }

    /** Reads [file] and decrypts it. A missing file is [AppError.NotFound], not a crash. */
    fun decryptFile(file: File): Outcome<ByteArray> {
        if (!file.exists()) {
            return Outcome.Failure(AppError.NotFound("note attachment file"))
        }
        val blob = try {
            file.readBytes()
        } catch (t: Throwable) {
            return Outcome.Failure(AppError.Storage(t))
        }
        return decrypt(blob).also { blob.wipe() }
    }

    /** Best-effort delete of the file at [path] (ciphertext — no wipe needed). */
    fun deleteFileQuietly(path: String) {
        runCatching { File(path).delete() }
            .onFailure { Timber.w(it, "NoteAttachmentCrypto: could not delete %s", path) }
    }

    /**
     * Fetches a per-call copy of the master key, runs [block], wipes the copy. A key
     * failure maps to [AppError.Crypto] (lost/corrupt) or [AppError.Storage] (key I/O).
     */
    private inline fun <T> withMasterKey(block: (ByteArray) -> Outcome<T>): Outcome<T> {
        val key = try {
            notesKeyManager.getOrCreateMasterKey()
        } catch (e: NotesKeyManager.Failure) {
            return Outcome.Failure(
                when (e) {
                    is NotesKeyManager.Failure.Io -> AppError.Storage(e)
                    else -> AppError.Crypto("notes key unavailable", e)
                },
            )
        }
        return try {
            block(key)
        } finally {
            key.wipe()
        }
    }

    companion object {
        /** Subdirectory of `<files>/notes/` holding the encrypted attachment files. */
        const val ATTACHMENTS_DIR = "attachments"

        /** Suffix for the temp file an atomic write stages before renaming into place. */
        const val TMP_SUFFIX = ".tmp"

        /**
         * Ciphertext overhead per file: 1 version byte + 12 IV bytes + 16 tag bytes.
         * `size_bytes` in the DB is the plaintext size; the file on disk is this much larger.
         */
        const val CIPHER_OVERHEAD_BYTES = 1 + AeadCipher.IV_SIZE + AeadCipher.TAG_BITS / 8
    }
}
