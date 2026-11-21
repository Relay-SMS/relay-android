package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Local-only error log — the crash handler's record step: a
 * recorded crash deletes the session marker (no double-report
 * at the next startup), a failed write keeps it (the startup
 * check then records the death from the exit reason instead),
 * and nothing here ever throws. Round 36 — when the error log
 * is off, nothing is written at all.
 */
class CrashLogHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private val metadata = CrashMetadata(
        appVersion = "1.29.2",
        buildType = "release",
        androidRelease = "14",
        androidSdk = 34,
        deviceModel = "Pixel 8",
        timestampMillis = 1_000L,
    )

    private fun dir(): File = File(tempDir, "crash-reports")

    @Test
    fun `recordCrash writes a report and deletes the marker`() {
        val store = CrashReportStore(dir())
        val markerStore = SessionMarkerStore(dir())
        markerStore.write(SessionMarker(500L, 900L, 1111, true, "Thread", null))
        val written = CrashLogHandler.recordCrash(
            store,
            markerStore,
            metadata,
            RuntimeException("boom"),
        )
        assertThat(written).isTrue()
        assertThat(store.listReports()).hasSize(1)
        assertThat(markerStore.read()).isNull()
    }

    @Test
    fun `recordCrash keeps the marker when the report write fails`() {
        val store = CrashReportStore(dir())
        val markerStore = SessionMarkerStore(dir())
        val marker = SessionMarker(500L, 900L, 1111, true, "Thread", null)
        markerStore.write(marker)
        // Block the report write: a directory sits where the
        // report file would go, so the write fails while the
        // marker file is untouched.
        val reportName = CrashReportFormatter.fileNameFor(1_000L, "RuntimeException")
        File(dir(), reportName).mkdirs()
        val written = CrashLogHandler.recordCrash(
            store,
            markerStore,
            metadata,
            RuntimeException("boom"),
        )
        assertThat(written).isFalse()
        assertThat(markerStore.read()).isEqualTo(marker)
    }

    @Test
    fun `recordCrash never throws on an unusable directory`() {
        val blockingFile = File(tempDir, "blocked")
        blockingFile.writeText("a file, not a directory")
        val brokenDir = File(blockingFile, "crash-reports")
        val written = CrashLogHandler.recordCrash(
            CrashReportStore(brokenDir),
            SessionMarkerStore(brokenDir),
            metadata,
            RuntimeException("boom"),
        )
        assertThat(written).isFalse()
    }

    @Test
    fun `recordCrash writes nothing when the error log is off`() {
        val store = CrashReportStore(dir())
        val markerStore = SessionMarkerStore(dir())
        val marker = SessionMarker(500L, 900L, 1111, true, "Thread", null)
        markerStore.write(marker)
        val written = CrashLogHandler.recordCrash(
            store,
            markerStore,
            metadata,
            RuntimeException("boom"),
            enabled = { false },
        )
        assertThat(written).isFalse()
        assertThat(store.listReports()).isEmpty()
        assertThat(markerStore.read()).isEqualTo(marker)
    }
}
