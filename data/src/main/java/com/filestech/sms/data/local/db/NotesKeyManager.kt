package com.filestech.sms.data.local.db

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the notes master key: a random 32-byte key that encrypts note ATTACHMENT files
 * (note text needs no key of its own — it lives in the SQLCipher database).
 *
 * File layout: `<files>/notes/master.key` — version(1) || nonce(12) || ct+tag(N).
 *
 * Mirrors [DatabaseKeyManager]: on first use a 32-byte random key is generated, wrapped
 * with the Keystore AES-GCM key alias [KeystoreManager.ALIAS_NOTES_AEAD] via [AeadCipher],
 * and persisted. Subsequent calls unwrap the blob to recover the raw key.
 *
 * One deliberate difference from [DatabaseKeyManager]: the unwrapped key is CACHED in
 * memory. The database key is unwrapped once per app start; the notes key would otherwise
 * be unwrapped once per attachment read — and the unwrap runs inside the Keystore (a
 * hardware-backed op), so a media tab showing dozens of thumbnails would pay dozens of
 * Keystore round-trips. The cache holds a copy; every caller gets its own copy to wipe
 * after use, so no caller can wipe the shared copy. [destroyKeyFile] and [clearCachedKey]
 * wipe the cache. The exposure is the same as the database key's, which SQLCipher holds
 * in memory for the app lifetime.
 *
 * Uninstall deletes the app's files AND its Keystore keys, so the notes key is gone and
 * note attachment bytes become unrecoverable — this is CORRECT per spec ("if you remove
 * the app, they go too"). The notes backup is the only recovery path, and it never depends
 * on this key (the backup blob is encrypted under the passphrase key).
 *
 * Failure mode: a lost/corrupt notes key makes attachment bytes unreadable while note TEXT
 * survives (different key hierarchy). Callers surface an unreadable-attachment state, not
 * a crash — and nothing here auto-deletes the wrapped key (audit F18: silent wipe = silent
 * data loss).
 */
@Singleton
class NotesKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
    private val aead: AeadCipher,
) {
    private val secureRandom = SecureRandom()

    private val keyDir: File by lazy {
        File(context.filesDir, NOTES_DIR).apply { if (!exists()) mkdirs() }
    }
    private val keyFile: File by lazy { File(keyDir, MASTER_KEY_FILE) }

    @Volatile
    private var cachedKey: ByteArray? = null

    sealed class Failure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
        /** The Keystore alias is gone or invalidated. Existing wrapped key cannot be recovered. */
        class KeystoreInvalidated(cause: Throwable? = null) :
            Failure("AndroidKeyStore notes alias was invalidated; note attachments unrecoverable", cause)

        /** AEAD decryption failed but the Keystore is healthy — likely file corruption. */
        class WrapCorrupted(cause: Throwable? = null) :
            Failure("wrapped notes key is corrupted on disk", cause)

        /** I/O failure while reading/writing the key blob. */
        class Io(cause: Throwable? = null) : Failure("I/O failure reading the wrapped notes key", cause)
    }

    /** Whether a wrapped notes key already exists on disk, before one is created. */
    fun hasWrappedKey(): Boolean = keyFile.exists()

    /**
     * Returns a COPY of the raw 32-byte notes master key, generating and wrapping it on
     * first call. The caller owns the returned array and must wipe it after use; wiping
     * it does not affect the cached copy.
     */
    @Throws(Failure::class)
    fun getOrCreateMasterKey(): ByteArray {
        cachedKey?.let { return it.copyOf() }
        return synchronized(this) {
            cachedKey?.let { return@synchronized it.copyOf() }
            val key = if (keyFile.exists()) unwrap() else generateAndWrap()
            cachedKey = key.copyOf()
            key
        }
    }

    /** Drops the in-memory cached key (e.g. on app lock). The wrapped key on disk survives. */
    fun clearCachedKey() {
        synchronized(this) {
            cachedKey?.wipe()
            cachedKey = null
        }
    }

    /**
     * Forcibly destroys the wrapped key file AND the cached key (used by panic-mode wipe
     * and "reset all"). Note attachment bytes become permanently unreadable.
     */
    fun destroyKeyFile() {
        clearCachedKey()
        if (keyFile.exists()) keyFile.delete()
    }

    /**
     * Single throw site for [Failure]s: keeps [generateAndWrap] and [unwrap] under the
     * throw-count limit while preserving the exact exception type and message.
     */
    private fun fail(error: Failure): Nothing = throw error

    private fun generateAndWrap(): ByteArray {
        val raw = ByteArray(AeadCipher.KEY_BYTES).also(secureRandom::nextBytes)
        val secretKey = try {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_NOTES_AEAD, allowUserIv = true)
        } catch (e: KeyPermanentlyInvalidatedException) {
            raw.wipe()
            fail(Failure.KeystoreInvalidated(e))
        }
        val wrapped = when (val r = aead.encrypt(secretKey, raw)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> {
                raw.wipe()
                fail(Failure.WrapCorrupted())
            }
        }
        try {
            keyFile.outputStream().use { it.write(wrapped) }
        } catch (e: Throwable) {
            raw.wipe()
            fail(Failure.Io(e))
        }
        return raw
    }

    private fun unwrap(): ByteArray {
        val wrapped = try {
            keyFile.readBytes()
        } catch (e: Throwable) {
            fail(Failure.Io(e))
        }
        val secretKey = try {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_NOTES_AEAD, allowUserIv = true)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.e("Notes keystore key invalidated (likely credential change on this device)")
            fail(Failure.KeystoreInvalidated(e))
        } catch (e: UserNotAuthenticatedException) {
            // We do not require user auth on the notes key. Re-throw mapped error for safety.
            fail(Failure.KeystoreInvalidated(e))
        }
        return when (val r = aead.decrypt(secretKey, wrapped)) {
            is Outcome.Success -> {
                wrapped.wipe()
                r.value
            }
            is Outcome.Failure -> {
                wrapped.wipe()
                // We DO NOT auto-delete the keyFile here (audit F18): silent wipe = silent
                // data loss. The caller surfaces an unreadable-attachment state instead.
                fail(Failure.WrapCorrupted())
            }
        }
    }

    companion object {
        /** Directory under `filesDir` holding the wrapped notes key (and, in a subdir, the files). */
        const val NOTES_DIR = "notes"

        /** Wrapped notes master key file name inside [NOTES_DIR]. */
        const val MASTER_KEY_FILE = "master.key"
    }
}
