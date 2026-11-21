package com.filestech.sms.e2e

import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.local.db.dao.E2eSessionDao
import com.filestech.sms.data.local.db.entity.E2eSessionEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Olm session pickles (design §4.2).
 *
 * Sessions are keyed by peer address, up to [MAX_SESSIONS_PER_PEER] per peer: the
 * current session + up to 2 older fallbacks, for messages encrypted with an older
 * session that arrive late. Decryption tries newest first
 * ([sessionIdsNewestFirst]). Sessions are pickled after every encrypt/decrypt — the
 * ratchet state advances on each message — so the send/receive path calls
 * [refreshSession] after every use; the pickle write is part of the send/receive
 * flow, not a background flush.
 *
 * The pickles are encrypted with the same Keystore-wrapped pickle key as the Account
 * ([E2eKeyManager]). A pickle that fails to unpickle (corrupt row) is logged and
 * reported as "not loaded" — never thrown: a corrupt fallback must not break the
 * thread, and the current session can still be re-established by re-probing.
 */
@Singleton
class E2eSessionStore @Inject constructor(
    private val sessionDao: E2eSessionDao,
    private val crypto: E2eCrypto,
    private val keyManager: E2eKeyManager,
) {

    /**
     * Persists a NEW session as the peer's current session (previous current becomes
     * a fallback), then prunes to [MAX_SESSIONS_PER_PEER]. Called after
     * `createOutboundSession` / `createInboundSession`.
     */
    suspend fun saveNewSession(
        address: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = keyManager.getOrCreatePickleKey()
        try {
            val pickle = crypto.pickleSession(sessionId, key)
            sessionDao.clearCurrent(address)
            val existing = sessionDao.findByAddressAndSessionId(address, sessionId)
            if (existing != null) {
                sessionDao.update(
                    existing.copy(pickle = pickle, isCurrent = true, lastUsedAt = now),
                )
            } else {
                sessionDao.insert(
                    E2eSessionEntity(
                        address = address,
                        sessionId = sessionId,
                        pickle = pickle,
                        isCurrent = true,
                        createdAt = now,
                        lastUsedAt = now,
                    ),
                )
            }
            prune(address)
        } finally {
            key.wipe()
        }
    }

    /**
     * Re-pickles a live session after an encrypt/decrypt advanced its ratchet,
     * keeping its current/fallback flag and touching its use time. If the session
     * was never stored (caller bug — the ratchet state must never be lost), it is
     * inserted as a fallback rather than dropped.
     */
    suspend fun refreshSession(
        address: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = keyManager.getOrCreatePickleKey()
        try {
            val pickle = crypto.pickleSession(sessionId, key)
            val existing = sessionDao.findByAddressAndSessionId(address, sessionId)
            if (existing != null) {
                sessionDao.updatePickle(address, sessionId, pickle, now)
            } else {
                Timber.w("E2E: refreshing an unstored session; inserting as fallback")
                sessionDao.insert(
                    E2eSessionEntity(
                        address = address,
                        sessionId = sessionId,
                        pickle = pickle,
                        isCurrent = false,
                        createdAt = now,
                        lastUsedAt = now,
                    ),
                )
            }
        } finally {
            key.wipe()
        }
    }

    /**
     * Loads a stored session into the wrapper. Returns true when the session is live
     * afterwards (already loaded, or unpickled); false when there is no stored
     * pickle or the pickle fails to unpickle.
     */
    suspend fun loadSession(address: String, sessionId: String): Boolean {
        if (crypto.hasSession(sessionId)) return true
        val stored = sessionDao.findByAddressAndSessionId(address, sessionId) ?: return false
        val key = keyManager.getOrCreatePickleKey()
        try {
            crypto.unpickleSession(stored.pickle, key)
            return true
        } catch (e: E2eCryptoException) {
            Timber.w(e, "E2E: session pickle failed to unpickle (session %s)", sessionId)
            return false
        } finally {
            key.wipe()
        }
    }

    /** The peer's current session id, or null when no session exists yet. */
    suspend fun currentSessionId(address: String): String? =
        sessionDao.currentSession(address)?.sessionId

    /**
     * The peer's session ids, current first, then newest-used first — the order
     * decryption tries them in.
     */
    suspend fun sessionIdsNewestFirst(address: String): List<String> =
        sessionDao.sessionsForPeer(address).map { it.sessionId }

    /** Makes an existing session the current one (previous current becomes a fallback). */
    suspend fun markCurrent(address: String, sessionId: String) {
        sessionDao.clearCurrent(address)
        sessionDao.markCurrent(address, sessionId)
    }

    /** How many sessions are stored for a peer (diagnostics / tests). */
    suspend fun sessionsCount(address: String): Int = sessionDao.countForPeer(address)

    /** Deletes all of a peer's sessions (panic wipe / reset). */
    suspend fun deleteAllForPeer(address: String): Int = sessionDao.deleteByAddress(address)

    /** Drops the peer's oldest fallbacks beyond the cap; the current session is never pruned. */
    private suspend fun prune(address: String) {
        sessionDao.pruneFallbacks(address, MAX_SESSIONS_PER_PEER - 1)
    }

    companion object {
        /** Current session + fallbacks, per peer (design §4.2). */
        const val MAX_SESSIONS_PER_PEER = 3
    }
}
