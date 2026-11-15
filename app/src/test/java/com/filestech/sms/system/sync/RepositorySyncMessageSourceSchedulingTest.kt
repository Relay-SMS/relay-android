package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
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
import com.filestech.sms.sync.CancelResult
import com.filestech.sms.sync.ScheduleResult
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Phase 2 — the glue's scheduled-send behavior, against mocked
 * collaborators. The dedup tests are the load-bearing ones: a missed
 * dedup means a duplicate scheduled message (and a duplicate charged
 * SMS at fire time), so every dedup state is pinned here — unseen,
 * seen-pending (scheduled row), and seen-fired (sent row) — plus the
 * validation guards and the key preservation through the use case.
 */
class RepositorySyncMessageSourceSchedulingTest {

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

    // -- schedule: unseen ------------------------------------------------

    @Test fun `an unseen key schedules through the phone scheduler with the key`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Success(4242L)

        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Happy birthday!", SEND_AT)

        assertThat(result).isEqualTo(ScheduleResult.Scheduled(messageId = 4242L, sendAt = SEND_AT))
        coVerify(exactly = 1) {
            scheduleUseCase.invoke(
                123L,
                match { it.map { a -> a.raw } == listOf("+15551234567") },
                "Happy birthday!",
                SEND_AT,
                null,
                emptyList(),
                CLIENT_KEY,
            )
        }
    }

    // -- schedule: dedup ---------------------------------------------------

    @Test fun `a seen pending key returns the stored scheduled row and never schedules twice`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns
            scheduled(id = 4242L, scheduledAt = SEND_AT)

        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Happy birthday!", SEND_AT)

        assertThat(result).isEqualTo(ScheduleResult.Scheduled(messageId = 4242L, sendAt = SEND_AT))
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a seen pending key returns the stored time even when the retry asks another`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns
            scheduled(id = 4242L, scheduledAt = SEND_AT)

        // The stored row is authoritative — a retry asking a different
        // time does not move the schedule, it finds the existing one.
        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Happy birthday!", SEND_AT + 60_000L)

        assertThat(result).isEqualTo(ScheduleResult.Scheduled(messageId = 4242L, sendAt = SEND_AT))
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a key whose message already fired returns the sent row and never schedules`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(id = 789L, status = com.filestech.sms.domain.model.Message.Status.SENT)

        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Happy birthday!", SEND_AT)

        assertThat(result).isEqualTo(ScheduleResult.Scheduled(messageId = 789L, sendAt = SEND_AT))
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // -- schedule: guards --------------------------------------------------

    @Test fun `a past send_at is refused without touching the scheduler`() = runTest {
        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Hi", System.currentTimeMillis() - 1_000L)

        assertThat(result).isEqualTo(
            ScheduleResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The scheduled time must be in the future.",
            ),
        )
        coVerify(exactly = 0) { repository.findById(any()) }
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a blank body is refused without touching the scheduler`() = runTest {
        val result = source.scheduleMessage(CLIENT_KEY, 123L, "   ", SEND_AT)

        assertThat(result).isEqualTo(
            ScheduleResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The message could not be scheduled.",
            ),
        )
        coVerify(exactly = 0) { repository.findById(any()) }
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a body over the 64 KB cap is refused without touching the scheduler`() = runTest {
        val result = source.scheduleMessage(CLIENT_KEY, 123L, "x".repeat(64 * 1024 + 1), SEND_AT)

        assertThat(result).isEqualTo(
            ScheduleResult.Failed(
                code = SyncProtocol.MessageFailed.MESSAGE_TOO_LONG,
                message = "The message is too long to send.",
            ),
        )
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `an unknown conversation is refused without touching the scheduler`() = runTest {
        coEvery { repository.findById(999L) } returns null

        val result = source.scheduleMessage(CLIENT_KEY, 999L, "Hi", SEND_AT)

        assertThat(result).isEqualTo(
            ScheduleResult.Failed(
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Conversation not found.",
            ),
        )
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a vault conversation is refused like an unknown one`() = runTest {
        coEvery { repository.findById(123L) } returns conversation(inVault = true)

        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Hi", SEND_AT)

        assertThat(result).isEqualTo(
            ScheduleResult.Failed(
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Conversation not found.",
            ),
        )
        coVerify(exactly = 0) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a scheduler failure maps to internal`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Failure(AppError.Validation("scheduledAt must be in the future"))

        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Hi", SEND_AT)

        assertThat(result).isEqualTo(
            ScheduleResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The message could not be scheduled.",
            ),
        )
    }

    // -- cancel ----------------------------------------------------------

    @Test fun `cancel deletes the scheduled row through the phone delete path`() = runTest {
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns
            scheduled(id = 4242L, scheduledAt = SEND_AT)
        coEvery { deleteScheduled.invoke(4242L) } returns Outcome.Success(Unit)

        assertThat(source.cancelScheduledMessage(CLIENT_KEY)).isEqualTo(CancelResult.Cancelled)
        coVerify(exactly = 1) { deleteScheduled.invoke(4242L) }
    }

    @Test fun `cancel of an unknown key acks without touching the delete path`() = runTest {
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns null

        assertThat(source.cancelScheduledMessage(CLIENT_KEY)).isEqualTo(CancelResult.Cancelled)
        coVerify(exactly = 0) { deleteScheduled.invoke(any()) }
    }

    @Test fun `cancel of an already fired schedule acks and deletes its trace row`() = runTest {
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns
            scheduled(id = 4242L, scheduledAt = SEND_AT, state = ScheduledMessage.State.SENT)
        coEvery { deleteScheduled.invoke(4242L) } returns Outcome.Success(Unit)

        assertThat(source.cancelScheduledMessage(CLIENT_KEY)).isEqualTo(CancelResult.Cancelled)
        coVerify(exactly = 1) { deleteScheduled.invoke(4242L) }
    }

    @Test fun `cancel then re-schedule with the same key creates a fresh schedule`() = runTest {
        // The row is there for the cancel, gone for the re-schedule —
        // the delete freed the key, no tombstone blocks it.
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns
            scheduled(id = 4242L, scheduledAt = SEND_AT) andThen null
        coEvery { deleteScheduled.invoke(4242L) } returns Outcome.Success(Unit)
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Success(4243L)

        assertThat(source.cancelScheduledMessage(CLIENT_KEY)).isEqualTo(CancelResult.Cancelled)
        val result = source.scheduleMessage(CLIENT_KEY, 123L, "Happy birthday!", SEND_AT)

        assertThat(result).isEqualTo(ScheduleResult.Scheduled(messageId = 4243L, sendAt = SEND_AT))
        coVerify(exactly = 1) { deleteScheduled.invoke(4242L) }
        coVerify(exactly = 1) {
            scheduleUseCase.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `a delete failure maps to internal`() = runTest {
        coEvery { scheduledMessages.findByClientMessageId(CLIENT_KEY) } returns
            scheduled(id = 4242L, scheduledAt = SEND_AT)
        coEvery { deleteScheduled.invoke(4242L) } throws IllegalStateException("boom")

        assertThat(source.cancelScheduledMessage(CLIENT_KEY)).isEqualTo(
            CancelResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The scheduled message could not be cancelled.",
            ),
        )
    }

    private companion object {
        /** Far-future send time (2027) — always ahead of the test clock. */
        private const val SEND_AT = 1_800_000_000_000L
    }
}

private fun scheduled(
    id: Long = 4242L,
    scheduledAt: Long = 1_800_000_000_000L,
    clientMessageId: String? = CLIENT_KEY,
    state: ScheduledMessage.State = ScheduledMessage.State.PENDING,
) = ScheduledMessage(
    id = id,
    conversationId = 123L,
    addresses = listOf(PhoneAddress.of("+15551234567")),
    body = "Happy birthday!",
    scheduledAt = scheduledAt,
    subId = null,
    state = state,
    createdAt = 0L,
    clientMessageId = clientMessageId,
)
