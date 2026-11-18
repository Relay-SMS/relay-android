package com.filestech.sms.crashlog

import java.io.File

/**
 * Local-only error log — the file-backed store for crash reports.
 *
 * Reports live in app-private internal storage (`filesDir/` +
 * [DIR_NAME]); nothing here touches the network or leaves the
 * device. The constructor takes the directory so tests can point it
 * at a temp dir on plain JVM — no Android calls in this class.
 *
 * Every method is best-effort and never throws: a store failure
 * must never crash the app it is diagnosing. Failures surface as
 * null / empty results instead.
 */
class CrashReportStore(private val reportsDir: File) {

    companion object {
        /** Directory under `filesDir` holding the reports. */
        const val DIR_NAME: String = "crash-reports"

        /** Reports kept; older ones are deleted on write (rotation). */
        const val MAX_REPORTS: Int = 10
    }

    /**
     * Writes a report and rotates the directory down to [MAX_REPORTS]
     * (oldest deleted first). Returns the written file, or null when
     * the write failed.
     */
    fun writeReport(text: String, timestampMillis: Long, crashType: String): File? {
        return runCatching {
            reportsDir.mkdirs()
            val file = File(reportsDir, CrashReportFormatter.fileNameFor(timestampMillis, crashType))
            file.writeText(text)
            rotate()
            file
        }.getOrNull()
    }

    /** Saved reports, newest first. Stray files in the directory are ignored. */
    fun listReports(): List<CrashReportSummary> {
        return runCatching {
            reportsDir.listFiles()
                ?.mapNotNull { CrashReportFormatter.parseFileName(it.name) }
                ?.sortedByDescending { it.timestampMillis }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Full text of one report, or null when it is missing or unreadable. */
    fun readReport(id: String): String? {
        val file = resolve(id) ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** Deletes every saved report. */
    fun clearAll() {
        runCatching {
            reportsDir.listFiles()
                ?.filter { CrashReportFormatter.parseFileName(it.name) != null }
                ?.forEach { it.delete() }
        }
    }

    /** Deletes the oldest reports past [MAX_REPORTS]. Never throws. */
    private fun rotate() {
        runCatching {
            val files = reportsDir.listFiles()
                ?.mapNotNull { file ->
                    CrashReportFormatter.parseFileName(file.name)?.let { file to it }
                }
                ?.sortedByDescending { it.second.timestampMillis }
                .orEmpty()
            files.drop(MAX_REPORTS).forEach { it.first.delete() }
        }
    }

    /**
     * Resolves an id to a file inside the reports directory, or null.
     * The id must parse as a report file name AND its canonical path
     * must stay inside the directory — a crafted id (e.g. "../…")
     * never escapes it.
     */
    private fun resolve(id: String): File? {
        if (CrashReportFormatter.parseFileName(id) == null) return null
        return runCatching {
            val base = reportsDir.canonicalPath
            val file = File(reportsDir, id).canonicalFile
            if (file.path.startsWith("$base${File.separator}")) file else null
        }.getOrNull()
    }
}
