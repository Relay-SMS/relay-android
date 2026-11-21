package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2ePeerState.ENCRYPTED
import com.filestech.sms.e2e.E2ePeerState.NOT_RELAY
import com.filestech.sms.e2e.E2ePeerState.PROBING
import com.filestech.sms.e2e.E2ePeerState.UNKNOWN
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * State-machine tests (design §4.3 / §2.4 / §10.1): every transition
 * plus the probe budget (3 attempts / 30 days, ≥24h apart). Pure JVM —
 * the machine is pure Kotlin over snapshots, no database involved.
 */
class E2ePeerStateMachineTest {

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
    }

    private val unknown: E2ePeerSnapshot = E2ePeerSnapshot(state = UNKNOWN)

    @Test
    fun `a probe from UNKNOWN moves to PROBING and starts the window`() {
        assertThat(E2ePeerStateMachine.canProbe(unknown, NOW)).isTrue()
        val next = E2ePeerStateMachine.onProbeSent(unknown, NOW)
        assertThat(next.state).isEqualTo(PROBING)
        assertThat(next.probeAttempts).isEqualTo(1)
        assertThat(next.windowStartedAt).isEqualTo(NOW)
        assertThat(next.lastProbeAt).isEqualTo(NOW)
    }

    @Test
    fun `a second probe within 24 hours is not allowed`() {
        val probing = E2ePeerStateMachine.onProbeSent(unknown, NOW)
        assertThat(E2ePeerStateMachine.canProbe(probing, NOW + DAY_MS - 1)).isFalse()
        assertThrows(IllegalStateException::class.java) {
            E2ePeerStateMachine.onProbeSent(probing, NOW + DAY_MS - 1)
        }
    }

    @Test
    fun `a second probe exactly 24 hours later is allowed and stays PROBING`() {
        val probing = E2ePeerStateMachine.onProbeSent(unknown, NOW)
        assertThat(E2ePeerStateMachine.canProbe(probing, NOW + DAY_MS)).isTrue()
        val next = E2ePeerStateMachine.onProbeSent(probing, NOW + DAY_MS)
        assertThat(next.state).isEqualTo(PROBING)
        assertThat(next.probeAttempts).isEqualTo(2)
        assertThat(next.windowStartedAt).isEqualTo(NOW)
        assertThat(next.lastProbeAt).isEqualTo(NOW + DAY_MS)
    }

    @Test
    fun `the third unanswered attempt moves to NOT_RELAY`() {
        var snapshot = E2ePeerStateMachine.onProbeSent(unknown, NOW)
        snapshot = E2ePeerStateMachine.onProbeSent(snapshot, NOW + DAY_MS)
        snapshot = E2ePeerStateMachine.onProbeSent(snapshot, NOW + 2 * DAY_MS)
        assertThat(snapshot.state).isEqualTo(NOT_RELAY)
        assertThat(snapshot.probeAttempts).isEqualTo(3)
        assertThat(E2ePeerStateMachine.canProbe(snapshot, NOW + 3 * DAY_MS)).isFalse()
    }

    @Test
    fun `NOT_RELAY probes again once the 30-day window elapses`() {
        var snapshot = E2ePeerStateMachine.onProbeSent(unknown, NOW)
        snapshot = E2ePeerStateMachine.onProbeSent(snapshot, NOW + DAY_MS)
        snapshot = E2ePeerStateMachine.onProbeSent(snapshot, NOW + 2 * DAY_MS)
        val windowEnd = NOW + E2ePeerStateMachine.PROBE_WINDOW_MS
        assertThat(E2ePeerStateMachine.canProbe(snapshot, windowEnd - 1)).isFalse()
        assertThat(E2ePeerStateMachine.canProbe(snapshot, windowEnd)).isTrue()
        val next = E2ePeerStateMachine.onProbeSent(snapshot, windowEnd)
        assertThat(next.state).isEqualTo(PROBING)
        assertThat(next.probeAttempts).isEqualTo(1)
        assertThat(next.windowStartedAt).isEqualTo(windowEnd)
    }

    @Test
    fun `onWindowElapsed resets NOT_RELAY to UNKNOWN and leaves others alone`() {
        val notRelay = E2ePeerSnapshot(
            state = NOT_RELAY,
            probeAttempts = 3,
            windowStartedAt = NOW,
            lastProbeAt = NOW + 2 * DAY_MS,
        )
        val windowEnd = NOW + E2ePeerStateMachine.PROBE_WINDOW_MS
        assertThat(E2ePeerStateMachine.onWindowElapsed(notRelay, windowEnd - 1)).isEqualTo(notRelay)
        val reset = E2ePeerStateMachine.onWindowElapsed(notRelay, windowEnd)
        assertThat(reset).isEqualTo(E2ePeerSnapshot(state = UNKNOWN))
        val encrypted = E2ePeerSnapshot(state = ENCRYPTED)
        assertThat(E2ePeerStateMachine.onWindowElapsed(encrypted, windowEnd)).isEqualTo(encrypted)
    }

    @Test
    fun `a bundle from UNKNOWN PROBING or NOT_RELAY moves to ENCRYPTED with a clean budget`() {
        val probing = E2ePeerSnapshot(
            state = PROBING,
            probeAttempts = 2,
            windowStartedAt = NOW,
            lastProbeAt = NOW,
        )
        assertThat(E2ePeerStateMachine.onBundleReceived(unknown))
            .isEqualTo(E2ePeerSnapshot(state = ENCRYPTED))
        assertThat(E2ePeerStateMachine.onBundleReceived(probing))
            .isEqualTo(E2ePeerSnapshot(state = ENCRYPTED))
        val notRelay = E2ePeerSnapshot(
            state = NOT_RELAY,
            probeAttempts = 3,
            windowStartedAt = NOW,
            lastProbeAt = NOW,
        )
        assertThat(E2ePeerStateMachine.onBundleReceived(notRelay))
            .isEqualTo(E2ePeerSnapshot(state = ENCRYPTED))
    }

    @Test
    fun `a bundle while ENCRYPTED changes nothing — first reply wins`() {
        val encrypted = E2ePeerSnapshot(state = ENCRYPTED)
        assertThat(E2ePeerStateMachine.onBundleReceived(encrypted)).isEqualTo(encrypted)
    }

    @Test
    fun `a pre-key message moves any non-ENCRYPTED state to ENCRYPTED`() {
        assertThat(E2ePeerStateMachine.onPreKeyMessageReceived(unknown))
            .isEqualTo(E2ePeerSnapshot(state = ENCRYPTED))
        val notRelay = E2ePeerSnapshot(state = NOT_RELAY, probeAttempts = 3, windowStartedAt = NOW)
        assertThat(E2ePeerStateMachine.onPreKeyMessageReceived(notRelay))
            .isEqualTo(E2ePeerSnapshot(state = ENCRYPTED))
        val encrypted = E2ePeerSnapshot(state = ENCRYPTED)
        assertThat(E2ePeerStateMachine.onPreKeyMessageReceived(encrypted)).isEqualTo(encrypted)
    }

    @Test
    fun `an ENCRYPTED peer is never probed`() {
        val encrypted = E2ePeerSnapshot(state = ENCRYPTED)
        assertThat(E2ePeerStateMachine.canProbe(encrypted, NOW)).isFalse()
        assertThrows(IllegalStateException::class.java) {
            E2ePeerStateMachine.onProbeSent(encrypted, NOW)
        }
    }

    @Test
    fun `isWindowExpired is false without a window and true at exactly 30 days`() {
        assertThat(E2ePeerStateMachine.isWindowExpired(unknown, NOW)).isFalse()
        val probing = E2ePeerSnapshot(state = PROBING, windowStartedAt = NOW)
        val windowEnd = NOW + E2ePeerStateMachine.PROBE_WINDOW_MS
        assertThat(E2ePeerStateMachine.isWindowExpired(probing, windowEnd - 1)).isFalse()
        assertThat(E2ePeerStateMachine.isWindowExpired(probing, windowEnd)).isTrue()
    }

    @Test
    fun `the budget constants match the design`() {
        assertThat(E2ePeerStateMachine.MAX_PROBE_ATTEMPTS).isEqualTo(3)
        assertThat(E2ePeerStateMachine.PROBE_WINDOW_MS).isEqualTo(30L * DAY_MS)
        assertThat(E2ePeerStateMachine.MIN_PROBE_SPACING_MS).isEqualTo(DAY_MS)
    }
}
