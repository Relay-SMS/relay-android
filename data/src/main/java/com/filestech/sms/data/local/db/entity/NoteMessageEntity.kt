package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry in a [NoteEntity]: the user's own text and/or attachments. There is no delivery
 * lifecycle (a local save has no status), no direction (every entry is the user's own), no
 * read/starred/reaction/reply columns in v1 — see the design doc §5.5 for what is disabled
 * for note threads and why.
 *
 * Deleting the note deletes its entries by [ForeignKey.CASCADE]; the repository deletes the
 * attachment FILES first (files are not covered by the cascade).
 */
@Entity(
    tableName = "note_messages",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["note_id", "date"]),
        // Backup dedup / merge key (D7).
        Index(value = ["uuid"], unique = true),
        // Phase 2 (D8) — the hub note-message id is the upsert key for mirrored entries.
        Index(value = ["hub_note_message_id"], unique = true),
        // Phase 2 (D8) — client send dedup key for `save_note_message`.
        Index(value = ["client_message_id"], unique = true),
    ],
)
data class NoteMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UUID v4, assigned at creation; the backup dedup key. */
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "note_id") val noteId: Long,
    /**
     * Entry text. May be empty when the entry is attachment-only — the note preview then
     * falls back to an emoji + file label (see `NoteRepositoryImpl.previewFor`).
     */
    @ColumnInfo(name = "body", defaultValue = "''") val body: String = "",
    /** Epoch ms the entry was saved (or imported). */
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "attachments_count", defaultValue = "0") val attachmentsCount: Int = 0,
    /** Phase 2 (D8) — the hub's Room id for this entry; null on hub-mode rows. */
    @ColumnInfo(name = "hub_note_message_id") val hubNoteMessageId: Long? = null,
    /** Phase 2 (D8) — the client-side send key; null on hub-mode rows. */
    @ColumnInfo(name = "client_message_id") val clientMessageId: String? = null,
)
