package com.filestech.sms.e2e

import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.ext.canonicalPhone
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
 *
 * Round 46 — every address is canonicalized with [canonicalPhone]
 * before touching the DAO (formatting stripped, NANP country code
 * unified to the 10-digit form), the same key [E2ePeerRepository]
 * uses. The store used to key on the raw address, so a session
 * written under the carrier E.164 form (`"+1…"`) was invisible to
 * the send path reading the conversation's national form
 * (`"610…"`) — the message went out plain (device-proven
 * 2026-11-23). Sessions stored under a non-canonical form before
 * this change become unreachable; the peer re-establishes from
 * UNKNOWN on the next probe (no migration).
 */
@Singleton
class E2eSessionStore @Inject constructor(
    private val sessionDao: E2eSessionDao,
    private val crypto: E2eCrypto,
    private val keyManager: E2eKeyManager,
) {

    /**
     * Canonical key for the session table: [canonicalPhone]
     * (formatting stripped, NANP country code unified to the
     * 10-digit form). Idempotent — safe to apply to an
     * already-canonical address.
     */
    private fun canonicalAddress(address: String): String = address.canonicalPhone()

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
            val canonical = canonicalAddress(address)
            sessionDao.clearCurrent(canonical)
            val existing = sessionDao.findByAddressAndSessionId(canonical, sessionId)
            if (existing != null) {
                sessionDao.update(
                    existing.copy(pickle = pickle, isCurrent = true, lastUsedAt = now),
                )
            } else {
                sessionDao.insert(
                    E2eSessionEntity(
                        address = canonical,
                        sessionId = sessionId,
                        pickle = pickle,
                        isCurrent = true,
                        createdAt = now,
                        lastUsedAt = now,
                    ),
                )
            }
            prune(canonical)
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
            val canonical = canonicalAddress(address)
            val existing = sessionDao.findByAddressAndSessionId(canonical, sessionId)
            if (existing != null) {
                sessionDao.updatePickle(canonical, sessionId, pickle, now)
            } else {
                Timber.w("E2E: refreshing an unstored session; inserting as fallback")
                sessionDao.insert(
                    E2eSessionEntity(
                        address = canonical,
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
        val stored = sessionDao.findByAddressAndSessionId(canonicalAddress(address), sessionId)
            ?: return false
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
        sessionDao.currentSession(canonicalAddress(address))?.sessionId

    /**
     * The peer's session ids, current first, then newest-used first — the order
     * decryption tries them in.
     */
    suspend fun sessionIdsNewestFirst(address: String): List<String> =
        sessionDao.sessionsForPeer(canonicalAddress(address)).map { it.sessionId }

    /** Makes an existing session the current one (previous current becomes a fallback). */
    suspend fun markCurrent(address: String, sessionId: String) {
        val canonical = canonicalAddress(address)
        sessionDao.clearCurrent(canonical)
        sessionDao.markCurrent(canonical, sessionId)
    }

    /** How many sessions are stored for a peer (diagnostics / tests). */
    suspend fun sessionsCount(address: String): Int =
        sessionDao.countForPeer(canonicalAddress(address))

    /** Deletes all of a peer's sessions (panic wipe / reset). */
    suspend fun deleteAllForPeer(address: String): Int =
        sessionDao.deleteByAddress(canonicalAddress(address))

    /**
     * Drops the peer's oldest fallbacks beyond the cap; the current session is never pruned.
     * Takes the already-canonical key — callers canonicalize before calling.
     */
    private suspend fun prune(address: String) {
        sessionDao.pruneFallbacks(address, MAX_SESSIONS_PER_PEER - 1)
    }

    companion object {
        /** Current session + fallbacks, per peer (design §4.2). */
        const val MAX_SESSIONS_PER_PEER = 3
    }
}
