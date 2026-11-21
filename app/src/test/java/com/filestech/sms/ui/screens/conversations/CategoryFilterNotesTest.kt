package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.PhoneAddress
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 36 — notes live in All AND in Personal (Kristin's feedback
 * on the round-34 build overrode the design doc's "All only" rule:
 * notes are personal, not spam/OTP/transactions). The helper tests
 * pin the three rules (the merge gate, the effective selection, the
 * tab row); the ViewModel tests pin the wiring end to end.
 */
class CategoryFilterNotesTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // — The merge gate —

    @Test
    fun `notes show in All`() {
        assertThat(notesVisibleInCategory(null)).isTrue()
    }

    @Test
    fun `notes show in Personal`() {
        assertThat(notesVisibleInCategory(MessageCategory.PERSONAL)).isTrue()
    }

    @Test
    fun `notes hide in OTP, Promotional, Transactions and Other`() {
        assertThat(notesVisibleInCategory(MessageCategory.OTP)).isFalse()
        assertThat(notesVisibleInCategory(MessageCategory.PROMOTIONAL)).isFalse()
        assertThat(notesVisibleInCategory(MessageCategory.TRANSACTIONS)).isFalse()
        assertThat(notesVisibleInCategory(MessageCategory.OTHER)).isFalse()
    }

    // — The effective selection —

    @Test
    fun `Personal stays selected while notes exist, even with no Personal conversation`() {
        val rows = listOf(otpConversation())
        assertThat(
            effectiveSelectedCategory(rows, MessageCategory.PERSONAL, true, hasNotes = true),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test
    fun `Personal falls back to All without notes and without a Personal conversation`() {
        val rows = listOf(otpConversation())
        assertThat(
            effectiveSelectedCategory(rows, MessageCategory.PERSONAL, true, hasNotes = false),
        ).isNull()
    }

    @Test
    fun `notes do not rescue a non-Personal selection`() {
        val rows = listOf(personalConversation())
        assertThat(
            effectiveSelectedCategory(rows, MessageCategory.OTP, true, hasNotes = true),
        ).isNull()
    }

    // — The tab row —

    @Test
    fun `notes give Personal a tab even with no Personal conversation`() {
        val (_, tabs) = applyCategoryFilter(
            listOf(otpConversation()),
            null,
            emptySet(),
            true,
            hasNotes = true,
        )
        assertThat(tabs.map { it.category }).contains(MessageCategory.PERSONAL)
    }

    @Test
    fun `no Personal tab without notes and without a Personal conversation`() {
        val (_, tabs) = applyCategoryFilter(
            listOf(otpConversation()),
            null,
            emptySet(),
            true,
            hasNotes = false,
        )
        assertThat(tabs.map { it.category }).doesNotContain(MessageCategory.PERSONAL)
    }

    @Test
    fun `Personal shows only Personal conversations - notes join at the merge`() {
        val (visible, _) = applyCategoryFilter(
            listOf(personalConversation(), otpConversation()),
            MessageCategory.PERSONAL,
            emptySet(),
            true,
        )
        assertThat(visible.map { it.id }).containsExactly(PERSONAL_ID)
    }

    // — The ViewModel wiring —

    @Test
    fun `All shows the note`() {
        runTest(dispatcher) {
            val vm = viewModel(
                MutableStateFlow(listOf(personalConversation(), otpConversation())),
                MutableStateFlow(listOf(note())),
                backgroundScope,
            )
            vm.state.test {
                val loaded = awaitLoaded()
                assertThat(loaded.selectedCategory).isNull()
                assertThat(loaded.items).contains(ConversationListItem.NoteItem(note()))
            }
        }
    }

    @Test
    fun `Personal shows the note next to the Personal conversation`() {
        runTest(dispatcher) {
            val vm = viewModel(
                MutableStateFlow(listOf(personalConversation(), otpConversation())),
                MutableStateFlow(listOf(note())),
                backgroundScope,
            )
            vm.state.test {
                awaitLoaded()
                vm.selectCategory(MessageCategory.PERSONAL)
                val personal = awaitItem()
                assertThat(personal.selectedCategory).isEqualTo(MessageCategory.PERSONAL)
                assertThat(personal.items).containsExactly(
                    ConversationListItem.ConversationItem(personalConversation()),
                    ConversationListItem.NoteItem(note()),
                )
            }
        }
    }

    @Test
    fun `OTP hides the note`() {
        runTest(dispatcher) {
            val vm = viewModel(
                MutableStateFlow(listOf(personalConversation(), otpConversation())),
                MutableStateFlow(listOf(note())),
                backgroundScope,
            )
            vm.state.test {
                awaitLoaded()
                vm.selectCategory(MessageCategory.OTP)
                val otp = awaitItem()
                assertThat(otp.selectedCategory).isEqualTo(MessageCategory.OTP)
                assertThat(otp.items).containsExactly(
                    ConversationListItem.ConversationItem(otpConversation()),
                )
            }
        }
    }

    @Test
    fun `the Personal tab exists with notes only, and selecting it shows just the note`() {
        runTest(dispatcher) {
            val vm = viewModel(
                MutableStateFlow(listOf(otpConversation())),
                MutableStateFlow(listOf(note())),
                backgroundScope,
            )
            vm.state.test {
                val loaded = awaitLoaded()
                assertThat(loaded.categoryTabs.map { it.category })
                    .contains(MessageCategory.PERSONAL)
                vm.selectCategory(MessageCategory.PERSONAL)
                val personal = awaitItem()
                assertThat(personal.selectedCategory).isEqualTo(MessageCategory.PERSONAL)
                assertThat(personal.items).containsExactly(
                    ConversationListItem.NoteItem(note()),
                )
            }
        }
    }

    private suspend fun ReceiveTurbine<ConversationsViewModel.UiState>.awaitLoaded(): ConversationsViewModel.UiState {
        var current = awaitItem()
        while (current.isLoading) current = awaitItem()
        return current
    }

    private fun viewModel(
        conversations: MutableStateFlow<List<Conversation>>,
        notes: MutableStateFlow<List<Note>>,
        appScope: CoroutineScope,
    ): ConversationsViewModel {
        val settingsFlow = MutableStateFlow(AppSettings())
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeAll(any()) } returns conversations
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
            every { observeAllNotes() } returns notes
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

    private fun personalConversation() = Conversation(
        id = PERSONAL_ID,
        threadId = PERSONAL_ID,
        addresses = listOf(PhoneAddress.of("+15551234567")),
        displayName = "Alice",
        lastMessageAt = 1000L,
        lastMessagePreview = "hello",
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun otpConversation() = Conversation(
        id = OTP_ID,
        threadId = OTP_ID,
        addresses = listOf(PhoneAddress.of("+15557654321")),
        displayName = null,
        lastMessageAt = 2000L,
        lastMessagePreview = "Your verification code is 123456",
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun note() = Note(
        id = NOTE_ID,
        uuid = "00000000-0000-0000-0000-000000000001",
        title = "Test note",
        createdAt = 500L,
        updatedAt = 500L,
        lastMessageAt = 3000L,
        lastMessagePreview = "Yay",
        draft = null,
        pinned = false,
        archived = false,
        bubbleColorArgb = null,
    )

    companion object {
        private const val PERSONAL_ID: Long = 1L
        private const val OTP_ID: Long = 2L
        private const val NOTE_ID: Long = 3L
    }
}
