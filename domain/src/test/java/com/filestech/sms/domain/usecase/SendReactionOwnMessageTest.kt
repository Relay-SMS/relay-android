package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
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
 * Reactions to our OWN messages now send (F1 relaxed): the recipient is the
 * conversation's other party in 1-1, all members in a group. The old F1
 * refusal was written when a reaction was a bare emoji with no context; the
 * quoted formats carry the original text, and [ReactionFormat.EMOJI_ONLY] is
 * upgraded to [ReactionFormat.EMOJI_WITH_QUOTE] for our own messages so the
 * recipient always gets context.
 *
 * Recipients are asserted on the mirror's written rows ([NoopMirror.lignes]
 * carries one address per recipient); bodies on [RecordingSender.sentTexts].
 */
class SendReactionOwnMessageTest {

    private val sms = RecordingSender()
    private val miroir = NoopMirror()
    private val senderName = object : SenderNameProvider {
        override fun resolveDisplayName(): String? = null
    }

    private fun conversation(id: Long, vararg numbers: String) = Conversation(
        id = id,
        threadId = null,
        addresses = numbers.map { PhoneAddress.of(it) },
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

    private fun message(
        id: Long,
        conversationId: Long,
        address: String,
        outgoing: Boolean,
        body: String = "salut",
    ) = Message(
        id = id,
        conversationId = conversationId,
        address = address,
        body = body,
        type = Message.Type.SMS,
        direction = if (outgoing) Message.Direction.OUTGOING else Message.Direction.INCOMING,
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

    private fun useCase(
        messagesById: Map<Long, Message>,
        conversationsById: Map<Long, Conversation> = emptyMap(),
        clientMode: Boolean = false,
    ): SendReactionUseCase {
        val reglages = FakeSettings()
        return SendReactionUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            conversationRepo = FakeConversationRepository(
                conversationsById = conversationsById,
                messagesById = messagesById,
            ),
            senderNameProvider = senderName,
            clientActions = FakeClientActionGateway(clientMode = clientMode),
        )
    }

    @Test
    fun `own-message reaction in 1-1 sends to the other party`() = runBlocking {
        val own = message(id = 11L, conversationId = 9L, address = "+33600000001", outgoing = true)
        val conv = conversation(9L, "+33600000001")
        val res = useCase(
            messagesById = mapOf(11L to own),
            conversationsById = mapOf(9L to conv),
        ).invoke(11L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).isEqualTo(listOf("Liked \"salut\""))
        assertThat(miroir.lignes.map { it.adresse }).isEqualTo(listOf("+33600000001"))
    }

    @Test
    fun `own-message reaction in a group sends to all members`() = runBlocking {
        // A group echo row carries the members joined with `;` — not a
        // dialable number, so the recipients must come from the conversation.
        val own = message(
            id = 12L,
            conversationId = 10L,
            address = "+33600000001;+33600000002;+33600000003",
            outgoing = true,
        )
        val conv = conversation(10L, "+33600000001", "+33600000002", "+33600000003")
        val res = useCase(
            messagesById = mapOf(12L to own),
            conversationsById = mapOf(10L to conv),
        ).invoke(12L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).hasSize(3)
        assertThat(sms.sentTexts).isEqualTo(List(3) { "Liked \"salut\"" })
        assertThat(miroir.lignes.map { it.adresse }).isEqualTo(
            listOf("+33600000001", "+33600000002", "+33600000003"),
        )
    }

    @Test
    fun `EMOJI_ONLY upgrades to the quoted form for own messages`() = runBlocking {
        val own = message(id = 13L, conversationId = 9L, address = "+33600000001", outgoing = true)
        val conv = conversation(9L, "+33600000001")
        val res = useCase(
            messagesById = mapOf(13L to own),
            conversationsById = mapOf(9L to conv),
        ).invoke(13L, "❤️", ReactionFormat.EMOJI_ONLY)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).isEqualTo(listOf("❤️ «salut»"))
    }

    @Test
    fun `EMOJI_ONLY stays bare for incoming messages`() = runBlocking {
        val incoming = message(
            id = 14L,
            conversationId = 9L,
            address = "+33600000001",
            outgoing = false,
        )
        val res = useCase(messagesById = mapOf(14L to incoming))
            .invoke(14L, "❤️", ReactionFormat.EMOJI_ONLY)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).isEqualTo(listOf("❤️"))
    }

    @Test
    fun `removal of an own-message reaction sends the removal form`() = runBlocking {
        val own = message(id = 15L, conversationId = 9L, address = "+33600000001", outgoing = true)
        val conv = conversation(9L, "+33600000001")
        val res = useCase(
            messagesById = mapOf(15L to own),
            conversationsById = mapOf(9L to conv),
        ).invoke(15L, "👍", ReactionFormat.EMOJI_ONLY, isRemoval = true)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).isEqualTo(listOf("Removed a like from \"salut\""))
    }

    @Test
    fun `short-code recipient refuses the send`() = runBlocking {
        val own = message(id = 16L, conversationId = 9L, address = "32665", outgoing = true)
        val conv = conversation(9L, "32665")
        val res = useCase(
            messagesById = mapOf(16L to own),
            conversationsById = mapOf(9L to conv),
        ).invoke(16L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat((res as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `alphanumeric recipient refuses the send`() = runBlocking {
        val own = message(id = 17L, conversationId = 9L, address = "FREE", outgoing = true)
        val conv = conversation(9L, "FREE")
        val res = useCase(
            messagesById = mapOf(17L to own),
            conversationsById = mapOf(9L to conv),
        ).invoke(17L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat((res as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `one invalid member refuses the whole group send`() = runBlocking {
        val own = message(
            id = 18L,
            conversationId = 10L,
            address = "+33600000001;32665",
            outgoing = true,
        )
        val conv = conversation(10L, "+33600000001", "32665")
        val res = useCase(
            messagesById = mapOf(18L to own),
            conversationsById = mapOf(10L to conv),
        ).invoke(18L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat((res as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(sms.sentTexts).isEmpty()
        assertThat(miroir.lignes).isEmpty()
    }

    @Test
    fun `missing conversation refuses the send`() = runBlocking {
        val own = message(id = 19L, conversationId = 9L, address = "+33600000001", outgoing = true)
        val res = useCase(messagesById = mapOf(19L to own))
            .invoke(19L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat((res as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `missing message refuses the send`() = runBlocking {
        val res = useCase(messagesById = emptyMap())
            .invoke(404L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat((res as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `client mode own-message reaction returns success without any SMS`() = runBlocking {
        val own = message(id = 20L, conversationId = 9L, address = "+33600000001", outgoing = true)
        val conv = conversation(9L, "+33600000001")
        val res = useCase(
            messagesById = mapOf(20L to own),
            conversationsById = mapOf(9L to conv),
            clientMode = true,
        ).invoke(20L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat((res as Outcome.Success).value.dispatched).isEmpty()
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `incoming group message still notifies only the sender`() = runBlocking {
        // F2 unchanged: reacting to Alice's message in a group notifies
        // Alice alone, never the other members.
        val incoming = message(
            id = 21L,
            conversationId = 10L,
            address = "+33600000001",
            outgoing = false,
        )
        val conv = conversation(10L, "+33600000001", "+33600000002", "+33600000003")
        val res = useCase(
            messagesById = mapOf(21L to incoming),
            conversationsById = mapOf(10L to conv),
        ).invoke(21L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(sms.sentTexts).isEqualTo(listOf("Liked \"salut\""))
        assertThat(miroir.lignes.map { it.adresse }).isEqualTo(listOf("+33600000001"))
    }
}
