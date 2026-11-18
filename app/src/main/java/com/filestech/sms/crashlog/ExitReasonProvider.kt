package com.filestech.sms.crashlog

import android.app.ActivityManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Local-only error log — one exit record for a previous process:
 * why it died, and when (wall-clock millis, as the system
 * recorded it).
 */
data class SessionExitInfo(
    /** Why the process died. */
    val reason: SessionExitReason,
    /** When the process died, epoch millis. */
    val timestampMillis: Long,
)

/**
 * Local-only error log — the seam between the startup check and
 * the system's process-exit history. The real implementation
 * queries `ActivityManager`; tests substitute a fake returning
 * canned records. A null result means "no record" — below
 * API 30, or when the system kept nothing for the process — and
 * the classifier falls back to the marker heuristic.
 */
interface ExitReasonProvider {
    /**
     * The most recent exit record for the previous session's
     * process, or null when the system has none for it.
     */
    fun mostRecentExit(marker: SessionMarker): SessionExitInfo?
}

/**
 * Local-only error log — the real [ExitReasonProvider], backed by
 * `ActivityManager.getHistoricalProcessExitReasons` (API 30+).
 *
 * The query is scoped to the previous session's pid (stored in
 * the marker), so it can only match our own dead process — never
 * another app's, and never an older session's. The timestamp is
 * checked against the session's start as well: pids are reused,
 * so an entry older than the session is a stale record for a
 * different process and is ignored.
 *
 * The API-30 call lives in [query], annotated `@RequiresApi`,
 * with the version check in [mostRecentExit] — the split keeps
 * the query unit-testable (on plain JVM `SDK_INT` is 0, so an
 * inline guard would make every test return null) while the
 * guard still satisfies NewApi lint at the only production
 * call site.
 */
class ActivityManagerExitReasonProvider(
    private val activityManager: ActivityManager,
    private val packageName: String,
) : ExitReasonProvider {

    override fun mostRecentExit(marker: SessionMarker): SessionExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return query(marker)
    }

    /**
     * The actual query. Never throws: a binder failure, an empty
     * history, or a stale entry all answer null (no record), and
     * the classifier falls back to the marker heuristic.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun query(marker: SessionMarker): SessionExitInfo? {
        val reasons = runCatching {
            activityManager.getHistoricalProcessExitReasons(packageName, marker.pid, 1)
        }.getOrNull() ?: return null
        val info = reasons.firstOrNull() ?: return null
        val exit = SessionExitInfo(
            reason = SessionExitReason.fromReasonCode(info.reason),
            timestampMillis = info.timestamp,
        )
        return if (exit.timestampMillis < marker.sessionStartMillis) null else exit
    }
}
