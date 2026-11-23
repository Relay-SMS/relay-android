package com.filestech.sms.e2e

import com.filestech.sms.core.ext.canonicalPhone
import com.filestech.sms.data.local.db.dao.E2ePeerDao
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.purge.MS_PER_DAY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Peer state CRUD + probe budget bookkeeping (design §2.4, §4.3).
 *
 * One row per peer address holds the key-exchange state machine and the probe
 * budget: at most [MAX_PROBE_ATTEMPTS] attempts per [PROBE_WINDOW_MS] window, at
 * least [PROBE_MIN_INTERVAL_MS] apart. The window resets when it elapses — a peer
 * that installs Relay later is re-probed in the next window, and a NOT_RELAY peer
 * becomes probeable again.
 *
 * [canProbe] answers the BUDGET question only (window + spacing). State
 * eligibility — which states may be probed (UNKNOWN / NOT_RELAY, or ENCRYPTED for
 * a decrypt-failure re-probe) — belongs to the state machine, which reads the
 * state via [state] and decides. Splitting the two keeps the budget testable in
 * isolation and the policy in one place.
 *
 * The bundle-reply rate limit (design §2.3, at most one bundle per sender per
 * day) is persisted here too via [recordBundleReply]; the eligibility check
 * lives in [E2eProbePolicy], which reads the stored timestamp.
 *
 * Every write is a targeted DAO update touching disjoint columns (never a whole-row
 * copy): a bundle arriving between a read and a write cannot be clobbered by a
 * stale probe bookkeeping write.
 *
 * Round 42 — the address is canonicalized at this boundary: every
 * method normalizes its address to [canonicalPhone] (formatting
 * stripped, NANP country code unified — see the round-46 note
 * below) before touching the DAO. The readers and writers used to
 * disagree — the thread UI observed the normalized form while the
 * probe path wrote the raw conversation form and the handshake
 * handler wrote the carrier form — and the DAO matches exactly,
 * so a peer whose number was stored with formatting had its probe
 * writes land under a key the UI never observed: the Details
 * panel stayed on UNKNOWN no matter what happened, and the
 * safety-number sheet found no keys. One canonical key per peer,
 * whatever form each caller passes. Rows stored under a
 * non-normalized form before this change become unreachable
 * (their peer re-exchanges from UNKNOWN on the next probe); rows
 * already in E.164 form are unaffected — normalization is
 * idempotent.
 *
 * Round 46 — the round-42 key was `normalizePhone` alone, which
 * strips formatting but does NOT reunite the E.164 form
 * (`"+16108423232"`) with the national form (`"6108423232"`):
 * the bundle arrived under `"+1…"`, the UI and the send path
 * read `"610…"`, the thread stayed PROBING and the message went
 * out plain (device-proven 2026-11-23). The canonical key is now
 * [canonicalPhone], which strips the NANP country code to the
 * 10-digit form. Rows stored under E.164 forms before this
 * change become unreachable and re-establish from UNKNOWN on the
 * next probe (no migration); rows already in 10-digit national
 * form are unaffected.
 */
@Singleton
class E2ePeerRepository @Inject constructor(
    private val peerDao: E2ePeerDao,
) {

    /**
     * Canonical key for the peer table: [canonicalPhone]
     * (formatting stripped, NANP country code unified to the
     * 10-digit form). Idempotent — safe to apply to an
     * already-canonical address — and safe for an alphanumeric
     * sender, whose canonical form falls back to the trimmed raw
     * string.
     */
    private fun canonicalAddress(address: String): String = address.canonicalPhone()

    /** The peer's row, or null when no exchange has ever started with this address. */
    suspend fun getPeer(address: String): E2ePeerEntity? =
        peerDao.findByAddress(canonicalAddress(address))

    /** The peer's state; UNKNOWN when no row exists (nothing has happened yet). */
    suspend fun state(address: String): E2ePeerState =
        peerDao.findByAddress(canonicalAddress(address))?.state ?: E2ePeerState.UNKNOWN

    /**
     * Live view of one peer's row (null = no exchange has ever started).
     * The thread UI collects it: the composer placeholder, the send icon
     * and the details-panel row follow the key exchange live.
     */
    fun observePeer(address: String): Flow<E2ePeerEntity?> =
        peerDao.observeByAddress(canonicalAddress(address))

    /**
     * Live view of the verified contacts, most recently updated first
     * (Settings → Verified contacts).
     */
    fun observeVerifiedPeers(): Flow<List<E2ePeerEntity>> = peerDao.observeVerifiedPeers()

    // The live ENCRYPTED-address set ([observeEncryptedAddresses],
    // collected by the conversation list for its avatar lock badges)
    // lives below with the sync API (§9.2) — one definition, shared
    // by the list and the sync engine.

    /**
     * E2E sync (round 42) — every peer row, address order: the
     * device-link snapshot source (the safety-number + verified
     * set). One-shot, for the snapshot paths.
     */
    suspend fun allPeers(): List<E2ePeerEntity> = peerDao.allPeers()

    /**
     * E2E sync (round 42) — the live version of [allPeers]:
     * re-emits whenever any `e2e_peers` row changes, so a state
     * flip, a key arrival, or a verified toggle re-sends the full
     * set to every linked device.
     */
    fun observeAllPeers(): Flow<List<E2ePeerEntity>> = peerDao.observeAllPeers()

    /** Returns the peer's row, creating it in state UNKNOWN when it does not exist yet. */
    suspend fun getOrCreateUnknown(
        address: String,
        now: Long = System.currentTimeMillis(),
    ): E2ePeerEntity {
        val key = canonicalAddress(address)
        peerDao.insert(E2ePeerEntity(address = key, updatedAt = now))
        // The insert is IGNORE on conflict, so the row is either the one just
        // inserted or the pre-existing one — re-read to return the real row.
        return checkNotNull(peerDao.findByAddress(key)) {
            "peer row missing right after insert"
        }
    }

    /** Sets the peer's state, creating the row in that state when it does not exist yet. */
    suspend fun setState(
        address: String,
        state: E2ePeerState,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = canonicalAddress(address)
        getOrCreateUnknown(key, now)
        peerDao.updateState(key, state, now)
    }

    /** Records the peer's identity keys from a received bundle (base64, 32 bytes each). */
    suspend fun recordKeys(
        address: String,
        theirCurveKey: String,
        theirEdKey: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = canonicalAddress(address)
        getOrCreateUnknown(key, now)
        peerDao.updateKeys(key, theirCurveKey, theirEdKey, now)
    }

    /**
     * Sets (or clears, on a key change) the verified flag. Never
     * sent to the peer — the flag is a local trust marker, not
     * part of the key exchange. It IS synced to the user's own
     * linked devices over the encrypted device link (round 42),
     * so the desktop shows the same verified state as the phone.
     */
    suspend fun setVerified(
        address: String,
        verified: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = canonicalAddress(address)
        getOrCreateUnknown(key, now)
        peerDao.setVerified(key, verified, now)
    }

    /**
     * Whether the probe BUDGET allows another probe now: the window has elapsed (or
     * never started), the attempt count is under the cap, and 24 hours have passed
     * since the last probe. State eligibility is the caller's decision (see the
     * class KDoc).
     */
    suspend fun canProbe(address: String, now: Long = System.currentTimeMillis()): Boolean {
        val peer = peerDao.findByAddress(canonicalAddress(address)) ?: return true
        val windowStart = peer.windowStartedAt
        if (windowStart == null || now - windowStart >= PROBE_WINDOW_MS) return true
        if (peer.probeAttempts >= MAX_PROBE_ATTEMPTS) return false
        val lastProbe = peer.lastProbeAt
        return lastProbe == null || now - lastProbe >= PROBE_MIN_INTERVAL_MS
    }

    /**
     * Records a probe send: bumps the attempt count (resetting the window when 30
     * days have elapsed) and moves the peer to PROBING. Call AFTER the probe data
     * SMS is sent — a failed send must not consume budget.
     */
    suspend fun recordProbeSent(address: String, now: Long = System.currentTimeMillis()) {
        val key = canonicalAddress(address)
        val peer = getOrCreateUnknown(key, now)
        val windowStart = peer.windowStartedAt
        val (attempts, windowStartedAt) =
            if (windowStart == null || now - windowStart >= PROBE_WINDOW_MS) {
                1 to now
            } else {
                peer.probeAttempts + 1 to windowStart
            }
        peerDao.recordProbe(key, attempts, windowStartedAt, now, now)
        peerDao.updateState(key, E2ePeerState.PROBING, now)
    }

    /**
     * Records a bundle reply sent to this peer (design §2.3): at most one
     * bundle per sender per day. Call AFTER the bundle SMS is sent — a failed
     * send must not consume the day's reply. The caller checks the rate limit
     * first via [E2eProbePolicy.canReplyWithBundle] on the stored timestamp
     * (null when no reply was ever sent).
     */
    suspend fun recordBundleReply(address: String, now: Long = System.currentTimeMillis()) {
        val key = canonicalAddress(address)
        getOrCreateUnknown(key, now)
        peerDao.recordBundleReply(key, now, now)
    }

    /** Verified contacts, most recently updated first (Settings → Verified contacts). */
    suspend fun verifiedPeers(): List<E2ePeerEntity> = peerDao.verifiedPeers()

    /**
     * Round 43 — testing/diagnostic reset (Settings → Advanced):
     * clears the probe-budget and bundle-reply timing for ALL
     * peers (attempt counts, window starts, last-probe and
     * last-bundle-reply times) and returns every non-ENCRYPTED
     * peer to UNKNOWN, so encryption can be tested again right
     * away instead of waiting out the 24-hour bundle rate limit
     * or the 30-day probe window. Keys, sessions, verified flags
     * and ENCRYPTED states are untouched — a working pair stays
     * encrypted and verified. The reset runs on THIS phone only:
     * the partner pair's blocker is the partner's bundle rate
     * limit, so the reset must be run on that phone too.
     */
    suspend fun resetEncryptionTiming(now: Long = System.currentTimeMillis()) {
        peerDao.resetTiming(now)
    }

    /**
     * E2E sync (design §9.2) — the addresses whose peer state is
     * ENCRYPTED, as a set: the hub maps them onto the 1:1
     * conversations it syncs (the `encrypted` flag on
     * `SyncedConversation`). One-shot, for the snapshot paths.
     */
    suspend fun encryptedAddresses(): Set<String> =
        peerDao.encryptedAddresses().toSet()

    /**
     * E2E sync (design §9.2) — the live version of
     * [encryptedAddresses]: re-emits whenever any `e2e_peers` row
     * changes, so a state flip to ENCRYPTED re-emits the hub's
     * conversation list and the sync engine pushes a
     * `conversation_updated` for the flipped thread.
     */
    fun observeEncryptedAddresses(): Flow<Set<String>> =
        peerDao.observeEncryptedAddresses().map { it.toSet() }

    /** Deletes a peer's row (panic wipe / reset — its sessions are deleted separately). */
    suspend fun deletePeer(address: String) {
        peerDao.deleteByAddress(canonicalAddress(address))
    }

    companion object {
        /** Probe attempts allowed per window (design §2.4). */
        const val MAX_PROBE_ATTEMPTS = 3

        /** The probe budget window: 30 days (design §2.4). */
        const val PROBE_WINDOW_MS: Long = 30 * MS_PER_DAY

        /** Minimum spacing between two probes to the same peer: 24 hours (design §2.4). */
        const val PROBE_MIN_INTERVAL_MS: Long = MS_PER_DAY
    }
}
