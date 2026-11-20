package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.filestech.sms.data.local.db.entity.AttachmentEntity

/**
 * Round 32 — an attachment, **accompanied by the date + body of the
 * message carrying it**.
 *
 * The details panel groups its Photos / Videos / Files tabs by date
 * (Today / Yesterday / …) and needs the message body for its sticker
 * check (a 512×512 PNG with no caption) — neither lives on the
 * attachment row itself, so the media queries join the message and
 * return this projection instead of the bare entity. The repository
 * maps it onto [com.filestech.sms.domain.model.Attachment] with the
 * two fields filled; every other attachment flow keeps the plain
 * entity mapping, where they stay `null`.
 */
data class AttachmentWithMessage(
    @Embedded val attachment: AttachmentEntity,
    @ColumnInfo(name = "message_date") val messageDate: Long,
    @ColumnInfo(name = "message_body") val messageBody: String,
)
