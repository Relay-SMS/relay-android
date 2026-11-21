package com.filestech.sms.e2e

/**
 * The per-peer encryption state (design §4.3). One row per address; the
 * conversation's encryption state is derived from its single address's
 * peer state (1:1 only).
 */
enum class E2ePeerState {
    /** Nothing has happened yet: placeholder "Message", no lock. */
    UNKNOWN,

    /** A probe is out, awaiting a bundle: "Message", no lock, details panel reads "Setting up…". */
    PROBING,

    /** A session is live: placeholder "Relay message", lock badges, locks on timestamps. */
    ENCRYPTED,

    /** The probe budget is exhausted with no reply: looks like UNKNOWN in the thread. */
    NOT_RELAY,
}

/**
 * The probe-budget half of a peer row, as the state machine sees it.
 * The storage layer maps its `e2e_peers` row to this snapshot and back;
 * all times are epoch milliseconds.
 */
data class E2ePeerSnapshot(
    val state: E2ePeerState,
    val probeAttempts: Int = 0,
    val windowStartedAt: Long? = null,
    val lastProbeAt: Long? = null,
)

/**
 * The per-peer state machine (design §4.3 + the §2.4 probe budget).
 * Pure Kotlin, no I/O: every function takes a snapshot and returns the
 * next snapshot, so the whole machine is unit-testable without a database.
 *
 *     UNKNOWN ──probe sent──► PROBING ──bundle received──► ENCRYPTED
 *        ▲                        │                            │
 *        │                        │ 3 attempts, no reply       │ peer's identity key changes
 *        │                        ▼                            ▼
 *        └────── 30 days ── NOT_RELAY                    ENCRYPTED (notice + re-establish)
 *
 * The key-change arrow is a no-op for the state (it stays ENCRYPTED);
 * the notice + verified-flag clearing live in the manager layer, not here.
 *
 * Probe budget (design §2.4): at most 3 attempts per conversation per
 * 30 days, at least 24 hours apart. After the 3rd attempt the state
 * becomes NOT_RELAY and probing pauses until the window elapses. A late
 * bundle (or a pre-key message) still flips any non-ENCRYPTED state to
 * ENCRYPTED — the cryptographic proof outweighs the local bookkeeping.
 */
object E2ePeerStateMachine {

    /** Maximum probe attempts per conversation per window. */
    const val MAX_PROBE_ATTEMPTS: Int = 3

    /** The probe window: 30 days, in milliseconds. */
    const val PROBE_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** Minimum spacing between two probe attempts: 24 hours, in milliseconds. */
    const val MIN_PROBE_SPACING_MS: Long = 24L * 60 * 60 * 1000

    /**
     * Whether a probe may go out now for this peer. False while ENCRYPTED,
     * while the window's budget is spent, or while the last attempt is
     * less than 24 hours old. A peer whose window has elapsed is
     * probeable again (the next probe starts a fresh window).
     */
    fun canProbe(snapshot: E2ePeerSnapshot, nowMs: Long): Boolean {
        if (snapshot.state == E2ePeerState.ENCRYPTED) return false
        if (isWindowExpired(snapshot, nowMs)) return true
        if (snapshot.probeAttempts >= MAX_PROBE_ATTEMPTS) return false
        val lastProbeAt = snapshot.lastProbeAt
        return lastProbeAt == null || nowMs - lastProbeAt >= MIN_PROBE_SPACING_MS
    }

    /**
     * Record a probe sent now. Increments the attempt count (starting a
     * fresh window when the old one elapsed); the state becomes PROBING,
     * or NOT_RELAY once the budget is spent.
     *
     * @throws IllegalStateException if [canProbe] is false for this snapshot.
     */
    fun onProbeSent(snapshot: E2ePeerSnapshot, nowMs: Long): E2ePeerSnapshot {
        check(canProbe(snapshot, nowMs)) { "probe not allowed in state ${snapshot.state}" }
        val freshWindow = snapshot.windowStartedAt == null || isWindowExpired(snapshot, nowMs)
        val attempts = if (freshWindow) 1 else snapshot.probeAttempts + 1
        val windowStartedAt = if (freshWindow) nowMs else snapshot.windowStartedAt
        val state = if (attempts >= MAX_PROBE_ATTEMPTS) E2ePeerState.NOT_RELAY else E2ePeerState.PROBING
        return snapshot.copy(
            state = state,
            probeAttempts = attempts,
            windowStartedAt = windowStartedAt,
            lastProbeAt = nowMs,
        )
    }

    /**
     * A bundle reply arrived: the peer runs Relay and we have session
     * material. Any non-ENCRYPTED state becomes ENCRYPTED and the budget
     * resets (it no longer matters). ENCRYPTED is unchanged — first reply
     * wins (a second install may answer the same probe).
     */
    fun onBundleReceived(snapshot: E2ePeerSnapshot): E2ePeerSnapshot =
        if (snapshot.state == E2ePeerState.ENCRYPTED) {
            snapshot
        } else {
            E2ePeerSnapshot(state = E2ePeerState.ENCRYPTED)
        }

    /**
     * A pre-key message arrived from this peer: they have a session with
     * us (they probed first, or re-established). Same transition as a
     * bundle — any non-ENCRYPTED state becomes ENCRYPTED.
     */
    fun onPreKeyMessageReceived(snapshot: E2ePeerSnapshot): E2ePeerSnapshot =
        onBundleReceived(snapshot)

    /**
     * The probe window elapsed without encryption: a NOT_RELAY (or
     * stale PROBING) peer goes back to UNKNOWN with a clean budget, so
     * the next open/send probes again. ENCRYPTED peers and peers inside
     * their window are unchanged.
     */
    fun onWindowElapsed(snapshot: E2ePeerSnapshot, nowMs: Long): E2ePeerSnapshot =
        if (snapshot.state == E2ePeerState.ENCRYPTED || !isWindowExpired(snapshot, nowMs)) {
            snapshot
        } else {
            E2ePeerSnapshot(state = E2ePeerState.UNKNOWN)
        }

    /** Whether this snapshot's probe window has run out (no window = not expired). */
    fun isWindowExpired(snapshot: E2ePeerSnapshot, nowMs: Long): Boolean {
        val windowStartedAt = snapshot.windowStartedAt ?: return false
        return nowMs - windowStartedAt >= PROBE_WINDOW_MS
    }
}
