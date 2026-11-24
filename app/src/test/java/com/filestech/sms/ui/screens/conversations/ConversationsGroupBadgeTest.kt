package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
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
 * Round 49 — the encrypted-group list badge (design §13): a group
 * shows its avatar lock badge when EVERY member is ENCRYPTED +
 * group-capable. The badge rule itself ([isConversationEncrypted])
 * is pure; the group-encrypted set comes from
 * [ConversationsViewModel.groupEncryptedAddresses], which is the
 * group-capable set MINUS the blocked addresses (a group with a
 * blocked member never encrypts).
 */
class ConversationsGroupBadgeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────── The badge rule ────────────────────

    @Test
    fun `group with every member group-encrypted shows the badge`() {
        val conv = conversation(listOf(ALICE, BOB))

        assertThat(isConversationEncrypted(conv, emptySet(), setOf(ALICE_CANON, BOB_CANON))).isTrue()
    }

    @Test
    fun `group with one member missing shows no badge`() {
        val conv = conversation(listOf(ALICE, BOB))

        assertThat(isConversationEncrypted(conv, emptySet(), setOf(ALICE_CANON))).isFalse()
    }

    @Test
    fun `group with no member group-encrypted shows no badge`() {
        val conv = conversation(listOf(ALICE, BOB))

        assertThat(isConversationEncrypted(conv, emptySet(), emptySet())).isFalse()
    }

    @Test
    fun `national-form member matches the canonical group set`() {
        // A member stored without the country code still matches
        // the canonical set — both forms canonicalize to the same
        // 10-digit key.
        val conv = conversation(listOf(ALICE_CANON, BOB_CANON))

        assertThat(isConversationEncrypted(conv, emptySet(), setOf(ALICE_CANON, BOB_CANON))).isTrue()
    }

    @Test
    fun `one-to-one badge is unchanged by the group set`() {
        val conv = conversation(listOf(ALICE))

        assertThat(isConversationEncrypted(conv, setOf(ALICE_CANON), emptySet())).isTrue()
        assertThat(isConversationEncrypted(conv, emptySet(), setOf(ALICE_CANON))).isFalse()
        assertThat(isConversationEncrypted(conv, emptySet(), emptySet())).isFalse()
    }

    @Test
    fun `one-to-one badge does not consult the group set for a group member`() {
        // A group never badges from the 1:1 set, even when every
        // member is in it — group capability is the separate set.
        val conv = conversation(listOf(ALICE, BOB))

        assertThat(
            isConversationEncrypted(conv, setOf(ALICE_CANON, BOB_CANON), emptySet()),
        ).isFalse()
    }

    // ──────────────────── The group-encrypted set ────────────────────

    @Test
    fun `blocked member is subtracted from the group-encrypted set`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(setOf(ALICE_CANON, BOB_CANON))
        val blockedFlow = MutableStateFlow(
            listOf(blocked(BOB)),
        )
        val vm = viewModel(peerFlow, blockedFlow, backgroundScope)

        assertThat(vm.groupEncryptedAddresses.first { it == setOf(ALICE_CANON) })
            .containsExactly(ALICE_CANON)
    }

    @Test
    fun `no blocked members leaves the set whole`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(setOf(ALICE_CANON, BOB_CANON))
        val vm = viewModel(peerFlow, MutableStateFlow(emptyList()), backgroundScope)

        assertThat(vm.groupEncryptedAddresses.first { it == setOf(ALICE_CANON, BOB_CANON) })
            .containsExactly(ALICE_CANON, BOB_CANON)
    }

    @Test
    fun `a member flip re-emits the set`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(setOf(ALICE_CANON))
        val vm = viewModel(peerFlow, MutableStateFlow(emptyList()), backgroundScope)

        vm.groupEncryptedAddresses.test {
            assertThat(awaitItem()).containsExactly(ALICE_CANON)
            peerFlow.value = setOf(ALICE_CANON, BOB_CANON)
            assertThat(awaitItem()).containsExactly(ALICE_CANON, BOB_CANON)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a block re-emits the set without the member`() = runTest(dispatcher) {
        val peerFlow = MutableStateFlow(setOf(ALICE_CANON, BOB_CANON))
        val blockedFlow = MutableStateFlow(emptyList<BlockedNumber>())
        val vm = viewModel(peerFlow, blockedFlow, backgroundScope)

        vm.groupEncryptedAddresses.test {
            assertThat(awaitItem()).containsExactly(ALICE_CANON, BOB_CANON)
            blockedFlow.value = listOf(blocked(BOB))
            assertThat(awaitItem()).containsExactly(ALICE_CANON)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun conversation(addresses: List<String>) = Conversation(
        id = 1L,
        threadId = 1L,
        addresses = addresses.map { PhoneAddress.of(it) },
        displayName = null,
        lastMessageAt = 1000L,
        lastMessagePreview = "hello",
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun blocked(number: String) = BlockedNumber(
        id = 1L,
        rawNumber = number,
        normalizedNumber = number,
        label = null,
        createdAt = 0L,
    )

    private fun viewModel(
        peerFlow: MutableStateFlow<Set<String>>,
        blockedFlow: MutableStateFlow<List<BlockedNumber>>,
        appScope: CoroutineScope,
    ): ConversationsViewModel {
        val settingsFlow = MutableStateFlow(AppSettings(mode = DeviceMode.HUB))
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
            every { observeEncryptedAddresses() } returns MutableStateFlow(emptySet())
            every { observeGroupEncryptedAddresses() } returns peerFlow
        }
        val blockedRepo: BlockedNumberRepository = mockk {
            every { observe() } returns blockedFlow
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
            blockedRepo = blockedRepo,
        )
    }

    private companion object {
        const val ALICE = "+16105551234"
        const val BOB = "+16105555678"
        const val ALICE_CANON = "6105551234"
        const val BOB_CANON = "6105555678"
    }
}
