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
 * A second body kind exists for sessions that died WITHOUT an
 * exception ([formatUnexpectedStop]): same metadata header, then
 * what the session marker knew (exit reason, session times, last
 * screen, sync state) instead of a stack trace.
 *
 * A third body kind exists for non-fatal failures recorded
 * deliberately ([formatDiagnostic]): a short header naming the
 * caller-supplied tag, then the full stack trace. No build/device
 * header — the entry carries the exception chain, which is the
 * diagnostic payload — and the header says "diagnostic", not
 * "crash report", so the detail screen never presents a
 * survived failure as a crash.
 *
 * Pure JVM — no Android calls — so everything here is unit-tested
 * on plain JVM.
 */
object CrashReportFormatter {

    private val FILE_NAME = Regex("""^crash-(\d+)-(.+)\.txt$""")

    /** Characters outside this set are replaced with '_' in file names. */
    private val SAFE_TYPE = Regex("""[^A-Za-z0-9._$-]""")

    /**
     * The crash type of an "unexpected stop" entry — a session
     * that died without a Java exception (see
     * [formatUnexpectedStop]). The Error log screen maps it to a
     * localized title; the file name carries this stable
     * identifier, never the localized wording.
     */
    const val UNEXPECTED_STOP_TYPE: String = "UnexpectedStop"

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
        return buildString {
            appendLine("Relay crash report")
            appendLine("Date: ${formatDate(metadata.timestampMillis)}")
            appendLine("App version: ${metadata.appVersion} (${metadata.buildType})")
            appendLine("Android: ${metadata.androidRelease} (API ${metadata.androidSdk})")
            appendLine("Device: ${metadata.deviceModel}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine()
            append(stackTrace(throwable))
        }
    }

    /**
     * The body of a diagnostic entry: a non-fatal failure recorded
     * deliberately in the error log (see
     * [com.filestech.sms.e2e.E2eFailureRecorder]). The header names
     * the caller's [tag] and the exception class, then the full
     * stack trace with its causes — the chain is the diagnostic
     * payload, so the wrapper and the failure it wraps both show.
     */
    fun formatDiagnostic(tag: String, timestampMillis: Long, throwable: Throwable): String {
        return buildString {
            appendLine("Relay diagnostic — $tag")
            appendLine("Date: ${formatDate(timestampMillis)}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine()
            append(stackTrace(throwable))
        }
    }

    /** What `Throwable.printStackTrace` writes, causes included. */
    private fun stackTrace(throwable: Throwable): String =
        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

    /**
     * The body of an "unexpected stop" entry: a session that died
     * without a Java exception, so there is no stack trace — the
     * entry carries what the session marker knew instead (see
     * [SessionMarker]). The header date is the death time, which
     * the caller sets as [CrashMetadata.timestampMillis] (the exit
     * record's time when there is one, else the marker's
     * last-alive time). [exitReason] is the system's reason, or
     * null below API 30 / when the system kept no record.
     */
    fun formatUnexpectedStop(
        metadata: CrashMetadata,
        marker: SessionMarker,
        exitReason: SessionExitReason?,
    ): String {
        return buildString {
            appendLine("App stopped unexpectedly (no crash recorded)")
            appendLine("Date: ${formatDate(metadata.timestampMillis)}")
            appendLine("App version: ${metadata.appVersion} (${metadata.buildType})")
            appendLine("Android: ${metadata.androidRelease} (API ${metadata.androidSdk})")
            appendLine("Device: ${metadata.deviceModel}")
            appendLine("Exit reason: ${exitReason?.displayName ?: "unknown"}")
            appendLine("Session started: ${formatDate(marker.sessionStartMillis)}")
            appendLine("Last alive: ${formatDate(marker.lastAliveMillis)}")
            appendLine("Foreground: ${if (marker.foreground) "yes" else "no"}")
            appendLine("Last screen: ${marker.screen ?: "unknown"}")
            appendLine("Sync state: ${marker.syncState ?: "unknown"}")
            appendLine()
            appendLine(
                "No Java exception was recorded for this session: the process died without " +
                    "throwing (a freeze, a system kill, or a native crash).",
            )
        }
    }

    /** One timestamp in the header format (UTC, ISO-like). Shared by both report kinds. */
    private fun formatDate(timestampMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMillis))
    }
}
