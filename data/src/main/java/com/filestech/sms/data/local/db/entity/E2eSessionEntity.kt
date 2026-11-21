package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * E2E encryption (schema v22) — one Olm session with a peer, stored as a vodozemac
 * encrypted session pickle.
 *
 * Up to 3 sessions per peer: the current session + up to 2 older fallbacks, for
 * messages encrypted with an older session that arrive late (out-of-order SMS, a
 * pre-key message that crossed a re-establishment). Decryption tries newest first.
 * Sessions do not expire; a session stays valid until the peer's identity key changes.
 *
 * The pickle is re-written after every encrypt/decrypt — the ratchet state advances
 * on each message — so [lastUsedAt] doubles as the freshness order for fallback
 * pruning ([com.filestech.sms.e2e.E2eSessionStore]).
 */
@Entity(
    tableName = "e2e_sessions",
    indices = [
        // One row per (peer, session id): a re-pickle upserts, never duplicates.
        Index(value = ["address", "session_id"], unique = true),
    ],
)
data class E2eSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The peer this session talks to (normalized phone number). */
    @ColumnInfo(name = "address") val address: String,
    /** The vodozemac session id. */
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** The vodozemac encrypted session pickle (opaque string). */
    @ColumnInfo(name = "pickle") val pickle: String,
    /** True for the session new messages encrypt with; at most one per peer. */
    @ColumnInfo(name = "is_current", defaultValue = "0") val isCurrent: Boolean = false,
    /** Epoch ms the session was created. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Epoch ms the session was last used (encrypt, decrypt, or re-pickle). */
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
)
