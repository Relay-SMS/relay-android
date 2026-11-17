package com.filestech.sms.ui.screens.conversations

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.sync.client.ClientSyncEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream

/**
 * Contacts-permission fix — the denied-permission diagnostic
 * experiment: the conversation list opens and works with
 * READ_CONTACTS denied.
 *
 * FINDING — read this first: the first test passes on the tree
 * BEFORE the guard fixes. Every cold-start contacts read was
 * already guarded (v1.28.13): the mirror's name backfill runs
 * inside `runCatching`, the photo flow swallows the throw per
 * lookup, and the list itself never touches the provider. The
 * reported cold-start crash therefore predates those guards — the
 * reporter's build is older than the fix — and this test pins the
 * fixed behaviour so it cannot regress silently. The guard fixes
 * in this change close the remaining latent holes (the photo
 * stream, the sync snapshot) and add the banner; they are pinned
 * by `ContactsReaderDeniedPermissionTest` and
 * `RepositorySyncMessageSourceSlice5Test`.
 */
class ConversationsDeniedPermissionTest {

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
    fun `the list opens without contacts permission - conversations load, no photos, no crash`() {
        runTest(dispatcher) {
            val contacts = ContactsSansPermission()
            val conversations = MutableStateFlow(listOf(conversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            backgroundScope.launch { vm.contactPhotos.collect {} }

            val loaded = vm.state.first { !it.isLoading }
            advanceUntilIdle()

            assertThat(loaded.conversations).hasSize(1)
            // The lookup was attempted (not skipped) and its throw
            // swallowed: no photos, no crash.
            assertThat(contacts.lookupCalls).isEqualTo(1)
            assertThat(vm.contactPhotos.value).isEmpty()
        }
    }

    @Test
    fun `dismissing the contacts banner persists the dismissal`() {
        runTest(dispatcher) {
            val settingsFlow = MutableStateFlow(AppSettings())
            val vm = viewModel(
                ContactsSansPermission(),
                MutableStateFlow(listOf(conversation())),
                backgroundScope,
                settingsFlow,
            )

            vm.dismissContactsPermissionBanner()
            advanceUntilIdle()

            assertThat(settingsFlow.value.conversations.contactsBannerDismissed).isTrue()
        }
    }

    @Test
    fun `granting contacts after a denied start resolves photos on refresh`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = ContactsSansPermission()
            val conversations = MutableStateFlow(listOf(conversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.contactPhotos.test {
                assertThat(awaitItem()).isEqualTo(emptyMap<Long, String>())
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // The user grants the permission (the banner's enable
                // action): the next refresh resolves the photo.
                contacts.refuse = false
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(mapOf(CONVERSATION_ID to PHOTO_URI))
            }
        }
    }

    private fun viewModel(
        contacts: ContactRepository,
        conversations: MutableStateFlow<List<Conversation>>,
        appScope: CoroutineScope,
        settingsFlow: MutableStateFlow<AppSettings> = MutableStateFlow(AppSettings()),
    ): ConversationsViewModel {
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeAll(any()) } returns conversations
            every { observeMessageSearch(any(), any()) } returns
                flowOf<List<MessageSearchHit>>(emptyList())
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { flow } returns settingsFlow
            every { state } returns settingsFlow
            // The dismissal write applies its transform to the
            // backing flow, so the test observes the persisted flag.
            coEvery { update(any()) } answers {
                settingsFlow.value = firstArg<(AppSettings) -> AppSettings>()(settingsFlow.value)
            }
        }
        val syncManager: TelephonySyncManager = mockk(relaxed = true) {
            every { state } returns
                MutableStateFlow<TelephonySyncManager.State>(TelephonySyncManager.State.Idle)
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns
                MutableStateFlow<AppLockManager.LockState>(AppLockManager.LockState.Unlocked)
        }
        return ConversationsViewModel(
            savedStateHandle = SavedStateHandle(),
            repo = repo,
            settings = settings,
            toggle = mockk(relaxed = true),
            mirror = mockk(relaxed = true),
            contacts = contacts,
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
        )
    }

    private fun conversation() = Conversation(
        id = CONVERSATION_ID,
        threadId = null,
        addresses = listOf(PhoneAddress.of(PHONE)),
        displayName = "Alice",
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    /** A contact repository that refuses every read while [refuse] is true, counting lookups. */
    private class ContactsSansPermission : ContactRepository {
        var refuse = true
        var lookupCalls = 0

        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            lookupCalls++
            if (refuse) {
                throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            }
            return Contact(CONTACT_ID, "Alice", listOf(PhoneAddress.of(rawPhone)), PHOTO_URI)
        }

        override suspend fun listAll(): List<Contact> {
            if (refuse) {
                throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            }
            return emptyList()
        }

        override suspend fun listAllDetailed(): List<Contact> = listAll()

        override suspend fun openPhotoStream(address: String): InputStream? {
            if (refuse) {
                throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
            }
            return null
        }

        override suspend fun setContactPhoto(
            address: String,
            photoBytes: ByteArray,
        ): ContactPhotoWriteResult {
            if (refuse) {
                throw SecurityException("Permission Denial: requires android.permission.WRITE_CONTACTS")
            }
            return ContactPhotoWriteResult.NotFound
        }
    }

    companion object {
        private const val CONVERSATION_ID = 7L
        private const val CONTACT_ID = 1L
        private const val PHONE = "+33612345678"
        private const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
    }
}
