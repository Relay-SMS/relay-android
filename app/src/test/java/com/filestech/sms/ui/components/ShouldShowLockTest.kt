package com.filestech.sms.ui.components

import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.toDisplayMessage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 49 — the lock decision ([shouldShowLock]): the lock rides
 * `encrypted` alone, for every bubble kind and both directions. The
 * rendering itself (the 9×11dp glyph in [BubbleTimestamp]) is Compose
 * UI and needs a UI test to assert per bubble; this file locks the
 * decision every bubble shares.
 */
class ShouldShowLockTest {

    private fun attachment(mimeType: String) = Attachment(
        id = 1L,
        messageId = 1L,
        mimeType = mimeType,
        fileName = "file",
        sizeBytes = 100L,
        localUri = "/tmp/file",
        width = null,
        height = null,
        durationMs = null,
    )

    private fun message(
        encrypted: Boolean,
        type: Message.Type = Message.Type.SMS,
        direction: Message.Direction = Message.Direction.OUTGOING,
        attachments: List<Attachment> = emptyList(),
        body: String = "",
    ) = Message(
        id = 1L,
        conversationId = 1L,
        address = "+33600000000",
        body = body,
        type = type,
        direction = direction,
        date = 0L,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.SENT,
        errorCode = null,
        attachmentsCount = attachments.size,
        subId = null,
        scheduledAt = null,
        attachments = attachments,
        encrypted = encrypted,
    )

    private fun noteEntry(attachments: List<Attachment>, body: String = "") = NoteMessage(
        id = 1L,
        uuid = "00000000-0000-0000-0000-000000000001",
        noteId = 1L,
        body = body,
        date = 0L,
        attachments = attachments,
    ).toDisplayMessage()

    @Test
    fun `encrypted text SMS - lock, outgoing and incoming`() {
        assertThat(shouldShowLock(message(encrypted = true))).isTrue()
        assertThat(
            shouldShowLock(
                message(encrypted = true, direction = Message.Direction.INCOMING),
            ),
        ).isTrue()
    }

    @Test
    fun `encrypted MMS image - lock, outgoing and incoming`() {
        val image = attachment("image/jpeg")
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    attachments = listOf(image),
                ),
            ),
        ).isTrue()
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    direction = Message.Direction.INCOMING,
                    attachments = listOf(image),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `encrypted MMS GIF and sticker - lock`() {
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    attachments = listOf(attachment("image/gif")),
                ),
            ),
        ).isTrue()
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    attachments = listOf(attachment("image/png")),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `encrypted MMS video - lock, outgoing and incoming`() {
        val video = attachment("video/mp4")
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    attachments = listOf(video),
                ),
            ),
        ).isTrue()
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    direction = Message.Direction.INCOMING,
                    attachments = listOf(video),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `encrypted voice message - lock, outgoing and incoming`() {
        val audio = attachment("audio/ogg")
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    attachments = listOf(audio),
                ),
            ),
        ).isTrue()
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    direction = Message.Direction.INCOMING,
                    attachments = listOf(audio),
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `encrypted MMS with a caption - lock`() {
        assertThat(
            shouldShowLock(
                message(
                    encrypted = true,
                    type = Message.Type.MMS,
                    attachments = listOf(attachment("image/jpeg")),
                    body = "look at this",
                ),
            ),
        ).isTrue()
    }

    @Test
    fun `plain MMS image - no lock, outgoing and incoming`() {
        val image = attachment("image/jpeg")
        assertThat(
            shouldShowLock(
                message(
                    encrypted = false,
                    type = Message.Type.MMS,
                    attachments = listOf(image),
                ),
            ),
        ).isFalse()
        assertThat(
            shouldShowLock(
                message(
                    encrypted = false,
                    type = Message.Type.MMS,
                    direction = Message.Direction.INCOMING,
                    attachments = listOf(image),
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `plain MMS video and voice - no lock`() {
        assertThat(
            shouldShowLock(
                message(
                    encrypted = false,
                    type = Message.Type.MMS,
                    attachments = listOf(attachment("video/mp4")),
                ),
            ),
        ).isFalse()
        assertThat(
            shouldShowLock(
                message(
                    encrypted = false,
                    type = Message.Type.MMS,
                    attachments = listOf(attachment("audio/ogg")),
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `plain text SMS - no lock`() {
        assertThat(shouldShowLock(message(encrypted = false))).isFalse()
    }

    @Test
    fun `note photo entry - lock, via the real display projection`() {
        assertThat(
            shouldShowLock(noteEntry(attachments = listOf(attachment("image/jpeg")))),
        ).isTrue()
    }

    @Test
    fun `note voice entry - lock, via the real display projection`() {
        assertThat(
            shouldShowLock(noteEntry(attachments = listOf(attachment("audio/ogg")))),
        ).isTrue()
    }

    @Test
    fun `note text-only entry - lock, as before`() {
        assertThat(shouldShowLock(noteEntry(attachments = emptyList(), body = "a note"))).isTrue()
    }
}
