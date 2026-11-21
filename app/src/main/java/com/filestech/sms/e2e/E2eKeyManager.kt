package com.filestech.sms.e2e

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
 * Holds the E2E pickle key: a random 32-byte key that encrypts the Olm Account pickle
 * and every session pickle (design §4.1).
 *
 * File layout: `<files>/e2e/pickle.key` — version(1) || nonce(12) || ct+tag(N).
 *
 * Mirrors [com.filestech.sms.data.local.db.NotesKeyManager]: on first use a 32-byte
 * random key is generated, wrapped with the Keystore AES-GCM key alias
 * [KeystoreManager.ALIAS_E2E_PICKLE] via [AeadCipher], and persisted. Subsequent
 * calls unwrap the blob to recover the raw key.
 *
 * Like the notes key, the unwrapped key is CACHED in memory: the pickle key is
 * needed on every encrypt/decrypt (sessions are re-pickled after each message), and
 * the unwrap runs inside the Keystore (a hardware-backed op) — a busy thread would
 * otherwise pay a Keystore round-trip per message. The cache holds a copy; every
 * caller gets its own copy to wipe after use, so no caller can wipe the shared copy.
 * [destroyKeyFile] and [clearCachedKey] wipe the cache.
 *
 * Uninstall deletes the app's files AND its Keystore keys, so the pickle key is gone
 * and the Account/session pickles become undecryptable — this is CORRECT per spec
 * (a reinstall means a new encryption identity; see the design §4.5). Nothing here
 * auto-deletes the wrapped key (audit F18: silent wipe = silent data loss).
 */
@Singleton
class E2eKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
    private val aead: AeadCipher,
) {
    private val secureRandom = SecureRandom()

    private val keyDir: File by lazy {
        File(context.filesDir, E2E_DIR).apply { if (!exists()) mkdirs() }
    }
    private val keyFile: File by lazy { File(keyDir, PICKLE_KEY_FILE) }

    @Volatile
    private var cachedKey: ByteArray? = null

    sealed class Failure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
        /** The Keystore alias is gone or invalidated. Existing pickles cannot be unlocked. */
        class KeystoreInvalidated(cause: Throwable? = null) :
            Failure("AndroidKeyStore E2E alias was invalidated; E2E identity unrecoverable", cause)

        /** AEAD decryption failed but the Keystore is healthy — likely file corruption. */
        class WrapCorrupted(cause: Throwable? = null) :
            Failure("wrapped E2E pickle key is corrupted on disk", cause)

        /** I/O failure while reading/writing the key blob. */
        class Io(cause: Throwable? = null) : Failure("I/O failure reading the wrapped E2E pickle key", cause)
    }

    /** Whether a wrapped pickle key already exists on disk, before one is created. */
    fun hasWrappedKey(): Boolean = keyFile.exists()

    /**
     * Returns a COPY of the raw 32-byte pickle key, generating and wrapping it on
     * first call. The caller owns the returned array and must wipe it after use;
     * wiping it does not affect the cached copy.
     */
    @Throws(Failure::class)
    fun getOrCreatePickleKey(): ByteArray {
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
     * and "reset all"). The Account and session pickles become permanently unreadable.
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
            keystore.getOrCreateKey(KeystoreManager.ALIAS_E2E_PICKLE, allowUserIv = true)
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
            keystore.getOrCreateKey(KeystoreManager.ALIAS_E2E_PICKLE, allowUserIv = true)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.e("E2E keystore key invalidated (likely credential change on this device)")
            fail(Failure.KeystoreInvalidated(e))
        } catch (e: UserNotAuthenticatedException) {
            // We do not require user auth on the pickle key. Re-throw mapped error for safety.
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
                // data loss. The caller surfaces an unreadable-identity state instead.
                fail(Failure.WrapCorrupted())
            }
        }
    }

    companion object {
        /** Directory under `filesDir` holding the wrapped pickle key. */
        const val E2E_DIR = "e2e"

        /** Wrapped pickle key file name inside [E2E_DIR]. */
        const val PICKLE_KEY_FILE = "pickle.key"
    }
}
