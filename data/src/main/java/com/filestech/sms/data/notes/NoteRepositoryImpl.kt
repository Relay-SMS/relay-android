package com.filestech.sms.data.notes

import androidx.room.withTransaction
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.NoteAttachmentDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.dao.NoteMessageDao
import com.filestech.sms.data.local.db.entity.NoteAttachmentEntity
import com.filestech.sms.data.local.db.entity.NoteEntity
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.NoteMessageWindow
import com.filestech.sms.domain.repository.NoteRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SINGLE write path for notes (see [NoteRepository] for the contract).
 *
 * Two invariants live here and nowhere else:
 *
 * - **Preview maintenance.** `notes.last_message_at` / `last_message_preview` are
 *   RECOMPUTED from the newest entry after every message/attachment mutation
 *   ([refreshPreview]), mirroring `ConversationMirror`'s preview maintenance but as a
 *   recompute rather than an incremental touch — imports insert out of order, and a
 *   recompute is order-independent. `updated_at` is deliberately NOT moved by content
 *   changes (see `NoteEntity.updatedAt`).
 * - **File lifecycle.** A note attachment file is owned solely by its row. Deletes collect
 *   the file paths BEFORE the rows go (inside the transaction boundary), delete the rows,
 *   then delete the files after commit. Inserts write + encrypt the file FIRST and insert
 *   the row second; if the insert fails, the orphan file is removed again.
 */
@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val noteDao: NoteDao,
    private val noteMessageDao: NoteMessageDao,
    private val noteAttachmentDao: NoteAttachmentDao,
    private val attachmentCrypto: NoteAttachmentCrypto,
    @IoDispatcher private val io: CoroutineDispatcher,
) : NoteRepository {

    // ------------------------------------------------------------------ reads

    override fun observeAllNotes(): Flow<List<Note>> =
        noteDao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(io)

    override fun observeNote(noteId: Long): Flow<Note?> =
        noteDao.observeById(noteId).map { it?.toDomain() }.flowOn(io)

    override fun observeMessagesWindow(noteId: Long, limit: Flow<Int>): Flow<NoteMessageWindow> {
        // Attachments are fetched for the WINDOW only, not the whole note (same rule as
        // `ConversationRepositoryImpl.observeMessagesWindow`, audit P-P0-1 / round 6):
        // `findForNote` would re-run a SQLCipher query over the entire history on every
        // emission, and its cost would grow with the note.
        val mapped = limit
            .distinctUntilChanged()
            .flatMapLatest { value -> noteMessageDao.observeWindowForNote(noteId, value) }
            .map { rows ->
                val attachmentsByMessage = attachmentsByMessage(rows)
                rows.map { entity -> entity.toDomain(attachmentsByMessage[entity.id].orEmpty()) }
            }
            .flowOn(io)
        // Assembly only: a stats re-emission costs one data class, not a re-fetch.
        return combine(
            mapped,
            noteMessageDao.observeStatsForNote(noteId).distinctUntilChanged(),
        ) { messages, stats ->
            NoteMessageWindow(
                messages = messages,
                totalCount = stats.total,
                hasMore = messages.size < stats.total,
                firstMessageAt = stats.firstAt,
                lastMessageAt = stats.lastAt,
            )
        }
    }

    override fun observeMessages(noteId: Long): Flow<List<NoteMessage>> =
        noteMessageDao.observeForNote(noteId)
            .map { rows ->
                val attachmentsByMessage = attachmentsByMessage(rows)
                rows.map { entity -> entity.toDomain(attachmentsByMessage[entity.id].orEmpty()) }
            }
            .flowOn(io)

    override suspend fun findNoteById(noteId: Long): Note? = withContext(io) {
        noteDao.findById(noteId)?.toDomain()
    }

    override suspend fun findNoteByUuid(uuid: String): Note? = withContext(io) {
        noteDao.findByUuid(uuid)?.toDomain()
    }

    override suspend fun findMessageById(messageId: Long): NoteMessage? = withContext(io) {
        val entity = noteMessageDao.findById(messageId) ?: return@withContext null
        entity.toDomain(noteAttachmentDao.findForMessage(messageId).map { it.toDomain() })
    }

    override suspend fun findMessageByUuid(uuid: String): NoteMessage? = withContext(io) {
        val entity = noteMessageDao.findByUuid(uuid) ?: return@withContext null
        entity.toDomain(noteAttachmentDao.findForMessage(entity.id).map { it.toDomain() })
    }

    override suspend fun findAttachmentById(attachmentId: Long): Attachment? = withContext(io) {
        noteAttachmentDao.findById(attachmentId)?.toDomain()
    }

    override suspend fun findAttachmentByUuid(uuid: String): Attachment? = withContext(io) {
        noteAttachmentDao.findByUuid(uuid)?.toDomain()
    }

    override suspend fun attachmentsForMessage(messageId: Long): List<Attachment> = withContext(io) {
        noteAttachmentDao.findForMessage(messageId).map { it.toDomain() }
    }

    override suspend fun attachmentsForNote(noteId: Long): List<Attachment> = withContext(io) {
        noteAttachmentDao.findForNote(noteId).map { it.toDomain() }
    }

    // ------------------------------------------------------------------ notes

    override suspend fun createNote(
        title: String,
        uuid: String,
        createdAt: Long,
        updatedAt: Long,
    ): Note = withContext(io) {
        val id = noteDao.insert(
            NoteEntity(
                uuid = uuid,
                title = title,
                createdAt = createdAt,
                updatedAt = updatedAt,
                lastMessageAt = null,
                lastMessagePreview = null,
                draft = null,
                pinned = false,
                archived = false,
                bubbleColorArgb = null,
                hubNoteId = null,
                clientNoteId = null,
            ),
        )
        noteDao.findById(id)?.toDomain()
            ?: error("createNote: row $id vanished immediately after insert")
    }

    override suspend fun renameNote(noteId: Long, title: String) = withContext(io) {
        noteDao.rename(noteId, title, System.currentTimeMillis())
    }

    override suspend fun deleteNote(noteId: Long) = withContext(io) {
        // Collect the file paths BEFORE the rows go — after the delete there is nothing
        // left to tell us which files belonged to the note.
        val files = noteAttachmentDao.findLocalUrisForNote(noteId)
        database.withTransaction {
            noteDao.deleteById(noteId)
        }
        files.forEach { attachmentCrypto.deleteFileQuietly(it) }
    }

    override suspend fun setPinned(noteId: Long, pinned: Boolean) = withContext(io) {
        noteDao.setPinned(noteId, pinned, System.currentTimeMillis())
    }

    override suspend fun setArchived(noteId: Long, archived: Boolean) = withContext(io) {
        noteDao.setArchived(noteId, archived, System.currentTimeMillis())
    }

    override suspend fun setDraft(noteId: Long, draft: String?) = withContext(io) {
        noteDao.setDraft(noteId, draft)
    }

    override suspend fun setBubbleColor(noteId: Long, colorArgb: Int?) = withContext(io) {
        noteDao.setBubbleColor(noteId, colorArgb, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ messages

    override suspend fun insertMessage(
        noteId: Long,
        body: String,
        uuid: String,
        date: Long,
    ): NoteMessage = withContext(io) {
        database.withTransaction {
            val id = noteMessageDao.insert(
                NoteMessageEntity(
                    uuid = uuid,
                    noteId = noteId,
                    body = body,
                    date = date,
                    attachmentsCount = 0,
                    hubNoteMessageId = null,
                    clientMessageId = null,
                ),
            )
            refreshPreview(noteId)
            noteMessageDao.findById(id)?.toDomain()
                ?: error("insertMessage: row $id vanished immediately after insert")
        }
    }

    override suspend fun deleteMessage(messageId: Long) = withContext(io) {
        val message = noteMessageDao.findById(messageId) ?: return@withContext
        val files = noteAttachmentDao.findForMessage(messageId).map { it.localUri }
        database.withTransaction {
            noteMessageDao.deleteById(messageId)
            refreshPreview(message.noteId)
        }
        files.forEach { attachmentCrypto.deleteFileQuietly(it) }
    }

    // ------------------------------------------------------------------ attachments

    override suspend fun addAttachment(
        messageId: Long,
        mimeType: String,
        fileName: String?,
        plaintext: ByteArray,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        uuid: String,
    ): Outcome<Attachment> = withContext(io) {
        val message = noteMessageDao.findById(messageId)
            ?: return@withContext Outcome.Failure(AppError.NotFound("note message"))
        // 1. Encrypt + write the file FIRST (outside the transaction — file I/O must not
        // hold the DB lock, and a rollback could not undo it anyway).
        val target = attachmentCrypto.fileForUuid(uuid)
        when (val written = attachmentCrypto.encryptToFile(plaintext, target)) {
            is Outcome.Failure -> return@withContext written
            is Outcome.Success -> Unit
        }
        // 2. Insert the row + refresh the preview atomically. If the insert fails, the
        // orphan file is removed again so no ciphertext lingers without a row.
        try {
            database.withTransaction {
                val id = noteAttachmentDao.insert(
                    NoteAttachmentEntity(
                        uuid = uuid,
                        noteMessageId = messageId,
                        mimeType = mimeType,
                        fileName = fileName,
                        sizeBytes = plaintext.size.toLong(),
                        localUri = target.absolutePath,
                        width = width,
                        height = height,
                        durationMs = durationMs,
                        hubAttachmentId = null,
                    ),
                )
                noteMessageDao.setAttachmentsCount(
                    messageId,
                    noteAttachmentDao.findForMessage(messageId).size,
                )
                refreshPreview(message.noteId)
                noteAttachmentDao.findById(id)?.toDomain()
                    ?: error("addAttachment: row $id vanished immediately after insert")
            }.let { Outcome.Success(it) }
        } catch (t: Throwable) {
            Timber.w(t, "NoteRepository: attachment insert failed, removing orphan file %s", uuid)
            attachmentCrypto.deleteFileQuietly(target.absolutePath)
            throw t
        }
    }

    override suspend fun deleteAttachment(attachmentId: Long) = withContext(io) {
        val attachment = noteAttachmentDao.findById(attachmentId) ?: return@withContext
        val message = noteMessageDao.findById(attachment.noteMessageId)
        database.withTransaction {
            noteAttachmentDao.deleteById(attachmentId)
            if (message != null) {
                noteMessageDao.setAttachmentsCount(
                    message.id,
                    noteAttachmentDao.findForMessage(message.id).size,
                )
                refreshPreview(message.noteId)
            }
        }
        attachmentCrypto.deleteFileQuietly(attachment.localUri)
    }

    // ------------------------------------------------------------------ backup

    override suspend fun listAllNotes(): List<Note> = withContext(io) {
        noteDao.listAll().map { it.toDomain() }
    }

    override suspend fun listAllMessages(): List<NoteMessage> = withContext(io) {
        val rows = noteMessageDao.listAll()
        val attachmentsByMessage = attachmentsByMessage(rows)
        rows.map { entity -> entity.toDomain(attachmentsByMessage[entity.id].orEmpty()) }
    }

    override suspend fun listAllAttachments(): List<Attachment> = withContext(io) {
        noteAttachmentDao.listAll().map { it.toDomain() }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Bulk-fetches attachments for [rows] and groups them by message id. Ids are chunked:
     * a window grown by repeated "load older" pages can exceed SQLite's bound-variable cap
     * for a single `IN` list. Skips the query entirely when no row carries attachments.
     */
    private suspend fun attachmentsByMessage(
        rows: List<NoteMessageEntity>,
    ): Map<Long, List<Attachment>> {
        if (rows.none { it.attachmentsCount > 0 }) return emptyMap()
        return rows.map { it.id }
            .chunked(ATTACHMENT_FETCH_BATCH_SIZE)
            .flatMap { batch -> noteAttachmentDao.findForMessages(batch) }
            .groupBy { it.noteMessageId }
            .mapValues { (_, list) -> list.map { it.toDomain() } }
    }

    /**
     * Recomputes the note's denormalized preview from its newest entry (body, or an emoji
     * + file label for an attachment-only entry), or clears it when the note is empty.
     * Must be called inside the caller's transaction so observers never see a stale
     * preview. A recompute (not an incremental touch) so out-of-order inserts — imports
     * in particular — always land on the right preview.
     */
    private suspend fun refreshPreview(noteId: Long) {
        val latest = noteMessageDao.findLatestForNote(noteId)
        if (latest == null) {
            noteDao.setPreview(noteId, null, null)
            return
        }
        val attachments = noteAttachmentDao.findForMessage(latest.id).map { it.toDomain() }
        noteDao.setPreview(noteId, latest.date, previewFor(latest.body, attachments))
    }

    /**
     * The list preview for an entry: its body, or — for an attachment-only entry — an
     * emoji + file label mirroring `ConversationMirror`'s MMS labels ("🖼️ photo.jpg").
     * Null when the entry has neither text nor attachments.
     */
    private fun previewFor(body: String, attachments: List<Attachment>): String? {
        if (body.isNotBlank()) return body.take(MAX_PREVIEW_CHARS)
        val first = attachments.firstOrNull() ?: return null
        return mediaPreviewLabel(first).take(MAX_PREVIEW_CHARS)
    }

    private fun mediaPreviewLabel(a: Attachment): String = when {
        a.mimeType.startsWith("image/") -> "🖼️ " + (a.fileName ?: "Image")
        a.mimeType.startsWith("video/") -> "🎞️ " + (a.fileName ?: "Video")
        a.mimeType.startsWith("audio/") -> "🎤 " + (a.fileName ?: "Audio")
        a.mimeType == "text/x-vcard" || a.mimeType == "text/vcard" -> "👤 " + (a.fileName ?: "Contact")
        else -> "📎 " + (a.fileName ?: a.mimeType)
    }

    companion object {
        /**
         * Preview cap, mirroring `ConversationMirror.MAX_PREVIEW` (240 chars). Own
         * constant, not an import: notes must not depend on the SMS mirror.
         */
        const val MAX_PREVIEW_CHARS = 240

        /** Chunk size for bulk attachment fetches (same value as the conversation repo). */
        private const val ATTACHMENT_FETCH_BATCH_SIZE: Int = 500
    }
}
