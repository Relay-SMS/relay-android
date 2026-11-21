package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [NoteMessageEntity]. All note writes go through
 * [com.filestech.sms.data.notes.NoteRepositoryImpl].
 *
 * TooManyFunctions is suppressed like the repository interface:
 * the sync section (hub/client lookups, paging, edits) puts this
 * DAO past the 20-function interface threshold, and a DAO is one
 * seam by definition — splitting it would split the table.
 */
@Suppress("TooManyFunctions")
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

    /** Phase 2 — mirror lookup: the entry carrying this hub id, if any. */
    @Query("SELECT * FROM note_messages WHERE hub_note_message_id = :hubId LIMIT 1")
    suspend fun findByHubId(hubId: Long): NoteMessageEntity?

    /** Phase 2 — hub dedup lookup: the entry carrying this client save key, if any. */
    @Query("SELECT * FROM note_messages WHERE client_message_id = :clientId LIMIT 1")
    suspend fun findByClientMessageId(clientId: String): NoteMessageEntity?

    /**
     * Phase 2 — the hub's edits collector: every edited row (id,
     * note, body). The collector diffs the body against its last
     * sighting and pushes `note_message_updated` on change.
     */
    @Query("SELECT id, note_id, body FROM note_messages WHERE edited = 1")
    fun observeEditedMessages(): Flow<List<EditedNoteMessageRow>>

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

    /** Phase 2 — the mirror's update arm (adopts + field refresh by primary key). */
    @Update
    suspend fun update(message: NoteMessageEntity)

    /** Attachments follow by `ForeignKey.CASCADE`; the repository deletes the files first. */
    @Query("DELETE FROM note_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE note_messages SET attachments_count = :count WHERE id = :id")
    suspend fun setAttachmentsCount(id: Long, count: Int)

    /** Phase 2 — edits the body and marks the entry edited (one statement, one emission). */
    @Query("UPDATE note_messages SET body = :body, edited = 1 WHERE id = :id")
    suspend fun setBodyEdited(id: Long, body: String)

    /**
     * Phase 2 — restores a pre-edit body (the client's revert when
     * the hub refuses `edit_note_message`), restoring the edited
     * flag it had before the optimistic edit.
     */
    @Query("UPDATE note_messages SET body = :body, edited = :edited WHERE id = :id")
    suspend fun setBody(id: Long, body: String, edited: Boolean)

    /** Phase 2 — links a client-saved entry to its hub id (the `note_message_saved` ack). */
    @Query("UPDATE note_messages SET hub_note_message_id = :hubId WHERE client_message_id = :clientId")
    suspend fun linkToHub(clientId: String, hubId: Long)

    /**
     * Phase 2 — the hub's snapshot page: the [limit] most recent
     * entries of a note strictly below [beforeId] (id order, not
     * date — ids are monotonic, dates are not), newest first. The
     * client pages with the last id it saw.
     */
    @Query(
        """
        SELECT * FROM note_messages
        WHERE note_id = :noteId AND id < :beforeId
        ORDER BY id DESC LIMIT :limit
        """,
    )
    suspend fun listBefore(noteId: Long, beforeId: Long, limit: Int): List<NoteMessageEntity>

    /**
     * Phase 2 — the hub's delta page: every entry of a note above
     * [afterId], oldest first (the client applies them in order and
     * advances its note cursor to the last id).
     */
    @Query(
        """
        SELECT * FROM note_messages
        WHERE note_id = :noteId AND id > :afterId
        ORDER BY id ASC
        """,
    )
    suspend fun listAfter(noteId: Long, afterId: Long): List<NoteMessageEntity>

    /** Phase 2 — the hub's note cursor: the highest note-message id in the store. */
    @Query("SELECT MAX(id) FROM note_messages")
    suspend fun maxId(): Long?

    /** Phase 2 — the entry count per note (the `message_count` of `SyncedNote`). */
    @Query("SELECT note_id, COUNT(*) AS message_count FROM note_messages GROUP BY note_id")
    suspend fun countsByNote(): List<NoteMessageCount>

    /**
     * Phase 2 — the hub's live push: every entry strictly above
     * [afterId], oldest first, across all notes.
     */
    @Query("SELECT * FROM note_messages WHERE id > :afterId ORDER BY id ASC")
    suspend fun listAfterAll(afterId: Long): List<NoteMessageEntity>
}
