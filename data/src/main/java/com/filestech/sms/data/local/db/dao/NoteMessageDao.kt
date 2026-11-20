package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [NoteMessageEntity]. All note writes go through
 * [com.filestech.sms.data.notes.NoteRepositoryImpl].
 */
@Dao
interface NoteMessageDao {

    /**
     * Bounded window: the [limit] most recent entries, returned oldest-first for the UI.
     * Mirrors `MessageDao.observeWindowForConversation` — including the `id` tie-breaker,
     * which is not optional: `date` alone is not unique (an import can land several
     * entries on the same millisecond), and without a total order the window boundary is
     * non-deterministic, so a row can be dropped or duplicated between two emissions.
     * The `(note_id, date)` index covers this ordering; no new index is needed.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM note_messages
            WHERE note_id = :noteId
            ORDER BY date DESC, id DESC
            LIMIT :limit
        ) ORDER BY date ASC, id ASC
        """,
    )
    fun observeWindowForNote(noteId: Long, limit: Int): Flow<List<NoteMessageEntity>>

    /**
     * Whole-thread statistics, computed in SQL over **every** entry — never over the loaded
     * window (same rule as `MessageDao.observeStatsForConversation`).
     */
    @Query(
        """
        SELECT COUNT(*) AS total, MIN(date) AS firstAt, MAX(date) AS lastAt FROM note_messages
        WHERE note_id = :noteId
        """,
    )
    fun observeStatsForNote(noteId: Long): Flow<ThreadStats>

    /**
     * Unbounded history, oldest first. The PDF export must see the entire note — bounding
     * it would silently export a truncated document. The thread UI uses
     * [observeWindowForNote] instead.
     */
    @Query("SELECT * FROM note_messages WHERE note_id = :noteId ORDER BY date ASC, id ASC")
    fun observeForNote(noteId: Long): Flow<List<NoteMessageEntity>>

    @Query("SELECT * FROM note_messages WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteMessageEntity?

    /** Backup import lookup: the row carrying this UUID, if any. */
    @Query("SELECT * FROM note_messages WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): NoteMessageEntity?

    /**
     * The newest entry of a note (by date, then id) — the preview source. Null when the
     * note is empty.
     */
    @Query(
        """
        SELECT * FROM note_messages
        WHERE note_id = :noteId
        ORDER BY date DESC, id DESC LIMIT 1
        """,
    )
    suspend fun findLatestForNote(noteId: Long): NoteMessageEntity?

    /** Snapshot read for the notes backup pipeline. */
    @Query("SELECT * FROM note_messages ORDER BY id ASC")
    suspend fun listAll(): List<NoteMessageEntity>

    @Insert
    suspend fun insert(message: NoteMessageEntity): Long

    /** Attachments follow by `ForeignKey.CASCADE`; the repository deletes the files first. */
    @Query("DELETE FROM note_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE note_messages SET attachments_count = :count WHERE id = :id")
    suspend fun setAttachmentsCount(id: Long, count: Int)
}
