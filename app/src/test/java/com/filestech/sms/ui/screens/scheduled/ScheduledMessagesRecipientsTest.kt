package com.filestech.sms.ui.screens.scheduled

import app.cash.turbine.test
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.domain.usecase.CancelScheduledMessageUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.RetryScheduledMessageUseCase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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
 * The scheduled screen resolves its rows' recipients to contacts —
 * the name and photo for the avatar and the "To:" line, the unknown
 * flag for a number with no contact — through the same cached lookup
 * the conversation list uses. Same shape as that list's photo flows:
 * the keys projection keeps an unchanged schedule list from re-running
 * lookups, and [ScheduledMessagesViewModel.refreshContactPhotos]
 * (called on resume) re-runs them so a photo or name edited while the
 * screen was away shows up.
 */
class ScheduledMessagesRecipientsTest {

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
    fun `a 1-to-1 schedule resolves the contact name and photo`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(mapOf(PHONE to contact(PHONE, "Alice", PHOTO_URI)))
            val vm = viewModel(
                contacts,
                MutableStateFlow(listOf(schedule(1L, PHONE))),
                MutableStateFlow(emptyList()),
            )
            vm.recipients.test {
                // First item is already the resolved map, not the
                // StateFlow's initial empty map: under the unconfined
                // Main dispatcher the sharing coroutine runs the
                // combine to completion synchronously during
                // subscription (same as the conversation list's photo
                // flows).
                assertThat(awaitItem()).isEqualTo(
                    mapOf(1L to listOf(ScheduledRecipient("Alice", false, PHOTO_URI))),
                )
            }
        }
    }

    @Test
    fun `an unknown number resolves to the raw number with the unknown flag`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(mapOf(PHONE to null))
            val vm = viewModel(
                contacts,
                MutableStateFlow(listOf(schedule(1L, PHONE))),
                MutableStateFlow(emptyList()),
            )
            vm.recipients.test {
                assertThat(awaitItem()).isEqualTo(
                    mapOf(1L to listOf(ScheduledRecipient(PHONE, true, null))),
                )
            }
        }
    }

    @Test
    fun `a group schedule resolves every member in address order`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(
                mapOf(
                    PHONE to contact(PHONE, "Alice", PHOTO_URI),
                    PHONE_B to contact(PHONE_B, "Bob", null),
                    PHONE_C to null,
                ),
            )
            val vm = viewModel(
                contacts,
                MutableStateFlow(listOf(schedule(1L, PHONE, PHONE_B, PHONE_C))),
                MutableStateFlow(emptyList()),
            )
            vm.recipients.test {
                // All three resolve — the row's "To:" line names every
                // recipient, even though only the first two get minis.
                assertThat(awaitItem()).isEqualTo(
                    mapOf(
                        1L to listOf(
                            ScheduledRecipient("Alice", false, PHOTO_URI),
                            ScheduledRecipient("Bob", false, null),
                            ScheduledRecipient(PHONE_C, true, null),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `failed schedules resolve too`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(mapOf(PHONE to contact(PHONE, "Alice", PHOTO_URI)))
            val vm = viewModel(
                contacts,
                MutableStateFlow(emptyList()),
                MutableStateFlow(listOf(schedule(4L, PHONE, state = ScheduledMessage.State.FAILED))),
            )
            vm.recipients.test {
                assertThat(awaitItem()).isEqualTo(
                    mapOf(4L to listOf(ScheduledRecipient("Alice", false, PHOTO_URI))),
                )
            }
        }
    }

    @Test
    fun `a photo added while the screen is away appears after refresh`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(mapOf(PHONE to contact(PHONE, "Alice", null)))
            val vm = viewModel(
                contacts,
                MutableStateFlow(listOf(schedule(1L, PHONE))),
                MutableStateFlow(emptyList()),
            )
            vm.recipients.test {
                assertThat(awaitItem()).isEqualTo(
                    mapOf(1L to listOf(ScheduledRecipient("Alice", false, null))),
                )
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // The photo is set on the phone while the screen is
                // away: the provider now answers a photo URI where it
                // answered none.
                contacts.contactsByPhone = mapOf(PHONE to contact(PHONE, "Alice", PHOTO_URI))
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(
                    mapOf(1L to listOf(ScheduledRecipient("Alice", false, PHOTO_URI))),
                )
            }
        }
    }

    @Test
    fun `unchanged schedules do not re-run lookups, but refresh does`() {
        runTest(dispatcher) {
            val scope = this
            val contacts = FakeContacts(mapOf(PHONE to contact(PHONE, "Alice", PHOTO_URI)))
            val pending = MutableStateFlow(listOf(schedule(1L, PHONE)))
            val vm = viewModel(contacts, pending, MutableStateFlow(emptyList()))
            vm.recipients.test {
                assertThat(awaitItem()).isEqualTo(
                    mapOf(1L to listOf(ScheduledRecipient("Alice", false, PHOTO_URI))),
                )
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // The body changes: the list re-emits, but the photo
                // keys (schedule id, addresses) are unchanged, so no
                // lookup re-runs.
                pending.value = listOf(schedule(1L, PHONE).copy(body = "edited"))
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(1)

                // An explicit refresh re-runs the lookup even with
                // unchanged keys — and, the photo being unchanged,
                // emits no new map (no row flicker).
                vm.refreshContactPhotos()
                scope.advanceUntilIdle()
                assertThat(contacts.lookupCalls).isEqualTo(2)
                expectNoEvents()
            }
        }
    }

    @Test
    fun `a throwing lookup is treated as unknown`() {
        runTest(dispatcher) {
            val contacts = FakeContacts(mapOf(PHONE to contact(PHONE, "Alice", PHOTO_URI)))
            contacts.throwOnLookup = SecurityException("READ_CONTACTS denied")
            val vm = viewModel(
                contacts,
                MutableStateFlow(listOf(schedule(1L, PHONE))),
                MutableStateFlow(emptyList()),
            )
            vm.recipients.test {
                // A denied permission never crashes the screen: the
                // row falls back to the grey glyph.
                assertThat(awaitItem()).isEqualTo(
                    mapOf(1L to listOf(ScheduledRecipient(PHONE, true, null))),
                )
            }
        }
    }

    private fun viewModel(
        contacts: ContactRepository,
        pending: MutableStateFlow<List<ScheduledMessage>>,
        failed: MutableStateFlow<List<ScheduledMessage>>,
    ): ScheduledMessagesViewModel {
        val repo = mockk<ScheduledMessageRepository>()
        every { repo.observePending() } returns pending
        every { repo.observeFailed() } returns failed
        val settings = mockk<SettingsRepository>()
        every { settings.flow } returns flowOf(AppSettings(mode = DeviceMode.HUB))
        return ScheduledMessagesViewModel(
            repo,
            mockk<CancelScheduledMessageUseCase>(relaxed = true),
            mockk<RetryScheduledMessageUseCase>(relaxed = true),
            mockk<DeleteScheduledMessageUseCase>(relaxed = true),
            settings,
            contacts,
            dispatcher,
        )
    }

    private fun schedule(
        id: Long,
        vararg phones: String,
        state: ScheduledMessage.State = ScheduledMessage.State.PENDING,
    ) = ScheduledMessage(
        id = id,
        conversationId = 7L,
        addresses = phones.map { PhoneAddress.of(it) },
        body = "later $id",
        scheduledAt = 1700000000000,
        subId = null,
        state = state,
        createdAt = 1699999999999,
    )

    private fun contact(phone: String, name: String?, photoUri: String?) =
        Contact(1L, name, listOf(PhoneAddress.of(phone)), photoUri)

    /** Contact repository whose answers can change mid-test, counting lookups. */
    private class FakeContacts(
        var contactsByPhone: Map<String, Contact?>,
    ) : ContactRepository {
        var lookupCalls = 0
        var throwOnLookup: Throwable? = null

        override suspend fun lookupByPhone(rawPhone: String): Contact? {
            lookupCalls++
            throwOnLookup?.let { throw it }
            return contactsByPhone[rawPhone]
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
        private const val PHONE = "+33612345678"
        private const val PHONE_B = "+33687654321"
        private const val PHONE_C = "+33611112222"
        private const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
    }
}
