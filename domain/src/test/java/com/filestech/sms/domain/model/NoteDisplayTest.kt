package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Notes — the note-entry-to-[Message] display mapping: an entry
 * renders as the user's own message (OUTGOING, RECEIVED, no status
 * line), a text-only entry is SMS, an entry with attachments is
 * MMS with the attachments carried, and the title cap is 40.
 */
class NoteDisplayTest {

    private fun entry(
        id: Long = 7L,
        noteId: Long = 3L,
        body: String = "Buy milk",
        date: Long = 5_000L,
        attachments: List<Attachment> = emptyList(),
    ) = NoteMessage(
        id = id,
        uuid = "entry-uuid",
        noteId = noteId,
        body = body,
        date = date,
        attachments = attachments,
    )

    private fun attachment() = Attachment(
        id = 11L,
        messageId = 7L,
        mimeType = "image/jpeg",
        fileName = "photo.jpg",
        sizeBytes = 100L,
        localUri = "note://11",
        width = 800,
        height = 600,
        durationMs = null,
    )

    @Test
    fun `text entry maps to an outgoing received SMS`() {
        val mapped = entry().toDisplayMessage()

        assertThat(mapped.id).isEqualTo(7L)
        assertThat(mapped.conversationId).isEqualTo(3L)
        assertThat(mapped.body).isEqualTo("Buy milk")
        assertThat(mapped.date).isEqualTo(5_000L)
        assertThat(mapped.direction).isEqualTo(Message.Direction.OUTGOING)
        assertThat(mapped.status).isEqualTo(Message.Status.RECEIVED)
        assertThat(mapped.type).isEqualTo(Message.Type.SMS)
        assertThat(mapped.read).isTrue()
        assertThat(mapped.attachments).isEmpty()
    }

    @Test
    fun `entry with attachments maps to MMS and carries them`() {
        val mapped = entry(attachments = listOf(attachment())).toDisplayMessage()

        assertThat(mapped.type).isEqualTo(Message.Type.MMS)
        assertThat(mapped.attachmentsCount).isEqualTo(1)
        assertThat(mapped.attachments).containsExactly(attachment())
        assertThat(mapped.direction).isEqualTo(Message.Direction.OUTGOING)
        assertThat(mapped.status).isEqualTo(Message.Status.RECEIVED)
    }

    @Test
    fun `note entries map to encrypted display messages`() {
        // Round 47 — a note entry IS encrypted (at rest), so the
        // display message carries the flag: the bubble keeps the
        // blue default instead of turning green like a plain SMS.
        assertThat(entry().toDisplayMessage().encrypted).isTrue()
        assertThat(entry(attachments = listOf(attachment())).toDisplayMessage().encrypted).isTrue()
    }

    @Test
    fun `title cap is 40`() {
        assertThat(NOTE_TITLE_MAX_LENGTH).isEqualTo(40)
    }
}
