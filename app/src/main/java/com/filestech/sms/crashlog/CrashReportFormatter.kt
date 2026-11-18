package com.filestech.sms.crashlog

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local-only error log — the on-disk format of a crash report.
 *
 * File name: `crash-<epochmillis>-<CrashType>.txt`. The crash type
 * rides in the name so the list screen never has to open a file to
 * show it; the timestamp sorts the files newest-first. The type is
 * sanitized to filename-safe characters on write.
 *
 * File body: a metadata header, a blank line, then the full stack
 * trace (causes included). The header carries build/device metadata
 * only — no user data. One caveat, stated rather than hidden: an
 * exception's *message* is part of its stack trace, and a message
 * could in principle quote a value from the crashing call. The
 * detail screen shows the full text before the user shares or
 * copies anything, so nothing leaves the device unseen.
 *
 * Pure JVM — no Android calls — so everything here is unit-tested
 * on plain JVM.
 */
object CrashReportFormatter {

    private val FILE_NAME = Regex("""^crash-(\d+)-(.+)\.txt$""")

    /** Characters outside this set are replaced with '_' in file names. */
    private val SAFE_TYPE = Regex("""[^A-Za-z0-9._$-]""")

    /**
     * The file name for a report. [crashType] is the exception's
     * simple name; anything filename-unsafe in it becomes '_'.
     */
    fun fileNameFor(timestampMillis: Long, crashType: String): String {
        val safeType = crashType.replace(SAFE_TYPE, "_").ifBlank { "Unknown" }
        return "crash-$timestampMillis-$safeType.txt"
    }

    /**
     * Parses a file name back into a summary, or null when the name
     * is not a report file (stray files in the directory are ignored
     * by the store, never shown).
     */
    fun parseFileName(name: String): CrashReportSummary? {
        val match = FILE_NAME.matchEntire(name) ?: return null
        val timestampMillis = match.groupValues[1].toLongOrNull() ?: return null
        return CrashReportSummary(
            id = name,
            timestampMillis = timestampMillis,
            crashType = match.groupValues[2],
        )
    }

    /**
     * The full report text: metadata header, a blank line, then the
     * stack trace with its causes (what `Throwable.printStackTrace`
     * writes).
     */
    fun format(metadata: CrashMetadata, throwable: Throwable): String {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(metadata.timestampMillis))
        return buildString {
            appendLine("Relay crash report")
            appendLine("Date: $date")
            appendLine("App version: ${metadata.appVersion} (${metadata.buildType})")
            appendLine("Android: ${metadata.androidRelease} (API ${metadata.androidSdk})")
            appendLine("Device: ${metadata.deviceModel}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine()
            append(trace)
        }
    }
}
