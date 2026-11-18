package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Local-only error log — the death classifier: every exit reason
 * pinned to its outcome (only user-asked deaths stay silent),
 * the pre-API-30 fallback (foreground → report, background →
 * silent), and the reason-code mapping (an unknown code is an
 * unexplained death, reported, never dropped).
 */
class SessionDeathClassifierTest {

    private val foregroundMarker = SessionMarker(
        sessionStartMillis = 1_000L,
        lastAliveMillis = 2_000L,
        pid = 1234,
        foreground = true,
        screen = "Thread",
        syncState = "syncing",
    )

    private val backgroundMarker = foregroundMarker.copy(foreground = false)

    private fun outcome(reason: SessionExitReason?): SessionDeathClassifier.Outcome {
        return SessionDeathClassifier.classify(reason, foregroundMarker)
    }

    @Test
    fun `user-asked deaths are silent`() {
        assertThat(outcome(SessionExitReason.USER_REQUESTED))
            .isEqualTo(SessionDeathClassifier.Outcome.SILENT)
        assertThat(outcome(SessionExitReason.USER_STOPPED))
            .isEqualTo(SessionDeathClassifier.Outcome.SILENT)
        assertThat(outcome(SessionExitReason.PERMISSION_CHANGE))
            .isEqualTo(SessionDeathClassifier.Outcome.SILENT)
    }

    @Test
    fun `anr crash native crash and low memory are reported`() {
        assertThat(outcome(SessionExitReason.ANR)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.CRASH)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.CRASH_NATIVE)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.LOW_MEMORY)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
    }

    @Test
    fun `every other reason is reported`() {
        assertThat(outcome(SessionExitReason.UNKNOWN)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.EXIT_SELF)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.SIGNALED)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.INITIALIZATION_FAILURE))
            .isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.EXCESSIVE_RESOURCE_USAGE))
            .isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.DEPENDENCY_DIED)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
        assertThat(outcome(SessionExitReason.OTHER)).isEqualTo(SessionDeathClassifier.Outcome.REPORT)
    }

    @Test
    fun `without an exit reason a foreground death is reported`() {
        assertThat(SessionDeathClassifier.classify(null, foregroundMarker))
            .isEqualTo(SessionDeathClassifier.Outcome.REPORT)
    }

    @Test
    fun `without an exit reason a background death is silent`() {
        assertThat(SessionDeathClassifier.classify(null, backgroundMarker))
            .isEqualTo(SessionDeathClassifier.Outcome.SILENT)
    }

    @Test
    fun `reason codes round-trip through fromReasonCode`() {
        SessionExitReason.entries.forEach { reason ->
            assertThat(SessionExitReason.fromReasonCode(reason.reasonCode)).isEqualTo(reason)
        }
        assertThat(SessionExitReason.fromReasonCode(99)).isEqualTo(SessionExitReason.UNKNOWN)
    }
}
