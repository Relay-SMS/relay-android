package com.filestech.sms

/**
 * The build marker shown on the Encryption and About screens:
 * "Build <sha> · <debug|release> · <date>".
 *
 * The user-facing version never changes per round, so nothing else
 * on screen distinguishes builds — a screenshot of this line proves
 * which build is installed. The commit + date come from BuildConfig
 * (set in `app/build.gradle.kts` at configuration time); the build
 * type comes from `BuildConfig.LOG_ENABLED`, the same signal
 * CrashLogHandler uses (true = debug).
 *
 * Pure Kotlin apart from [current], which reads BuildConfig.
 */
object BuildMarker {

    /** Formats a marker from its parts (pure, unit tested). */
    fun format(commit: String, isDebug: Boolean, date: String): String =
        "Build $commit · ${if (isDebug) "debug" else "release"} · $date"

    /** The marker for this build. */
    fun current(): String =
        format(BuildConfig.GIT_COMMIT, BuildConfig.LOG_ENABLED, BuildConfig.BUILD_DATE)
}
