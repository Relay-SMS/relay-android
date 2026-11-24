package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.e2e.E2ePeerRepository
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.sync.client.ClientSyncEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * E2E list badges — [ConversationsViewModel.encryptedAddresses] is
 * the row lock badge's source in BOTH modes. On a linked device the
 * peer rows are DERIVED (the client mirror records ENCRYPTED from
 * the synced conversation flag, phase 2), so the flow must observe
 * the peer repository in client mode too — it used to answer an
 * empty set there, and no client row ever showed its badge.
 */
class ConversationsEncryptedBadgeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `client mode observes the derived encrypted set`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(setOf(PEER))
        val vm = viewModel(DeviceMode.CLIENT, peerFlow, backgroundScope)

        assertThat(vm.encryptedAddresses.first { it == setOf(PEER) }).containsExactly(PEER)
    }

    @Test
    fun `hub mode observes the encrypted set`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(setOf(PEER))
        val vm = viewModel(DeviceMode.HUB, peerFlow, backgroundScope)

        assertThat(vm.encryptedAddresses.first { it == setOf(PEER) }).containsExactly(PEER)
    }

    @Test
    fun `no peers means an empty set in client mode`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(emptySet<String>())
        val vm = viewModel(DeviceMode.CLIENT, peerFlow, backgroundScope)

        vm.encryptedAddresses.test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a peer flip re-emits the set in client mode`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(emptySet<String>())
        val vm = viewModel(DeviceMode.CLIENT, peerFlow, backgroundScope)

        vm.encryptedAddresses.test {
            assertThat(awaitItem()).isEmpty()
            peerFlow.value = setOf(PEER)
            assertThat(awaitItem()).containsExactly(PEER)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel(
        mode: DeviceMode,
        peerFlow: MutableStateFlow<Set<String>>,
        appScope: CoroutineScope,
    ): ConversationsViewModel {
        val settingsFlow = MutableStateFlow(AppSettings(mode = mode))
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeAll(any()) } returns MutableStateFlow(emptyList())
            every { observeMessageSearch(any(), any()) } returns
                flowOf<List<MessageSearchHit>>(emptyList())
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { flow } returns settingsFlow
            every { state } returns settingsFlow
        }
        val syncManager: TelephonySyncManager = mockk(relaxed = true) {
            every { state } returns
                MutableStateFlow<TelephonySyncManager.State>(TelephonySyncManager.State.Idle)
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns
                MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Unlocked)
        }
        val noteRepo: NoteRepository = mockk(relaxed = true) {
            every { observeAllNotes() } returns MutableStateFlow(emptyList())
        }
        val peers: E2ePeerRepository = mockk {
            every { observeEncryptedAddresses() } returns peerFlow
            // Round 49 — the badge reads the group-encrypted set
            // too; these 1:1 tests keep it empty.
            every { observeGroupEncryptedAddresses() } returns flowOf(emptySet())
        }
        return ConversationsViewModel(
            savedStateHandle = SavedStateHandle(),
            repo = repo,
            settings = settings,
            toggle = mockk(relaxed = true),
            mirror = mockk(relaxed = true),
            contacts = mockk<ContactRepository>(relaxed = true),
            syncManager = syncManager,
            appLock = appLock,
            blockNumber = mockk(relaxed = true),
            unblockNumber = mockk(relaxed = true),
            iAmOk = mockk(relaxed = true),
            defaultAppManager = mockk(relaxed = true),
            io = dispatcher,
            appScope = appScope,
            syncEngine = dagger.Lazy { mockk<ClientSyncEngine>(relaxed = true) },
            noteRepo = noteRepo,
            e2ePeers = peers,
        )
    }

    private companion object {
        const val PEER = "+15551234567"
    }
}
