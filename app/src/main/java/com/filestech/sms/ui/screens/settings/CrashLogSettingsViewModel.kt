package com.filestech.sms.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.filestech.sms.crashlog.CrashLogSettings
import com.filestech.sms.crashlog.SessionMarkerTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Round 36 — the error-log on/off switch (Settings → Advanced).
 *
 * The flag itself lives in [CrashLogSettings] and is read live
 * by every log writer, so flipping it takes effect at once.
 * This ViewModel only handles the session marker's edges,
 * which the flag alone cannot reach:
 *
 * - Turning OFF deletes the current session's marker right
 *   away, so the next startup does not record this session's
 *   end as an unexpected stop — logging off means no trace.
 * - Turning ON restarts the marker for this session, so the
 *   session is tracked again without waiting for a relaunch.
 *   (The crash handler needs no restart: it reads the flag at
 *   crash time.)
 *
 * Saved reports are NOT touched by the switch: turning the log
 * off stops new writes, it does not delete what is already
 * saved — the Error log screen's Clear all does that.
 */
@HiltViewModel
class CrashLogSettingsViewModel @Inject constructor(
    private val settings: CrashLogSettings,
) : ViewModel() {

    /** Whether the error log is on. */
    val enabled: StateFlow<Boolean> = settings.enabledFlow

    /** Turns the error log on or off. A no-op when unchanged. */
    fun setEnabled(value: Boolean) {
        if (value == settings.enabled) return
        settings.setEnabled(value)
        if (value) {
            SessionMarkerTracker.current?.restartSession()
        } else {
            SessionMarkerTracker.current?.clearMarker()
        }
    }
}
