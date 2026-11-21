package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.NoteAttachmentDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.dao.NoteMessageCount
import com.filestech.sms.data.local.db.dao.NoteMessageDao
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.sync.ActionResult
import com.filestech.sms.sync.CreateNoteResult
import com.filestech.sms.sync.SaveNoteMessageResult
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 36 phase 2 — the hub note source: the dedup-by-client-key
 * rules, the staged-file resolution (missing file → `upload_expired`
 * with no partial row), the attachment-failure rollback, the sticker
 * skip, and the action guards. The repository + DAOs are mocks; the
 * staged files land in a temp `cacheDir` via the real `uploadDir` /
 * `stagedFileName` helpers (the same paths the uploader writes).
 */
class RepositoryNoteSyncSourceTest {

    private class Fixture {
        val cacheDir: File = Files.createTempDirectory("note-source-test").toFile()
        val context = mockk<Context>()
        val noteRepository = mockk<NoteRepository>(relaxed = true)
        val noteDao = mockk<NoteDao>(relaxed = true)
        val noteMessageDao = mockk<NoteMessageDao>(relaxed = true)
        val noteAttachmentDao = mockk<NoteAttachmentDao>(relaxed = true)
        val resolver = mockk<NoteAttachmentResolver>(relaxed = true)

        init {
            every { context.cacheDir } returns cacheDir
            // A relaxed mock answers object returns with a mock
            // instance — the dedup lookups must default to null
            // (no existing row), or every save/create dedup-hits.
            coEvery { noteRepository.findNoteByClientId(any()) } returns null
            coEvery { noteRepository.findMessageByClientId(any()) } returns null
        }

        val source = RepositoryNoteSyncSource(
            context,
            noteRepository,
            noteDao,
            noteMessageDao,
            noteAttachmentDao,
            resolver,
        )

        /** Stages one upload file exactly where the uploader would put it. */
        fun stageUpload(
            clientMessageId: String,
            index: Int,
            fileName: String?,
            mimeType: String?,
            bytes: ByteArray,
        ): File {
            val dir = uploadDir(cacheDir, clientMessageId)
            dir.mkdirs()
            return File(dir, stagedFileName(index, fileName, mimeType)).also {
                it.writeBytes(bytes)
            }
        }
    }

    private fun note(id: Long = 7L, title: String = "Groceries") = Note(
        id = id,
        uuid = "note-uuid",
        title = title,
        createdAt = 1000L,
        updatedAt = 1000L,
        lastMessageAt = null,
        lastMessagePreview = null,
        draft = null,
        pinned = false,
        archived = false,
        bubbleColorArgb = null,
    )

    private fun message(id: Long = 42L, noteId: Long = 7L, body: String = "hello") =
        NoteMessage(
            id = id,
            uuid = "msg-uuid",
            noteId = noteId,
            body = body,
            date = 1000L,
        )

    // -- create -----------------------------------------------------

    @Test fun `createNote dedups on the client key`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteByClientId("uuid-9") } returns note()
        coEvery { f.noteMessageDao.countsByNote() } returns listOf(NoteMessageCount(7L, 3))

        val result = f.source.createNote("uuid-9", "Groceries")

        assertThat(result).isInstanceOf(CreateNoteResult.Created::class.java)
        assertThat((result as CreateNoteResult.Created).note.id).isEqualTo("7")
        assertThat(result.note.messageCount).isEqualTo(3)
        coVerify(exactly = 0) {
            f.noteRepository.createNote(any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `createNote with a blank title fails invalid_request`() = runTest {
        val f = Fixture()

        val result = f.source.createNote("uuid-9", "   ")

        assertThat(result).isInstanceOf(CreateNoteResult.Failed::class.java)
        assertThat((result as CreateNoteResult.Failed).code)
            .isEqualTo(SyncProtocol.MessageFailed.INVALID_REQUEST)
    }

    @Test fun `createNote inserts with the client key`() = runTest {
        val f = Fixture()
        coEvery {
            f.noteRepository.createNote(any(), any(), any(), any(), any(), any())
        } returns note()

        val result = f.source.createNote("uuid-9", "Groceries")

        assertThat(result).isInstanceOf(CreateNoteResult.Created::class.java)
        coVerify {
            f.noteRepository.createNote("Groceries", any(), any(), any(), null, "uuid-9")
        }
    }

    // -- save --------------------------------------------------------

    @Test fun `saveNoteMessage dedups on the client key`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findMessageByClientId("uuid-1") } returns message()

        val result = f.source.saveNoteMessage("uuid-1", 7L, "hello", null)

        assertThat(result).isEqualTo(SaveNoteMessageResult.Saved(42L))
        coVerify(exactly = 0) {
            f.noteRepository.insertMessage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `saveNoteMessage to an unknown note fails unknown_note`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns null

        val result = f.source.saveNoteMessage("uuid-1", 7L, "hello", null)

        assertThat(result).isInstanceOf(SaveNoteMessageResult.Failed::class.java)
        assertThat((result as SaveNoteMessageResult.Failed).code)
            .isEqualTo(SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE)
    }

    @Test fun `saveNoteMessage over the body cap fails message_too_long`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns note()
        val tooLong = "x".repeat(SyncProtocol.MAX_SEND_BODY_BYTES + 1)

        val result = f.source.saveNoteMessage("uuid-1", 7L, tooLong, null)

        assertThat((result as SaveNoteMessageResult.Failed).code)
            .isEqualTo(SyncProtocol.NoteMessageFailed.MESSAGE_TOO_LONG)
        coVerify(exactly = 0) {
            f.noteRepository.insertMessage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `saveNoteMessage with a missing staged file fails upload_expired`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns note()

        val result = f.source.saveNoteMessage(
            "uuid-1",
            7L,
            "hello",
            listOf(
                SyncProtocol.SendAttachment(
                    uploadIndex = 0,
                    mimeType = "image/jpeg",
                    fileName = "a.jpg",
                ),
            ),
        )

        assertThat((result as SaveNoteMessageResult.Failed).code)
            .isEqualTo(SyncProtocol.NoteMessageFailed.UPLOAD_EXPIRED)
        // Resolved BEFORE any insert: no partial row left behind.
        coVerify(exactly = 0) {
            f.noteRepository.insertMessage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `saveNoteMessage with a staged file inserts the entry and the attachment`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns note()
        coEvery {
            f.noteRepository.insertMessage(any(), any(), any(), any(), any(), any())
        } returns message()
        coEvery {
            f.noteRepository.addAttachment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Success(
            Attachment(
                id = 5L,
                messageId = 42L,
                mimeType = "image/jpeg",
                fileName = "a.jpg",
                sizeBytes = 3L,
                localUri = "note://5",
                width = null,
                height = null,
                durationMs = null,
            ),
        )
        val bytes = byteArrayOf(1, 2, 3)
        val staged = f.stageUpload("uuid-1", 0, "a.jpg", "image/jpeg", bytes)

        val result = f.source.saveNoteMessage(
            "uuid-1",
            7L,
            "hello",
            listOf(
                SyncProtocol.SendAttachment(
                    uploadIndex = 0,
                    mimeType = "image/jpeg",
                    fileName = "a.jpg",
                    width = 800,
                    height = 600,
                ),
            ),
        )

        assertThat(result).isEqualTo(SaveNoteMessageResult.Saved(42L))
        val stored = slot<ByteArray>()
        coVerify {
            f.noteRepository.addAttachment(
                42L,
                "image/jpeg",
                "a.jpg",
                capture(stored),
                800,
                600,
                null,
                any(),
            )
        }
        assertThat(stored.captured).isEqualTo(bytes)
        // The staged dir is swept after a successful save.
        assertThat(staged.exists()).isFalse()
    }

    @Test fun `saveNoteMessage rolls back when an attachment fails`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns note()
        coEvery {
            f.noteRepository.insertMessage(any(), any(), any(), any(), any(), any())
        } returns message()
        coEvery {
            f.noteRepository.addAttachment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Failure(AppError.Storage())
        f.stageUpload("uuid-1", 0, "a.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        val result = f.source.saveNoteMessage(
            "uuid-1",
            7L,
            "hello",
            listOf(
                SyncProtocol.SendAttachment(
                    uploadIndex = 0,
                    mimeType = "image/jpeg",
                    fileName = "a.jpg",
                ),
            ),
        )

        assertThat(result).isInstanceOf(SaveNoteMessageResult.Failed::class.java)
        assertThat((result as SaveNoteMessageResult.Failed).code)
            .isEqualTo(SyncProtocol.NoteMessageFailed.INTERNAL)
        // The partial row is deleted: a retry re-saves the whole
        // entry instead of dedup-hitting a row that lost a file.
        coVerify { f.noteRepository.deleteMessage(42L) }
    }

    @Test fun `saveNoteMessage skips sticker entries`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns note()
        coEvery {
            f.noteRepository.insertMessage(any(), any(), any(), any(), any(), any())
        } returns message()

        val result = f.source.saveNoteMessage(
            "uuid-1",
            7L,
            "hello",
            listOf(SyncProtocol.SendAttachment(stickerEmoji = "😀")),
        )

        assertThat(result).isEqualTo(SaveNoteMessageResult.Saved(42L))
        coVerify(exactly = 0) {
            f.noteRepository.addAttachment(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `saveNoteMessage with no text and no uploads fails`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns note()

        val result = f.source.saveNoteMessage("uuid-1", 7L, "   ", null)

        assertThat((result as SaveNoteMessageResult.Failed).code)
            .isEqualTo(SyncProtocol.NoteMessageFailed.INTERNAL)
    }

    // -- actions -----------------------------------------------------

    @Test fun `editNoteMessage on a message from another note fails unknown_message`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findMessageById(42L) } returns message(noteId = 8L)

        val result = f.source.editNoteMessage(7L, 42L, "Fixed")

        assertThat(result).isEqualTo(
            ActionResult(ok = false, code = SyncProtocol.NoteMessageFailed.UNKNOWN_MESSAGE),
        )
        coVerify(exactly = 0) { f.noteRepository.editMessage(any(), any()) }
    }

    @Test fun `editNoteMessage over the cap fails message_too_long`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findMessageById(42L) } returns message()
        val tooLong = "x".repeat(SyncProtocol.MAX_SEND_BODY_BYTES + 1)

        val result = f.source.editNoteMessage(7L, 42L, tooLong)

        assertThat(result.code).isEqualTo(SyncProtocol.NoteMessageFailed.MESSAGE_TOO_LONG)
    }

    @Test fun `renameNote with a blank title fails invalid_request`() = runTest {
        val f = Fixture()

        val result = f.source.renameNote(7L, "   ")

        assertThat(result).isEqualTo(
            ActionResult(ok = false, code = SyncProtocol.MessageFailed.INVALID_REQUEST),
        )
    }

    @Test fun `deleteNote on an unknown note fails unknown_note`() = runTest {
        val f = Fixture()
        coEvery { f.noteRepository.findNoteById(7L) } returns null

        val result = f.source.deleteNote(7L)

        assertThat(result).isEqualTo(
            ActionResult(ok = false, code = SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE),
        )
        coVerify(exactly = 0) { f.noteRepository.deleteNote(any()) }
    }
}
