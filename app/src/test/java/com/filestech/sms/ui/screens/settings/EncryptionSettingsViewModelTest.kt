package com.filestech.sms.ui.screens.settings

import com.filestech.sms.crashlog.CrashLogSettings
import com.filestech.sms.crashlog.CrashReportStore
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.EncryptionSettings
import com.filestech.sms.e2e.E2eAccountManager
import com.filestech.sms.e2e.E2eCryptoException
import com.filestech.sms.e2e.E2eFailureRecorder
import com.filestech.sms.e2e.E2ePeerRepository
import com.filestech.sms.e2e.IdentityKeys
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The Encryption settings ViewModel: the fingerprint loads on
 * success, and a failed identity load surfaces the reason in
 * state (instead of a silently missing row, as in round 38)
 * and records it in the local error log. Round 40: the
 * discovery toggle (outbound probing, default ON) is read from
 * settings and written back on flip.
 */
class EncryptionSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var store: CrashReportStore
    private lateinit var settings: CrashLogSettings
    private lateinit var recorder: E2eFailureRecorder
    private lateinit var peers: E2ePeerRepository
    private lateinit var accountManager: E2eAccountManager
    private lateinit var contacts: ContactRepository
    private lateinit var appSettings: FakeAppSettingsSource

    /** A settings source holding one value, recording writes. */
    private class FakeAppSettingsSource(initial: AppSettings) : AppSettingsSource {
        var current: AppSettings = initial
            private set

        override val flow: Flow<AppSettings> = flowOf(initial)
        override val state: StateFlow<AppSettings> = MutableStateFlow(initial)

        override suspend fun hydratedOrNull(): AppSettings? = current

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            current = transform(current)
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("encryption-vm-test").toFile()
        store = CrashReportStore(File(tempDir, "crash-reports"))
        settings = mockk { every { enabled } returns true }
        recorder = E2eFailureRecorder(store, settings)
        peers = mockk { every { observeVerifiedPeers() } returns emptyFlow() }
        accountManager = mockk()
        contacts = mockk()
        appSettings = FakeAppSettingsSource(AppSettings())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun viewModel() =
        EncryptionSettingsViewModel(
            peers,
            accountManager,
            contacts,
            recorder,
            appSettings,
            dispatcher,
        )

    @Test
    fun `successful identity load shows the fingerprint with no error`() {
        coEvery { accountManager.identityKeys() } returns
            IdentityKeys(ByteArray(32), ByteArray(32))

        val state = viewModel().state.value

        assertThat(state.fingerprint).isNotNull()
        assertThat(state.fingerprintLoaded).isTrue()
        assertThat(state.fingerprintError).isNull()
        assertThat(store.listReports()).isEmpty()
    }

    @Test
    fun `failed identity load sets the error state and records the failure`() {
        coEvery { accountManager.identityKeys() } throws
            E2eCryptoException("end-to-end encryption is unavailable on this device")

        val state = viewModel().state.value

        assertThat(state.fingerprint).isNull()
        assertThat(state.fingerprintLoaded).isTrue()
        assertThat(state.fingerprintError).isEqualTo(
            "E2eCryptoException: end-to-end encryption is unavailable on this device",
        )
        assertThat(store.listReports()).hasSize(1)
    }

    @Test
    fun `error text names the root cause when the failure is wrapped`() {
        coEvery { accountManager.identityKeys() } throws E2eCryptoException(
            "vodozemac failed to pickle account",
            UnsatisfiedLinkError("JNI_ERR returned from JNI_OnLoad"),
        )

        val state = viewModel().state.value

        assertThat(state.fingerprintError).isEqualTo(
            "E2eCryptoException: vodozemac failed to pickle account " +
                "(caused by UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad)",
        )
    }

    @Test
    fun `error log off still shows the error but records nothing`() {
        every { settings.enabled } returns false
        coEvery { accountManager.identityKeys() } throws E2eCryptoException("boom")

        val state = viewModel().state.value

        assertThat(state.fingerprintError).isEqualTo("E2eCryptoException: boom")
        assertThat(store.listReports()).isEmpty()
    }

    @Test
    fun `reopening the screen does not record the same failure twice`() {
        coEvery { accountManager.identityKeys() } throws E2eCryptoException("boom")

        viewModel()
        viewModel()

        assertThat(store.listReports()).hasSize(1)
    }

    @Test
    fun `discovery off in settings shows the toggle off`() {
        appSettings = FakeAppSettingsSource(
            AppSettings(encryption = EncryptionSettings(autoDiscoverRelayContacts = false)),
        )

        val state = viewModel().state.value

        assertThat(state.autoDiscoverRelayContacts).isFalse()
    }

    @Test
    fun `flipping the toggle writes the setting`() {
        val vm = viewModel()

        vm.setAutoDiscoverRelayContacts(false)

        assertThat(appSettings.current.encryption.autoDiscoverRelayContacts).isFalse()
    }
}
