package com.filestech.sms.ui.screens

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.ui.screens.thread.ThreadViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 42 — "Edit contact" in the details panel opens the system
 * editor on THAT person's card (`ACTION_EDIT` + contact URI), not
 * the contacts list. The ViewModel re-looks the contact up on tap
 * and emits [ThreadViewModel.Event.OpenEditContact] with the
 * resolved id; a missing contact (deleted, no id, or the lookup
 * failing) falls back to the add flow instead of crashing.
 *
 * Only the event emission is testable here: the intent itself
 * (`ContentUris`, `ACTION_EDIT`) lives in `ContactIntents`, which
 * touches Android framework classes that throw Stub! on plain JVM.
 *
 * Round 47 — the same routing covers a group member tap
 * ([ThreadViewModel.openMemberContact]): a saved member opens
 * that person's card, an unknown member (or a failed lookup)
 * opens a blank form prefilled with the member's number.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class ThreadViewModelEditContactTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Fake contact repo with a configurable lookup outcome. */
    private class FakeContacts(
        private val lookupResult: Contact?,
        private val throwOnLookup: Boolean = false,
    ) : ContactRepository {
        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            if (throwOnLookup) {
                throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            }
            return lookupResult
        }

        override suspend fun listAll(): List<Contact> = listOfNotNull(lookupResult)

        override suspend fun listAllDetailed(): List<Contact> = listAll()

        override suspend fun openPhotoStream(address: String): java.io.InputStream? = null

        override suspend fun setContactPhoto(
            address: String,
            photoBytes: ByteArray,
        ): ContactPhotoWriteResult = ContactPhotoWriteResult.NotFound
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `edit contact emits the resolved contact id`() = runTest(dispatcher) {
        val alice = Contact(42L, "Alice", listOf(PhoneAddress.of("+33612345678")), null)
        val vm = threadViewModel(FakeContacts(alice))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.requestEditContact()
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>())
            .containsExactly(ThreadViewModel.Event.OpenEditContact(42L))
    }

    @Test
    fun `a deleted contact falls back to the add flow`() = runTest(dispatcher) {
        val vm = threadViewModel(FakeContacts(null))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.requestEditContact()
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenAddContact>())
            .containsExactly(ThreadViewModel.Event.OpenAddContact("+33612345678"))
        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>()).isEmpty()
    }

    @Test
    fun `a contact without an id falls back to the add flow`() = runTest(dispatcher) {
        val noId = Contact(null, "Alice", listOf(PhoneAddress.of("+33612345678")), null)
        val vm = threadViewModel(FakeContacts(noId))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.requestEditContact()
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenAddContact>())
            .containsExactly(ThreadViewModel.Event.OpenAddContact("+33612345678"))
        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>()).isEmpty()
    }

    @Test
    fun `a denied permission falls back to the add flow without crashing`() = runTest(dispatcher) {
        val vm = threadViewModel(FakeContacts(null, throwOnLookup = true))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.requestEditContact()
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenAddContact>())
            .containsExactly(ThreadViewModel.Event.OpenAddContact("+33612345678"))
        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>()).isEmpty()
    }

    // Round 47 — group member tap: the member's number is NOT the
    // thread's number (+33612345678), so the events below prove the
    // tap routes the tapped member, not the conversation.

    @Test
    fun `a saved member tap opens that person's card`() = runTest(dispatcher) {
        val bob = Contact(77L, "Bob", listOf(PhoneAddress.of("+33698765432")), null)
        val vm = threadViewModel(FakeContacts(bob))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.openMemberContact("+33698765432")
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>())
            .containsExactly(ThreadViewModel.Event.OpenEditContact(77L))
    }

    @Test
    fun `an unsaved member tap opens a blank contact form`() = runTest(dispatcher) {
        val vm = threadViewModel(FakeContacts(null))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.openMemberContact("+33698765432")
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenCreateContact>())
            .containsExactly(ThreadViewModel.Event.OpenCreateContact("+33698765432"))
        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>()).isEmpty()
    }

    @Test
    fun `a member contact without an id falls back to the create flow`() = runTest(dispatcher) {
        val noId = Contact(null, "Bob", listOf(PhoneAddress.of("+33698765432")), null)
        val vm = threadViewModel(FakeContacts(noId))
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }

        vm.openMemberContact("+33698765432")
        testScheduler.advanceUntilIdle()

        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenCreateContact>())
            .containsExactly(ThreadViewModel.Event.OpenCreateContact("+33698765432"))
        assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>()).isEmpty()
    }

    @Test
    fun `a denied permission on a member tap falls back to the create flow without crashing`() =
        runTest(dispatcher) {
            val vm = threadViewModel(FakeContacts(null, throwOnLookup = true))
            val events = mutableListOf<ThreadViewModel.Event>()
            backgroundScope.launch { vm.events.toList(events) }

            vm.openMemberContact("+33698765432")
            testScheduler.advanceUntilIdle()

            assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenCreateContact>())
                .containsExactly(ThreadViewModel.Event.OpenCreateContact("+33698765432"))
            assertThat(events.filterIsInstance<ThreadViewModel.Event.OpenEditContact>()).isEmpty()
        }

    /**
     * Round 43 — the Details panel's 96dp header avatar reads the
     * best available photo: the full-size one when the contact has
     * it, the thumbnail otherwise (the fallback lives in the state,
     * not the panel — the panel takes one photo URI). The thread
     * header keeps the thumbnail.
     */
    @Test
    fun `the state carries the full-size photo for the details panel`() = runTest(dispatcher) {
        val alice = Contact(
            id = 42L,
            displayName = "Alice",
            phones = listOf(PhoneAddress.of("+33612345678")),
            photoUri = "content://thumb",
            photoFullUri = "content://full",
        )
        val vm = threadViewModel(FakeContacts(alice))

        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value.contactPhotoUri).isEqualTo("content://thumb")
        assertThat(vm.state.value.contactPhotoLargeUri).isEqualTo("content://full")
    }

    @Test
    fun `the details photo falls back to the thumbnail without a full photo`() =
        runTest(dispatcher) {
            val alice = Contact(
                id = 42L,
                displayName = "Alice",
                phones = listOf(PhoneAddress.of("+33612345678")),
                photoUri = "content://thumb",
                photoFullUri = null,
            )
            val vm = threadViewModel(FakeContacts(alice))

            testScheduler.advanceUntilIdle()

            assertThat(vm.state.value.contactPhotoLargeUri).isEqualTo("content://thumb")
        }

    private fun threadViewModel(
        contacts: ContactRepository,
        context: Context = mockk(relaxed = true),
    ): ThreadViewModel {
        val conversation = Conversation(
            id = 7L,
            threadId = null,
            addresses = listOf(PhoneAddress.of("+33612345678")),
            displayName = null,
            lastMessageAt = 0L,
            lastMessagePreview = null,
            unreadCount = 0,
            pinned = false,
            archived = false,
            muted = false,
            inVault = false,
            draft = null,
        )
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeOne(any()) } returns flowOf(conversation)
            every { observeVaultHidden(any()) } returns flowOf(false)
            every { observeMessagesWindow(any(), any()) } returns emptyFlow()
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppSettings())
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
        }
        return ThreadViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversation.id)),
            repo = repo,
            envoyer = mockk(relaxed = true),
            sendVoiceMms = mockk(relaxed = true),
            retrySend = mockk(relaxed = true),
            markRead = mockk(relaxed = true),
            segCounter = mockk(relaxed = true),
            exportPdf = mockk(relaxed = true),
            voiceRecorder = mockk(relaxed = true),
            playbackController = mockk(relaxed = true),
            settings = settings,
            blockNumber = mockk(relaxed = true),
            toggleConvState = mockk(relaxed = true),
            contactRepo = contacts,
            sendReaction = mockk(relaxed = true),
            scheduleMessage = mockk(relaxed = true),
            incomingShare = mockk(relaxed = true),
            activeConversationTracker = mockk(relaxed = true),
            context = context,
            io = dispatcher,
            appLock = appLock,
        )
    }
}
