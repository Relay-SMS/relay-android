package com.filestech.sms.crashlog

/**
 * Local-only error log — decides whether a previous session's
 * death (a marker left behind, no crash report) deserves an
 * entry in the error log.
 *
 * The rule is deliberately narrow about silence: only deaths the
 * USER asked for are silent (swipe-away, force-stop, and the
 * permission-change kill, which is the system doing what the user
 * asked). Everything else — ANR, crash, native crash, low
 * memory, and the rest — is reported, because an unexplained
 * death is exactly what the marker exists to surface. A new
 * reason added to [SessionExitReason] must be placed in one of
 * the two branches: the `when` is exhaustive on purpose, so the
 * compiler refuses a silent default.
 *
 * Below API 30 there is no exit reason to read (min SDK is 26),
 * so the marker itself decides: a session last seen in the
 * foreground died unexpectedly (a user does not swipe an app
 * away while looking at it — the background transition is
 * recorded first), while a session last seen in the background
 * is treated as a normal system kill and stays silent.
 *
 * Pure — no Android calls — so every reason is unit-tested.
 */
object SessionDeathClassifier {

    /** What the startup check does with a previous session's death. */
    enum class Outcome {
        /** Write an "unexpected stop" entry to the error log. */
        REPORT,

        /** Clear the marker silently; the death was expected. */
        SILENT,
    }

    /**
     * Classifies a death. [exitReason] is the system's record
     * (API 30+), or null when there is none — below API 30, or
     * when the system kept no record for the process — in which
     * case the marker's foreground state decides (see above).
     */
    fun classify(exitReason: SessionExitReason?, marker: SessionMarker): Outcome {
        val reason = exitReason ?: return if (marker.foreground) Outcome.REPORT else Outcome.SILENT
        return when (reason) {
            SessionExitReason.USER_REQUESTED,
            SessionExitReason.USER_STOPPED,
            SessionExitReason.PERMISSION_CHANGE,
            -> Outcome.SILENT

            SessionExitReason.UNKNOWN,
            SessionExitReason.EXIT_SELF,
            SessionExitReason.SIGNALED,
            SessionExitReason.LOW_MEMORY,
            SessionExitReason.CRASH,
            SessionExitReason.CRASH_NATIVE,
            SessionExitReason.ANR,
            SessionExitReason.INITIALIZATION_FAILURE,
            SessionExitReason.EXCESSIVE_RESOURCE_USAGE,
            SessionExitReason.DEPENDENCY_DIED,
            SessionExitReason.OTHER,
            -> Outcome.REPORT
        }
    }
}
