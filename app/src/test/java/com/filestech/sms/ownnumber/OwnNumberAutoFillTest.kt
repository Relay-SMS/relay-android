package com.filestech.sms.ownnumber

import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Group-send own-number fix (b) — the startup auto-fill of "My phone
 * number" from the SIM. Drives the injectable [autoFillOwnNumber]
 * directly (the `OwnNumberAutoFill` wrapper only binds it to the real
 * SIM read); no Android framework involved.
 */
class OwnNumberAutoFillTest {

    /** In-memory settings; [setDirectly] simulates a write landing mid-detection. */
    private class FakeOwnNumberSettings(initial: AppSettings) : AppSettingsSource {
        private val _state = MutableStateFlow(initial)
        override val flow: Flow<AppSettings> = _state.asStateFlow()
        override val state: StateFlow<AppSettings> = _state.asStateFlow()
        override suspend fun hydratedOrNull(): AppSettings = _state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            _state.value = transform(_state.value)
        }

        fun setDirectly(settings: AppSettings) {
            _state.value = settings
        }
    }

    private fun settingsWith(mode: DeviceMode, msisdn: String?): FakeOwnNumberSettings {
        return FakeOwnNumberSettings(
            AppSettings(mode = mode, sending = AppSettings().sending.copy(userMsisdn = msisdn)),
        )
    }

    @Test
    fun `blank setting and SIM number found - fills the setting`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            autoFillOwnNumber(settings) { "+33612345678" }
            assertThat(settings.state.value.sending.userMsisdn).isEqualTo("+33612345678")
        }
    }

    @Test
    fun `number already set - leaves it alone and never reads the SIM`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, "+33699999999")
            var reads = 0
            autoFillOwnNumber(settings) {
                reads++
                "+33612345678"
            }
            assertThat(reads).isEqualTo(0)
            assertThat(settings.state.value.sending.userMsisdn).isEqualTo("+33699999999")
        }
    }

    @Test
    fun `SIM read returns nothing - leaves the setting blank`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            autoFillOwnNumber(settings) { null }
            assertThat(settings.state.value.sending.userMsisdn).isNull()
        }
    }

    @Test
    fun `SIM read returns a blank string - leaves the setting blank`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            autoFillOwnNumber(settings) { "   " }
            assertThat(settings.state.value.sending.userMsisdn).isNull()
        }
    }

    @Test
    fun `client mode - skips everything and never reads the SIM`() {
        runTest {
            val settings = settingsWith(DeviceMode.CLIENT, null)
            var reads = 0
            autoFillOwnNumber(settings) {
                reads++
                "+33612345678"
            }
            assertThat(reads).isEqualTo(0)
            assertThat(settings.state.value.sending.userMsisdn).isNull()
        }
    }

    @Test
    fun `detection throws - fails soft, setting stays blank`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            autoFillOwnNumber(settings) { throw SecurityException("no permission") }
            assertThat(settings.state.value.sending.userMsisdn).isNull()
        }
    }

    @Test
    fun `number typed while detection runs - keeps the typed number`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            autoFillOwnNumber(settings) {
                // The user confirms a number in Settings while the
                // SIM read is in flight: the re-check inside the
                // update must let the typed number win.
                settings.setDirectly(
                    AppSettings(
                        mode = DeviceMode.HUB,
                        sending = AppSettings().sending.copy(userMsisdn = "+33655555555"),
                    ),
                )
                "+33612345678"
            }
            assertThat(settings.state.value.sending.userMsisdn).isEqualTo("+33655555555")
        }
    }
}
