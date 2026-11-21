package com.filestech.sms.crashlog

/**
 * Local-only error log — the startup check for a previous
 * session that died without a Java exception.
 *
 * Called once per cold start, after the crash handler is
 * installed and the new session's marker is written (so the old
 * marker is already replaced — this check never deletes
 * anything). A null marker means the previous session ended
 * cleanly or left a crash report (the handler deletes the marker
 * when it records one), so there is nothing to do.
 *
 * Otherwise the system's exit record for the previous process
 * (API 30+, via [ExitReasonProvider]) decides, with the marker
 * heuristic as the fallback below API 30 or when the system kept
 * no record — see [SessionDeathClassifier]. A death worth
 * reporting becomes an "unexpected stop" entry in the same store
 * as the crash reports, timestamped at the death (the exit
 * record's time when there is one, else the marker's last-alive
 * time), so it sorts with the reports and counts toward the
 * same 10-report rotation.
 *
 * Never throws: a failed provider query or a failed write just
 * means the death goes unrecorded.
 *
 * Round 36 — the error-log switch ([CrashLogSettings]): the
 * check is a writer, so it reads the flag live too. When the
 * log is off, no entry is written — the marker is normally
 * already gone ([SessionMarkerTracker] deletes it when the log
 * is off), so this is the second line of defence, not the
 * first.
 */
class SessionDeathChecker(
    private val store: CrashReportStore,
    private val exitReasonProvider: ExitReasonProvider?,
    private val enabled: () -> Boolean = { true },
) {

    /**
     * Checks the previous session's marker and writes an entry
     * when the death was unexpected. [metadata] is this launch's
     * build/device metadata (the header of the entry).
     */
    fun check(marker: SessionMarker?, metadata: CrashMetadata) {
        if (!enabled()) return
        val previous = marker ?: return
        val exit = runCatching { exitReasonProvider?.mostRecentExit(previous) }.getOrNull()
        if (SessionDeathClassifier.classify(exit?.reason, previous) == SessionDeathClassifier.Outcome.SILENT) {
            return
        }
        val deathMillis = exit?.timestampMillis ?: previous.lastAliveMillis
        val text = CrashReportFormatter.formatUnexpectedStop(
            metadata.copy(timestampMillis = deathMillis),
            previous,
            exit?.reason,
        )
        store.writeReport(text, deathMillis, CrashReportFormatter.UNEXPECTED_STOP_TYPE)
    }
}
