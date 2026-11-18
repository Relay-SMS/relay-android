package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Local-only error log — the session-marker store: atomic write,
 * read (missing / corrupt → null), delete, and the guarantee
 * that the marker never shows up as a crash report in the shared
 * directory (and survives Clear all — it is session state, not
 * a report). Every method is best-effort and never throws.
 */
class SessionMarkerStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val marker = SessionMarker(
        sessionStartMillis = 1_000L,
        lastAliveMillis = 2_000L,
        pid = 1234,
        foreground = true,
        screen = "Thread",
        syncState = "syncing",
    )

    private fun store(): SessionMarkerStore = SessionMarkerStore(File(tempDir, "crash-reports"))

    @Test
    fun `write then read round-trips`() {
        val markerStore = store()
        assertThat(markerStore.write(marker)).isTrue()
        assertThat(markerStore.read()).isEqualTo(marker)
    }

    @Test
    fun `read returns null when there is no marker`() {
        assertThat(store().read()).isNull()
    }

    @Test
    fun `read returns null for a corrupt marker`() {
        val dir = File(tempDir, "crash-reports")
        dir.mkdirs()
        File(dir, SessionMarkerStore.FILE_NAME).writeText("torn {\"sessionStart")
        assertThat(store().read()).isNull()
    }

    @Test
    fun `delete removes the marker`() {
        val markerStore = store()
        markerStore.write(marker)
        assertThat(markerStore.delete()).isTrue()
        assertThat(markerStore.read()).isNull()
    }

    @Test
    fun `delete returns false when there is no marker`() {
        assertThat(store().delete()).isFalse()
    }

    @Test
    fun `write leaves no temp file behind`() {
        store().write(marker)
        val files = File(tempDir, "crash-reports").listFiles()?.map { it.name }.orEmpty()
        assertThat(files).containsExactly(SessionMarkerStore.FILE_NAME).inOrder()
    }

    @Test
    fun `write to an unusable directory returns false instead of throwing`() {
        val blockingFile = File(tempDir, "blocked")
        blockingFile.writeText("a file, not a directory")
        val broken = SessionMarkerStore(File(blockingFile, "crash-reports"))
        assertThat(broken.write(marker)).isFalse()
        assertThat(broken.read()).isNull()
    }

    @Test
    fun `the marker file is invisible to the report store`() {
        val markerStore = store()
        markerStore.write(marker)
        val reports = CrashReportStore(File(tempDir, "crash-reports"))
        assertThat(reports.listReports()).isEmpty()
        reports.clearAll()
        assertThat(markerStore.read()).isEqualTo(marker)
    }
}
