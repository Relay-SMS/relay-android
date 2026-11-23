package com.filestech.sms.crashlog

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The error-log on/off switch (Settings → Advanced): the Flow
 * the toggle reads must follow [CrashLogSettings.setEnabled]
 * synchronously — on device the switch did not redraw when
 * tapped (round 44), because the Flow only moved when the
 * SharedPreferences listener delivered, and it did not.
 *
 * Plain JVM: the Android types are MockK mocks (no framework
 * calls run), and the prefs mock never fires the listener
 * unless a test fires it by hand — so a Flow update after
 * [CrashLogSettings.setEnabled] proves the direct update, not
 * the listener.
 */
class CrashLogSettingsTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var listenerSlot: CapturingSlot<SharedPreferences.OnSharedPreferenceChangeListener>
    private lateinit var settings: CrashLogSettings

    @BeforeEach
    fun setUp() {
        prefs = mockk {
            every { getBoolean(CrashLogSettings.KEY_ENABLED, CrashLogSettings.DEFAULT_ENABLED) } returns true
        }
        editor = mockk()
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } just Runs
        listenerSlot = slot()
        every { prefs.edit() } returns editor
        every { prefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs
        val context = mockk<Context> {
            every { getSharedPreferences(CrashLogSettings.PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
        }
        settings = CrashLogSettings(context)
    }

    @Test
    fun `enabledFlow starts at the stored pref value`() {
        val offPrefs = mockk<SharedPreferences> {
            every { getBoolean(CrashLogSettings.KEY_ENABLED, CrashLogSettings.DEFAULT_ENABLED) } returns false
            every { registerOnSharedPreferenceChangeListener(any()) } just Runs
        }
        val offContext = mockk<Context> {
            every { getSharedPreferences(CrashLogSettings.PREFS_NAME, Context.MODE_PRIVATE) } returns offPrefs
        }

        val offSettings = CrashLogSettings(offContext)

        assertThat(offSettings.enabledFlow.value).isFalse()
        // The default is ON: a fresh install (no stored value)
        // reads true.
        verify { offPrefs.getBoolean(CrashLogSettings.KEY_ENABLED, true) }
    }

    @Test
    fun `setEnabled updates enabledFlow synchronously without waiting for the listener`() {
        assertThat(settings.enabledFlow.value).isTrue()

        settings.setEnabled(false)

        // The prefs mock never fires the listener — this update
        // is the direct Flow write in setEnabled (round 44).
        assertThat(settings.enabledFlow.value).isFalse()
        verify { editor.putBoolean(CrashLogSettings.KEY_ENABLED, false) }
        verify { editor.apply() }
    }

    @Test
    fun `setEnabled back on updates enabledFlow too`() {
        settings.setEnabled(false)
        assertThat(settings.enabledFlow.value).isFalse()

        settings.setEnabled(true)

        assertThat(settings.enabledFlow.value).isTrue()
    }

    @Test
    fun `the change listener updates enabledFlow when it fires`() {
        // The listener still works when it delivers — the round-44
        // bug was delivery, not the listener logic. A change to
        // another key must not move the Flow.
        every { prefs.getBoolean(any(), any()) } returns false
        listenerSlot.captured.onSharedPreferenceChanged(prefs, "some-other-key")
        assertThat(settings.enabledFlow.value).isTrue()

        listenerSlot.captured.onSharedPreferenceChanged(prefs, CrashLogSettings.KEY_ENABLED)

        assertThat(settings.enabledFlow.value).isFalse()
    }
}
