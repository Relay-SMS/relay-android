package com.filestech.sms.domain.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.NoteMessageWindow
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The SINGLE write path for notes. Every note read the UI or the backup builder needs, and
 * every note mutation, goes through here — the DAOs are never called directly outside the
 * data layer.
 *
 * The repository owns:
 * - the denormalized note preview (`last_message_at` / `last_message_preview`), recomputed
 *   from the newest entry after every message/attachment mutation (recompute, not
 *   incremental touch, so imports that insert out of order still land on the right preview);
 * - the attachment-file lifecycle: a note attachment file is owned solely by its row, so
 *   deleting a row deletes its file (no refcounting — a note file is never cited twice);
 * - attachment encryption on write: [addAttachment] encrypts the plaintext bytes before
 *   they touch disk (decryption is `NoteAttachmentResolver`'s job).
 *
 * The `uuid` / `createdAt` / `updatedAt` / `date` parameters default to fresh values for
 * live use; the notes backup import passes the backed-up values so a re-import is
 * idempotent (rows are matched by UUID — see `findNoteByUuid` / `findMessageByUuid` /
 * `findAttachmentByUuid`).
 *
 * Threading: every function is main-safe (reads/writes run on the IO dispatcher inside the
 * implementation).
 *
 * The function count is the single-write-path contract: one interface owns every note
 * read and mutation so the UI, the backup builder, and the restorer never touch a DAO
 * directly. Splitting it would only move the functions, not reduce them.
 */
@Suppress("TooManyFunctions")
interface NoteRepository {

    // ------------------------------------------------------------------ reads

    /**
     * Every note, pinned first, then by recency (an empty note sorts by its creation time).
     * The conversation list merges this with the conversations flow.
     */
    fun observeAllNotes(): Flow<List<Note>>

    fun observeNote(noteId: Long): Flow<Note?>

    /**
     * Bounded window for the note thread: the [limit] most recent entries, oldest first,
     * plus whole-note stats. The thread grows [limit] on "load older". Mirrors
     * `ConversationRepository.observeMessagesWindow` (tail window only — note threads have
     * no search jump in v1).
     */
    fun observeMessagesWindow(noteId: Long, limit: Flow<Int>): Flow<NoteMessageWindow>

    /**
     * Whole history, oldest first. For the PDF export, which must see the entire note.
     * The thread UI uses [observeMessagesWindow] instead.
     */
    fun observeMessages(noteId: Long): Flow<List<NoteMessage>>

    suspend fun findNoteById(noteId: Long): Note?

    /** Backup import lookup: the note carrying this UUID, if any. */
    suspend fun findNoteByUuid(uuid: String): Note?

    suspend fun findMessageById(messageId: Long): NoteMessage?

    /** Backup import lookup: the entry carrying this UUID, if any. */
    suspend fun findMessageByUuid(uuid: String): NoteMessage?

    suspend fun findAttachmentById(attachmentId: Long): Attachment?

    /** Backup import lookup: the attachment carrying this UUID, if any. */
    suspend fun findAttachmentByUuid(uuid: String): Attachment?

    suspend fun attachmentsForMessage(messageId: Long): List<Attachment>

    /** Every attachment of a note, oldest entry first (details panel media/files tabs). */
    suspend fun attachmentsForNote(noteId: Long): List<Attachment>

    // ------------------------------------------------------------------ notes

    /**
     * Creates a note with the given [title] and returns it. The title is required (a note
     * with no title has no identity in the list).
     */
    suspend fun createNote(
        title: String,
        uuid: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = createdAt,
    ): Note

    suspend fun renameNote(noteId: Long, title: String)

    /**
     * Deletes the note, its entries and its attachment rows (by FK cascade) AND their
     * encrypted files. There is no undo and no system counterpart to propagate to.
     */
    suspend fun deleteNote(noteId: Long)

    suspend fun setPinned(noteId: Long, pinned: Boolean)

    suspend fun setArchived(noteId: Long, archived: Boolean)

    suspend fun setDraft(noteId: Long, draft: String?)

    /** Chat color (ARGB); null restores the default brand blue. */
    suspend fun setBubbleColor(noteId: Long, colorArgb: Int?)

    // ------------------------------------------------------------------ messages

    /**
     * Appends an entry to the note and refreshes the note preview. The caller passes a
     * non-blank [body], or adds at least one attachment afterwards — an entry with an
     * empty body and no attachments has no preview and renders as an empty bubble.
     */
    suspend fun insertMessage(
        noteId: Long,
        body: String,
        uuid: String = UUID.randomUUID().toString(),
        date: Long = System.currentTimeMillis(),
    ): NoteMessage

    /** Deletes the entry, its attachment rows and their encrypted files. */
    suspend fun deleteMessage(messageId: Long)

    // ------------------------------------------------------------------ attachments

    /**
     * Encrypts [plaintext] and stores it as an attachment of [messageId], returning the
     * stored row. `size_bytes` records the PLAINTEXT size; the ciphertext on disk is 29
     * bytes larger. The note preview is refreshed when this entry is the newest one.
     *
     * Returns `Outcome.Failure` ([AppError.Crypto] when the notes key is lost/corrupt,
     * [AppError.Storage] on an I/O failure) — the caller surfaces an unreadable-attachment
     * state, never a crash. On failure nothing is stored: no row, no file.
     */
    suspend fun addAttachment(
        messageId: Long,
        mimeType: String,
        fileName: String?,
        plaintext: ByteArray,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        uuid: String = UUID.randomUUID().toString(),
    ): Outcome<Attachment>

    /** Deletes the attachment row and its encrypted file. */
    suspend fun deleteAttachment(attachmentId: Long)

    // ------------------------------------------------------------------ backup

    /** Every note, by id — the export snapshot (includes archived rows). */
    suspend fun listAllNotes(): List<Note>

    /**
     * Every entry, by id, WITH attachments loaded — the export snapshot. The backup pairs
     * entries to notes by `noteId` and reads attachment bytes through
     * `NoteAttachmentResolver.decryptToMemory`.
     */
    suspend fun listAllMessages(): List<NoteMessage>

    /** Every attachment, by id — the export snapshot (flat list). */
    suspend fun listAllAttachments(): List<Attachment>
}
