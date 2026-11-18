package com.filestech.sms.crashlog

import android.content.Context
import android.os.Build
import android.os.Process
import com.filestech.sms.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Local-only error log — installs the uncaught-exception handler
 * that writes crash reports.
 *
 * Installed once, first thing in `MainApplication.onCreate`, so
 * even a startup crash is captured. On a crash the handler writes
 * one report file (metadata header + stack trace, see
 * [CrashReportFormatter]) and then chains to the previous handler,
 * so the app still dies exactly as it would without us — the system
 * crash dialog appears, nothing is swallowed, no restart is
 * attempted.
 *
 * The handler runs on the crashing thread while the process is
 * dying, so it is deliberately minimal: one synchronous file write
 * of a few KB, wrapped in `runCatching` — a failure to write is
 * silently dropped, never allowed to throw inside the handler.
 * There is no network, no upload, and no third-party code on this
 * path; the report only leaves the device if the user shares it
 * from the Error log screen.
 */
object CrashLogHandler {

    private val installed = AtomicBoolean(false)

    /** Installs the handler. Idempotent — a second call is a no-op. */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val store = CrashReportStore(File(context.filesDir, CrashReportStore.DIR_NAME))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val metadata = collectMetadata()
                val text = CrashReportFormatter.format(metadata, throwable)
                val crashType = throwable.javaClass.simpleName.ifBlank { "Unknown" }
                store.writeReport(text, metadata.timestampMillis, crashType)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // Defensive only: the framework always installs a
                // default handler, so this is unreachable on device.
                // Without a chain the process would linger half-dead.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * Build/device metadata for the report header. `LOG_ENABLED` is
     * the codebase's debug/release discriminator (true in debug,
     * false in release — see `app/build.gradle.kts`); it is also
     * what the local harness's BuildConfig stub provides, where
     * `BuildConfig.DEBUG` does not exist.
     */
    private fun collectMetadata(): CrashMetadata {
        return CrashMetadata(
            appVersion = BuildConfig.VERSION_NAME,
            buildType = if (BuildConfig.LOG_ENABLED) "debug" else "release",
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL ?: "unknown",
            timestampMillis = System.currentTimeMillis(),
        )
    }
}
