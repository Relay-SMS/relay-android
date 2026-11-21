package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.settings.AppSettings
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * First-run import progress — the phone's import screen shows the
 * same bar + "N of M" count as the client-mode sync bar.
 * These tests pin the state propagation: [TelephonySyncManager.State.Running]
 * (with its up-front total) reaches the UiState the placeholder
 * renders. The manager itself has no JVM test — driving it needs a
 * ContentResolver and its permission check calls the Android
 * `ContextCompat` static, which throws `Stub!` on plain JVM.
 */
class ConversationsImportProgressTest {

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
    fun `first run with a known total surfaces importing, count and total`() {
        runTest(dispatcher) {
            val syncState = MutableStateFlow<TelephonySyncManager.State>(
                TelephonySyncManager.State.Running(
                    isFirstRun = true,
                    importedSoFar = 250,
                    totalMessages = 5000,
                ),
            )
            val vm = viewModel(syncState, backgroundScope)

            val loaded = vm.state.first { !it.isLoading }
            advanceUntilIdle()

            assertThat(loaded.isImporting).isTrue()
            assertThat(loaded.importedCount).isEqualTo(250)
            assertThat(loaded.importTotalCount).isEqualTo(5000)
        }
    }

    @Test
    fun `first run with an unknown total surfaces a null total`() {
        runTest(dispatcher) {
            val syncState = MutableStateFlow<TelephonySyncManager.State>(
                TelephonySyncManager.State.Running(
                    isFirstRun = true,
                    importedSoFar = 250,
                    totalMessages = null,
                ),
            )
            val vm = viewModel(syncState, backgroundScope)

            val loaded = vm.state.first { !it.isLoading }
            advanceUntilIdle()

            assertThat(loaded.isImporting).isTrue()
            assertThat(loaded.importedCount).isEqualTo(250)
            assertThat(loaded.importTotalCount).isNull()
        }
    }

    @Test
    fun `a delta run never shows the import screen`() {
        runTest(dispatcher) {
            val syncState = MutableStateFlow<TelephonySyncManager.State>(
                TelephonySyncManager.State.Running(
                    isFirstRun = false,
                    importedSoFar = 5,
                    totalMessages = null,
                ),
            )
            val vm = viewModel(syncState, backgroundScope)

            val loaded = vm.state.first { !it.isLoading }
            advanceUntilIdle()

            assertThat(loaded.isImporting).isFalse()
            assertThat(loaded.importedCount).isEqualTo(5)
            assertThat(loaded.importTotalCount).isNull()
        }
    }

    @Test
    fun `idle shows no import state`() {
        runTest(dispatcher) {
            val syncState = MutableStateFlow<TelephonySyncManager.State>(
                TelephonySyncManager.State.Idle,
            )
            val vm = viewModel(syncState, backgroundScope)

            val loaded = vm.state.first { !it.isLoading }
            advanceUntilIdle()

            assertThat(loaded.isImporting).isFalse()
            assertThat(loaded.importedCount).isEqualTo(0)
            assertThat(loaded.importTotalCount).isNull()
        }
    }

    private fun viewModel(
        syncState: MutableStateFlow<TelephonySyncManager.State>,
        appScope: CoroutineScope,
    ): ConversationsViewModel {
        val settingsFlow = MutableStateFlow(AppSettings())
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
            every { state } returns syncState
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns
                MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Unlocked)
        }
        // Notes — the list merges the notes flow; a bare relaxed
        // mock returns `emptyFlow()` (never emits), which would
        // stall the packed combine, so the notes arm emits an
        // empty list.
        val noteRepo: NoteRepository = mockk(relaxed = true) {
            every { observeAllNotes() } returns MutableStateFlow(emptyList())
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
            // Client mode C2 — hub-mode tests never touch the engine
            // (the ViewModel only calls syncEngine.get() in client
            // mode); a relaxed mock behind a Lazy is enough.
            syncEngine = dagger.Lazy { mockk<ClientSyncEngine>(relaxed = true) },
            noteRepo = noteRepo,
            // E2E — the relaxed mock never emits an encrypted set, so no
            // row shows a lock badge in these tests.
            e2ePeers = mockk(relaxed = true),
        )
    }
}
