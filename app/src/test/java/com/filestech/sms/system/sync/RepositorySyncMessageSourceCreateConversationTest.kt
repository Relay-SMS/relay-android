package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
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
import com.filestech.sms.sync.CreateConversationResult
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedConversation
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Create conversation — the glue's new-conversation behavior,
 * against mocked collaborators: the phone's own composer path
 * (`findOrCreate`, then `setCustomName` when a name was typed).
 * The load-bearing pins are the name handling (applied when
 * given, never when blank or absent) and the real message count
 * (an already-existing group must open with its history, not as
 * an empty thread).
 */
class RepositorySyncMessageSourceCreateConversationTest {

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

    @Test fun `a create calls findOrCreate with the parsed addresses and returns the real count`() = runTest {
        coEvery { repository.findOrCreate(any()) } returns Outcome.Success(conversation())
        coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 7)

        val result = source.createConversation(
            listOf("+15551234567", "+15550001111"),
            customName = null,
        )

        assertThat(result).isEqualTo(
            CreateConversationResult.Created(expectedConversation(messageCount = 7)),
        )
        coVerify(exactly = 1) {
            repository.findOrCreate(
                match { it.map { a -> a.raw } == listOf("+15551234567", "+15550001111") },
            )
        }
        coVerify(exactly = 0) { repository.setCustomName(any(), any()) }
    }

    @Test fun `a group name is applied and the re-read row is returned`() = runTest {
        coEvery { repository.findOrCreate(any()) } returns Outcome.Success(conversation())
        coEvery { repository.setCustomName(any(), any()) } just Runs
        coEvery { repository.findById(123L) } returns conversation().copy(customName = "Family")
        coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 0)

        val result = source.createConversation(
            listOf("+15551234567", "+15550001111"),
            customName = "  Family  ",
        )

        // The name is trimmed, and the returned row carries it —
        // `findOrCreate` returned before the name was written, so
        // the glue re-reads the row instead of answering stale.
        assertThat(result).isEqualTo(
            CreateConversationResult.Created(
                expectedConversation(messageCount = 0, customName = "Family", title = "Family"),
            ),
        )
        coVerify(exactly = 1) { repository.setCustomName(123L, "Family") }
    }

    @Test fun `a blank group name is not applied`() = runTest {
        coEvery { repository.findOrCreate(any()) } returns Outcome.Success(conversation())
        coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 0)

        val result = source.createConversation(listOf("+15551234567"), customName = "   ")

        assertThat(result).isEqualTo(
            CreateConversationResult.Created(expectedConversation(messageCount = 0)),
        )
        coVerify(exactly = 0) { repository.setCustomName(any(), any()) }
        coVerify(exactly = 0) { repository.findById(any()) }
    }

    @Test fun `an absent group name is not applied`() = runTest {
        coEvery { repository.findOrCreate(any()) } returns Outcome.Success(conversation())
        coEvery { repository.messageCountsByConversation() } returns mapOf(123L to 0)

        val result = source.createConversation(listOf("+15551234567"), customName = null)

        assertThat(result).isEqualTo(
            CreateConversationResult.Created(expectedConversation(messageCount = 0)),
        )
        coVerify(exactly = 0) { repository.setCustomName(any(), any()) }
        coVerify(exactly = 0) { repository.findById(any()) }
    }

    @Test fun `an empty address list fails with invalid_request and never creates`() = runTest {
        val result = source.createConversation(emptyList(), customName = "Family")

        assertThat(result).isEqualTo(
            CreateConversationResult.Failed(
                code = SyncProtocol.MessageFailed.INVALID_REQUEST,
                message = "Choose at least one contact to start a conversation.",
            ),
        )
        coVerify(exactly = 0) { repository.findOrCreate(any()) }
    }

    @Test fun `an all-blank address list fails with invalid_request and never creates`() = runTest {
        val result = source.createConversation(listOf("  ", ""), customName = null)

        assertThat(result).isEqualTo(
            CreateConversationResult.Failed(
                code = SyncProtocol.MessageFailed.INVALID_REQUEST,
                message = "Choose at least one contact to start a conversation.",
            ),
        )
        coVerify(exactly = 0) { repository.findOrCreate(any()) }
    }

    @Test fun `a findOrCreate failure maps to internal`() = runTest {
        coEvery { repository.findOrCreate(any()) } returns
            Outcome.Failure(AppError.Database())

        val result = source.createConversation(listOf("+15551234567"), customName = "Family")

        assertThat(result).isEqualTo(
            CreateConversationResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The conversation could not be created.",
            ),
        )
        coVerify(exactly = 0) { repository.setCustomName(any(), any()) }
    }

    /**
     * The wire DTO for the shared [conversation] helper (id 123,
     * one address, display name "Marie", no messages) — the
     * expected `conversation_created` payload.
     */
    private fun expectedConversation(
        messageCount: Int,
        customName: String? = null,
        title: String = "Marie",
    ) = SyncedConversation(
        id = "123",
        threadId = null,
        addresses = listOf("+15551234567"),
        displayName = "Marie",
        customName = customName,
        title = title,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unread = 0,
        pinned = false,
        archived = false,
        muted = false,
        blocked = false,
        messageCount = messageCount,
        pinnedMessageId = null,
        pinnedMessageAt = null,
    )
}
