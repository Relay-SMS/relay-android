package com.filestech.sms.ownnumber

import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Group-send own-number fix — the just-in-time own-number check
 * before a group MMS send. Drives the injectable
 * [ensureOwnNumberForGroupSend] directly (the `OwnNumberEnsurerImpl`
 * wrapper only binds it to the real auto-fill and the real settings
 * store); no Android framework involved.
 *
 * The guard this feeds is pinned in `OwnNumberGroupSendTest` in
 * `:domain` (proceed on `true`, fail fast with `OwnNumberMissing`
 * on `false`); what is pinned here is the check itself — when
 * detection runs, and what a failed detection means.
 */
class OwnNumberEnsurerImplTest {

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

    /** A settings store that cannot be read at all. */
    private class UnreadableSettings : AppSettingsSource {
        private val value = AppSettings()
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings? = null
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun settingsWith(mode: DeviceMode, msisdn: String?): FakeOwnNumberSettings {
        return FakeOwnNumberSettings(
            AppSettings(mode = mode, sending = AppSettings().sending.copy(userMsisdn = msisdn)),
        )
    }

    @Test
    fun `number already set - returns true without running detection`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, "+33699999999")
            var runs = 0
            val ensured = ensureOwnNumberForGroupSend(settings) { runs++ }
            assertThat(ensured).isTrue()
            assertThat(runs).isEqualTo(0)
        }
    }

    @Test
    fun `blank number, auto-fill fills it - returns true and the number is on file`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            // The real auto-fill seam, with a SIM that answers: the
            // post-fill re-read must see the just-persisted number.
            val ensured = ensureOwnNumberForGroupSend(settings) {
                autoFillOwnNumber(settings) { "+33612345678" }
            }
            assertThat(ensured).isTrue()
            assertThat(settings.state.value.sending.userMsisdn).isEqualTo("+33612345678")
        }
    }

    @Test
    fun `blank number, auto-fill finds nothing - returns false`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            val ensured = ensureOwnNumberForGroupSend(settings) {
                autoFillOwnNumber(settings) { null }
            }
            assertThat(ensured).isFalse()
            assertThat(settings.state.value.sending.userMsisdn).isNull()
        }
    }

    @Test
    fun `detection throws - fails soft, returns false, no throw escapes`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            // A throwing detector is a failed detection: still blank,
            // so the caller fails fast instead of hanging — and the
            // send path never sees the exception.
            val ensured = ensureOwnNumberForGroupSend(settings) {
                throw SecurityException("no permission")
            }
            assertThat(ensured).isFalse()
            assertThat(settings.state.value.sending.userMsisdn).isNull()
        }
    }

    @Test
    fun `number typed while detection runs - returns true`() {
        runTest {
            val settings = settingsWith(DeviceMode.HUB, null)
            val ensured = ensureOwnNumberForGroupSend(settings) {
                // The user confirms a number in Settings while the
                // SIM read is in flight: the re-read must find it —
                // a "did the fill happen" boolean would say no.
                settings.setDirectly(
                    AppSettings(
                        mode = DeviceMode.HUB,
                        sending = AppSettings().sending.copy(userMsisdn = "+33655555555"),
                    ),
                )
            }
            assertThat(ensured).isTrue()
        }
    }

    @Test
    fun `client mode - returns true without running detection`() {
        runTest {
            val settings = settingsWith(DeviceMode.CLIENT, null)
            var runs = 0
            val ensured = ensureOwnNumberForGroupSend(settings) { runs++ }
            assertThat(ensured).isTrue()
            assertThat(runs).isEqualTo(0)
        }
    }

    @Test
    fun `unreadable settings - returns true, fail-open`() {
        runTest {
            val settings = UnreadableSettings()
            var runs = 0
            val ensured = ensureOwnNumberForGroupSend(settings) { runs++ }
            // Nothing here can verify the number, so the send
            // proceeds as it did before this guard existed.
            assertThat(ensured).isTrue()
            assertThat(runs).isEqualTo(0)
        }
    }
}
