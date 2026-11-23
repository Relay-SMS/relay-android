package com.filestech.sms.e2e

import com.filestech.sms.crashlog.CrashLogSettings
import com.filestech.sms.crashlog.CrashReportStore
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The E2E failure recorder: writes go to the local error log
 * with the tag in the body, the error-log switch gates them,
 * and one root cause is recorded once per process no matter
 * how many wrappers or screens report it.
 */
class E2eFailureRecorderTest {

    private lateinit var tempDir: File
    private lateinit var store: CrashReportStore
    private lateinit var settings: CrashLogSettings
    private lateinit var recorder: E2eFailureRecorder

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("e2e-recorder-test").toFile()
        store = CrashReportStore(File(tempDir, "crash-reports"))
        settings = mockk { every { enabled } returns true }
        recorder = E2eFailureRecorder(store, settings)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `record writes a diagnostic entry with the tag and the trace`() {
        recorder.record("e2e-fingerprint", RuntimeException("boom"))

        val reports = store.listReports()
        assertThat(reports).hasSize(1)
        assertThat(reports.single().crashType).isEqualTo("RuntimeException")
        val text = store.readReport(reports.single().id)
        assertThat(text).contains("Relay diagnostic — e2e-fingerprint")
        assertThat(text).contains("RuntimeException: boom")
        assertThat(text).doesNotContain("Relay crash report")
    }

    @Test
    fun `record writes nothing when the error log is off`() {
        every { settings.enabled } returns false

        recorder.record("e2e-fingerprint", RuntimeException("boom"))

        assertThat(store.listReports()).isEmpty()
    }

    @Test
    fun `record dedupes a wrapper and the failure it wraps`() {
        val root = UnsatisfiedLinkError("JNI_ERR returned from JNI_OnLoad")
        val wrapper = E2eCryptoException("end-to-end encryption is unavailable", root)

        recorder.record("e2e-native-load", root)
        recorder.record("e2e-fingerprint", wrapper)

        assertThat(store.listReports()).hasSize(1)
    }

    @Test
    fun `record dedupes the same failure reported twice`() {
        val failure = RuntimeException("boom")

        recorder.record("e2e-fingerprint", failure)
        recorder.record("e2e-fingerprint", failure)

        assertThat(store.listReports()).hasSize(1)
    }

    @Test
    fun `record keeps distinct root causes as separate entries`() {
        recorder.record("e2e-fingerprint", RuntimeException("first"))
        recorder.record("e2e-fingerprint", IllegalStateException("second"))

        assertThat(store.listReports()).hasSize(2)
    }

    @Test
    fun `record never throws when the store cannot write`() {
        val blocker = File(tempDir, "blocker").apply { writeText("not a directory") }
        val brokenStore = CrashReportStore(blocker)
        val brokenRecorder = E2eFailureRecorder(brokenStore, settings)

        brokenRecorder.record("e2e-fingerprint", RuntimeException("boom"))

        assertThat(brokenStore.listReports()).isEmpty()
    }

    // -- recordEvent (round 42): success-event diagnostics --------

    @Test
    fun `recordEvent writes an entry titled by the tag, with the tag and message in the body`() {
        recorder.recordEvent("e2e-probe-received", "Probe received; replying with a bundle.")

        val reports = store.listReports()
        assertThat(reports).hasSize(1)
        // The list title is the tag itself — the user sees which
        // event fired without opening the entry.
        assertThat(reports.single().crashType).isEqualTo("e2e-probe-received")
        val text = store.readReport(reports.single().id)
        assertThat(text).contains("Relay diagnostic — e2e-probe-received")
        assertThat(text).contains("Probe received; replying with a bundle.")
        assertThat(text).contains("E2eDiagnosticEvent")
        assertThat(text).doesNotContain("Relay crash report")
    }

    @Test
    fun `recordEvent writes nothing when the error log is off`() {
        every { settings.enabled } returns false

        recorder.recordEvent("e2e-probe-received", "Probe received; replying with a bundle.")

        assertThat(store.listReports()).isEmpty()
    }

    @Test
    fun `recordEvent writes every occurrence, even identical ones`() {
        // Round 44 — events are NEVER deduped: the tester tapping
        // Retry repeatedly must see each probe and bundle with its
        // timestamp. A mocked store counts the writes directly — the
        // real store names files by millisecond timestamp, so two
        // back-to-back writes would collide on the file name.
        val countingStore = mockk<CrashReportStore>(relaxed = true)
        val countingRecorder = E2eFailureRecorder(countingStore, settings)

        countingRecorder.recordEvent("e2e-probe-received", "Probe received; replying with a bundle.")
        countingRecorder.recordEvent("e2e-probe-received", "Probe received; replying with a bundle.")

        verify(exactly = 2) {
            countingStore.writeReport(any(), any(), "e2e-probe-received")
        }
    }

    @Test
    fun `recordEvent keeps distinct events as separate entries`() {
        recorder.recordEvent("e2e-probe-received", "Probe received; replying with a bundle.")
        recorder.recordEvent("e2e-bundle-sent", "Bundle reply sent.")
        recorder.recordEvent("e2e-bundle-rate-limited", "Bundle reply rate-limited (one per sender per day).")

        val reports = store.listReports()
        assertThat(reports).hasSize(3)
        assertThat(reports.map { it.crashType }).containsExactly(
            "e2e-probe-received",
            "e2e-bundle-sent",
            "e2e-bundle-rate-limited",
        )
    }

    @Test
    fun `recordEvent never throws when the store cannot write`() {
        val blocker = File(tempDir, "blocker").apply { writeText("not a directory") }
        val brokenStore = CrashReportStore(blocker)
        val brokenRecorder = E2eFailureRecorder(brokenStore, settings)

        brokenRecorder.recordEvent("e2e-probe-received", "Probe received; replying with a bundle.")

        assertThat(brokenStore.listReports()).isEmpty()
    }
}
