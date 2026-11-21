package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.NoteAttachmentEntity

/**
 * DAO for [NoteAttachmentEntity]. All note writes go through
 * [com.filestech.sms.data.notes.NoteRepositoryImpl], which owns the attachment-file
 * lifecycle: a file is deleted when its row is deleted, never before, never refcounted.
 */
@Dao
interface NoteAttachmentDao {

    @Insert
    suspend fun insert(attachment: NoteAttachmentEntity): Long

    @Query("SELECT * FROM note_attachments WHERE note_message_id = :messageId")
    suspend fun findForMessage(messageId: Long): List<NoteAttachmentEntity>

    /**
     * Bulk-fetch attachments for exactly the given messages. Used by the windowed thread
     * read, whose window is bounded: fetching the whole note there would re-run a
     * SQLCipher query over the entire history on every emission. The caller chunks the
     * ids — a window grown by repeated "load older" pages can exceed SQLite's
     * bound-variable cap for a single `IN` list.
     */
    @Query("SELECT * FROM note_attachments WHERE note_message_id IN (:messageIds)")
    suspend fun findForMessages(messageIds: List<Long>): List<NoteAttachmentEntity>

    @Query("SELECT * FROM note_attachments WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteAttachmentEntity?

    /** Backup import lookup: the row carrying this UUID, if any. */
    @Query("SELECT * FROM note_attachments WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): NoteAttachmentEntity?

    /** Phase 2 — mirror lookup: the attachment carrying this hub id, if any. */
    @Query("SELECT * FROM note_attachments WHERE hub_attachment_id = :hubId LIMIT 1")
    suspend fun findByHubId(hubId: Long): NoteAttachmentEntity?

    /**
     * Phase 2 — the downloader fills a mirrored attachment's
     * `local_uri` once the bytes land (encrypted). The mirror starts
     * the row blank, which is also what makes the download tile
     * render.
     */
    @Query("UPDATE note_attachments SET local_uri = :localUri WHERE id = :id")
    suspend fun updateLocalUri(id: Long, localUri: String)

    /**
     * Phase 2 — links a client-saved attachment to its hub id: the
     * hub echoes attachments in send order, so the mirror zips the
     * optimistic rows (no hub id yet) with the wire list by position.
     */
    @Query("UPDATE note_attachments SET hub_attachment_id = :hubId WHERE id = :id")
    suspend fun setHubId(id: Long, hubId: Long)

    /** Snapshot read for the notes backup pipeline. */
    @Query("SELECT * FROM note_attachments ORDER BY id ASC")
    suspend fun listAll(): List<NoteAttachmentEntity>

    /**
     * Every attachment of a note, oldest entry first. Used by the details panel's
     * media/files tabs and by the repository to collect file paths before a note delete.
     */
    @Query(
        """
        SELECT a.* FROM note_attachments a
        JOIN note_messages m ON m.id = a.note_message_id
        WHERE m.note_id = :noteId
        ORDER BY m.date ASC, m.id ASC, a.id ASC
        """,
    )
    suspend fun findForNote(noteId: Long): List<NoteAttachmentEntity>

    /** The encrypted-file paths of a note, for the file wipe that follows a note delete. */
    @Query(
        """
        SELECT a.local_uri FROM note_attachments a
        JOIN note_messages m ON m.id = a.note_message_id
        WHERE m.note_id = :noteId
        """,
    )
    suspend fun findLocalUrisForNote(noteId: Long): List<String>

    @Query("DELETE FROM note_attachments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM note_attachments WHERE note_message_id = :messageId")
    suspend fun deleteForMessage(messageId: Long)
}
