package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.NoteAttachmentDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.dao.NoteMessageDao
import com.filestech.sms.data.local.db.entity.NoteEntity
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.sync.ActionResult
import com.filestech.sms.sync.AttachmentData
import com.filestech.sms.sync.AttachmentOpenResult
import com.filestech.sms.sync.CreateNoteResult
import com.filestech.sms.sync.EditedNoteMessage
import com.filestech.sms.sync.NoteSyncSource
import com.filestech.sms.sync.SaveNoteMessageResult
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedNote
import com.filestech.sms.sync.SyncedNoteMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 36 phase 2 — the [NoteSyncSource] backed by the real note
 * store: [NoteRepository] for the writes (preview maintenance and
 * the attachment-file lifecycle live there), the note DAOs for the
 * snapshot / delta / counts reads (the repository exposes no
 * counts, and the engine needs them per emission).
 *
 * Reads map entities straight to the wire DTOs; writes go through
 * the repository so a synced create / save / edit behaves exactly
 * like a local one (same preview refresh, same encryption).
 *
 * Uploads share the staging directory with the message glue
 * ([uploadDir] / [stagedFileName] — internal in this package):
 * `beginUpload` (message glue) stages the bytes, this class reads
 * them back at save time. A staged file that is gone by save time
 * (24 h sweep, or an upload that never landed) fails the save with
 * `upload_expired`.
 *
 * Note attachment bytes cross the link as PLAINTEXT — decrypted
 * from the hub's AES-256-GCM envelope in [openNoteAttachment],
 * re-encrypted by the receiving device — inside the existing TLS
 * device link.
 */
@Singleton
class RepositoryNoteSyncSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteRepository: NoteRepository,
    private val noteDao: NoteDao,
    private val noteMessageDao: NoteMessageDao,
    private val noteAttachmentDao: NoteAttachmentDao,
    private val attachmentResolver: NoteAttachmentResolver,
) : NoteSyncSource {

    /** Serializes creates + saves — the dedup check and the insert must be atomic. */
    private val saveMutex = Mutex()

    override suspend fun notes(): List<SyncedNote> = withContext(Dispatchers.IO) {
        val rows = noteDao.listAll()
        val counts = noteMessageDao.countsByNote().associate { it.noteId to it.messageCount }
        rows.map { it.toSynced(counts[it.id] ?: 0) }
    }

    override fun observeNotes(): Flow<List<SyncedNote>> =
        noteDao.observeAll().map { rows ->
            val counts = noteMessageDao.countsByNote().associate { it.noteId to it.messageCount }
            rows.map { it.toSynced(counts[it.id] ?: 0) }
        }

    override suspend fun totalNotes(): Int = noteDao.count()

    override suspend fun maxNoteMessageId(): Long = noteMessageDao.maxId() ?: 0L

    override suspend fun noteMessagesBefore(
        noteId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedNoteMessage> = withContext(Dispatchers.IO) {
        val floor = if (beforeId <= 0L) Long.MAX_VALUE else beforeId
        mapMessages(noteMessageDao.listBefore(noteId, floor, limit))
    }

    override suspend fun noteMessagesAfter(
        noteId: Long,
        afterId: Long,
    ): List<SyncedNoteMessage> = withContext(Dispatchers.IO) {
        mapMessages(noteMessageDao.listAfter(noteId, afterId))
    }

    override suspend fun noteMessagesAfterAll(afterId: Long): List<SyncedNoteMessage> =
        withContext(Dispatchers.IO) {
            mapMessages(noteMessageDao.listAfterAll(afterId))
        }

    override suspend fun noteMessageById(messageId: Long): SyncedNoteMessage? =
        withContext(Dispatchers.IO) {
            val entity = noteMessageDao.findById(messageId) ?: return@withContext null
            val attachments = noteAttachmentDao.findForMessage(messageId).map { it.toDomain() }
            entity.toSynced(attachments)
        }

    override fun observeEditedMessages(): Flow<List<EditedNoteMessage>> =
        noteMessageDao.observeEditedMessages().map { rows ->
            rows.map { EditedNoteMessage(it.id, it.noteId, it.body) }
        }

    override suspend fun createNote(
        clientNoteId: String,
        title: String,
    ): CreateNoteResult = saveMutex.withLock {
        if (title.isBlank()) {
            return CreateNoteResult.Failed(
                SyncProtocol.MessageFailed.INVALID_REQUEST,
                "A note needs a title.",
            )
        }
        // Dedup: a retried create (lost `note_created`) lands on the
        // same row instead of duplicating the note.
        noteRepository.findNoteByClientId(clientNoteId)?.let { existing ->
            return CreateNoteResult.Created(existing.toSynced(countFor(existing.id)))
        }
        try {
            val created = noteRepository.createNote(title, clientNoteId = clientNoteId)
            CreateNoteResult.Created(created.toSynced(0))
        } catch (e: Exception) {
            Timber.w(e, "RepositoryNoteSyncSource: createNote failed")
            CreateNoteResult.Failed(
                SyncProtocol.NoteMessageFailed.INTERNAL,
                "This note couldn't be created.",
            )
        }
    }

    override suspend fun saveNoteMessage(
        clientMessageId: String,
        noteId: Long,
        body: String,
        attachments: List<SyncProtocol.SendAttachment>?,
    ): SaveNoteMessageResult = saveMutex.withLock {
        // Dedup first: a retried save (lost `note_message_saved`)
        // lands on the same entry instead of duplicating it.
        noteRepository.findMessageByClientId(clientMessageId)?.let { existing ->
            return SaveNoteMessageResult.Saved(existing.id)
        }
        val note = noteRepository.findNoteById(noteId)
            ?: return SaveNoteMessageResult.Failed(
                SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE,
                "This note doesn't exist.",
            )
        if (body.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_SEND_BODY_BYTES) {
            return SaveNoteMessageResult.Failed(
                SyncProtocol.NoteMessageFailed.MESSAGE_TOO_LONG,
                "This entry is too long.",
            )
        }
        // Resolve every staged file BEFORE inserting anything: a
        // missing file fails the save with no partial row left
        // behind. Sticker entries carry no upload — notes have no
        // sticker rendering on the wire, so they are skipped.
        val uploads = attachments.orEmpty().mapNotNull { it.uploadIndex?.let { index -> index to it } }
        if (body.isBlank() && uploads.isEmpty()) {
            return SaveNoteMessageResult.Failed(
                SyncProtocol.NoteMessageFailed.INTERNAL,
                "An entry needs text or an attachment.",
            )
        }
        val staged = withContext(Dispatchers.IO) {
            uploads.map { (index, attachment) ->
                val file = File(
                    uploadDir(context.cacheDir, clientMessageId),
                    stagedFileName(index, attachment.fileName, attachment.mimeType),
                )
                if (!file.exists()) {
                    return@withContext null
                }
                attachment to file.readBytes()
            }
        }
        if (staged == null) {
            return SaveNoteMessageResult.Failed(
                SyncProtocol.NoteMessageFailed.UPLOAD_EXPIRED,
                "An attachment expired — please try again.",
            )
        }
        try {
            val message = noteRepository.insertMessage(
                noteId = note.id,
                body = body,
                clientMessageId = clientMessageId,
            )
            for ((attachment, bytes) in staged) {
                when (
                    noteRepository.addAttachment(
                        messageId = message.id,
                        mimeType = attachment.mimeType ?: "application/octet-stream",
                        fileName = attachment.fileName,
                        plaintext = bytes,
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                    )
                ) {
                    is Outcome.Failure -> {
                        // Roll back: a retry re-saves the whole
                        // entry instead of dedup-hitting a partial
                        // row that lost an attachment.
                        noteRepository.deleteMessage(message.id)
                        return SaveNoteMessageResult.Failed(
                            SyncProtocol.NoteMessageFailed.INTERNAL,
                            "This entry couldn't be saved.",
                        )
                    }
                    is Outcome.Success -> Unit
                }
            }
            sweepStagedUpload(clientMessageId)
            SaveNoteMessageResult.Saved(message.id)
        } catch (e: Exception) {
            Timber.w(e, "RepositoryNoteSyncSource: saveNoteMessage failed")
            SaveNoteMessageResult.Failed(
                SyncProtocol.NoteMessageFailed.INTERNAL,
                "This entry couldn't be saved.",
            )
        }
    }

    override suspend fun editNoteMessage(
        noteId: Long,
        messageId: Long,
        body: String,
    ): ActionResult {
        val message = noteRepository.findMessageById(messageId)
            ?.takeIf { it.noteId == noteId }
            ?: return noteActionResult(false, SyncProtocol.NoteMessageFailed.UNKNOWN_MESSAGE)
        if (body.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_SEND_BODY_BYTES) {
            return noteActionResult(false, SyncProtocol.NoteMessageFailed.MESSAGE_TOO_LONG)
        }
        return try {
            noteRepository.editMessage(messageId, body)
            noteActionResult(true)
        } catch (e: Exception) {
            Timber.w(e, "RepositoryNoteSyncSource: editNoteMessage failed")
            noteActionResult(false, SyncProtocol.NoteMessageFailed.INTERNAL)
        }
    }

    override suspend fun renameNote(
        noteId: Long,
        title: String,
    ): ActionResult {
        if (title.isBlank()) {
            return noteActionResult(false, SyncProtocol.MessageFailed.INVALID_REQUEST)
        }
        if (noteRepository.findNoteById(noteId) == null) {
            return noteActionResult(false, SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE)
        }
        noteRepository.renameNote(noteId, title)
        return noteActionResult(true)
    }

    override suspend fun deleteNote(noteId: Long): ActionResult {
        if (noteRepository.findNoteById(noteId) == null) {
            return noteActionResult(false, SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE)
        }
        noteRepository.deleteNote(noteId)
        return noteActionResult(true)
    }

    override suspend fun deleteNoteMessage(
        noteId: Long,
        messageId: Long,
    ): ActionResult {
        val message = noteRepository.findMessageById(messageId)
            ?.takeIf { it.noteId == noteId }
            ?: return noteActionResult(false, SyncProtocol.NoteMessageFailed.UNKNOWN_MESSAGE)
        noteRepository.deleteMessage(message.id)
        return noteActionResult(true)
    }

    override suspend fun setNotePinned(noteId: Long, pinned: Boolean): ActionResult {
        if (noteRepository.findNoteById(noteId) == null) {
            return noteActionResult(false, SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE)
        }
        noteRepository.setPinned(noteId, pinned)
        return noteActionResult(true)
    }

    override suspend fun setNoteArchived(
        noteId: Long,
        archived: Boolean,
    ): ActionResult {
        if (noteRepository.findNoteById(noteId) == null) {
            return noteActionResult(false, SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE)
        }
        noteRepository.setArchived(noteId, archived)
        return noteActionResult(true)
    }

    override suspend fun openNoteAttachment(
        attachmentId: Long,
        messageId: Long,
    ): AttachmentOpenResult {
        val attachment = noteRepository.findAttachmentById(attachmentId)
            ?.takeIf { it.messageId == messageId }
            ?: return AttachmentOpenResult.Unknown
        return withContext(Dispatchers.IO) {
            when (val decrypted = attachmentResolver.decryptToMemory(attachment)) {
                is Outcome.Failure -> {
                    // File gone, key lost, tampered envelope — the
                    // row exists but the bytes don't.
                    Timber.w(
                        "RepositoryNoteSyncSource: note attachment %s unreadable",
                        attachmentId,
                    )
                    AttachmentOpenResult.Unreadable
                }
                is Outcome.Success -> AttachmentOpenResult.Opened(
                    AttachmentData(
                        attachment.sizeBytes,
                        ByteArrayInputStream(decrypted.value),
                    ),
                )
            }
        }
    }

    private suspend fun countFor(noteId: Long): Int =
        noteMessageDao.countsByNote().firstOrNull { it.noteId == noteId }?.messageCount ?: 0

    private suspend fun mapMessages(
        rows: List<NoteMessageEntity>,
    ): List<SyncedNoteMessage> {
        if (rows.isEmpty()) return emptyList()
        val attachmentsByMessage = noteAttachmentDao.findForMessages(rows.map { it.id })
            .groupBy { it.noteMessageId }
            .mapValues { (_, list) -> list.map { it.toDomain() } }
        return rows.map { entity ->
            entity.toSynced(attachmentsByMessage[entity.id].orEmpty())
        }
    }

    /** Best-effort: the staged dir is swept by TTL anyway, so a failure here is harmless. */
    private fun sweepStagedUpload(clientMessageId: String) {
        runCatching { uploadDir(context.cacheDir, clientMessageId).deleteRecursively() }
    }
}

/** The note `action_result`s: the engine wraps these into the wire type (with the action name + ids). */
private fun noteActionResult(ok: Boolean, code: String? = null): ActionResult =
    ActionResult(ok = ok, code = code)

/** Domain → wire mapping. Ids are decimal strings of the Room ids. */
private fun NoteEntity.toSynced(messageCount: Int): SyncedNote = SyncedNote(
    id = id.toString(),
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    pinned = pinned,
    archived = archived,
    messageCount = messageCount,
)

private fun Note.toSynced(messageCount: Int): SyncedNote =
    SyncedNote(
        id = id.toString(),
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        pinned = pinned,
        archived = archived,
        messageCount = messageCount,
    )

private fun NoteMessageEntity.toSynced(attachments: List<Attachment>): SyncedNoteMessage =
    SyncedNoteMessage(
        id = id.toString(),
        noteId = noteId.toString(),
        body = body,
        date = date,
        attachments = attachments.map { it.toSyncedNoteAttachment() },
        edited = edited,
        clientMessageId = clientMessageId,
    )

private fun Attachment.toSyncedNoteAttachment(): SyncedAttachment = SyncedAttachment(
    id = id.toString(),
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
)
