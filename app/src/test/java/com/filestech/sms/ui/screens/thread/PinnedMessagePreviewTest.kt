package com.filestech.sms.ui.screens.thread

import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Feedback round 5 — pinned-message banner: preview text and sender label
 * (pure helpers, no Android dependencies).
 *
 * Covers: body wins over attachments (a captioned photo shows its caption);
 * blank body falls back to Photo / Video / Voice message by MIME type; an
 * unknown attachment shows its file name, or "Attachment" when nameless;
 * a textless message with no attachment at all shows "Attachment"; the
 * sender reads "You" for outgoing, the conversation title for 1-to-1
 * incoming, and the resolved member name (raw address as fallback) in
 * groups.
 */
class PinnedMessagePreviewTest {

    private fun msg(
        id: Long,
        body: String,
        outgoing: Boolean = false,
        attachments: List<Attachment> = emptyList(),
    ) = Message(
        id = id,
        conversationId = 1L,
        address = if (outgoing) "me" else "+33612345678",
        body = body,
        type = Message.Type.MMS,
        direction = if (outgoing) Message.Direction.OUTGOING else Message.Direction.INCOMING,
        date = id * 1_000L,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.SENT,
        errorCode = null,
        attachmentsCount = attachments.size,
        subId = null,
        scheduledAt = null,
        attachments = attachments,
    )

    private fun attachment(mimeType: String, fileName: String? = null) = Attachment(
        id = 1L,
        messageId = 1L,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = 0L,
        localUri = "content://x",
        width = null,
        height = null,
        durationMs = null,
    )

    private fun preview(message: Message) = pinnedPreviewText(
        message = message,
        photoLabel = "Photo",
        videoLabel = "Video",
        voiceLabel = "Voice message",
        fileLabel = "Attachment",
    )

    @Test fun `text body wins over attachments`() {
        val captioned = msg(1, "By the soda machine", attachments = listOf(attachment("image/jpeg")))
        assertThat(preview(captioned)).isEqualTo("By the soda machine")
    }

    @Test fun `blank body falls back to the attachment label`() {
        assertThat(preview(msg(1, "", attachments = listOf(attachment("image/jpeg"))))).isEqualTo("Photo")
        assertThat(preview(msg(1, "", attachments = listOf(attachment("video/mp4"))))).isEqualTo("Video")
        assertThat(preview(msg(1, "", attachments = listOf(attachment("audio/mp4"))))).isEqualTo("Voice message")
    }

    @Test fun `unknown attachment shows its file name, or Attachment when nameless`() {
        val named = msg(1, "", attachments = listOf(attachment("application/pdf", "menu.pdf")))
        assertThat(preview(named)).isEqualTo("menu.pdf")
        val nameless = msg(1, "", attachments = listOf(attachment("application/pdf")))
        assertThat(preview(nameless)).isEqualTo("Attachment")
    }

    @Test fun `textless message with no attachment shows Attachment`() {
        assertThat(preview(msg(1, ""))).isEqualTo("Attachment")
    }

    @Test fun `sender is You for outgoing, title for 1-to-1, member name in groups`() {
        val outgoing = msg(1, "hi", outgoing = true)
        assertThat(
            pinnedSenderLabel(outgoing, false, "Marie", emptyMap(), "You"),
        ).isEqualTo("You")
        val incoming = msg(2, "hi")
        assertThat(
            pinnedSenderLabel(incoming, false, "Marie", emptyMap(), "You"),
        ).isEqualTo("Marie")
        val groupNamed = msg(3, "hi")
        assertThat(
            pinnedSenderLabel(
                groupNamed,
                true,
                "Family",
                mapOf("+33612345678" to "Marie"),
                "You",
            ),
        ).isEqualTo("Marie")
        val groupUnknown = msg(4, "hi")
        assertThat(
            pinnedSenderLabel(groupUnknown, true, "Family", emptyMap(), "You"),
        ).isEqualTo("+33612345678")
        // Clarification — the label is the AUTHOR, never the pinner: your own
        // message in a group still reads "You", not the group title.
        val groupOutgoing = msg(5, "hi", outgoing = true)
        assertThat(
            pinnedSenderLabel(groupOutgoing, true, "Family", emptyMap(), "You"),
        ).isEqualTo("You")
    }
}
