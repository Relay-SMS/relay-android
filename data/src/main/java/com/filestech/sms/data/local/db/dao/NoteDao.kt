package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.filestech.sms.data.local.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [NoteEntity]. All note writes go through
 * [com.filestech.sms.data.notes.NoteRepositoryImpl] — never call this DAO from the UI layer
 * directly (preview maintenance and attachment-file lifecycle live in the repository).
 */
@Dao
interface NoteDao {

    /**
     * Every note, pinned first, then by recency. An empty note has a null `last_message_at`,
     * and SQLite sorts NULLs FIRST under `DESC` — so the order falls back to `created_at`:
     * a brand-new empty note sorts by its creation time instead of jumping above notes
     * with real entries.
     */
    @Query(
        """
        SELECT * FROM notes
        ORDER BY pinned DESC, COALESCE(last_message_at, created_at) DESC
        """,
    )
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteEntity?

    /** Backup import lookup: the row carrying this UUID, if any. */
    @Query("SELECT * FROM notes WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): NoteEntity?

    /** Phase 2 — mirror lookup: the note carrying this hub id, if any. */
    @Query("SELECT * FROM notes WHERE hub_note_id = :hubId LIMIT 1")
    suspend fun findByHubId(hubId: Long): NoteEntity?

    /** Phase 2 — hub dedup lookup: the note carrying this client create key, if any. */
    @Query("SELECT * FROM notes WHERE client_note_id = :clientId LIMIT 1")
    suspend fun findByClientNoteId(clientId: String): NoteEntity?

    /** Phase 2 — every hub note id currently mirrored (the snapshot-prune input). */
    @Query("SELECT hub_note_id FROM notes WHERE hub_note_id IS NOT NULL")
    suspend fun hubNoteIds(): List<Long>

    /** Phase 2 — the note count (the `total_notes` of `sync_started`). */
    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    /** Snapshot read for the notes backup pipeline (includes archived rows). */
    @Query("SELECT * FROM notes ORDER BY id ASC")
    suspend fun listAll(): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    /** Children follow by `ForeignKey.CASCADE`; the repository deletes the files first. */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE notes SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE notes SET pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: Long, draft: String?)

    @Query("UPDATE notes SET bubble_color_argb = :colorArgb, updated_at = :updatedAt WHERE id = :id")
    suspend fun setBubbleColor(id: Long, colorArgb: Int?, updatedAt: Long)

    /**
     * Rewrites the denormalized preview. Called by the repository after every
     * message/attachment mutation; `at`/`preview` are null when the note holds no entries.
     * Deliberately does NOT touch `updated_at` (see `NoteEntity.updatedAt`).
     */
    @Query("UPDATE notes SET last_message_at = :at, last_message_preview = :preview WHERE id = :id")
    suspend fun setPreview(id: Long, at: Long?, preview: String?)
}
