package com.filestech.sms.ui.screens.notes

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.NoteMessageWindow
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.repository.SyncedNoteData
import com.filestech.sms.domain.repository.SyncedNoteMessageData
import com.filestech.sms.domain.repository.SyncedNoteMessageResult
import com.filestech.sms.domain.repository.SyncedNoteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Notes — an in-memory [NoteRepository] for the note thread tests:
 * flows for the ViewModel's collectors, call logs for the
 * assertions. Round 36 phase 2 — the sync section is implemented
 * too (the mirror tests drive it): hub/client ids live in side
 * tables, since the domain [Note]/[NoteMessage] carry none.
 *
 * Kept in its own file: the interface has 26 methods in phase 1
 * and 40 in phase 2, past Detekt's 35-function file cap — hence
 * the suppression (the interface carries the same one).
 */
@Suppress("TooManyFunctions")
class FakeNoteRepository(
    initialNote: Note? = null,
    private val defaultNoteId: Long = 1L,
) : NoteRepository {
    val noteFlow = MutableStateFlow(initialNote)
    val messagesFlow = MutableStateFlow<List<NoteMessage>>(emptyList())
    val allNotesFlow = MutableStateFlow<List<Note>>(listOfNotNull(initialNote))

    var nextMessageId = 1L
    var nextAttachmentId = 1L
    var nextNoteId = defaultNoteId + 1
    val setDraftCalls = mutableListOf<Pair<Long, String?>>()
    val renameCalls = mutableListOf<Pair<Long, String>>()
    val colorCalls = mutableListOf<Pair<Long, Int?>>()
    val addAttachmentCalls = mutableListOf<AddAttachmentCall>()
    var deleteNoteCalls = 0

    /** Round 36 phase 2 — hub/client id side tables (the domain models carry none). */
    val hubNoteIdsByLocal = mutableMapOf<Long, Long>()
    val clientNoteIdsByLocal = mutableMapOf<Long, String>()
    val hubMessageIdsByLocal = mutableMapOf<Long, Long>()
    val clientMessageIdsByLocal = mutableMapOf<Long, String>()

    data class AddAttachmentCall(
        val messageId: Long,
        val mimeType: String,
        val fileName: String?,
        val plaintext: ByteArray,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
    )

    override fun observeAllNotes(): Flow<List<Note>> = allNotesFlow

    override fun observeNote(noteId: Long): Flow<Note?> = noteFlow

    override fun observeMessagesWindow(noteId: Long, limit: Flow<Int>): Flow<NoteMessageWindow> =
        combine(limit, messagesFlow) { lim, msgs ->
            NoteMessageWindow(
                messages = msgs.takeLast(lim),
                totalCount = msgs.size,
                hasMore = msgs.size > lim,
                firstMessageAt = msgs.firstOrNull()?.date,
                lastMessageAt = msgs.lastOrNull()?.date,
            )
        }

    override fun observeMessages(noteId: Long): Flow<List<NoteMessage>> = messagesFlow

    override suspend fun findNoteById(noteId: Long): Note? =
        noteFlow.value?.takeIf { it.id == noteId }

    override suspend fun findNoteByUuid(uuid: String): Note? =
        noteFlow.value?.takeIf { it.uuid == uuid }

    override suspend fun findMessageById(messageId: Long): NoteMessage? =
        messagesFlow.value.firstOrNull { it.id == messageId }

    override suspend fun findMessageByUuid(uuid: String): NoteMessage? =
        messagesFlow.value.firstOrNull { it.uuid == uuid }

    override suspend fun findAttachmentById(attachmentId: Long): Attachment? =
        messagesFlow.value.flatMap { it.attachments }.firstOrNull { it.id == attachmentId }

    override suspend fun findAttachmentByUuid(uuid: String): Attachment? = null

    override suspend fun attachmentsForMessage(messageId: Long): List<Attachment> =
        messagesFlow.value.firstOrNull { it.id == messageId }?.attachments ?: emptyList()

    override suspend fun attachmentsForNote(noteId: Long): List<Attachment> =
        messagesFlow.value.flatMap { it.attachments }

    override suspend fun createNote(
        title: String,
        uuid: String,
        createdAt: Long,
        updatedAt: Long,
        hubNoteId: Long?,
        clientNoteId: String?,
    ): Note = Note(
        id = defaultNoteId,
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
    ).also {
        hubNoteId?.let { hub -> hubNoteIdsByLocal[defaultNoteId] = hub }
        clientNoteId?.let { client -> clientNoteIdsByLocal[defaultNoteId] = client }
        noteFlow.value = it
    }

    override suspend fun renameNote(noteId: Long, title: String) {
        renameCalls += noteId to title
        noteFlow.value = noteFlow.value?.copy(title = title)
    }

    override suspend fun deleteNote(noteId: Long) {
        deleteNoteCalls++
        noteFlow.value = null
        messagesFlow.value = emptyList()
    }

    override suspend fun setPinned(noteId: Long, pinned: Boolean) {
        noteFlow.value = noteFlow.value?.copy(pinned = pinned)
    }

    override suspend fun setArchived(noteId: Long, archived: Boolean) {
        noteFlow.value = noteFlow.value?.copy(archived = archived)
    }

    override suspend fun setDraft(noteId: Long, draft: String?) {
        setDraftCalls += noteId to draft
        noteFlow.value = noteFlow.value?.copy(draft = draft)
    }

    override suspend fun setBubbleColor(noteId: Long, colorArgb: Int?) {
        colorCalls += noteId to colorArgb
        noteFlow.value = noteFlow.value?.copy(bubbleColorArgb = colorArgb)
    }

    override suspend fun insertMessage(
        noteId: Long,
        body: String,
        uuid: String,
        date: Long,
        hubNoteMessageId: Long?,
        clientMessageId: String?,
    ): NoteMessage {
        val inserted = NoteMessage(
            id = nextMessageId++,
            uuid = uuid,
            noteId = noteId,
            body = body,
            date = date,
        )
        hubNoteMessageId?.let { hubMessageIdsByLocal[inserted.id] = it }
        clientMessageId?.let { clientMessageIdsByLocal[inserted.id] = it }
        messagesFlow.value = messagesFlow.value + inserted
        noteFlow.value = noteFlow.value?.copy(
            lastMessageAt = date,
            lastMessagePreview = body.ifBlank { null },
        )
        return inserted
    }

    override suspend fun editMessage(messageId: Long, body: String) {
        messagesFlow.value = messagesFlow.value.map { msg ->
            if (msg.id == messageId) msg.copy(body = body, edited = true) else msg
        }
    }

    override suspend fun deleteMessage(messageId: Long) {
        messagesFlow.value = messagesFlow.value.filterNot { it.id == messageId }
        hubMessageIdsByLocal.remove(messageId)
        clientMessageIdsByLocal.remove(messageId)
    }

    override suspend fun addAttachment(
        messageId: Long,
        mimeType: String,
        fileName: String?,
        plaintext: ByteArray,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        uuid: String,
    ): Outcome<Attachment> {
        addAttachmentCalls += AddAttachmentCall(
            messageId = messageId,
            mimeType = mimeType,
            fileName = fileName,
            plaintext = plaintext,
            width = width,
            height = height,
            durationMs = durationMs,
        )
        return Outcome.Success(
            Attachment(
                id = nextAttachmentId++,
                messageId = messageId,
                mimeType = mimeType,
                fileName = fileName,
                sizeBytes = plaintext.size.toLong(),
                localUri = "note://$nextAttachmentId",
                width = width,
                height = height,
                durationMs = durationMs,
            ),
        )
    }

    override suspend fun deleteAttachment(attachmentId: Long) {
        messagesFlow.value = messagesFlow.value.map { msg ->
            msg.copy(attachments = msg.attachments.filterNot { it.id == attachmentId })
        }
    }

    override suspend fun listAllNotes(): List<Note> = listOfNotNull(noteFlow.value)

    override suspend fun listAllMessages(): List<NoteMessage> = messagesFlow.value

    override suspend fun listAllAttachments(): List<Attachment> =
        messagesFlow.value.flatMap { it.attachments }

    // -- Round 36 phase 2: sync section ------------------------------

    override suspend fun noteHubId(localNoteId: Long): Long? = hubNoteIdsByLocal[localNoteId]

    override suspend fun messageHubId(localMessageId: Long): Long? =
        hubMessageIdsByLocal[localMessageId]

    override suspend fun findNoteByHubId(hubNoteId: Long): Note? {
        val localId = hubNoteIdsByLocal.entries.firstOrNull { it.value == hubNoteId }?.key
            ?: return null
        return allNotesFlow.value.firstOrNull { it.id == localId }
    }

    override suspend fun findNoteByClientId(clientNoteId: String): Note? {
        val localId = clientNoteIdsByLocal.entries.firstOrNull { it.value == clientNoteId }?.key
            ?: return null
        return allNotesFlow.value.firstOrNull { it.id == localId }
    }

    override suspend fun findMessageByHubId(hubMessageId: Long): NoteMessage? {
        val localId = hubMessageIdsByLocal.entries.firstOrNull { it.value == hubMessageId }?.key
            ?: return null
        return messagesFlow.value.firstOrNull { it.id == localId }
    }

    override suspend fun findMessageByClientId(clientMessageId: String): NoteMessage? {
        val localId = clientMessageIdsByLocal.entries
            .firstOrNull { it.value == clientMessageId }?.key
            ?: return null
        return messagesFlow.value.firstOrNull { it.id == localId }
    }

    override suspend fun findAttachmentByHubId(hubAttachmentId: Long): Attachment? =
        messagesFlow.value.flatMap { it.attachments }
            .firstOrNull { it.hubAttachmentId == hubAttachmentId }

    override suspend fun linkMessageToHub(clientMessageId: String, hubMessageId: Long) {
        val localId = clientMessageIdsByLocal.entries
            .firstOrNull { it.value == clientMessageId }?.key
            ?: return
        hubMessageIdsByLocal[localId] = hubMessageId
    }

    override suspend fun upsertSyncedNote(data: SyncedNoteData): SyncedNoteResult {
        val existingLocalId = hubNoteIdsByLocal.entries
            .firstOrNull { it.value == data.hubNoteId }?.key
        if (existingLocalId != null) {
            val updated = allNotesFlow.value.firstOrNull { it.id == existingLocalId }
                ?.copy(
                    title = data.title,
                    updatedAt = data.updatedAt,
                    lastMessageAt = data.lastMessageAt,
                    lastMessagePreview = data.lastMessagePreview,
                    pinned = data.pinned,
                    archived = data.archived,
                )
            if (updated != null) {
                allNotesFlow.value = allNotesFlow.value.map {
                    if (it.id == existingLocalId) updated else it
                }
                if (noteFlow.value?.id == existingLocalId) noteFlow.value = updated
            }
            return SyncedNoteResult(existingLocalId, created = false)
        }
        val localId = nextNoteId++
        val created = Note(
            id = localId,
            uuid = "synced-note-$localId",
            title = data.title,
            createdAt = data.createdAt,
            updatedAt = data.updatedAt,
            lastMessageAt = data.lastMessageAt,
            lastMessagePreview = data.lastMessagePreview,
            draft = null,
            pinned = data.pinned,
            archived = data.archived,
            bubbleColorArgb = null,
        )
        hubNoteIdsByLocal[localId] = data.hubNoteId
        allNotesFlow.value = allNotesFlow.value + created
        return SyncedNoteResult(localId, created = true)
    }

    override suspend fun upsertSyncedMessage(
        data: SyncedNoteMessageData,
    ): SyncedNoteMessageResult? {
        val localNoteId = hubNoteIdsByLocal.entries
            .firstOrNull { it.value == data.hubNoteId }?.key
            ?: return null
        val existingLocalId = hubMessageIdsByLocal.entries
            .firstOrNull { it.value == data.hubMessageId }?.key
            ?: data.clientMessageId?.let { client ->
                clientMessageIdsByLocal.entries.firstOrNull { it.value == client }?.key
            }
        val attachments = data.attachments.map { att ->
            Attachment(
                id = nextAttachmentId++,
                messageId = existingLocalId ?: -1L,
                mimeType = att.mimeType,
                fileName = att.fileName,
                sizeBytes = att.sizeBytes,
                localUri = "",
                width = att.width,
                height = att.height,
                durationMs = att.durationMs,
                hubAttachmentId = att.hubAttachmentId,
            )
        }
        if (existingLocalId != null) {
            hubMessageIdsByLocal[existingLocalId] = data.hubMessageId
            messagesFlow.value = messagesFlow.value.map { msg ->
                if (msg.id == existingLocalId) {
                    msg.copy(body = data.body, edited = data.edited, attachments = attachments)
                } else {
                    msg
                }
            }
            return SyncedNoteMessageResult(existingLocalId, inserted = false)
        }
        val inserted = NoteMessage(
            id = nextMessageId++,
            uuid = "synced-msg-${data.hubMessageId}",
            noteId = localNoteId,
            body = data.body,
            date = data.date,
            attachments = attachments.map { it.copy(messageId = nextMessageId - 1) },
            edited = data.edited,
        )
        hubMessageIdsByLocal[inserted.id] = data.hubMessageId
        data.clientMessageId?.let { clientMessageIdsByLocal[inserted.id] = it }
        messagesFlow.value = messagesFlow.value + inserted
        return SyncedNoteMessageResult(inserted.id, inserted = true)
    }

    override suspend fun deleteSyncedNote(hubNoteId: Long) {
        val localId = hubNoteIdsByLocal.entries.firstOrNull { it.value == hubNoteId }?.key
            ?: return
        hubNoteIdsByLocal.remove(localId)
        clientNoteIdsByLocal.remove(localId)
        allNotesFlow.value = allNotesFlow.value.filterNot { it.id == localId }
        if (noteFlow.value?.id == localId) noteFlow.value = null
        val removed = messagesFlow.value.filter { it.noteId == localId }.map { it.id }
        messagesFlow.value = messagesFlow.value.filterNot { it.noteId == localId }
        removed.forEach {
            hubMessageIdsByLocal.remove(it)
            clientMessageIdsByLocal.remove(it)
        }
    }

    override suspend fun deleteSyncedMessage(hubMessageId: Long) {
        val localId = hubMessageIdsByLocal.entries.firstOrNull { it.value == hubMessageId }?.key
            ?: return
        hubMessageIdsByLocal.remove(localId)
        clientMessageIdsByLocal.remove(localId)
        messagesFlow.value = messagesFlow.value.filterNot { it.id == localId }
    }

    override suspend fun hubNoteIds(): Set<Long> = hubNoteIdsByLocal.values.toSet()

    override suspend fun pruneSyncedNotes(keepHubIds: Set<Long>): Int {
        val pruned = hubNoteIdsByLocal.entries
            .filter { it.value !in keepHubIds }
            .map { it.key }
        pruned.forEach { localId ->
            hubNoteIdsByLocal.remove(localId)
            clientNoteIdsByLocal.remove(localId)
            allNotesFlow.value = allNotesFlow.value.filterNot { it.id == localId }
            if (noteFlow.value?.id == localId) noteFlow.value = null
            val removed = messagesFlow.value.filter { it.noteId == localId }.map { it.id }
            messagesFlow.value = messagesFlow.value.filterNot { it.noteId == localId }
            removed.forEach {
                hubMessageIdsByLocal.remove(it)
                clientMessageIdsByLocal.remove(it)
            }
        }
        return pruned.size
    }
}
