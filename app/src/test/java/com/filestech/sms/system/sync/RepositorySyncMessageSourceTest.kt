package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.sync.ActionResult
import com.filestech.sms.sync.SendResult
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Slice 3 — the glue's send + write-back behavior, against mocked
 * collaborators. The dedup tests are the load-bearing ones: a missed
 * dedup means a duplicate charged SMS, so every dedup state is pinned
 * here — unseen, seen-pending, seen-sent, seen-failed (re-dispatch),
 * and the failure mappings (blocked-all, not-default).
 */
class RepositorySyncMessageSourceTest {

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

    // Typed as the interface: the 4-arg `sendMessage` calls below
    // pick up the interface's `attachments = null` default (an
    // override may not redeclare it).
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
    )

    /** The router call the glue makes, with the key passed through. */
    private fun stubRouter(result: Outcome<SendReport>) {
        coEvery {
            envoyer.invoke(any(), any(), any(), any(), any(), any())
        } returns result
    }

    // -- send: unseen --------------------------------------------------

    @Test fun `an unseen key dispatches through the router with the key`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageById(789L) } returns message(status = Message.Status.PENDING)
        stubRouter(Outcome.Success(report()))

        val result = source.sendMessage(CLIENT_KEY, 123L, "See you at 6", null)

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 1) {
            envoyer.invoke(
                match { it.map { a -> a.raw } == listOf("+15551234567") },
                "See you at 6",
                emptyList(),
                null,
                null,
                CLIENT_KEY,
            )
        }
    }

    @Test fun `a group broadcast send reports the echo row id`() = runTest {
        coEvery { repository.findById(123L) } returns conversation(
            addresses = listOf("+15551234567", "+15557654321"),
        )
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageById(800L) } returns message(
            id = 800L,
            status = Message.Status.SENT,
            address = "+15551234567;+15557654321",
        )
        stubRouter(
            Outcome.Success(
                report(
                    dispatched = listOf(789L, 790L),
                    blocked = listOf(PhoneAddress.of("+15550000000")),
                    groupEchoId = 800L,
                ),
            ),
        )

        val result = source.sendMessage(CLIENT_KEY, 123L, "Hi all", null)

        // The desktop shows the echo row, with its frozen SENT status,
        // and learns one recipient was blocked.
        assertThat(result).isEqualTo(SendResult.Sent(messageId = 800L, status = "sent", blockedCount = 1))
    }

    @Test fun `a reply id is passed to the router`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageById(789L) } returns message(status = Message.Status.PENDING)
        stubRouter(Outcome.Success(report()))

        source.sendMessage(CLIENT_KEY, 123L, "Agreed", 555L)

        coVerify(exactly = 1) {
            envoyer.invoke(any(), "Agreed", any(), any(), 555L, CLIENT_KEY)
        }
    }

    // -- send: dedup ---------------------------------------------------

    @Test fun `a seen pending key returns the stored result and never re-sends`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(status = Message.Status.PENDING)

        val result = source.sendMessage(CLIENT_KEY, 123L, "See you at 6", null)

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { retrySend.invoke(any()) }
    }

    @Test fun `a seen delivered key returns the stored result with the current status`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(status = Message.Status.DELIVERED)

        val result = source.sendMessage(CLIENT_KEY, 123L, "See you at 6", null)

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "delivered"))
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a seen failed key re-dispatches the existing row instead of sending anew`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(status = Message.Status.FAILED)
        coEvery { retrySend.invoke(789L) } returns Outcome.Success(Unit)

        val result = source.sendMessage(CLIENT_KEY, 123L, "See you at 6", null)

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 1) { retrySend.invoke(789L) }
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a failed re-dispatch that hits the blocklist maps to blocked_recipient`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(status = Message.Status.FAILED)
        coEvery { retrySend.invoke(789L) } returns Outcome.Failure(AppError.RecipientBlocked)

        val result = source.sendMessage(CLIENT_KEY, 123L, "See you at 6", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.BLOCKED_RECIPIENT,
                message = "The recipient is blocked.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a failed group echo cannot be re-dispatched and maps to send_failed`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns message(
            status = Message.Status.FAILED,
            address = "+15551234567;+15557654321",
        )
        coEvery { retrySend.invoke(789L) } returns
            Outcome.Failure(AppError.GroupEchoRetryUnsupported)

        val result = source.sendMessage(CLIENT_KEY, 123L, "Hi all", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.SEND_FAILED,
                message = "This message can't be sent again automatically. Send it again from the phone.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    // -- send: guards + failure mapping --------------------------------

    @Test fun `an unknown conversation is refused without touching the router`() = runTest {
        coEvery { repository.findById(999L) } returns null

        val result = source.sendMessage(CLIENT_KEY, 999L, "Hi", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Conversation not found.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a vault conversation is refused like an unknown one`() = runTest {
        coEvery { repository.findById(123L) } returns conversation(inVault = true)

        val result = source.sendMessage(CLIENT_KEY, 123L, "Hi", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Conversation not found.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a blank body is refused without touching the router`() = runTest {
        val result = source.sendMessage(CLIENT_KEY, 123L, "   ", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The message could not be sent.",
            ),
        )
        coVerify(exactly = 0) { repository.findById(any()) }
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a body over the 64 KB cap is refused without touching the router`() = runTest {
        val result = source.sendMessage(CLIENT_KEY, 123L, "x".repeat(64 * 1024 + 1), null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.MESSAGE_TOO_LONG,
                message = "The message is too long to send.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `not-default-sms maps to hub_not_default_sms`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        stubRouter(Outcome.Failure(AppError.NotDefaultSmsApp))

        val result = source.sendMessage(CLIENT_KEY, 123L, "Hi", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.HUB_NOT_DEFAULT_SMS,
                message = "Relay on the phone isn't your default messaging app.",
            ),
        )
    }

    @Test fun `all recipients blocked maps to blocked_recipient`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        stubRouter(Outcome.Failure(AppError.RecipientBlocked))

        val result = source.sendMessage(CLIENT_KEY, 123L, "Hi", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.BLOCKED_RECIPIENT,
                message = "The recipient is blocked.",
            ),
        )
    }

    @Test fun `a telephony failure maps to send_failed`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        stubRouter(Outcome.Failure(AppError.Telephony("no SIM")))

        val result = source.sendMessage(CLIENT_KEY, 123L, "Hi", null)

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.SEND_FAILED,
                message = "The message could not be sent.",
            ),
        )
    }

    // -- actions -------------------------------------------------------

    @Test fun `mark_read applies and answers ok`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.markRead(123L) } returns Unit

        assertThat(source.markRead(123L)).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.markRead(123L) }
    }

    @Test fun `mark_read on an unknown conversation answers unknown_conversation`() = runTest {
        coEvery { repository.findById(999L) } returns null

        assertThat(source.markRead(999L)).isEqualTo(
            ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Conversation not found.",
            ),
        )
        coVerify(exactly = 0) { repository.markRead(any()) }
    }

    @Test fun `mark_read on a vault conversation is refused`() = runTest {
        coEvery { repository.findById(123L) } returns conversation(inVault = true)

        assertThat(source.markRead(123L).ok).isFalse()
        coVerify(exactly = 0) { repository.markRead(any()) }
    }

    @Test fun `mark_all_read applies and answers ok`() = runTest {
        coEvery { repository.markAllRead() } returns Unit

        assertThat(source.markAllRead()).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.markAllRead() }
    }

    @Test fun `set_pin set_archived and set_muted apply and answer ok`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.setPinned(123L, true) } returns Unit
        coEvery { repository.setArchived(123L, false) } returns Unit
        coEvery { repository.setMuted(123L, true) } returns Unit

        assertThat(source.setPinned(123L, true)).isEqualTo(ActionResult(ok = true))
        assertThat(source.setArchived(123L, false)).isEqualTo(ActionResult(ok = true))
        assertThat(source.setMuted(123L, true)).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.setPinned(123L, true) }
        coVerify(exactly = 1) { repository.setArchived(123L, false) }
        coVerify(exactly = 1) { repository.setMuted(123L, true) }
    }

    // -- reactions -----------------------------------------------------

    @Test fun `set_reaction sets local state first, then sends the SMS`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message(
            direction = Message.Direction.INCOMING,
            reactionEmoji = null,
        )
        coEvery { repository.setReaction(789L, "❤️") } returns
            SetReactionResult.First(789L, "❤️")
        coEvery { settings.hydratedOrNull() } returns AppSettings()
        coEvery { sendReaction.invoke(any(), any(), any(), any()) } returns
            Outcome.Success(report())

        assertThat(source.setReaction(123L, 789L, "❤️")).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.setReaction(789L, "❤️") }
        coVerify(exactly = 1) {
            sendReaction.invoke(
                789L,
                "❤️",
                AppSettings().sending.reactionFormat,
                false,
            )
        }
    }

    @Test fun `a failed reaction SMS keeps the local reaction and answers send_failed`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message(
            direction = Message.Direction.INCOMING,
            reactionEmoji = null,
        )
        coEvery { repository.setReaction(789L, "❤️") } returns
            SetReactionResult.First(789L, "❤️")
        coEvery { settings.hydratedOrNull() } returns AppSettings()
        coEvery { sendReaction.invoke(any(), any(), any(), any()) } returns
            Outcome.Failure(AppError.Telephony("no SIM"))

        assertThat(source.setReaction(123L, 789L, "❤️")).isEqualTo(
            ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.SEND_FAILED,
                message = "The reaction was saved on the phone but could not be sent.",
            ),
        )
        // The local reaction was still set — the phone's own behavior.
        coVerify(exactly = 1) { repository.setReaction(789L, "❤️") }
    }

    @Test fun `set_reaction on a message from another conversation is refused`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message(conversationId = 456L)

        assertThat(source.setReaction(123L, 789L, "❤️")).isEqualTo(
            ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Message not found.",
            ),
        )
        coVerify(exactly = 0) { repository.setReaction(any(), any()) }
        coVerify(exactly = 0) { sendReaction.invoke(any(), any(), any(), any()) }
    }

    @Test fun `set_reaction on an outgoing message sets local state but sends no SMS`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message(
            direction = Message.Direction.OUTGOING,
            reactionEmoji = null,
        )
        coEvery { repository.setReaction(789L, "❤️") } returns
            SetReactionResult.First(789L, "❤️")

        assertThat(source.setReaction(123L, 789L, "❤️")).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.setReaction(789L, "❤️") }
        coVerify(exactly = 0) { sendReaction.invoke(any(), any(), any(), any()) }
    }

    @Test fun `set_reaction sends nothing when reactions-to-recipient is off`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message(
            direction = Message.Direction.INCOMING,
            reactionEmoji = null,
        )
        coEvery { repository.setReaction(789L, "❤️") } returns
            SetReactionResult.First(789L, "❤️")
        coEvery { settings.hydratedOrNull() } returns AppSettings(
            sending = AppSettings().sending.copy(sendReactionsToRecipient = false),
        )

        assertThat(source.setReaction(123L, 789L, "❤️")).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 0) { sendReaction.invoke(any(), any(), any(), any()) }
    }

    @Test fun `a removal sends the removal SMS with the previous emoji`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        // Two reads: before the set (reaction present) and the anti-race
        // re-read before dispatch (reaction gone — the removal stands).
        coEvery { repository.findMessageById(789L) } returns
            message(direction = Message.Direction.INCOMING, reactionEmoji = "❤️") andThen
            message(direction = Message.Direction.INCOMING, reactionEmoji = null)
        coEvery { repository.setReaction(789L, null) } returns SetReactionResult.Removed
        coEvery { settings.hydratedOrNull() } returns AppSettings()
        coEvery { sendReaction.invoke(any(), any(), any(), any()) } returns
            Outcome.Success(report())

        assertThat(source.setReaction(123L, 789L, null)).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) {
            sendReaction.invoke(789L, "❤️", AppSettings().sending.reactionFormat, true)
        }
    }

    // -- pinned message ------------------------------------------------

    @Test fun `set_pinned_message pins a message of the thread`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message()
        coEvery { repository.setPinnedMessage(123L, 789L, any()) } returns Unit

        assertThat(source.setPinnedMessage(123L, 789L)).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.setPinnedMessage(123L, 789L, any()) }
    }

    @Test fun `set_pinned_message with null unpins`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.setPinnedMessage(123L, null, null) } returns Unit

        assertThat(source.setPinnedMessage(123L, null)).isEqualTo(ActionResult(ok = true))
        coVerify(exactly = 1) { repository.setPinnedMessage(123L, null, null) }
    }

    @Test fun `set_pinned_message refuses a message from another conversation`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageById(789L) } returns message(conversationId = 456L)

        assertThat(source.setPinnedMessage(123L, 789L)).isEqualTo(
            ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Message not found.",
            ),
        )
        coVerify(exactly = 0) { repository.setPinnedMessage(any(), any(), any()) }
    }
}

internal const val CLIENT_KEY = "3f2a1b00-0000-4000-8000-00000000c41d"

internal fun conversation(
    id: Long = 123L,
    addresses: List<String> = listOf("+15551234567"),
    inVault: Boolean = false,
) = Conversation(
    id = id,
    threadId = null,
    addresses = addresses.map { PhoneAddress.of(it) },
    displayName = "Marie",
    lastMessageAt = 0L,
    lastMessagePreview = null,
    unreadCount = 0,
    pinned = false,
    archived = false,
    muted = false,
    inVault = inVault,
    draft = null,
)

internal fun message(
    id: Long = 789L,
    conversationId: Long = 123L,
    status: Message.Status = Message.Status.PENDING,
    address: String = "+15551234567",
    direction: Message.Direction = Message.Direction.OUTGOING,
    reactionEmoji: String? = null,
    clientMessageId: String? = CLIENT_KEY,
) = Message(
    id = id,
    conversationId = conversationId,
    address = address,
    body = "See you at 6",
    type = Message.Type.SMS,
    direction = direction,
    date = 0L,
    dateSent = null,
    read = true,
    starred = false,
    status = status,
    errorCode = null,
    attachmentsCount = 0,
    subId = null,
    scheduledAt = null,
    reactionEmoji = reactionEmoji,
    clientMessageId = clientMessageId,
)

internal fun report(
    dispatched: List<Long> = listOf(789L),
    blocked: List<PhoneAddress> = emptyList(),
    groupEchoId: Long? = null,
) = SendReport(
    dispatched = dispatched,
    failed = emptyList(),
    blocked = blocked,
    groupEchoId = groupEchoId,
)
