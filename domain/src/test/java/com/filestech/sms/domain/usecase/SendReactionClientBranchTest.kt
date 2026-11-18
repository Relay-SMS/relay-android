package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ReactionFormat
import com.filestech.sms.domain.sender.SenderNameProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Client mode C3 — the reaction SMS path: in client mode the
 * `set_reaction` frame already went out via the repository, so this
 * use case returns success without sending any Tapback SMS. Hub mode
 * is unchanged.
 */
class SendReactionClientBranchTest {

    private val sms = RecordingSender()
    private val miroir = NoopMirror()
    private val senderName = object : SenderNameProvider {
        override fun resolveDisplayName(): String? = null
    }

    private fun incomingMessage(id: Long) = Message(
        id = id,
        conversationId = 9L,
        address = "+33600000001",
        body = "salut",
        type = Message.Type.SMS,
        direction = Message.Direction.INCOMING,
        date = System.currentTimeMillis(),
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.RECEIVED,
        errorCode = null,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    private fun useCase(clientMode: Boolean): SendReactionUseCase {
        val reglages = FakeSettings()
        return SendReactionUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            conversationRepo = FakeConversationRepository(messagesById = mapOf(11L to incomingMessage(11L))),
            senderNameProvider = senderName,
            clientActions = FakeClientActionGateway(clientMode = clientMode),
        )
    }

    @Test
    fun `client mode reaction returns success without any Tapback SMS`() = runBlocking {
        val res = useCase(clientMode = true).invoke(11L, "❤️", ReactionFormat.EMOJI_ONLY)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat((res as Outcome.Success).value.dispatched).isEmpty()
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `client mode reaction removal also skips the SMS`() = runBlocking {
        val res = useCase(clientMode = true).invoke(11L, "❤️", ReactionFormat.EMOJI_ONLY, isRemoval = true)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `client mode still refuses a blank emoji`() = runBlocking {
        val res = useCase(clientMode = true).invoke(11L, "  ", ReactionFormat.EMOJI_ONLY)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat(sms.sentTexts).isEmpty()
    }

    /** Hub mode unchanged: the reaction SMS goes out. */
    @Test
    fun `hub mode reaction still sends the Tapback SMS`() = runBlocking {
        val res = useCase(clientMode = false).invoke(11L, "❤️", ReactionFormat.EMOJI_ONLY)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).hasSize(1)
    }

    /** Hub mode: a reaction on an outgoing message now sends (F1 relaxed). */
    @Test
    fun `hub mode reaction on an outgoing message now sends to the other party`() = runBlocking {
        val reglages = FakeSettings()
        val outgoing = incomingMessage(12L).copy(direction = Message.Direction.OUTGOING)
        val conv = Conversation(
            id = 9L,
            threadId = null,
            addresses = listOf(PhoneAddress.of("+33600000001")),
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
        val useCase = SendReactionUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            conversationRepo = FakeConversationRepository(
                conversationsById = mapOf(9L to conv),
                messagesById = mapOf(12L to outgoing),
            ),
            senderNameProvider = senderName,
            clientActions = FakeClientActionGateway(clientMode = false),
        )

        val res = useCase.invoke(12L, "❤️", ReactionFormat.EMOJI_ONLY)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).hasSize(1)
        // EMOJI_ONLY upgrades to the quoted form for our own messages.
        assertThat(sms.sentTexts).isEqualTo(listOf("❤️ «salut»"))
    }
}
