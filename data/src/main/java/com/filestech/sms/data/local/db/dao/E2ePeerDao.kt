package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [E2ePeerEntity] — one row per peer address, holding the key-exchange state
 * machine and the probe budget.
 *
 * All access goes through [com.filestech.sms.e2e.E2ePeerRepository], which owns the
 * probe-window bookkeeping (attempts reset when the 30-day window elapses) and the
 * get-or-create-UNKNOWN flow. Never call this DAO from the UI layer directly.
 */
@Dao
interface E2ePeerDao {

    @Query("SELECT * FROM e2e_peers WHERE address = :address LIMIT 1")
    suspend fun findByAddress(address: String): E2ePeerEntity?

    /**
     * Live view of one peer's row (null = no row yet). The thread UI
     * collects it: the composer placeholder and the send icon flip the
     * moment the key exchange completes, without reopening the thread.
     */
    @Query("SELECT * FROM e2e_peers WHERE address = :address LIMIT 1")
    fun observeByAddress(address: String): Flow<E2ePeerEntity?>

    /**
     * Live list of the addresses in one state. The conversation list
     * collects the ENCRYPTED set for its avatar lock badges.
     */
    @Query("SELECT address FROM e2e_peers WHERE state = :state")
    fun observeAddressesInState(state: E2ePeerState): Flow<List<String>>

    /** Verified contacts, most recently updated first (Settings → Verified contacts). */
    @Query("SELECT * FROM e2e_peers WHERE verified = 1 ORDER BY updated_at DESC")
    suspend fun verifiedPeers(): List<E2ePeerEntity>

    /** Live view of the verified contacts, most recently updated first (Settings). */
    @Query("SELECT * FROM e2e_peers WHERE verified = 1 ORDER BY updated_at DESC")
    fun observeVerifiedPeers(): Flow<List<E2ePeerEntity>>

    /**
     * E2E sync (§9.2) — the addresses whose peer state is ENCRYPTED:
     * the hub maps them onto the 1:1 conversations it syncs (the
     * `encrypted` flag on `SyncedConversation`). The state is stored
     * as its enum name ([MessageEnumConverters.e2ePeerStateToRaw]),
     * so the literal matches the stored value.
     */
    @Query("SELECT address FROM e2e_peers WHERE state = 'ENCRYPTED'")
    suspend fun encryptedAddresses(): List<String>

    /**
     * E2E sync (§9.2) — the live version of [encryptedAddresses]:
     * re-emits whenever any `e2e_peers` row changes, so a state flip
     * to ENCRYPTED re-emits the hub's conversation list and the
     * engine's whole-DTO diff pushes a `conversation_updated` (the
     * desktop re-renders the row badge + composer placeholder). Room
     * invalidates on any write to the table, not just ENCRYPTED
     * flips — the diff drops the no-change emissions.
     */
    @Query("SELECT address FROM e2e_peers WHERE state = 'ENCRYPTED'")
    fun observeEncryptedAddresses(): Flow<List<String>>

    /**
     * E2E sync (round 42) — every peer row, address order: the
     * device-link snapshot source (the safety-number + verified
     * set). A stable order keeps the frame stable.
     */
    @Query("SELECT * FROM e2e_peers ORDER BY address ASC")
    suspend fun allPeers(): List<E2ePeerEntity>

    /**
     * E2E sync (round 42) — the live version of [allPeers]:
     * re-emits whenever any `e2e_peers` row changes, so a state
     * flip, a key arrival, or a verified toggle re-sends the full
     * set to every linked device. Room invalidates on any write
     * to the table — the collector dedupes the no-change
     * emissions.
     */
    @Query("SELECT * FROM e2e_peers ORDER BY address ASC")
    fun observeAllPeers(): Flow<List<E2ePeerEntity>>

    /**
     * Inserts a peer row, ignoring the insert if the address already has one —
     * the get-or-create path, safe under concurrent first contact.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(peer: E2ePeerEntity): Long

    // Deliberately no @Update: every write is a targeted UPDATE touching disjoint
    // columns, so a bundle arriving between a read and a write cannot be clobbered
    // by a stale whole-row copy (state + keys are written by different paths).
    @Query("UPDATE e2e_peers SET state = :state, updated_at = :updatedAt WHERE address = :address")
    suspend fun updateState(address: String, state: E2ePeerState, updatedAt: Long)

    /** Records the peer's identity keys from a received bundle. */
    @Query(
        """
        UPDATE e2e_peers
           SET their_curve_key = :curveKey, their_ed_key = :edKey, updated_at = :updatedAt
         WHERE address = :address
        """,
    )
    suspend fun updateKeys(address: String, curveKey: String, edKey: String, updatedAt: Long)

    @Query("UPDATE e2e_peers SET verified = :verified, updated_at = :updatedAt WHERE address = :address")
    suspend fun setVerified(address: String, verified: Boolean, updatedAt: Long)

    /**
     * Round 48 — records that this peer declared it handles
     * encrypted media (the 0x03 capability packet). Targeted
     * UPDATE, like the other peer writes: a bundle arriving
     * between a read and a write cannot be clobbered.
     */
    @Query("UPDATE e2e_peers SET media_capable = :mediaCapable, updated_at = :updatedAt WHERE address = :address")
    suspend fun setMediaCapable(address: String, mediaCapable: Boolean, updatedAt: Long)

    /** Round 48 — records that our declaration went out to this peer (radio accepted it). */
    @Query("UPDATE e2e_peers SET media_announced = :announced, updated_at = :updatedAt WHERE address = :address")
    suspend fun setMediaAnnounced(address: String, announced: Boolean, updatedAt: Long)

    /**
     * Round 48 — records the channel our outbound handshake
     * packets provably used for this peer (`DATA_SMS` /
     * `TEXT_SMS`, the `HandshakeChannel` enum names stored raw).
     * Null clears it (unused today — recorded channels stay).
     */
    @Query("UPDATE e2e_peers SET handshake_channel = :channel, updated_at = :updatedAt WHERE address = :address")
    suspend fun setHandshakeChannel(address: String, channel: String?, updatedAt: Long)

    /**
     * Records a probe send: the attempt count, the window the attempt belongs to, and
     * the send time. The caller ([com.filestech.sms.e2e.E2ePeerRepository]) computes
     * the new values — including the window reset when 30 days have elapsed.
     */
    @Query(
        """
        UPDATE e2e_peers
           SET probe_attempts = :attempts, window_started_at = :windowStartedAt,
               last_probe_at = :lastProbeAt, updated_at = :updatedAt
         WHERE address = :address
        """,
    )
    suspend fun recordProbe(
        address: String,
        attempts: Int,
        windowStartedAt: Long,
        lastProbeAt: Long,
        updatedAt: Long,
    )

    /**
     * Records a bundle reply sent to this sender (design §2.3): at most one
     * bundle per sender per day. The caller checks the rate limit first (the
     * probe policy reads the stored timestamp); this only persists the send
     * time.
     */
    @Query(
        """
        UPDATE e2e_peers
           SET last_bundle_reply_at = :lastBundleReplyAt, updated_at = :updatedAt
         WHERE address = :address
        """,
    )
    suspend fun recordBundleReply(address: String, lastBundleReplyAt: Long, updatedAt: Long)

    /**
     * Round 43 — testing/diagnostic reset (Settings → Advanced):
     * clears the probe-budget and bundle-reply timing for EVERY
     * peer (attempt counts, window starts, last-probe and
     * last-bundle-reply times) and returns every non-ENCRYPTED
     * peer to UNKNOWN, so encryption can be tested again right
     * away instead of waiting out the 24-hour bundle rate limit
     * or the 30-day probe window. Keys, the verified flag and
     * ENCRYPTED states are untouched — a working pair stays
     * encrypted and verified. One statement, so the reset is
     * atomic: no probe or bundle write can land half-way
     * through. The state column stores the enum name (see
     * [E2ePeerState]), so the CASE compares names, like
     * [encryptedAddresses].
     */
    @Query(
        """
        UPDATE e2e_peers
           SET probe_attempts = 0, window_started_at = NULL,
               last_probe_at = NULL, last_bundle_reply_at = NULL,
               state = CASE WHEN state = 'ENCRYPTED' THEN 'ENCRYPTED' ELSE 'UNKNOWN' END,
               updated_at = :updatedAt
        """,
    )
    suspend fun resetTiming(updatedAt: Long)

    /** Deletes a peer's row (panic wipe / reset — its sessions are deleted separately). */
    @Query("DELETE FROM e2e_peers WHERE address = :address")
    suspend fun deleteByAddress(address: String)
}
