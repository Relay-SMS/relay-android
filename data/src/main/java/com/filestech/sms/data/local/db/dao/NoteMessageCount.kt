package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Phase 2 — one row of [NoteMessageDao.countsByNote]: how many
 * entries a note holds. Notes with no entry produce no row.
 */
data class NoteMessageCount(
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)
