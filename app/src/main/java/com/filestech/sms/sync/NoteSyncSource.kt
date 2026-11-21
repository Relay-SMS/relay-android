package com.filestech.sms.sync

import com.filestech.sms.sync.SyncProtocol.NoteMessageFailed
import com.filestech.sms.sync.SyncProtocol.SendAttachment
import kotlinx.coroutines.flow.Flow

/**
 * Round 36 phase 2 — the seam between the note sync engine and the
 * phone's note store (SYNC_PROTOCOL.md, Notes sync). The sync package
 * defines this contract and never sees Room, the DAOs, or the domain
 * models: the implementation lives outside the sync package and maps
 * domain models to the wire DTOs.
 *
 * A separate interface from [SyncMessageSource] on purpose: the note
 * store is a different subsystem (its own Room tables, its own
 * encryption), and the hub engine that consumes it
 * ([NoteSyncEngine]) is a separate class too — the message engine
 * is at the Detekt class-size limit, and notes must not push it
 * over. The two sources share only the upload staging directory
 * (see `uploadDir` in the glue package).
 *
 * All ids on the wire are decimal strings of the phone's Room ids.
 * Note attachment bytes cross the link as PLAINTEXT — decrypted
 * from the hub's AES-256-GCM envelope here, re-encrypted by the
 * receiving device — inside the existing TLS device link.
 */
interface NoteSyncSource {

    /** Every note, newest activity first — the snapshot source. */
    suspend fun notes(): List<SyncedNote>

    /** The note list as a Flow — the snapshot-diff collector watches it. */
    fun observeNotes(): Flow<List<SyncedNote>>

    /** The note count (the `total_notes` of `sync_started`). */
    suspend fun totalNotes(): Int

    /** The highest note-message id in the store (the note cursor). */
    suspend fun maxNoteMessageId(): Long

    /**
     * The [limit] most recent entries of [noteId] strictly below
     * [beforeId], newest first. [beforeId] `0` means newest.
     */
    suspend fun noteMessagesBefore(
        noteId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedNoteMessage>

    /** Every entry of [noteId] above [afterId], oldest first (the delta page). */
    suspend fun noteMessagesAfter(noteId: Long, afterId: Long): List<SyncedNoteMessage>

    /**
     * Every entry above [afterId], oldest first, across all notes —
     * the live push's source. The engine diffs against its last
     * sighting and pushes one `note_message_new` per new entry.
     */
    suspend fun noteMessagesAfterAll(afterId: Long): List<SyncedNoteMessage>

    /** One entry by hub id, or null when unknown — the edits collector's push source. */
    suspend fun noteMessageById(messageId: Long): SyncedNoteMessage?

    /**
     * The edited entries (id, note, body) — the edits collector
     * diffs the body against its last sighting and pushes
     * `note_message_updated` on change.
     */
    fun observeEditedMessages(): Flow<List<EditedNoteMessage>>

    /**
     * Creates a note for a `create_note` frame. [clientNoteId] is
     * the client's dedup key: a note already carrying it is
     * returned, never duplicated.
     */
    suspend fun createNote(clientNoteId: String, title: String): CreateNoteResult

    /**
     * Saves an entry for a `save_note_message` frame. [attachments]
     * are the frame's upload references — the bytes were staged
     * beforehand (`kind: "note"`); a missing staged file fails
     * with [NoteMessageFailed.UPLOAD_EXPIRED]. [clientMessageId]
     * is the client's dedup key: an entry already carrying it is
     * returned, never duplicated.
     */
    suspend fun saveNoteMessage(
        clientMessageId: String,
        noteId: Long,
        body: String,
        attachments: List<SendAttachment>?,
    ): SaveNoteMessageResult

    /** Applies `edit_note_message`: updates the row + marks it edited. */
    suspend fun editNoteMessage(noteId: Long, messageId: Long, body: String): ActionResult

    /** Applies `rename_note`. */
    suspend fun renameNote(noteId: Long, title: String): ActionResult

    /** Applies `delete_note`. */
    suspend fun deleteNote(noteId: Long): ActionResult

    /** Applies `delete_note_message`. */
    suspend fun deleteNoteMessage(noteId: Long, messageId: Long): ActionResult

    /** Applies `set_note_pin`. */
    suspend fun setNotePinned(noteId: Long, pinned: Boolean): ActionResult

    /** Applies `set_note_archived`. */
    suspend fun setNoteArchived(noteId: Long, archived: Boolean): ActionResult

    /**
     * Opens one note attachment's bytes for download, DECRYPTED
     * from the hub's envelope. [Unknown] when the attachment
     * doesn't exist or doesn't belong to [messageId] (both look
     * the same, so ids can't be probed); [Unreadable] when the row
     * exists but the bytes don't (file gone, key lost). The
     * [AttachmentData.stream] must be closed by the caller.
     */
    suspend fun openNoteAttachment(
        attachmentId: Long,
        messageId: Long,
    ): AttachmentOpenResult
}

/** One row of [NoteSyncSource.observeEditedMessages]. */
data class EditedNoteMessage(
    val id: Long,
    val noteId: Long,
    val body: String,
)

/** What [NoteSyncSource.createNote] did. */
sealed interface CreateNoteResult {
    /** The note (newly created, or the dedup hit for the client key). */
    data class Created(val note: SyncedNote) : CreateNoteResult

    /** The create was refused; [code] is an `action_result`-style code (`invalid_request`, `internal`). */
    data class Failed(val code: String, val message: String) : CreateNoteResult
}

/** What [NoteSyncSource.saveNoteMessage] did. */
sealed interface SaveNoteMessageResult {
    /** The entry's hub id (newly saved, or the dedup hit for the client key). */
    data class Saved(val messageId: Long) : SaveNoteMessageResult

    /** The save was refused; [code] is a [NoteMessageFailed] code. */
    data class Failed(val code: String, val message: String) : SaveNoteMessageResult
}
