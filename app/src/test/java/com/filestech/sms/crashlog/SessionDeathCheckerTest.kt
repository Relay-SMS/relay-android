package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Local-only error log — the startup check: no marker means
 * nothing to do; a reportable death writes an "unexpected stop"
 * entry (timestamped at the death, carrying the marker's
 * details); a user-asked death stays silent; and a missing or
 * failing exit-reason provider falls back to the marker
 * heuristic instead of crashing the startup.
 */
class SessionDeathCheckerTest {

    @TempDir
    lateinit var tempDir: File

    private val metadata = CrashMetadata(
        appVersion = "1.29.2",
        buildType = "release",
        androidRelease = "14",
        androidSdk = 34,
        deviceModel = "Pixel 8",
        timestampMillis = 3_000L,
    )

    private val marker = SessionMarker(
        sessionStartMillis = 1_000L,
        lastAliveMillis = 2_000L,
        pid = 1234,
        foreground = true,
        screen = "Thread",
        syncState = "syncing",
    )

    private fun store(): CrashReportStore = CrashReportStore(File(tempDir, "crash-reports"))

    private class FakeProvider(private val exit: SessionExitInfo?) : ExitReasonProvider {
        override fun mostRecentExit(marker: SessionMarker): SessionExitInfo? = exit
    }

    private class ThrowingProvider : ExitReasonProvider {
        override fun mostRecentExit(marker: SessionMarker): SessionExitInfo? {
            error("binder died")
        }
    }

    @Test
    fun `no marker means no report`() {
        val reports = store()
        SessionDeathChecker(reports, FakeProvider(SessionExitInfo(SessionExitReason.ANR, 2_500L)))
            .check(null, metadata)
        assertThat(reports.listReports()).isEmpty()
    }

    @Test
    fun `an ANR death writes an unexpected stop entry`() {
        val reports = store()
        SessionDeathChecker(reports, FakeProvider(SessionExitInfo(SessionExitReason.ANR, 2_500L)))
            .check(marker, metadata)
        val listed = reports.listReports()
        assertThat(listed).hasSize(1)
        assertThat(listed[0].crashType).isEqualTo(CrashReportFormatter.UNEXPECTED_STOP_TYPE)
        assertThat(listed[0].timestampMillis).isEqualTo(2_500L)
        val text = reports.readReport(listed[0].id)
        assertThat(text).isNotNull()
        assertThat(text).contains("App stopped unexpectedly (no crash recorded)")
        assertThat(text).contains("Exit reason: ANR")
        assertThat(text).contains("Last screen: Thread")
        assertThat(text).contains("Sync state: syncing")
    }

    @Test
    fun `a user-requested death stays silent`() {
        val reports = store()
        SessionDeathChecker(
            reports,
            FakeProvider(SessionExitInfo(SessionExitReason.USER_REQUESTED, 2_500L)),
        ).check(marker, metadata)
        assertThat(reports.listReports()).isEmpty()
    }

    @Test
    fun `without an exit record a foreground death is reported at last-alive time`() {
        val reports = store()
        SessionDeathChecker(reports, FakeProvider(null)).check(marker, metadata)
        val listed = reports.listReports()
        assertThat(listed).hasSize(1)
        assertThat(listed[0].timestampMillis).isEqualTo(2_000L)
        assertThat(reports.readReport(listed[0].id)).contains("Exit reason: unknown")
    }

    @Test
    fun `without an exit record a background death stays silent`() {
        val reports = store()
        SessionDeathChecker(reports, FakeProvider(null))
            .check(marker.copy(foreground = false), metadata)
        assertThat(reports.listReports()).isEmpty()
    }

    @Test
    fun `a null provider falls back to the marker heuristic`() {
        val reports = store()
        SessionDeathChecker(reports, null).check(marker, metadata)
        assertThat(reports.listReports()).hasSize(1)
    }

    @Test
    fun `a throwing provider falls back to the marker heuristic instead of crashing`() {
        val reports = store()
        SessionDeathChecker(reports, ThrowingProvider()).check(marker, metadata)
        assertThat(reports.listReports()).hasSize(1)
    }

    @Test
    fun `the entry counts toward the ten-report rotation`() {
        val reports = store()
        for (i in 1..10) {
            reports.writeReport("report $i", i * 1_000L, "Crash$i")
        }
        SessionDeathChecker(reports, FakeProvider(SessionExitInfo(SessionExitReason.ANR, 11_000L)))
            .check(marker, metadata)
        val listed = reports.listReports()
        assertThat(listed).hasSize(CrashReportStore.MAX_REPORTS)
        assertThat(listed.map { it.crashType }).contains(CrashReportFormatter.UNEXPECTED_STOP_TYPE)
    }

    @Test
    fun `a reportable death writes nothing when the error log is off`() {
        val reports = store()
        SessionDeathChecker(
            reports,
            FakeProvider(SessionExitInfo(SessionExitReason.ANR, 2_500L)),
            enabled = { false },
        ).check(marker, metadata)
        assertThat(reports.listReports()).isEmpty()
    }
}
