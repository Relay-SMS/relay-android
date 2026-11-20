package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.NotesKeyManager
import com.filestech.sms.data.notes.NoteAttachmentCrypto
import com.filestech.sms.data.notes.NoteAttachmentResolverImpl
import com.filestech.sms.data.notes.NoteRepositoryImpl
import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Covers [NoteRepositoryImpl] against a real in-memory Room database plus the real
 * Keystore-backed notes key (this is an instrumented test: the Keystore exists here).
 *
 * The tests pin the two invariants the repository owns: the denormalized preview
 * (recomputed from the newest entry after every mutation) and the attachment-file
 * lifecycle (a file is deleted when its row is deleted, never refcounted).
 *
 * ⚠️ Block bodies, not `= runBlocking { … }`: JUnit4 requires `void` test methods, and an
 * expression body returns the last statement's value.
 */
@RunWith(AndroidJUnit4::class)
class NoteRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepositoryImpl
    private lateinit var resolver: NoteAttachmentResolverImpl

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyManager = NotesKeyManager(context, KeystoreManager(), AeadCipher())
        val crypto = NoteAttachmentCrypto(context, keyManager, AeadCipher())
        repository = NoteRepositoryImpl(
            db,
            db.noteDao(),
            db.noteMessageDao(),
            db.noteAttachmentDao(),
            crypto,
            Dispatchers.Unconfined,
        )
        resolver = NoteAttachmentResolverImpl(context, crypto, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        // Fresh key + no leftover files for the next test (each test builds its own rows).
        File(context.filesDir, "notes").deleteRecursively()
        File(context.cacheDir, "notes_playback").deleteRecursively()
    }

    @Test
    fun createNote_returnsNoteWithGeneratedUuidAndEmptyPreview() {
        runBlocking {
            val note = repository.createNote("Groceries")

            assertThat(note.id).isGreaterThan(0L)
            assertThat(note.uuid).isNotEmpty()
            assertThat(note.title).isEqualTo("Groceries")
            assertThat(note.lastMessageAt).isNull()
            assertThat(note.lastMessagePreview).isNull()
            assertThat(note.pinned).isFalse()
            assertThat(note.archived).isFalse()
        }
    }

    @Test
    fun createNote_withExplicitUuidAndTimestamps_keepsThem() {
        runBlocking {
            val note = repository.createNote(
                title = "Imported",
                uuid = "uuid-imported-note",
                createdAt = 1_000L,
                updatedAt = 2_000L,
            )

            assertThat(note.uuid).isEqualTo("uuid-imported-note")
            assertThat(note.createdAt).isEqualTo(1_000L)
            assertThat(note.updatedAt).isEqualTo(2_000L)
            assertThat(repository.findNoteByUuid("uuid-imported-note")?.id).isEqualTo(note.id)
        }
    }

    @Test
    fun renameNote_updatesTitleAndMovesUpdatedAt() {
        runBlocking {
            val note = repository.createNote("Old", createdAt = 1_000L, updatedAt = 1_000L)

            repository.renameNote(note.id, "New")

            val renamed = repository.findNoteById(note.id)
            assertThat(renamed?.title).isEqualTo("New")
            assertThat(renamed?.updatedAt).isGreaterThan(1_000L)
        }
    }

    @Test
    fun deleteNote_removesRowsAndAttachmentFiles() {
        runBlocking {
            val note = repository.createNote("Doomed")
            val message = repository.insertMessage(note.id, "with a photo")
            val attachment = addPhoto(message.id)
            val file = File(attachment.localUri)
            assertThat(file.exists()).isTrue()

            repository.deleteNote(note.id)

            assertThat(repository.findNoteById(note.id)).isNull()
            assertThat(repository.findMessageById(message.id)).isNull()
            assertThat(repository.findAttachmentById(attachment.id)).isNull()
            assertThat(file.exists()).isFalse()
        }
    }

    @Test
    fun setPinnedAndArchived_toggleFlags() {
        runBlocking {
            val note = repository.createNote("Flags")

            repository.setPinned(note.id, true)
            repository.setArchived(note.id, true)
            assertThat(repository.findNoteById(note.id)?.pinned).isTrue()
            assertThat(repository.findNoteById(note.id)?.archived).isTrue()

            repository.setPinned(note.id, false)
            repository.setArchived(note.id, false)
            assertThat(repository.findNoteById(note.id)?.pinned).isFalse()
            assertThat(repository.findNoteById(note.id)?.archived).isFalse()
        }
    }

    @Test
    fun setDraft_roundTripsNullAndText() {
        runBlocking {
            val note = repository.createNote("Drafty")

            repository.setDraft(note.id, "half-written")
            assertThat(repository.findNoteById(note.id)?.draft).isEqualTo("half-written")

            repository.setDraft(note.id, null)
            assertThat(repository.findNoteById(note.id)?.draft).isNull()
        }
    }

    @Test
    fun setBubbleColor_roundTripsNullAndColor() {
        runBlocking {
            val note = repository.createNote("Colorful")

            repository.setBubbleColor(note.id, 0xFF00FF00.toInt())
            assertThat(repository.findNoteById(note.id)?.bubbleColorArgb).isEqualTo(0xFF00FF00.toInt())

            repository.setBubbleColor(note.id, null)
            assertThat(repository.findNoteById(note.id)?.bubbleColorArgb).isNull()
        }
    }

    @Test
    fun insertMessage_updatesPreviewAndLastMessageAt() {
        runBlocking {
            val note = repository.createNote("Chatty")

            repository.insertMessage(note.id, "hello world", date = 5_000L)

            val updated = repository.findNoteById(note.id)
            assertThat(updated?.lastMessageAt).isEqualTo(5_000L)
            assertThat(updated?.lastMessagePreview).isEqualTo("hello world")
        }
    }

    @Test
    fun insertMessage_truncatesLongPreviewTo240Chars() {
        runBlocking {
            val note = repository.createNote("Long")
            val longBody = "x".repeat(300)

            repository.insertMessage(note.id, longBody, date = 5_000L)

            val preview = repository.findNoteById(note.id)?.lastMessagePreview
            assertThat(preview).isEqualTo("x".repeat(240))
        }
    }

    @Test
    fun insertMessage_attachmentOnly_usesFileLabelPreview() {
        runBlocking {
            val note = repository.createNote("Photos")
            val message = repository.insertMessage(note.id, "", date = 5_000L)

            addPhoto(message.id, fileName = "sunset.jpg")

            val updated = repository.findNoteById(note.id)
            assertThat(updated?.lastMessageAt).isEqualTo(5_000L)
            assertThat(updated?.lastMessagePreview).isEqualTo("🖼️ sunset.jpg")
        }
    }

    @Test
    fun deleteMessage_recomputesPreviewFromPreviousEntry() {
        runBlocking {
            val note = repository.createNote("Thread")
            repository.insertMessage(note.id, "first", date = 1_000L)
            val second = repository.insertMessage(note.id, "second", date = 2_000L)
            assertThat(repository.findNoteById(note.id)?.lastMessagePreview).isEqualTo("second")

            repository.deleteMessage(second.id)

            val updated = repository.findNoteById(note.id)
            assertThat(updated?.lastMessageAt).isEqualTo(1_000L)
            assertThat(updated?.lastMessagePreview).isEqualTo("first")
        }
    }

    @Test
    fun deleteMessage_removesAttachmentFiles() {
        runBlocking {
            val note = repository.createNote("Thread")
            val message = repository.insertMessage(note.id, "with a photo")
            val attachment = addPhoto(message.id)
            val file = File(attachment.localUri)
            assertThat(file.exists()).isTrue()

            repository.deleteMessage(message.id)

            assertThat(repository.findMessageById(message.id)).isNull()
            assertThat(repository.findAttachmentById(attachment.id)).isNull()
            assertThat(file.exists()).isFalse()
        }
    }

    @Test
    fun deleteLastMessage_clearsPreview() {
        runBlocking {
            val note = repository.createNote("Thread")
            val only = repository.insertMessage(note.id, "only", date = 1_000L)
            assertThat(repository.findNoteById(note.id)?.lastMessagePreview).isEqualTo("only")

            repository.deleteMessage(only.id)

            val updated = repository.findNoteById(note.id)
            assertThat(updated?.lastMessageAt).isNull()
            assertThat(updated?.lastMessagePreview).isNull()
        }
    }

    @Test
    fun observeAllNotes_ordersPinnedFirstThenByRecency() {
        runBlocking {
            val older = repository.createNote("Older", createdAt = 1_000L, updatedAt = 1_000L)
            val recent = repository.createNote("Recent", createdAt = 2_000L, updatedAt = 2_000L)
            val empty = repository.createNote("Empty", createdAt = 2_500L, updatedAt = 2_500L)
            repository.insertMessage(recent.id, "news", date = 3_000L)

            // Unpinned: recent entry (3000) > empty note by creation (2500) > older (1000).
            val unpinned = repository.observeAllNotes().first()
            assertThat(unpinned.map { it.id }).containsExactly(recent.id, empty.id, older.id).inOrder()

            // Pinning the oldest note lifts it above the rest.
            repository.setPinned(older.id, true)
            val pinned = repository.observeAllNotes().first()
            assertThat(pinned.map { it.id }).containsExactly(older.id, recent.id, empty.id).inOrder()
        }
    }

    @Test
    fun observeMessagesWindow_returnsBoundedTailOldestFirst() {
        runBlocking {
            val note = repository.createNote("Long note")
            repeat(5) { i ->
                repository.insertMessage(note.id, "m${i + 1}", date = (i + 1) * 1_000L)
            }

            val window = repository.observeMessagesWindow(note.id, MutableStateFlow(3)).first()

            assertThat(window.messages.map { it.body }).containsExactly("m3", "m4", "m5").inOrder()
            assertThat(window.totalCount).isEqualTo(5)
            assertThat(window.hasMore).isTrue()
            assertThat(window.firstMessageAt).isEqualTo(1_000L)
            assertThat(window.lastMessageAt).isEqualTo(5_000L)
        }
    }

    @Test
    fun observeMessagesWindow_growsWithLimit() {
        runBlocking {
            val note = repository.createNote("Growing")
            repeat(5) { i ->
                repository.insertMessage(note.id, "m${i + 1}", date = (i + 1) * 1_000L)
            }
            val limit = MutableStateFlow(2)

            val small = repository.observeMessagesWindow(note.id, limit).first()
            assertThat(small.messages.map { it.body }).containsExactly("m4", "m5").inOrder()
            assertThat(small.hasMore).isTrue()

            limit.value = 5
            val full = repository.observeMessagesWindow(note.id, limit).first()
            assertThat(full.messages).hasSize(5)
            assertThat(full.hasMore).isFalse()
        }
    }

    @Test
    fun addAttachment_storesEncryptedBytesAndPlaintextSize() {
        runBlocking {
            val note = repository.createNote("Files")
            val message = repository.insertMessage(note.id, "see attached")
            val plaintext = "fake-jpeg-bytes".toByteArray()

            val stored = repository.addAttachment(
                message.id,
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                plaintext = plaintext,
                width = 100,
                height = 80,
            )
            assertThat(stored).isInstanceOf(Outcome.Success::class.java)
            val attachment = (stored as Outcome.Success).value

            // size_bytes is the PLAINTEXT size; the file on disk is 29 bytes larger.
            assertThat(attachment.sizeBytes).isEqualTo(plaintext.size.toLong())
            val file = File(attachment.localUri)
            assertThat(file.length()).isEqualTo((plaintext.size + 29).toLong())
            // The bytes on disk are ciphertext, not the plaintext.
            assertThat(file.readBytes()).isNotEqualTo(plaintext)

            // …and they decrypt back to the original bytes.
            val roundTripped = resolver.decryptToMemory(attachment)
            assertThat(roundTripped).isInstanceOf(Outcome.Success::class.java)
            assertThat((roundTripped as Outcome.Success).value).isEqualTo(plaintext)
        }
    }

    @Test
    fun addAttachment_toMissingMessage_returnsNotFoundFailure() {
        runBlocking {
            val result = repository.addAttachment(
                messageId = 999L,
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                plaintext = "x".toByteArray(),
            )

            assertThat(result).isInstanceOf(Outcome.Failure::class.java)
            assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.NotFound::class.java)
        }
    }

    @Test
    fun deleteAttachment_removesRowFileAndRefreshesCount() {
        runBlocking {
            val note = repository.createNote("Files")
            val message = repository.insertMessage(note.id, "two files")
            val first = addPhoto(message.id, fileName = "one.jpg")
            val second = addPhoto(message.id, fileName = "two.jpg")
            assertThat(db.noteMessageDao().findById(message.id)?.attachmentsCount).isEqualTo(2)

            repository.deleteAttachment(first.id)

            assertThat(repository.findAttachmentById(first.id)).isNull()
            assertThat(File(first.localUri).exists()).isFalse()
            assertThat(repository.findAttachmentById(second.id)).isNotNull()
            assertThat(File(second.localUri).exists()).isTrue()
            assertThat(db.noteMessageDao().findById(message.id)?.attachmentsCount).isEqualTo(1)
        }
    }

    @Test
    fun findByUuid_lookupsFindNotesMessagesAndAttachments() {
        runBlocking {
            val note = repository.createNote("Lookup", uuid = "uuid-note")
            val message = repository.insertMessage(note.id, "body", uuid = "uuid-message")
            val attachment = addPhoto(message.id, uuid = "uuid-attachment")

            assertThat(repository.findNoteByUuid("uuid-note")?.id).isEqualTo(note.id)
            assertThat(repository.findMessageByUuid("uuid-message")?.id).isEqualTo(message.id)
            assertThat(repository.findAttachmentByUuid("uuid-attachment")?.id).isEqualTo(attachment.id)
            assertThat(repository.findNoteByUuid("uuid-nope")).isNull()
            assertThat(repository.findMessageByUuid("uuid-nope")).isNull()
            assertThat(repository.findAttachmentByUuid("uuid-nope")).isNull()
        }
    }

    @Test
    fun listAll_returnsEverythingForBackup() {
        runBlocking {
            val first = repository.createNote("First")
            val second = repository.createNote("Second")
            val m1 = repository.insertMessage(first.id, "one")
            val m2 = repository.insertMessage(first.id, "two")
            repository.insertMessage(second.id, "three")
            addPhoto(m1)
            addPhoto(m2)

            assertThat(repository.listAllNotes()).hasSize(2)
            val messages = repository.listAllMessages()
            assertThat(messages).hasSize(3)
            assertThat(messages.filter { it.noteId == first.id }).hasSize(2)
            // Messages come back WITH their attachments loaded.
            assertThat(messages.first { it.id == m1.id }.attachments).hasSize(1)
            assertThat(repository.listAllAttachments()).hasSize(2)
        }
    }

    @Test
    fun insertMessage_withHistoricalDate_doesNotMoveUpdatedAt() {
        runBlocking {
            // Import fidelity: a backup re-import inserts messages with historical dates;
            // the note's own updated_at must survive them untouched.
            val note = repository.createNote("Imported", createdAt = 1_000L, updatedAt = 5_000L)

            repository.insertMessage(note.id, "old entry", date = 6_000L)

            val updated = repository.findNoteById(note.id)
            assertThat(updated?.updatedAt).isEqualTo(5_000L)
            assertThat(updated?.lastMessageAt).isEqualTo(6_000L)
        }
    }

    @Test
    fun deleteNote_onMissingNote_doesNothing() {
        runBlocking {
            repository.deleteNote(999L)
        }
    }

    private suspend fun addPhoto(
        messageId: Long,
        fileName: String = "photo.jpg",
        uuid: String = java.util.UUID.randomUUID().toString(),
    ): Attachment {
        val stored = repository.addAttachment(
            messageId,
            mimeType = "image/jpeg",
            fileName = fileName,
            plaintext = "fake-jpeg-bytes".toByteArray(),
            width = 100,
            height = 80,
            uuid = uuid,
        )
        assertThat(stored).isInstanceOf(Outcome.Success::class.java)
        return (stored as Outcome.Success).value
    }
}
