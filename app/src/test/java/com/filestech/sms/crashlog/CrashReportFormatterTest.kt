package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Local-only error log — the report format: file names carry the
 * timestamp + crash type (so the list never opens a file), and the
 * body is a metadata header plus the full stack trace.
 */
class CrashReportFormatterTest {

    private val metadata = CrashMetadata(
        appVersion = "1.29.2",
        buildType = "release",
        androidRelease = "14",
        androidSdk = 34,
        deviceModel = "Pixel 8",
        timestampMillis = 1_763_000_000_000L,
    )

    @Test
    fun `file name carries timestamp and crash type`() {
        val name = CrashReportFormatter.fileNameFor(1_763_000_000_000L, "NullPointerException")
        assertThat(name).isEqualTo("crash-1763000000000-NullPointerException.txt")
    }

    @Test
    fun `file name sanitizes unsafe characters in the crash type`() {
        val name = CrashReportFormatter.fileNameFor(1000L, "weird/type:name")
        assertThat(name).isEqualTo("crash-1000-weird_type_name.txt")
    }

    @Test
    fun `file name round-trips through parse`() {
        val name = CrashReportFormatter.fileNameFor(1_763_000_000_000L, "IllegalStateException")
        val parsed = CrashReportFormatter.parseFileName(name)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.id).isEqualTo(name)
        assertThat(parsed.timestampMillis).isEqualTo(1_763_000_000_000L)
        assertThat(parsed.crashType).isEqualTo("IllegalStateException")
    }

    @Test
    fun `parse rejects names that are not report files`() {
        assertThat(CrashReportFormatter.parseFileName("notes.txt")).isNull()
        assertThat(CrashReportFormatter.parseFileName("crash-abc-X.txt")).isNull()
        assertThat(CrashReportFormatter.parseFileName("crash-123-X.log")).isNull()
    }

    @Test
    fun `format writes the metadata header`() {
        val text = CrashReportFormatter.format(metadata, RuntimeException("boom"))
        assertThat(text).contains("Relay crash report")
        assertThat(text).contains("App version: 1.29.2 (release)")
        assertThat(text).contains("Android: 14 (API 34)")
        assertThat(text).contains("Device: Pixel 8")
        assertThat(text).contains("Exception: java.lang.RuntimeException")
    }

    @Test
    fun `format includes the stack trace and its causes`() {
        val cause = IllegalStateException("root cause")
        val error = RuntimeException("boom", cause)
        val text = CrashReportFormatter.format(metadata, error)
        assertThat(text).contains("java.lang.RuntimeException: boom")
        assertThat(text).contains("Caused by: java.lang.IllegalStateException: root cause")
    }

    @Test
    fun `formatUnexpectedStop writes the marker details, not a stack trace`() {
        val marker = SessionMarker(
            sessionStartMillis = 1_000L,
            lastAliveMillis = 2_000L,
            pid = 1234,
            foreground = true,
            screen = "Thread",
            syncState = "syncing",
        )
        val text = CrashReportFormatter.formatUnexpectedStop(metadata, marker, SessionExitReason.ANR)
        assertThat(text).contains("App stopped unexpectedly (no crash recorded)")
        assertThat(text).contains("Exit reason: ANR")
        assertThat(text).contains("Foreground: yes")
        assertThat(text).contains("Last screen: Thread")
        assertThat(text).contains("Sync state: syncing")
        assertThat(text).doesNotContain("Exception:")
    }

    @Test
    fun `formatUnexpectedStop says unknown for missing details`() {
        val marker = SessionMarker(
            sessionStartMillis = 1_000L,
            lastAliveMillis = 2_000L,
            pid = 1234,
            foreground = false,
        )
        val text = CrashReportFormatter.formatUnexpectedStop(metadata, marker, null)
        assertThat(text).contains("Exit reason: unknown")
        assertThat(text).contains("Foreground: no")
        assertThat(text).contains("Last screen: unknown")
        assertThat(text).contains("Sync state: unknown")
    }

    @Test
    fun `the unexpected stop type round-trips through file names`() {
        val name = CrashReportFormatter.fileNameFor(2_500L, CrashReportFormatter.UNEXPECTED_STOP_TYPE)
        assertThat(name).isEqualTo("crash-2500-UnexpectedStop.txt")
        assertThat(CrashReportFormatter.parseFileName(name)?.crashType)
            .isEqualTo(CrashReportFormatter.UNEXPECTED_STOP_TYPE)
    }

    @Test
    fun `formatDiagnostic heads the entry with the tag, not a crash`() {
        val text = CrashReportFormatter.formatDiagnostic(
            "e2e-fingerprint",
            1_763_000_000_000L,
            RuntimeException("boom"),
        )
        assertThat(text).contains("Relay diagnostic — e2e-fingerprint")
        assertThat(text).contains("Exception: java.lang.RuntimeException")
        assertThat(text).contains("RuntimeException: boom")
        assertThat(text).doesNotContain("Relay crash report")
    }

    @Test
    fun `formatDiagnostic includes the cause chain`() {
        val error = RuntimeException("wrapper", IllegalStateException("root"))
        val text = CrashReportFormatter.formatDiagnostic("e2e-fingerprint", 1_000L, error)
        assertThat(text).contains("java.lang.RuntimeException: wrapper")
        assertThat(text).contains("Caused by: java.lang.IllegalStateException: root")
    }
}
