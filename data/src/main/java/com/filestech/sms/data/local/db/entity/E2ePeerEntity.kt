package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * E2E encryption (schema v22) — one row per peer address. The key-exchange state
 * machine lives here (design §4.3), keyed by normalized phone number rather than
 * conversation id: the session is with a person, and a thread can be re-created for
 * the same number.
 *
 * Probe budget (design §2.4): at most 3 attempts per 30-day window, at least 24 hours
 * apart. [probeAttempts] counts attempts in the window that started at
 * [windowStartedAt]; [E2ePeerRepository] resets both when the window elapses.
 * [lastProbeAt] is the epoch ms of the last probe sent, for the 24-hour spacing.
 *
 * Bundle-reply rate limit (design §2.3): at most one bundle per sender per day.
 * [lastBundleReplyAt] is the epoch ms of the last bundle reply sent, null when
 * none was ever sent; the probe policy reads it before answering a probe.
 * (This column is not in the §8.1 DDL — the rate limit needs a timestamp to
 * persist against, so it was added with the v22 schema.)
 */
@Entity(tableName = "e2e_peers")
data class E2ePeerEntity(
    /** Normalized phone number — the peer's identity for encryption. */
    @PrimaryKey
    @ColumnInfo(name = "address")
    val address: String,
    /** Where this peer stands in the key exchange. */
    @ColumnInfo(name = "state") val state: E2ePeerState = E2ePeerState.UNKNOWN,
    /** Their Curve25519 identity key, base64, 32 bytes. Null until a bundle arrives. */
    @ColumnInfo(name = "their_curve_key") val theirCurveKey: String? = null,
    /** Their Ed25519 identity key, base64, 32 bytes. Null until a bundle arrives. */
    @ColumnInfo(name = "their_ed_key") val theirEdKey: String? = null,
    /** True once the user compared safety numbers and marked this peer verified. Local only. */
    @ColumnInfo(name = "verified", defaultValue = "0") val verified: Boolean = false,
    /** Epoch ms of the last probe sent to this peer, null if never probed. */
    @ColumnInfo(name = "last_probe_at") val lastProbeAt: Long? = null,
    /** Probe attempts in the current 30-day window. */
    @ColumnInfo(name = "probe_attempts", defaultValue = "0") val probeAttempts: Int = 0,
    /** Epoch ms the current probe window began, null before the first probe. */
    @ColumnInfo(name = "window_started_at") val windowStartedAt: Long? = null,
    /** Epoch ms of the last bundle reply sent to this peer, null if never replied. */
    @ColumnInfo(name = "last_bundle_reply_at") val lastBundleReplyAt: Long? = null,
    /** Epoch ms of the last state/key update. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
