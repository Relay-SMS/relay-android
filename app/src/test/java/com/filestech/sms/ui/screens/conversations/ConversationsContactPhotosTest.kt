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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * The conversation list re-resolves its contact photos on refresh.
 *
 * A photo change touches no conversation row, so the list only learns about it through the
 * explicit re-trigger ([ConversationsViewModel.refreshContactPhotos], called on resume like
 * the thread header's `refreshContactPhoto`). Two defects used to swallow it: nothing ever
 * bumped the tick on resume, and the tick was discarded in the combine lambda, so the
 * `distinctUntilChanged` on the photo keys dropped the bump. The list then kept the old
 * photo (or no photo) until the process restarted. All three tests fail with the old flow
 * shape — the refresh emits nothing and the lookup never re-runs.
 *
 * These tests cover the ViewModel re-resolution only: whether the image loader shows the
 * new bytes for an unchanged photo URI is a separate cache matter, outside this level.
 *
 * The group flow ([ConversationsViewModel.groupMemberPhotos]) gets the same treatment:
 * the list used to resolve photos for 1-to-1 conversations only, so group rows showed
 * initials even when the members had photos. The group tests pin the resolution (both
 * members, one member, none), the custom-avatar skip, and the refresh path.
 */
class ConversationsContactPhotosTest {

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
    fun `photo added while the list is away appears after refresh`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(photoUri = null)
            val conversations = MutableStateFlow(listOf(conversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.contactPhotos.test {
                assertThat(awaitItem()).isEqualTo(emptyMap<Long, String>())
                scope.advanceUntilIdle()
                // The initial resolution ran and found no photo.
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // The photo is set on the phone (a linked device, or an edit in the
                // contacts app): the provider now answers a photo URI where it answered none.
                contacts.photoUri = PHOTO_URI
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(mapOf(CONVERSATION_ID to PHOTO_URI))
            }
        }
    }

    @Test
    fun `photo removed while the list is away drops out after refresh`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(photoUri = PHOTO_URI)
            val conversations = MutableStateFlow(listOf(conversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.contactPhotos.test {
                // The first item is already the resolved map, not the
                // StateFlow's initial empty map: the Unconfined dispatchers
                // run the initial lookup eagerly, before the first
                // collection delivers a value.
                assertThat(awaitItem()).isEqualTo(mapOf(CONVERSATION_ID to PHOTO_URI))

                contacts.photoUri = null
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(emptyMap<Long, String>())
            }
        }
    }

    @Test
    fun `unchanged conversations do not re-run lookups, but refresh does`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(photoUri = PHOTO_URI)
            val conversations = MutableStateFlow(listOf(conversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.contactPhotos.test {
                // First item is the resolved map (see the note above).
                assertThat(awaitItem()).isEqualTo(mapOf(CONVERSATION_ID to PHOTO_URI))
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // A new message arrives: the state re-emits, but the photo keys
                // (conversation id, first address) are unchanged, so no lookup re-runs.
                conversations.value = listOf(conversation().copy(unreadCount = 1))
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // An explicit refresh re-runs the lookup even with unchanged keys —
                // and, the photo being unchanged, emits no new map (no list flicker).
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(2)
                expectNoEvents()
            }
        }
    }

    @Test
    fun `group members with photos appear in the group map`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(
                photoUri = null,
                photosByPhone = mapOf(PHONE to PHOTO_URI, PHONE_B to PHOTO_B),
            )
            val conversations = MutableStateFlow(listOf(groupConversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.groupMemberPhotos.test {
                // First item is already the resolved map (see the note above).
                assertThat(awaitItem()).isEqualTo(
                    mapOf(GROUP_ID to listOf(PHOTO_URI, PHOTO_B)),
                )
            }
        }
    }

    @Test
    fun `a member without a photo leaves a null slot`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(
                photoUri = null,
                photosByPhone = mapOf(PHONE to PHOTO_URI, PHONE_B to null),
            )
            val conversations = MutableStateFlow(listOf(groupConversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.groupMemberPhotos.test {
                // The entry is still emitted — one member has a photo — and
                // the null slot keeps the minis in address order.
                assertThat(awaitItem()).isEqualTo(
                    mapOf(GROUP_ID to listOf(PHOTO_URI, null)),
                )
            }
        }
    }

    @Test
    fun `a group with no member photos has no entry`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(photoUri = null)
            val conversations = MutableStateFlow(listOf(groupConversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.groupMemberPhotos.test {
                assertThat(awaitItem()).isEqualTo(emptyMap<Long, List<String?>>())
                scope.advanceUntilIdle()
                // Both members were looked up; neither had a photo, so no
                // entry is emitted.
                assertThat(contacts.lookupCalls).isEqualTo(2)
            }
        }
    }

    @Test
    fun `a group with a custom avatar is skipped`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(
                photoUri = null,
                photosByPhone = mapOf(PHONE to PHOTO_URI, PHONE_B to PHOTO_B),
            )
            val conversations = MutableStateFlow(
                listOf(groupConversation(avatarUri = CUSTOM_AVATAR)),
            )
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.groupMemberPhotos.test {
                assertThat(awaitItem()).isEqualTo(emptyMap<Long, List<String?>>())
                scope.advanceUntilIdle()
                // The custom photo wins in the row, so the members are
                // never looked up at all.
                assertThat(contacts.lookupCalls).isEqualTo(0)
            }
        }
    }

    @Test
    fun `a member photo added while the list is away appears after refresh`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(
                photoUri = null,
                photosByPhone = mapOf(PHONE to null, PHONE_B to null),
            )
            val conversations = MutableStateFlow(listOf(groupConversation()))
            val vm = viewModel(contacts, conversations, backgroundScope)
            vm.groupMemberPhotos.test {
                assertThat(awaitItem()).isEqualTo(emptyMap<Long, List<String?>>())
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(2)

                // A photo is set for the first member while the list is away.
                contacts.photosByPhone = mapOf(PHONE to PHOTO_URI, PHONE_B to null)
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(
                    mapOf(GROUP_ID to listOf(PHOTO_URI, null)),
                )
            }
        }
    }

    private fun viewModel(
        contacts: ContactRepository,
        conversations: MutableStateFlow<List<Conversation>>,
        appScope: CoroutineScope,
    ): ConversationsViewModel {
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeAll(any()) } returns conversations
            every { observeMessageSearch(any(), any()) } returns
                flowOf<List<MessageSearchHit>>(emptyList())
        }
        val settingsFlow = MutableStateFlow(AppSettings())
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

    private fun groupConversation(avatarUri: String? = null) = Conversation(
        id = GROUP_ID,
        threadId = null,
        addresses = listOf(PhoneAddress.of(PHONE), PhoneAddress.of(PHONE_B)),
        displayName = "Alice, Bob",
        avatarUri = avatarUri,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    /** Contact repository whose photo answer can change mid-test, counting lookups. */
    private class FakeContacts(
        var photoUri: String?,
        var photosByPhone: Map<String, String?>? = null,
    ) : ContactRepository {
        var lookupCalls = 0

        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            lookupCalls++
            val byPhone = photosByPhone
            val uri = if (byPhone == null) photoUri else byPhone[rawPhone]
            return Contact(CONTACT_ID, "Alice", listOf(PhoneAddress.of(rawPhone)), uri)
        }

        override suspend fun listAll(): List<Contact> = emptyList()

        override suspend fun listAllDetailed(): List<Contact> = emptyList()

        override suspend fun openPhotoStream(address: String): InputStream? = null

        override suspend fun setContactPhoto(
            address: String,
            photoBytes: ByteArray,
        ): ContactPhotoWriteResult = ContactPhotoWriteResult.Failed
    }

    companion object {
        private const val CONVERSATION_ID = 7L
        private const val GROUP_ID = 8L
        private const val CONTACT_ID = 1L
        private const val PHONE = "+33612345678"
        private const val PHONE_B = "+33687654321"
        private const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
        private const val PHOTO_B = "content://com.android.contacts/contacts/2/photo"
        private const val CUSTOM_AVATAR = "content://com.filestech.sms/avatars/group-8.jpg"
    }
}
