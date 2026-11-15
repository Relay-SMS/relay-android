package com.filestech.sms.system.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.usecase.BlockNumberUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.domain.usecase.UnblockNumberUseCase
import com.filestech.sms.sync.AvatarOpenResult
import com.filestech.sms.sync.ContactReadiness
import com.filestech.sms.sync.SyncMessageSource
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Slice 5 — the glue's contact-sync behavior, against mocked
 * collaborators: the provider-row → wire-DTO mapping (normalization
 * matrix, blank-name drop, photo version), the avatar open, and the
 * readiness check (permission denied, provider `SecurityException`).
 * The `ContentResolver` branches themselves (provider queries, photo
 * streams) are device-only — this pins the glue around them.
 */
class RepositorySyncMessageSourceSlice5Test {

    private val context = mockk<Context>()
    private val repository = mockk<ConversationRepository>()
    private val defaultSmsAppChecker = mockk<DefaultSmsAppChecker>()
    private val envoyer = mockk<EnvoyerMessageUseCase>()
    private val retrySend = mockk<RetrySendUseCase>()
    private val sendReaction = mockk<SendReactionUseCase>()
    private val settings = mockk<AppSettingsSource>()
    private val scheduledMessages = mockk<ScheduledMessageRepository>()
    private val scheduleUseCase = mockk<ScheduleMessageUseCase>()
    private val deleteScheduled = mockk<DeleteScheduledMessageUseCase>()
    private val contactsRepository = mockk<ContactRepository>()
    private val blockNumber = mockk<BlockNumberUseCase>()
    private val unblockNumber = mockk<UnblockNumberUseCase>()

    private val source: SyncMessageSource = RepositorySyncMessageSource(
        context = context,
        repository = repository,
        defaultSmsAppChecker = defaultSmsAppChecker,
        envoyerMessage = envoyer,
        retrySend = retrySend,
        sendReaction = sendReaction,
        settings = settings,
        scheduledMessageRepository = scheduledMessages,
        scheduleMessageUseCase = scheduleUseCase,
        deleteScheduledMessage = deleteScheduled,
        contactsRepository = contactsRepository,
        blockNumber = blockNumber,
        unblockNumber = unblockNumber,
    )

    // -- contacts: the mapping ------------------------------------------

    @Test fun `contacts normalizes every number the phone's way`() = runTest {
        coEvery { contactsRepository.listAllDetailed() } returns listOf(
            contact(id = 1L, name = "Marie", rawPhones = listOf("(555) 123-4567")),
            contact(id = 2L, name = "Curt", rawPhones = listOf("+1 (555) 765-4321")),
            contact(id = 3L, name = "Léa", rawPhones = listOf("555.123.4567")),
            contact(id = 4L, name = "Paris", rawPhones = listOf("+33 6 12 34 56 78")),
            contact(id = 5L, name = "Short", rawPhones = listOf("12345")),
            contact(id = 6L, name = "Service", rawPhones = listOf("*#06#")),
        )

        val synced = source.contacts()

        assertThat(synced.map { it.phones }).containsExactly(
            listOf("5551234567"),
            listOf("+15557654321"),
            listOf("5551234567"),
            listOf("+33612345678"),
            listOf("12345"),
            listOf("*#06#"),
        ).inOrder()
    }

    @Test fun `contacts dedupes numbers that normalize alike`() = runTest {
        coEvery { contactsRepository.listAllDetailed() } returns listOf(
            contact(
                id = 1L,
                name = "Marie",
                rawPhones = listOf("(555) 123-4567", "5551234567", "+15551234567"),
            ),
        )

        val synced = source.contacts()

        // The first two collapse to the same normalized form; the
        // +1 form stays distinct (the desktop's NANP fallback joins
        // them at lookup time, not in storage).
        assertThat(synced.single().phones)
            .containsExactly("5551234567", "+15551234567").inOrder()
    }

    @Test fun `contacts drops blank names`() = runTest {
        coEvery { contactsRepository.listAllDetailed() } returns listOf(
            contact(id = 1L, name = null, rawPhones = listOf("5551234567")),
            contact(id = 2L, name = "", rawPhones = listOf("5551234568")),
            contact(id = 3L, name = "   ", rawPhones = listOf("5551234569")),
            contact(id = 4L, name = "Marie", rawPhones = listOf("5551234570")),
        )

        val synced = source.contacts()

        assertThat(synced.map { it.displayName }).containsExactly("Marie")
    }

    @Test fun `contacts drops contacts with no usable numbers`() = runTest {
        coEvery { contactsRepository.listAllDetailed() } returns listOf(
            contact(id = 1L, name = "NoNumber", rawPhones = emptyList()),
            contact(id = 2L, name = "Marie", rawPhones = listOf("5551234567")),
        )

        val synced = source.contacts()

        assertThat(synced.map { it.displayName }).containsExactly("Marie")
    }

    @Test fun `contacts carries the photo version only when a photo exists`() = runTest {
        coEvery { contactsRepository.listAllDetailed() } returns listOf(
            contact(
                id = 1L,
                name = "WithPhoto",
                rawPhones = listOf("5551234567"),
                photoUri = "content://com.android.contacts/contacts/1/photo",
                photoUpdatedAt = 1_794_670_000_000L,
            ),
            contact(
                id = 2L,
                name = "WithoutPhoto",
                rawPhones = listOf("5551234568"),
                photoUri = null,
                // A timestamp with no photo must not leak through:
                // the version is meaningless without a photo.
                photoUpdatedAt = 1_794_670_000_000L,
            ),
        )

        val synced = source.contacts()

        assertThat(synced[0].hasPhoto).isTrue()
        assertThat(synced[0].photoUpdatedAt).isEqualTo(1_794_670_000_000L)
        assertThat(synced[1].hasPhoto).isFalse()
        assertThat(synced[1].photoUpdatedAt).isNull()
    }

    @Test fun `contacts keeps the contact id for the internal diff`() = runTest {
        coEvery { contactsRepository.listAllDetailed() } returns listOf(
            contact(id = 42L, name = "Marie", rawPhones = listOf("5551234567")),
        )

        val synced = source.contacts()

        // The id rides the DTO (the server diffs on it) but is
        // `@Transient` — it never crosses the wire (pinned in
        // `SyncProtocolSlice5Test`).
        assertThat(synced.single().contactId).isEqualTo(42L)
    }

    // -- contactsReadiness ------------------------------------------------

    @Test fun `contactsReadiness is Ready when the permission is granted`() = runTest {
        grantContactsPermission()
        coEvery { contactsRepository.listAll() } returns emptyList()

        assertThat(source.contactsReadiness()).isEqualTo(ContactReadiness.Ready)
    }

    @Test fun `contactsReadiness is not-ready when the permission is denied`() = runTest {
        denyContactsPermission()

        assertThat(source.contactsReadiness())
            .isEqualTo(ContactReadiness.NoContactsPermission)
        // The provider is never read without the permission.
        coVerify(exactly = 0) { contactsRepository.listAll() }
    }

    @Test fun `contactsReadiness is not-ready when the provider read throws SecurityException`() = runTest {
        grantContactsPermission()
        coEvery { contactsRepository.listAll() } throws
            SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")

        assertThat(source.contactsReadiness())
            .isEqualTo(ContactReadiness.NoContactsPermission)
    }

    // -- openAvatar -------------------------------------------------------

    @Test fun `openAvatar returns the photo stream`() = runTest {
        val bytes = ByteArray(128) { it.toByte() }
        val stream = ByteArrayInputStream(bytes)
        coEvery { contactsRepository.openPhotoStream("+15551234567") } returns stream

        val result = source.openAvatar("+15551234567")

        assertThat(result).isEqualTo(AvatarOpenResult.Opened(stream))
    }

    @Test fun `openAvatar returns Unknown when there is no photo`() = runTest {
        coEvery { contactsRepository.openPhotoStream("+15550000000") } returns null

        assertThat(source.openAvatar("+15550000000")).isEqualTo(AvatarOpenResult.Unknown)
    }

    @Test fun `openAvatar returns Unknown when the provider read throws`() = runTest {
        coEvery { contactsRepository.openPhotoStream(any()) } throws
            SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")

        // Never a crash — the engine answers `unknown_attachment`.
        assertThat(source.openAvatar("+15551234567")).isEqualTo(AvatarOpenResult.Unknown)
    }

    /**
     * The glue calls `Context.checkSelfPermission` directly (API
     * 23+, minSdk 26) — mockk answers it, and no android stub is
     * ever touched (see `contactsReadiness` for why it does not go
     * through `ContextCompat`).
     */
    private fun grantContactsPermission() {
        every {
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
        } returns PackageManager.PERMISSION_GRANTED
    }

    private fun denyContactsPermission() {
        every {
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
        } returns PackageManager.PERMISSION_DENIED
    }

    private fun contact(
        id: Long,
        name: String?,
        rawPhones: List<String>,
        photoUri: String? = null,
        photoUpdatedAt: Long? = null,
    ): Contact = Contact(
        id = id,
        displayName = name,
        phones = rawPhones.map { PhoneAddress.of(it) },
        photoUri = photoUri,
        photoUpdatedAt = photoUpdatedAt,
    )
}
