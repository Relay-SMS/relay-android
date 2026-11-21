package com.filestech.sms.sync.client

import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import com.filestech.sms.sync.SyncedNote
import com.filestech.sms.sync.SyncedNoteMessage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode C2 — the wire → mirror mapping, pure functions. The
 * rules pinned here: ids that do not parse drop the row (an
 * attachment drops on its own, never its message), and enum
 * fallbacks never throw (unknown type → SMS, unknown direction →
 * incoming, unknown status → the state the direction implies).
 */
class WireMappersTest {

    @Test fun `parseHubId accepts decimals and rejects the rest`() {
        assertThat(parseHubId("42")).isEqualTo(42L)
        assertThat(parseHubId("0")).isEqualTo(0L)
        assertThat(parseHubId("abc")).isNull()
        assertThat(parseHubId("")).isNull()
        assertThat(parseHubId("12x")).isNull()
    }

    @Test fun `parseMessageType maps mms and falls back to sms`() {
        assertThat(parseMessageType("mms")).isEqualTo(MessageType.MMS)
        assertThat(parseMessageType("sms")).isEqualTo(MessageType.SMS)
        assertThat(parseMessageType("rcs")).isEqualTo(MessageType.SMS)
        assertThat(parseMessageType("")).isEqualTo(MessageType.SMS)
    }

    @Test fun `parseMessageDirection maps outgoing and falls back to incoming`() {
        assertThat(parseMessageDirection("outgoing")).isEqualTo(MessageDirection.OUTGOING)
        assertThat(parseMessageDirection("incoming")).isEqualTo(MessageDirection.INCOMING)
        assertThat(parseMessageDirection("sideways")).isEqualTo(MessageDirection.INCOMING)
        assertThat(parseMessageDirection("")).isEqualTo(MessageDirection.INCOMING)
    }

    @Test fun `parseMessageStatus maps every known name`() {
        assertThat(parseMessageStatus("pending", MessageDirection.OUTGOING))
            .isEqualTo(MessageStatus.PENDING)
        assertThat(parseMessageStatus("sent", MessageDirection.OUTGOING))
            .isEqualTo(MessageStatus.SENT)
        assertThat(parseMessageStatus("delivered", MessageDirection.OUTGOING))
            .isEqualTo(MessageStatus.DELIVERED)
        assertThat(parseMessageStatus("failed", MessageDirection.OUTGOING))
            .isEqualTo(MessageStatus.FAILED)
        assertThat(parseMessageStatus("received", MessageDirection.INCOMING))
            .isEqualTo(MessageStatus.RECEIVED)
        assertThat(parseMessageStatus("scheduled", MessageDirection.OUTGOING))
            .isEqualTo(MessageStatus.SCHEDULED)
    }

    @Test fun `parseMessageStatus falls back to the state the direction implies`() {
        assertThat(parseMessageStatus("teleported", MessageDirection.INCOMING))
            .isEqualTo(MessageStatus.RECEIVED)
        assertThat(parseMessageStatus("teleported", MessageDirection.OUTGOING))
            .isEqualTo(MessageStatus.SENT)
    }

    @Test fun `message toData maps every field`() {
        val data = sampleMessage().toData()
        assertThat(data).isNotNull()
        assertThat(data!!.hubMessageId).isEqualTo(50L)
        assertThat(data.hubConversationId).isEqualTo(7L)
        assertThat(data.address).isEqualTo("+33612345678")
        assertThat(data.body).isEqualTo("hello")
        assertThat(data.type).isEqualTo(MessageType.MMS)
        assertThat(data.direction).isEqualTo(MessageDirection.OUTGOING)
        assertThat(data.date).isEqualTo(1700000000000L)
        assertThat(data.dateSent).isEqualTo(1700000001000L)
        assertThat(data.read).isFalse()
        assertThat(data.starred).isTrue()
        assertThat(data.status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(data.errorCode).isEqualTo(12)
        assertThat(data.replyToHubMessageId).isEqualTo(40L)
        assertThat(data.reaction).isEqualTo("❤️")
        assertThat(data.clientMessageId).isEqualTo("uuid-1")
        assertThat(data.attachments).hasSize(1)
        assertThat(data.attachments[0].hubAttachmentId).isEqualTo(9L)
        assertThat(data.attachments[0].mimeType).isEqualTo("image/jpeg")
        assertThat(data.attachments[0].fileName).isEqualTo("photo.jpg")
        assertThat(data.attachments[0].sizeBytes).isEqualTo(1234L)
        assertThat(data.attachments[0].width).isEqualTo(800)
        assertThat(data.attachments[0].height).isEqualTo(600)
        assertThat(data.attachments[0].durationMs).isNull()
    }

    @Test fun `message toData drops the row when an id does not parse`() {
        assertThat(sampleMessage(id = "abc").toData()).isNull()
        assertThat(sampleMessage(conversationId = "xyz").toData()).isNull()
    }

    @Test fun `message toData drops only the bad attachment, never the message`() {
        val data = sampleMessage(
            attachments = listOf(
                sampleAttachment(id = "bad"),
                sampleAttachment(id = "9"),
            ),
        ).toData()
        assertThat(data).isNotNull()
        assertThat(data!!.attachments.map { it.hubAttachmentId }).containsExactly(9L).inOrder()
    }

    @Test fun `message toData reads an unparsable reply target as no target`() {
        val data = sampleMessage(replyToMessageId = "nonsense").toData()
        assertThat(data).isNotNull()
        assertThat(data!!.replyToHubMessageId).isNull()
    }

    @Test fun `conversation toData maps every field`() {
        val data = sampleConversation().toData()
        assertThat(data).isNotNull()
        assertThat(data!!.hubConversationId).isEqualTo(7L)
        assertThat(data.title).isEqualTo("Alice")
        assertThat(data.displayName).isEqualTo("Alice A")
        assertThat(data.customName).isEqualTo("Fam")
        assertThat(data.addresses).containsExactly("+33612345678", "+33698765432").inOrder()
        assertThat(data.lastMessagePreview).isEqualTo("hi")
        assertThat(data.lastMessageAt).isEqualTo(1700000000000L)
        assertThat(data.unreadCount).isEqualTo(2)
        assertThat(data.pinned).isTrue()
        assertThat(data.muted).isFalse()
        assertThat(data.archived).isFalse()
        assertThat(data.isBlocked).isTrue()
        assertThat(data.pinnedMessageHubId).isEqualTo(50L)
        assertThat(data.pinnedMessageAt).isEqualTo(1700000002000L)
    }

    @Test fun `conversation toData drops the row when the id does not parse`() {
        assertThat(sampleConversation(id = "nope").toData()).isNull()
    }

    // -- E2E (§9.1): the flags survive the wire → mirror mapping ------

    @Test fun `message toData carries the E2E flags`() {
        val data = sampleMessage().copy(encrypted = true, decryptFailed = true).toData()
        assertThat(data).isNotNull()
        assertThat(data!!.encrypted).isTrue()
        assertThat(data.decryptFailed).isTrue()
    }

    @Test fun `message toData defaults the E2E flags to false`() {
        // An older hub omits the fields — the wire defaults apply,
        // and the mirror stores plain rows, never null flags.
        val data = sampleMessage().toData()
        assertThat(data).isNotNull()
        assertThat(data!!.encrypted).isFalse()
        assertThat(data.decryptFailed).isFalse()
    }

    @Test fun `conversation toData carries the E2E flag`() {
        val data = sampleConversation().copy(encrypted = true).toData()
        assertThat(data).isNotNull()
        assertThat(data!!.encrypted).isTrue()
    }

    @Test fun `conversation toData defaults the E2E flag to false`() {
        val data = sampleConversation().toData()
        assertThat(data).isNotNull()
        assertThat(data!!.encrypted).isFalse()
    }

    private fun sampleMessage(
        id: String = "50",
        conversationId: String = "7",
        replyToMessageId: String? = "40",
        attachments: List<SyncedAttachment> = listOf(sampleAttachment()),
    ) = SyncedMessage(
        id = id,
        conversationId = conversationId,
        address = "+33612345678",
        body = "hello",
        type = "mms",
        direction = "outgoing",
        date = 1700000000000,
        dateSent = 1700000001000,
        read = false,
        starred = true,
        status = "delivered",
        errorCode = 12,
        replyToMessageId = replyToMessageId,
        reaction = "❤️",
        attachments = attachments,
        clientMessageId = "uuid-1",
    )

    private fun sampleAttachment(id: String = "9") = SyncedAttachment(
        id = id,
        mimeType = "image/jpeg",
        fileName = "photo.jpg",
        sizeBytes = 1234,
        width = 800,
        height = 600,
        durationMs = null,
    )

    private fun sampleConversation(id: String = "7") = SyncedConversation(
        id = id,
        threadId = 4242,
        addresses = listOf("+33612345678", "+33698765432"),
        displayName = "Alice A",
        customName = "Fam",
        title = "Alice",
        lastMessageAt = 1700000000000,
        lastMessagePreview = "hi",
        unread = 2,
        pinned = true,
        archived = false,
        muted = false,
        blocked = true,
        messageCount = 9,
        pinnedMessageId = "50",
        pinnedMessageAt = 1700000002000,
    )

    // -- Round 36 phase 2: notes --------------------------------------

    private fun sampleNote(id: String = "7") = SyncedNote(
        id = id,
        title = "Groceries",
        createdAt = 1_794_670_000_000L,
        updatedAt = 1_794_670_100_000L,
        lastMessageAt = 1_794_670_100_000L,
        lastMessagePreview = "Milk",
        pinned = true,
        archived = false,
        messageCount = 3,
    )

    private fun sampleNoteMessage(
        id: String = "42",
        noteId: String = "7",
        edited: Boolean = false,
        attachments: List<SyncedAttachment> = emptyList(),
    ) = SyncedNoteMessage(
        id = id,
        noteId = noteId,
        body = "Milk, eggs",
        date = 1_794_670_100_000L,
        attachments = attachments,
        edited = edited,
    )

    private fun sampleNoteAttachment(id: String = "9") = SyncedAttachment(
        id = id,
        mimeType = "image/jpeg",
        fileName = "list.jpg",
        sizeBytes = 12_345L,
        width = 800,
        height = 600,
        durationMs = null,
    )

    @Test fun `SyncedNote toData maps the row and drops the count`() {
        val data = sampleNote().toData()
        assertThat(data).isNotNull()
        assertThat(data!!.hubNoteId).isEqualTo(7L)
        assertThat(data.title).isEqualTo("Groceries")
        assertThat(data.pinned).isTrue()
        assertThat(data.archived).isFalse()
        // message_count is wire-only (the mobile list shows a preview,
        // never a count) — the data class has no such field.
    }

    @Test fun `SyncedNote toData drops a row whose id does not parse`() {
        assertThat(sampleNote(id = "abc").toData()).isNull()
    }

    @Test fun `SyncedNoteMessage toData maps the row with its edited flag`() {
        val data = sampleNoteMessage(edited = true).toData()
        assertThat(data).isNotNull()
        assertThat(data!!.hubMessageId).isEqualTo(42L)
        assertThat(data.hubNoteId).isEqualTo(7L)
        assertThat(data.body).isEqualTo("Milk, eggs")
        assertThat(data.edited).isTrue()
    }

    @Test fun `SyncedNoteMessage toData drops a row whose note id does not parse`() {
        assertThat(sampleNoteMessage(noteId = "abc").toData()).isNull()
        assertThat(sampleNoteMessage(id = "abc").toData()).isNull()
    }

    @Test fun `SyncedNoteMessage toData drops an unparseable attachment on its own`() {
        val data = sampleNoteMessage(
            attachments = listOf(sampleNoteAttachment(), sampleNoteAttachment(id = "abc")),
        ).toData()
        assertThat(data).isNotNull()
        assertThat(data!!.attachments).hasSize(1)
        assertThat(data.attachments[0].hubAttachmentId).isEqualTo(9L)
        assertThat(data.attachments[0].width).isEqualTo(800)
    }

    @Test fun `SyncedAttachment toNoteData drops an unparseable id`() {
        assertThat(sampleNoteAttachment().toNoteData()).isNotNull()
        assertThat(sampleNoteAttachment(id = "abc").toNoteData()).isNull()
    }
}
