package com.filestech.sms.domain.model

/**
 * One entry in a [Note]: the user's own text and/or attachments. There is no delivery
 * lifecycle (a local save has no status), no direction (every entry is the user's own) and
 * no read/starred/reaction state in v1.
 *
 * [attachments] reuse the domain [Attachment] model. For note attachments,
 * `Attachment.messageId` is the NOTE message id, and `Attachment.localUri` points at the
 * ENCRYPTED file on disk — decrypt through `NoteAttachmentResolver`, never by reading the
 * URI directly (Coil would render ciphertext).
 */
data class NoteMessage(
    val id: Long,
    /** UUID v4, assigned at creation; the backup dedup key. */
    val uuid: String,
    val noteId: Long,
    /** Entry text; may be empty when the entry is attachment-only. */
    val body: String,
    /** Epoch ms the entry was saved (or imported). */
    val date: Long,
    val attachments: List<Attachment> = emptyList(),
)
