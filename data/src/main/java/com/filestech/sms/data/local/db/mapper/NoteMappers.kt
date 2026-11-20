package com.filestech.sms.data.local.db.mapper

import com.filestech.sms.data.local.db.entity.NoteAttachmentEntity
import com.filestech.sms.data.local.db.entity.NoteEntity
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage

/** Room entity → domain model mappers for notes (see `EntityMappers.kt` for the pattern). */

fun NoteEntity.toDomain(): Note = Note(
    id = id,
    uuid = uuid,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    draft = draft,
    pinned = pinned,
    archived = archived,
    bubbleColorArgb = bubbleColorArgb,
)

fun NoteMessageEntity.toDomain(attachments: List<Attachment> = emptyList()): NoteMessage =
    NoteMessage(
        id = id,
        uuid = uuid,
        noteId = noteId,
        body = body,
        date = date,
        attachments = attachments,
    )

/**
 * Note attachments reuse the domain [Attachment] model. `messageId` is the NOTE message
 * id here (not an SMS message id), and `localUri` points at the ENCRYPTED file — decrypt
 * through `NoteAttachmentResolver`, never by reading the URI directly.
 */
fun NoteAttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    messageId = noteMessageId,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    localUri = localUri,
    width = width,
    height = height,
    durationMs = durationMs,
    hubAttachmentId = hubAttachmentId,
)
