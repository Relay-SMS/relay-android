package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * E2E encryption (schema v23) — one row per peer address. The key-exchange state
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
 *
 * Round 48 (schema v23) — media capability (design §2.7): [mediaCapable]
 * says the peer declared it handles encrypted media; [mediaAnnounced]
 * says WE already sent our declaration to this peer; [handshakeChannel]
 * is the channel our outbound handshake packets provably used
 * (`DATA_SMS` / `TEXT_SMS`, null when the handshake predates round 48
 * and no channel was ever recorded). The channel is a raw String, not
 * the `HandshakeChannel` enum: that enum lives in `:app` and this
 * entity in `:data`, and the repository maps between the two.
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
    /**
     * True once the user compared safety numbers and marked this peer
     * verified. Never sent to the peer; synced to the user's own
     * linked devices over the encrypted device link (round 42).
     */
    @ColumnInfo(name = "verified", defaultValue = "0") val verified: Boolean = false,
    /** Epoch ms of the last probe sent to this peer, null if never probed. */
    @ColumnInfo(name = "last_probe_at") val lastProbeAt: Long? = null,
    /** Probe attempts in the current 30-day window. */
    @ColumnInfo(name = "probe_attempts", defaultValue = "0") val probeAttempts: Int = 0,
    /** Epoch ms the current probe window began, null before the first probe. */
    @ColumnInfo(name = "window_started_at") val windowStartedAt: Long? = null,
    /** Epoch ms of the last bundle reply sent to this peer, null if never replied. */
    @ColumnInfo(name = "last_bundle_reply_at") val lastBundleReplyAt: Long? = null,
    /**
     * Round 48 — true once this peer's phone declared it handles
     * encrypted media (the 0x03 capability packet). False by
     * default: an old build never declares, so media to it stays
     * plain. Set only by the declaration receive path, never by
     * the sender guessing.
     */
    @ColumnInfo(name = "media_capable", defaultValue = "0") val mediaCapable: Boolean = false,
    /**
     * Round 48 — true once WE sent our media-capability
     * declaration to this peer (and the radio accepted it). The
     * thread-open hook sends the declaration exactly once per
     * peer; a failed send leaves this false so the next
     * thread-open retries. Set only after a successful send.
     */
    @ColumnInfo(name = "media_announced", defaultValue = "0") val mediaAnnounced: Boolean = false,
    /**
     * Round 48 — the channel our outbound handshake packets
     * provably used for this peer: `DATA_SMS` or `TEXT_SMS`
     * (the `HandshakeChannel` enum names, stored raw — the enum
     * lives in `:app`). Recorded when a bundle arrives (our probe
     * reached them on that channel) and when a probe arrives
     * while not yet ENCRYPTED (our bundle reply goes out on that
     * channel, and the session establishment proves it arrived).
     * Null when the handshake predates round 48: the declaration
     * then goes out on BOTH channels, once.
     */
    @ColumnInfo(name = "handshake_channel") val handshakeChannel: String? = null,
    /** Epoch ms of the last state/key update. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
