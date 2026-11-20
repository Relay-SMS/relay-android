package com.filestech.sms.ui.screens.notes

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.NoteMessageWindow
import com.filestech.sms.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Notes — an in-memory [NoteRepository] for the note thread tests:
 * flows for the ViewModel's collectors, call logs for the
 * assertions. Kept in its own file: the interface has 26 methods,
 * and Detekt caps a file at 35 functions.
 */
class FakeNoteRepository(
    initialNote: Note? = null,
    private val defaultNoteId: Long = 1L,
) : NoteRepository {
    val noteFlow = MutableStateFlow(initialNote)
    val messagesFlow = MutableStateFlow<List<NoteMessage>>(emptyList())
    val allNotesFlow = MutableStateFlow<List<Note>>(listOfNotNull(initialNote))

    var nextMessageId = 1L
    var nextAttachmentId = 1L
    val setDraftCalls = mutableListOf<Pair<Long, String?>>()
    val renameCalls = mutableListOf<Pair<Long, String>>()
    val colorCalls = mutableListOf<Pair<Long, Int?>>()
    val addAttachmentCalls = mutableListOf<AddAttachmentCall>()
    var deleteNoteCalls = 0

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
    ).also { noteFlow.value = it }

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
    ): NoteMessage {
        val inserted = NoteMessage(
            id = nextMessageId++,
            uuid = uuid,
            noteId = noteId,
            body = body,
            date = date,
        )
        messagesFlow.value = messagesFlow.value + inserted
        noteFlow.value = noteFlow.value?.copy(
            lastMessageAt = date,
            lastMessagePreview = body.ifBlank { null },
        )
        return inserted
    }

    override suspend fun deleteMessage(messageId: Long) {
        messagesFlow.value = messagesFlow.value.filterNot { it.id == messageId }
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
}
