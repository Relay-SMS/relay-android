package com.filestech.sms.crashlog

/**
 * Local-only error log — one saved crash report, as listed on the
 * Error log screen (Settings → Advanced).
 *
 * The report file itself (see [CrashReportFormatter]) holds a
 * metadata header plus the stack trace. No message content, phone
 * numbers, contact names, or addresses are ever written: the header
 * is build/device metadata only, and a stack trace names code
 * locations. Reports stay on the device in app-private storage; the
 * only way one leaves is the user tapping Share or Copy on the
 * detail screen. There is no network, no auto-upload, and no
 * telemetry SDK behind any of this.
 */
data class CrashReportSummary(
    /** File name inside the reports directory; also the stable id. */
    val id: String,
    /** When the crash happened, epoch millis. */
    val timestampMillis: Long,
    /** Simple name of the thrown exception (e.g. "NullPointerException"). */
    val crashType: String,
)

/**
 * Device + build metadata written at the head of every report.
 * Plain data — the Android-dependent collection lives in
 * [CrashLogHandler], so the formatter stays testable on plain JVM.
 */
data class CrashMetadata(
    /** e.g. "1.29.2" — [com.filestech.sms.BuildConfig.VERSION_NAME]. */
    val appVersion: String,
    /** "debug" or "release". */
    val buildType: String,
    /** e.g. "14" — [android.os.Build.VERSION.RELEASE]. */
    val androidRelease: String,
    /** e.g. 34 — [android.os.Build.VERSION.SDK_INT]. */
    val androidSdk: Int,
    /** e.g. "Pixel 8" — [android.os.Build.MODEL]. */
    val deviceModel: String,
    /** When the crash happened, epoch millis. */
    val timestampMillis: Long,
)
