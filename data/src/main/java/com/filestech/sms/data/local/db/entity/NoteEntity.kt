package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note: a local-only thread of entries, stored in Relay's own database and NEVER in the
 * system SMS store. Notes have no addresses, no thread id and no telephony counterpart — no
 * Telephony import path touches this table (see the design doc, decision D1).
 *
 * The row's text (title, preview, draft) is encrypted at rest by the whole-database SQLCipher
 * envelope (decision D2); only attachment FILES get their own envelope (see
 * [com.filestech.sms.data.notes.NoteAttachmentCrypto]).
 *
 * Uninstall deletes the app's files AND its Keystore keys, so notes are unrecoverable after
 * an uninstall — the separate notes backup is the only recovery path.
 */
@Entity(
    tableName = "notes",
    indices = [
        // Backup dedup / merge key (D7): one row per UUID, so re-importing the same backup
        // file is a no-op.
        Index(value = ["uuid"], unique = true),
        // Phase 2 (D8) — the hub note id is the upsert key for mirrored notes on a client
        // device; SQLite admits several NULLs, so hub-mode rows are unaffected.
        Index(value = ["hub_note_id"], unique = true),
        // Phase 2 (D8) — client create dedup key: a retried `create_note` frame lands on
        // the same row instead of creating a second note.
        Index(value = ["client_note_id"], unique = true),
        Index(value = ["pinned", "last_message_at"]),
        Index(value = ["archived"]),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UUID v4, assigned at creation; the backup dedup key and the phase-2 sync key. */
    @ColumnInfo(name = "uuid") val uuid: String,
    /** The note's name, required — unlike groups, a note with no title has no identity. */
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * Last metadata edit (rename, pin, archive, bubble color). Message inserts/deletes
     * intentionally do NOT move this: content recency is [lastMessageAt], and keeping the
     * two apart makes a backup import round-trip exact (imported messages carry historical
     * dates, which must not rewrite the note's own timestamp).
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * Denormalized date of the newest entry, null while the note is empty. Maintained by
     * [com.filestech.sms.data.notes.NoteRepositoryImpl] on every message/attachment
     * mutation, mirroring `ConversationMirror`'s preview maintenance. List sorting falls
     * back to [createdAt] when this is null (see `NoteDao.observeAll`).
     */
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long?,
    /** Denormalized preview of the newest entry (body, or an emoji + file label). */
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String?,
    /** Composer draft, parity with conversations. */
    @ColumnInfo(name = "draft") val draft: String? = null,
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Boolean = false,
    /** Chat color, parity with conversations; null = default brand blue. */
    @ColumnInfo(name = "bubble_color_argb") val bubbleColorArgb: Int? = null,
    /** Phase 2 (D8) — the hub's Room id for this note; null on hub-mode rows. */
    @ColumnInfo(name = "hub_note_id") val hubNoteId: Long? = null,
    /** Phase 2 (D8) — the client-side create key; null on hub-mode rows. */
    @ColumnInfo(name = "client_note_id") val clientNoteId: String? = null,
)
