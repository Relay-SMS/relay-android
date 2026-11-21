package com.filestech.sms.crashlog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local-only error log — the on/off switch for the whole error
 * log: crash reports, the session marker, and the startup
 * death check. Round 36 — one flag gates every writer (see
 * [CrashLogHandler], [SessionMarkerTracker],
 * [SessionDeathChecker]); each of them reads it live through a
 * `() -> Boolean`, so flipping the switch mid-session takes
 * effect at once — no writer trusts a value captured at
 * startup.
 *
 * SharedPreferences, not DataStore, on purpose: the crash
 * handler runs on a dying thread and must read the flag
 * synchronously; a DataStore read there would block the crash
 * path. The value is a single boolean, so there is nothing a
 * DataStore would buy here.
 *
 * Default ON: the error log has always been on, and turning it
 * off is the user's explicit choice. The flag lives in its own
 * preferences file, separate from the app settings, so it
 * survives a "Reset all" — resetting preferences must not
 * silently re-enable logging the user turned off.
 *
 * The change listener is registered for the life of the
 * process: this class is a Hilt singleton, and the two
 * instances built at startup (handler, tracker) are held by
 * their install lambdas for the process lifetime anyway.
 */
class CrashLogSettings(context: Context) {

    companion object {
        /** Preferences file for the error-log switch. */
        const val PREFS_NAME: String = "crash-log"

        /** The on/off flag inside [PREFS_NAME]. */
        const val KEY_ENABLED: String = "enabled"

        /** The error log is on unless the user turns it off. */
        const val DEFAULT_ENABLED: Boolean = true
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabledFlow = MutableStateFlow(enabled)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENABLED) {
            _enabledFlow.value = enabled
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Whether the error log is on. Read live on every call. */
    val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** The flag as a flow, for the Settings toggle. */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    /** Turns the error log on or off. */
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
