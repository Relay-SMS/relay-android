package com.filestech.sms.system.sync

import android.content.Context
import app.cash.turbine.test
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
import com.filestech.sms.e2e.E2ePeerRepository
import com.filestech.sms.sync.SyncMessageSource
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E (§9.1–§9.2) — the hub glue's encryption flags: a 1:1
 * conversation is `encrypted` when its peer's state is ENCRYPTED,
 * messages carry their stored flags, and a peer-state flip re-emits
 * the conversation list (so the engine's whole-DTO diff pushes a
 * `conversation_updated` for the flipped thread). The raw ciphertext
 * is never on the wire — the DTOs have no field for it, so there is
 * nothing to assert beyond the flags themselves.
 */
class RepositorySyncMessageSourceE2eTest {

    private val context = mockk<Context>()
    private val repository = mockk<ConversationRepository>()
    private val peerRepository = mockk<E2ePeerRepository>()

    private fun source(withPeers: Boolean = true): SyncMessageSource =
        RepositorySyncMessageSource(
            context = context,
            repository = repository,
            defaultSmsAppChecker = mockk<DefaultSmsAppChecker>(),
            envoyerMessage = mockk<EnvoyerMessageUseCase>(),
            retrySend = mockk<RetrySendUseCase>(),
            sendReaction = mockk<SendReactionUseCase>(),
            settings = mockk<AppSettingsSource>(),
            scheduledMessageRepository = mockk<ScheduledMessageRepository>(),
            scheduleMessageUseCase = mockk<ScheduleMessageUseCase>(),
            deleteScheduledMessage = mockk<DeleteScheduledMessageUseCase>(),
            contactsRepository = mockk<ContactRepository>(),
            blockNumber = mockk<BlockNumberUseCase>(),
            unblockNumber = mockk<UnblockNumberUseCase>(),
            e2ePeerRepository = if (withPeers) peerRepository else null,
        )

    /**
     * The peer key for the default test conversation's address:
     * the CANONICAL key (round 46), while the conversation itself
     * stores `+1…` — the round-47 fix reunites the two.
     */
    private val peerAddress: String
        get() = "5551234567"

    private fun stubConversations() {
        every { repository.observeAll(false) } returns flowOf(listOf(conversation()))
        every { repository.observeAll(true) } returns flowOf(emptyList())
        coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 3)
        // Round 49 — the sync flag reads the group-encrypted set
        // too; these 1:1 tests keep it empty.
        coEvery { peerRepository.groupEncryptedAddresses() } returns emptySet()
    }

    @Test fun `conversations marks a 1-1 thread encrypted when its peer is encrypted`() = runTest {
        stubConversations()
        coEvery { peerRepository.encryptedAddresses() } returns setOf(peerAddress)

        val result = source().conversations()

        assertThat(result).hasSize(1)
        assertThat(result[0].encrypted).isTrue()
    }

    @Test fun `conversations marks a 1-1 thread encrypted when the number is stored national`() =
        runTest {
            every { repository.observeAll(false) } returns flowOf(
                listOf(conversation(addresses = listOf("5551234567"))),
            )
            every { repository.observeAll(true) } returns flowOf(emptyList())
            coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 3)
            coEvery { peerRepository.encryptedAddresses() } returns setOf(peerAddress)
            coEvery { peerRepository.groupEncryptedAddresses() } returns emptySet()

            val result = source().conversations()

            assertThat(result).hasSize(1)
            assertThat(result[0].encrypted).isTrue()
        }

    @Test fun `conversations leaves a 1-1 thread plain for a legacy non-canonical peer row`() =
        runTest {
            // Round 46 left pre-round-46 peer rows (keyed by
            // normalizePhone alone, `+1…`) unreachable by design —
            // their sessions are unreachable too, so the thread
            // reads UNKNOWN and messages go out plain. The sync
            // flag must agree with the thread, not the stale row.
            stubConversations()
            coEvery { peerRepository.encryptedAddresses() } returns
                setOf(PhoneAddress.of("+15551234567").normalized)

            val result = source().conversations()

            assertThat(result).hasSize(1)
            assertThat(result[0].encrypted).isFalse()
        }

    @Test fun `conversations leaves a 1-1 thread plain when its peer is not encrypted`() = runTest {
        stubConversations()
        coEvery { peerRepository.encryptedAddresses() } returns emptySet()

        val result = source().conversations()

        assertThat(result).hasSize(1)
        assertThat(result[0].encrypted).isFalse()
    }

    @Test fun `conversations leaves a group plain when its members are not group-encrypted`() = runTest {
        every { repository.observeAll(false) } returns flowOf(
            listOf(
                conversation(
                    addresses = listOf("+15551234567", "+15557654321"),
                ),
            ),
        )
        every { repository.observeAll(true) } returns flowOf(emptyList())
        coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 3)
        coEvery { peerRepository.encryptedAddresses() } returns setOf(peerAddress)
        // Round 49 — groups CAN be encrypted now; this one stays
        // plain because no member is in the group-encrypted set.
        coEvery { peerRepository.groupEncryptedAddresses() } returns emptySet()

        val result = source().conversations()

        assertThat(result).hasSize(1)
        assertThat(result[0].encrypted).isFalse()
    }

    @Test fun `conversations without a peer repository maps every thread plain`() = runTest {
        stubConversations()

        val result = source(withPeers = false).conversations()

        assertThat(result).hasSize(1)
        assertThat(result[0].encrypted).isFalse()
    }

    @Test fun `observeConversations re-emits with the flag when a peer flips to encrypted`() =
        runTest {
            stubConversations()
            val encrypted = MutableStateFlow(emptySet<String>())
            every { peerRepository.observeEncryptedAddresses() } returns encrypted
            every { peerRepository.observeGroupEncryptedAddresses() } returns flowOf(emptySet())

            source().observeConversations().test {
                // Plain at first: no peer is encrypted yet.
                assertThat(awaitItem()[0].encrypted).isFalse()
                // The flip: the peer's state becomes ENCRYPTED.
                encrypted.value = setOf(peerAddress)
                assertThat(awaitItem()[0].encrypted).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test fun `messagesBefore carries the stored E2E flags`() = runTest {
        coEvery { repository.messagesBefore(123L, 0L, 200) } returns listOf(
            message().copy(encrypted = true),
            message(id = 790L).copy(decryptFailed = true),
            message(id = 791L),
        )

        val result = source().messagesBefore(123L, 0L, 200)

        assertThat(result.map { it.encrypted }).containsExactly(true, false, false).inOrder()
        assertThat(result.map { it.decryptFailed }).containsExactly(false, true, false).inOrder()
    }

    @Test fun `messagesAfter carries the stored E2E flags`() = runTest {
        coEvery { repository.messagesAfter(123L, 789L) } returns listOf(
            message(id = 790L).copy(encrypted = true, decryptFailed = true),
        )

        val result = source().messagesAfter(123L, 789L)

        assertThat(result).hasSize(1)
        assertThat(result[0].encrypted).isTrue()
        assertThat(result[0].decryptFailed).isTrue()
    }

    @Test fun `messages default both flags to false`() = runTest {
        coEvery { repository.messagesBefore(123L, 0L, 200) } returns listOf(message())

        val result = source().messagesBefore(123L, 0L, 200)

        assertThat(result).hasSize(1)
        assertThat(result[0].encrypted).isFalse()
        assertThat(result[0].decryptFailed).isFalse()
    }
}
