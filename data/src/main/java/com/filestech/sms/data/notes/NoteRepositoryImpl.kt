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
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.clientmode.NoOpClientActionGateway
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.NoteMessageWindow
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.repository.SyncedNoteAttachmentData
import com.filestech.sms.domain.repository.SyncedNoteData
import com.filestech.sms.domain.repository.SyncedNoteMessageData
import com.filestech.sms.domain.repository.SyncedNoteMessageResult
import com.filestech.sms.domain.repository.SyncedNoteResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
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
 *
 * Phase 2 (notes sync) — in client mode the pin / archive / rename /
 * edit writes are optimistic + frame (the `ConversationRepositoryImpl`
 * pattern: the hub is authoritative, the sender reverts on
 * `ok:false`), while the deletes send FIRST and delete locally only
 * on the hub's success (a deleted row cannot be reverted). The
 * mirror methods (`upsertSynced*`, `pruneSyncedNotes`) are the
 * engine's write path and never send frames.
 *
 * TooManyFunctions is suppressed like the interface: the sync
 * section alone adds 14 methods to an already-wide store, and
 * splitting the single write path would cost more than it saves.
 */
@Suppress("TooManyFunctions")
@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val noteDao: NoteDao,
    private val noteMessageDao: NoteMessageDao,
    private val noteAttachmentDao: NoteAttachmentDao,
    private val attachmentCrypto: NoteAttachmentCrypto,
    private val clientActions: ClientActionGateway = NoOpClientActionGateway,
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
        hubNoteId: Long?,
        clientNoteId: String?,
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
                hubNoteId = hubNoteId,
                clientNoteId = clientNoteId,
            ),
        )
        noteDao.findById(id)?.toDomain()
            ?: error("createNote: row $id vanished immediately after insert")
    }

    override suspend fun renameNote(noteId: Long, title: String) {
        withContext(io) {
            // Phase 2 — client mode: optimistic local rename, then the
            // `rename_note` frame; the sender reverts on `ok:false`.
            if (clientActions.isClientMode()) {
                val previous = noteDao.findById(noteId)?.title
                noteDao.rename(noteId, title, System.currentTimeMillis())
                clientActions.sendAction(ClientAction.RenameNote(noteId, title)) {
                    if (previous != null) {
                        noteDao.rename(noteId, previous, System.currentTimeMillis())
                    }
                }
            } else {
                noteDao.rename(noteId, title, System.currentTimeMillis())
            }
        }
    }

    override suspend fun deleteNote(noteId: Long) = withContext(io) {
        // Phase 2 — client mode: the delete sends FIRST and the local
        // row goes only on the hub's success — a deleted row cannot
        // be reverted. On failure the note stays, as if nothing
        // happened (the caller shows the error).
        if (clientActions.isClientMode()) {
            if (clientActions.deleteNote(noteId) is Outcome.Success) deleteNoteLocal(noteId)
            return@withContext
        }
        deleteNoteLocal(noteId)
    }

    private suspend fun deleteNoteLocal(noteId: Long) {
        // Collect the file paths BEFORE the rows go — after the delete there is nothing
        // left to tell us which files belonged to the note.
        val files = noteAttachmentDao.findLocalUrisForNote(noteId)
        database.withTransaction {
            noteDao.deleteById(noteId)
        }
        files.forEach { attachmentCrypto.deleteFileQuietly(it) }
    }

    override suspend fun setPinned(noteId: Long, pinned: Boolean) {
        withContext(io) {
            // Phase 2 — client mode: optimistic local change, then the
            // `set_note_pin` frame; the sender reverts on `ok:false`.
            if (clientActions.isClientMode()) {
                val previous = noteDao.findById(noteId)?.pinned
                noteDao.setPinned(noteId, pinned, System.currentTimeMillis())
                clientActions.sendAction(ClientAction.SetNotePin(noteId, pinned)) {
                    if (previous != null) {
                        noteDao.setPinned(noteId, previous, System.currentTimeMillis())
                    }
                }
            } else {
                noteDao.setPinned(noteId, pinned, System.currentTimeMillis())
            }
        }
    }

    override suspend fun setArchived(noteId: Long, archived: Boolean) {
        withContext(io) {
            // Phase 2 — client mode: same optimistic + `set_note_archived` frame pattern.
            if (clientActions.isClientMode()) {
                val previous = noteDao.findById(noteId)?.archived
                noteDao.setArchived(noteId, archived, System.currentTimeMillis())
                clientActions.sendAction(ClientAction.SetNoteArchived(noteId, archived)) {
                    if (previous != null) {
                        noteDao.setArchived(noteId, previous, System.currentTimeMillis())
                    }
                }
            } else {
                noteDao.setArchived(noteId, archived, System.currentTimeMillis())
            }
        }
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
        hubNoteMessageId: Long?,
        clientMessageId: String?,
    ): NoteMessage = withContext(io) {
        database.withTransaction {
            val id = noteMessageDao.insert(
                NoteMessageEntity(
                    uuid = uuid,
                    noteId = noteId,
                    body = body,
                    date = date,
                    attachmentsCount = 0,
                    hubNoteMessageId = hubNoteMessageId,
                    clientMessageId = clientMessageId,
                ),
            )
            refreshPreview(noteId)
            noteMessageDao.findById(id)?.toDomain()
                ?: error("insertMessage: row $id vanished immediately after insert")
        }
    }

    override suspend fun editMessage(messageId: Long, body: String) = withContext(io) {
        // Phase 2 — client mode: optimistic local edit, then the
        // `edit_note_message` frame; the sender reverts (body AND
        // edited flag) on `ok:false`. Hub mode edits directly — the
        // hub's edits collector pushes `note_message_updated`.
        if (clientActions.isClientMode()) {
            val previous = noteMessageDao.findById(messageId) ?: return@withContext
            database.withTransaction {
                noteMessageDao.setBodyEdited(messageId, body)
                refreshPreview(previous.noteId)
            }
            clientActions.sendAction(
                ClientAction.EditNoteMessage(previous.noteId, messageId, body),
            ) {
                database.withTransaction {
                    noteMessageDao.setBody(messageId, previous.body, previous.edited)
                    refreshPreview(previous.noteId)
                }
            }
        } else {
            val message = noteMessageDao.findById(messageId) ?: return@withContext
            database.withTransaction {
                noteMessageDao.setBodyEdited(messageId, body)
                refreshPreview(message.noteId)
            }
        }
    }

    override suspend fun deleteMessage(messageId: Long) = withContext(io) {
        // Phase 2 — client mode: the delete sends FIRST and the local
        // row goes only on the hub's success (a deleted row cannot
        // be reverted).
        if (clientActions.isClientMode()) {
            val message = noteMessageDao.findById(messageId) ?: return@withContext
            if (clientActions.deleteNoteMessage(message.noteId, messageId) is Outcome.Success) {
                deleteMessageLocal(messageId)
            }
            return@withContext
        }
        deleteMessageLocal(messageId)
    }

    private suspend fun deleteMessageLocal(messageId: Long) {
        val message = noteMessageDao.findById(messageId) ?: return
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

    // ------------------------------------------------------------------ sync (phase 2)

    override suspend fun noteHubId(localNoteId: Long): Long? = withContext(io) {
        noteDao.findById(localNoteId)?.hubNoteId
    }

    override suspend fun messageHubId(localMessageId: Long): Long? = withContext(io) {
        noteMessageDao.findById(localMessageId)?.hubNoteMessageId
    }

    override suspend fun findNoteByHubId(hubNoteId: Long): Note? = withContext(io) {
        noteDao.findByHubId(hubNoteId)?.toDomain()
    }

    override suspend fun findNoteByClientId(clientNoteId: String): Note? = withContext(io) {
        noteDao.findByClientNoteId(clientNoteId)?.toDomain()
    }

    override suspend fun findMessageByHubId(hubMessageId: Long): NoteMessage? = withContext(io) {
        val entity = noteMessageDao.findByHubId(hubMessageId) ?: return@withContext null
        entity.toDomain(noteAttachmentDao.findForMessage(entity.id).map { it.toDomain() })
    }

    override suspend fun findMessageByClientId(clientMessageId: String): NoteMessage? =
        withContext(io) {
            val entity = noteMessageDao.findByClientMessageId(clientMessageId)
                ?: return@withContext null
            entity.toDomain(noteAttachmentDao.findForMessage(entity.id).map { it.toDomain() })
        }

    override suspend fun findAttachmentByHubId(hubAttachmentId: Long): Attachment? =
        withContext(io) {
            noteAttachmentDao.findByHubId(hubAttachmentId)?.toDomain()
        }

    override suspend fun linkMessageToHub(clientMessageId: String, hubMessageId: Long) =
        withContext(io) {
            noteMessageDao.linkToHub(clientMessageId, hubMessageId)
        }

    override suspend fun upsertSyncedNote(data: SyncedNoteData): SyncedNoteResult =
        withContext(io) {
            database.withTransaction {
                val existing = noteDao.findByHubId(data.hubNoteId)
                if (existing == null) {
                    val id = noteDao.insert(
                        NoteEntity(
                            uuid = UUID.randomUUID().toString(),
                            title = data.title,
                            createdAt = data.createdAt,
                            updatedAt = data.updatedAt,
                            lastMessageAt = data.lastMessageAt,
                            lastMessagePreview = data.lastMessagePreview,
                            draft = null,
                            pinned = data.pinned,
                            archived = data.archived,
                            bubbleColorArgb = null,
                            hubNoteId = data.hubNoteId,
                            clientNoteId = null,
                        ),
                    )
                    SyncedNoteResult(id, true)
                } else {
                    // The draft and the bubble color are local-only:
                    // the wire carries neither, so the copy keeps
                    // them untouched.
                    noteDao.update(
                        existing.copy(
                            title = data.title,
                            updatedAt = data.updatedAt,
                            lastMessageAt = data.lastMessageAt,
                            lastMessagePreview = data.lastMessagePreview,
                            pinned = data.pinned,
                            archived = data.archived,
                        ),
                    )
                    SyncedNoteResult(existing.id, false)
                }
            }
        }

    override suspend fun upsertSyncedMessage(
        data: SyncedNoteMessageData,
    ): SyncedNoteMessageResult? = withContext(io) {
        database.withTransaction {
            val note = noteDao.findByHubId(data.hubNoteId) ?: return@withTransaction null
            val existing = noteMessageDao.findByHubId(data.hubMessageId)
                ?: data.clientMessageId?.let { noteMessageDao.findByClientMessageId(it) }
            val localId = if (existing == null) {
                noteMessageDao.insert(
                    NoteMessageEntity(
                        uuid = UUID.randomUUID().toString(),
                        noteId = note.id,
                        body = data.body,
                        date = data.date,
                        attachmentsCount = data.attachments.size,
                        hubNoteMessageId = data.hubMessageId,
                        clientMessageId = data.clientMessageId,
                        edited = data.edited,
                    ),
                )
            } else {
                // An optimistic row (client save) is adopted: its hub
                // id is set, and its attachments are linked to the
                // hub's by position — the hub echoes attachments in
                // send order.
                noteMessageDao.update(
                    existing.copy(
                        body = data.body,
                        date = data.date,
                        attachmentsCount = data.attachments.size,
                        hubNoteMessageId = data.hubMessageId,
                        edited = data.edited,
                    ),
                )
                val localAttachments = noteAttachmentDao.findForMessage(existing.id)
                if (localAttachments.any { it.hubAttachmentId == null }) {
                    localAttachments.zip(data.attachments).forEach { (local, wire) ->
                        noteAttachmentDao.setHubId(local.id, wire.hubAttachmentId)
                    }
                }
                existing.id
            }
            upsertMessageAttachments(localId, data.attachments)
            refreshPreview(note.id)
            SyncedNoteMessageResult(localId, existing == null)
        }
    }

    /**
     * Inserts the wire attachments the message does not have yet
     * (matched by hub id). Mirrored rows start with a blank
     * `localUri` — the downloader fills it once the bytes land, and
     * the blank is what makes the download tile render. Attachments
     * are append-only on the hub (there is no attachment delete),
     * so nothing is ever removed here; removals arrive as message
     * deletes.
     */
    private suspend fun upsertMessageAttachments(
        localMessageId: Long,
        attachments: List<SyncedNoteAttachmentData>,
    ) {
        val known = noteAttachmentDao.findForMessage(localMessageId)
            .mapNotNull { it.hubAttachmentId }.toSet()
        for (attachment in attachments) {
            if (attachment.hubAttachmentId !in known) {
                noteAttachmentDao.insert(
                    NoteAttachmentEntity(
                        uuid = UUID.randomUUID().toString(),
                        noteMessageId = localMessageId,
                        mimeType = attachment.mimeType,
                        fileName = attachment.fileName,
                        sizeBytes = attachment.sizeBytes,
                        localUri = "",
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                        hubAttachmentId = attachment.hubAttachmentId,
                    ),
                )
            }
        }
    }

    override suspend fun deleteSyncedNote(hubNoteId: Long) = withContext(io) {
        val note = noteDao.findByHubId(hubNoteId) ?: return@withContext
        deleteNoteLocal(note.id)
    }

    override suspend fun deleteSyncedMessage(hubMessageId: Long) = withContext(io) {
        val message = noteMessageDao.findByHubId(hubMessageId) ?: return@withContext
        deleteMessageLocal(message.id)
    }

    override suspend fun hubNoteIds(): Set<Long> = withContext(io) {
        noteDao.hubNoteIds().toSet()
    }

    override suspend fun pruneSyncedNotes(keepHubIds: Set<Long>): Int = withContext(io) {
        var pruned = 0
        for (hubId in noteDao.hubNoteIds()) {
            if (hubId !in keepHubIds) {
                noteDao.findByHubId(hubId)?.let { deleteNoteLocal(it.id) }
                pruned++
            }
        }
        pruned
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
