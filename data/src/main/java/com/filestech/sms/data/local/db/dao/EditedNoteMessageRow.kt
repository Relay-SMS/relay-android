package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Phase 2 — one row of [NoteMessageDao.observeEditedMessages]: the
 * hub's edits collector watches edited entries and pushes
 * `note_message_updated` when a body changes after its first
 * sighting.
 */
data class EditedNoteMessageRow(
    val id: Long,
    @ColumnInfo(name = "note_id") val noteId: Long,
    val body: String,
)
