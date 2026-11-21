package com.filestech.sms.e2e

import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.local.db.dao.E2eAccountDao
import com.filestech.sms.data.local.db.entity.E2eAccountEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns our Olm Account: the Curve25519 + Ed25519 identity keys and the one-time-key
 * pool (design §4.1).
 *
 * The Account is created LAZILY — on first E2E use (first probe sent or first probe
 * received), not at app install, so users who never use E2E pay nothing. It is stored
 * as a vodozemac encrypted pickle (one row in `e2e_account`), locked with the
 * Keystore-wrapped pickle key ([E2eKeyManager]).
 *
 * Lifecycle contract with [E2eCrypto]: the wrapper owns one Account. Before any
 * [E2eCrypto.unpickleAccount] call the Account is fresh (created in the wrapper's
 * constructor), so [E2eCrypto.pickleAccount] on a never-unpickled wrapper returns the
 * pickle of that fresh Account — that is how a first-run identity is persisted here,
 * with no separate "create" call. [ensureAccount] is idempotent and mutex-guarded:
 * concurrent first uses (a probe send racing a probe receive) load or create the
 * Account exactly once.
 *
 * The one-time-key pool lives INSIDE the Account pickle, so every pool change must
 * be followed by [saveAccount] — [generateOneTimeKeys] does it; the handshake path
 * must do it after an inbound session consumes a key.
 */
@Singleton
class E2eAccountManager @Inject constructor(
    private val accountDao: E2eAccountDao,
    private val crypto: E2eCrypto,
    private val keyManager: E2eKeyManager,
) {
    private val loadMutex = Mutex()

    @Volatile
    private var loaded = false

    /**
     * Loads the Account from its pickle — or creates and persists a fresh one on
     * first E2E use — and returns our identity keys. Safe to call on every E2E
     * entry point: after the first call it is a cheap in-memory read.
     */
    suspend fun ensureAccount(): IdentityKeys {
        if (loaded) return crypto.identityKeys()
        return loadMutex.withLock {
            if (loaded) {
                crypto.identityKeys()
            } else {
                loadOrCreate()
            }
        }
    }

    /** Our identity keys, loading the Account first if needed. */
    suspend fun identityKeys(): IdentityKeys = ensureAccount()

    /**
     * One unpublished one-time public key for a bundle reply, or null if the pool is
     * empty. Loads the Account first if needed. Does NOT save the Account: handing
     * out a key does not mutate the pool (the key is consumed when the peer's
     * pre-key message arrives — see [saveAccount]).
     */
    suspend fun nextOneTimeKey(): ByteArray? {
        ensureAccount()
        return crypto.nextOneTimeKey()
    }

    /** Tops the one-time-key pool back up to [ONE_TIME_KEY_POOL_SIZE] and re-saves the Account. */
    suspend fun generateOneTimeKeys() {
        ensureAccount()
        crypto.generateOneTimeKeys()
        saveAccount()
    }

    /**
     * Re-pickles the loaded Account and persists it. Call after any Account mutation
     * (one-time-key generation, an inbound session consuming a key). A no-op when
     * no Account is loaded — there is nothing in memory to save.
     */
    suspend fun saveAccount() {
        if (!loaded) return
        val key = keyManager.getOrCreatePickleKey()
        try {
            accountDao.upsert(E2eAccountEntity(pickle = crypto.pickleAccount(key)))
        } finally {
            key.wipe()
        }
    }

    /** Whether an Account row exists on disk, without loading it. */
    suspend fun hasAccount(): Boolean = accountDao.get() != null

    private suspend fun loadOrCreate(): IdentityKeys {
        val key = keyManager.getOrCreatePickleKey()
        try {
            val stored = accountDao.get()
            if (stored != null) {
                crypto.unpickleAccount(stored.pickle, key)
            } else {
                // First E2E use: the wrapper holds a fresh Account — persist its pickle.
                accountDao.upsert(E2eAccountEntity(pickle = crypto.pickleAccount(key)))
            }
            loaded = true
            return crypto.identityKeys()
        } finally {
            key.wipe()
        }
    }
}
