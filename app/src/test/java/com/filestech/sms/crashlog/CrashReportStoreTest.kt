package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Local-only error log — the file-backed store: write, list
 * (newest first), read, rotation to the last 10, clear. Every
 * method is best-effort and never throws: a store failure must
 * never crash the app it is diagnosing.
 */
class CrashReportStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun store(): CrashReportStore = CrashReportStore(File(tempDir, "crash-reports"))

    @Test
    fun `write creates a file the list can read back`() {
        val written = store().writeReport("report body", 1_000L, "NullPointerException")
        assertThat(written).isNotNull()
        assertThat(written!!.readText()).isEqualTo("report body")
        val listed = store().listReports()
        assertThat(listed).hasSize(1)
        assertThat(listed[0].timestampMillis).isEqualTo(1_000L)
        assertThat(listed[0].crashType).isEqualTo("NullPointerException")
    }

    @Test
    fun `list is newest first`() {
        val reports = store()
        reports.writeReport("old", 1_000L, "A")
        reports.writeReport("new", 3_000L, "B")
        reports.writeReport("mid", 2_000L, "C")
        val listed = reports.listReports()
        assertThat(listed.map { it.timestampMillis }).containsExactly(3_000L, 2_000L, 1_000L).inOrder()
    }

    @Test
    fun `list ignores stray files in the directory`() {
        val reports = store()
        reports.writeReport("real", 1_000L, "A")
        File(tempDir, "crash-reports").resolve("notes.txt").writeText("not a report")
        assertThat(reports.listReports()).hasSize(1)
    }

    @Test
    fun `rotation keeps the ten newest and deletes the oldest`() {
        val reports = store()
        for (i in 1..12) {
            reports.writeReport("report $i", i * 1_000L, "Crash$i")
        }
        val listed = reports.listReports()
        assertThat(listed).hasSize(CrashReportStore.MAX_REPORTS)
        assertThat(listed.map { it.timestampMillis }).doesNotContain(1_000L)
        assertThat(listed.map { it.timestampMillis }).doesNotContain(2_000L)
        assertThat(listed.map { it.timestampMillis }).contains(12_000L)
    }

    @Test
    fun `read returns the full text of a listed report`() {
        val reports = store()
        reports.writeReport("full text here", 1_000L, "A")
        val id = reports.listReports()[0].id
        assertThat(reports.readReport(id)).isEqualTo("full text here")
    }

    @Test
    fun `read returns null for an unknown id`() {
        assertThat(store().readReport("crash-999-Nope.txt")).isNull()
    }

    @Test
    fun `read rejects ids that escape the directory`() {
        assertThat(store().readReport("../secret.txt")).isNull()
        // Parses as a report name, but resolves outside the directory.
        assertThat(store().readReport("crash-1-../../secret.txt")).isNull()
    }

    @Test
    fun `clear deletes reports but leaves stray files alone`() {
        val reports = store()
        reports.writeReport("real", 1_000L, "A")
        val stray = File(tempDir, "crash-reports").resolve("notes.txt")
        stray.writeText("not a report")
        reports.clearAll()
        assertThat(reports.listReports()).isEmpty()
        assertThat(stray.exists()).isTrue()
    }

    @Test
    fun `write to an unusable directory returns null instead of throwing`() {
        val blockingFile = File(tempDir, "blocked")
        blockingFile.writeText("a file, not a directory")
        val broken = CrashReportStore(File(blockingFile, "crash-reports"))
        assertThat(broken.writeReport("x", 1_000L, "A")).isNull()
        assertThat(broken.listReports()).isEmpty()
    }
}
