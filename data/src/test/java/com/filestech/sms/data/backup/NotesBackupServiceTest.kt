package com.filestech.sms.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.NoteAttachmentDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.entity.NoteAttachmentEntity
import com.filestech.sms.data.local.db.entity.NoteEntity
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.domain.backup.NotesBackupRestorer
import com.filestech.sms.domain.backup.RestoreNotesResult
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.NoteMessageWindow
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.usecase.RestoreNotesBackupUseCase
import com.filestech.sms.security.AppLockManager
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.Executor

/**
 * Covers [NotesBackupService] end to end on the plain JVM harness (no Robolectric, no
 * real Room — the local harness has no KSP-generated `AppDatabase_Impl`):
 *
 * - the notes store is a fake ([FakeNotesStore]) shared by a fake [NoteRepository], fake
 *   DAOs and a fake [NoteAttachmentResolver], so export and import run against the same
 *   state a real database would hold;
 * - `AppDatabase` is a MockK mock with the five `withTransaction` stubs Room 2.8 needs
 *   (transaction executor, suspending-transaction ThreadLocal, begin / setSuccessful /
 *   end) — see the harness lesson in AGENTS.md;
 * - the crypto is REAL (`PasswordKdf` + `AeadCipher`), so the envelope, the AAD binding
 *   and the KDF calibration path are all exercised — only `calibrate()` is pinned to
 *   the iteration floor so the suite stays fast;
 * - the SAF side is a mocked `ContentResolver` backed by one in-memory stream.
 */
class NotesBackupServiceTest {

    private val store = FakeNotesStore()
    private val repository = FakeNoteRepository(store)
    private val resolver = FakeNoteAttachmentResolver(store)
    private val noteDao = FakeNoteDao(store)
    private val noteAttachmentDao = FakeNoteAttachmentDao(store)
    private val appLock = mockk<AppLockManager>()
    private val written = ByteArrayOutputStream()
    private val uri = mockk<Uri>()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private fun database(): AppDatabase {
        val db = mockk<AppDatabase>()
        every { db.transactionExecutor } returns Executor { it.run() }
        every { db.suspendingTransactionContext } returns ThreadLocal()
        every { db.beginTransaction() } just Runs
        every { db.setTransactionSuccessful() } just Runs
        every { db.endTransaction() } just Runs
        return db
    }

    private fun fastKdf(): PasswordKdf {
        val kdf = spyk(PasswordKdf())
        // Calibration measures the device (~300 ms per export); pin the iteration
        // count to the floor so the suite stays fast. Derivation itself stays real.
        every { kdf.calibrate(any()) } returns PasswordKdf.MIN_ITERATIONS
        return kdf
    }

    private fun service(
        state: AppLockManager.LockState = AppLockManager.LockState.Unlocked,
    ): NotesBackupService {
        every { appLock.state } returns MutableStateFlow(state)
        val contentResolver = mockk<ContentResolver>()
        every { contentResolver.openOutputStream(any(), any()) } returns written
        every { contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(written.toByteArray())
        }
        val context = mockk<Context>()
        every { context.contentResolver } returns contentResolver
        return NotesBackupService(
            context = context,
            database = database(),
            noteRepository = repository,
            attachmentResolver = resolver,
            noteDao = noteDao,
            noteAttachmentDao = noteAttachmentDao,
            kdf = fastKdf(),
            aead = AeadCipher(),
            appLock = appLock,
            io = Dispatchers.Unconfined,
        )
    }

    /**
     * Three notes: one pinned + colored with three entries (text, photo-only, text +
     * file), one archived with one entry, one empty. 3 notes, 4 messages, 2 attachments.
     */
    private suspend fun seedNotes() {
        val groceries = repository.createNote("Groceries", "note-groceries", 1_000L, 2_000L)
        noteDao.setPinned(groceries.id, true, 2_000L)
        noteDao.setBubbleColor(groceries.id, 0x00112233, 2_000L)
        repository.insertMessage(groceries.id, "Milk, eggs, bread", "msg-a1", 3_000L)
        val photoMessage = repository.insertMessage(groceries.id, "", "msg-a2", 4_000L)
        repository.addAttachment(
            photoMessage.id,
            "image/png",
            "fridge.png",
            "fake-png-bytes".toByteArray(),
            width = 800,
            height = 600,
            uuid = "att-photo",
        )
        val fileMessage = repository.insertMessage(groceries.id, "Also butter", "msg-a3", 5_000L)
        repository.addAttachment(
            fileMessage.id,
            "application/pdf",
            "list.pdf",
            "pdf-content".toByteArray(),
            uuid = "att-file",
        )
        val ideas = repository.createNote("Ideas", "note-ideas", 6_000L, 7_000L)
        noteDao.setArchived(ideas.id, true, 7_000L)
        repository.insertMessage(ideas.id, "Build a treehouse", "msg-b1", 8_000L)
        repository.createNote("Empty", "note-empty", 9_000L, 9_000L)
    }

    /** Wraps a JSON payload in a valid `.notebk` envelope (test-side encoder). */
    private fun notebkBytes(payloadJson: String, password: CharArray): ByteArray {
        val kdf = PasswordKdf()
        val salt = kdf.newSalt()
        val iter = PasswordKdf.MIN_ITERATIONS
        val magic = "NTBK".toByteArray(Charsets.US_ASCII)
        val iterBytes = byteArrayOf(
            ((iter ushr 24) and 0xFF).toByte(),
            ((iter ushr 16) and 0xFF).toByte(),
            ((iter ushr 8) and 0xFF).toByte(),
            (iter and 0xFF).toByte(),
        )
        val aad = magic + byteArrayOf(1) + salt + iterBytes
        val rawKey = kdf.derive(password, salt, iter)
        try {
            val blob = when (
                val encrypted = AeadCipher().encryptRaw(
                    rawKey,
                    payloadJson.toByteArray(Charsets.UTF_8),
                    aad = aad,
                )
            ) {
                is Outcome.Success -> encrypted.value
                is Outcome.Failure -> error("test setup: encrypt failed")
            }
            return aad + blob
        } finally {
            rawKey.fill(0)
        }
    }

    private fun payloadBytes(
        payload: NotesBackupService.NotesBackupPayload,
        password: CharArray,
    ): ByteArray = notebkBytes(
        json.encodeToString(NotesBackupService.NotesBackupPayload.serializer(), payload),
        password,
    )

    @Test
    fun `export then import round-trips notes messages and attachment bytes`() = runTest {
        seedNotes()
        val beforeNotes = repository.listAllNotes()
        val beforeMessages = repository.listAllMessages()
        val beforeAttachmentEntities = noteAttachmentDao.listAll()
        val beforeBytesByUuid = beforeAttachmentEntities.associate { entity ->
            entity.uuid to store.attachmentBytes[entity.uuid]!!
        }

        val exported = service().writeNotesBackup(uri, "correct horse".toCharArray())
        assertThat(exported).isEqualTo(Outcome.Success(uri))

        // Fresh install: everything wiped.
        store.clear()

        val imported = service().readNotesBackup(uri, "correct horse".toCharArray())
        assertThat(imported).isInstanceOf(Outcome.Success::class.java)
        val result = (imported as Outcome.Success).value
        assertThat(result.notesCreated).isEqualTo(3)
        assertThat(result.messagesImported).isEqualTo(4)
        assertThat(result.attachmentsImported).isEqualTo(2)

        val afterNotes = repository.listAllNotes()
        assertThat(afterNotes.map { it.uuid }).containsExactlyElementsIn(beforeNotes.map { it.uuid })
        for (before in beforeNotes) {
            val after = afterNotes.first { it.uuid == before.uuid }
            assertThat(after.title).isEqualTo(before.title)
            assertThat(after.createdAt).isEqualTo(before.createdAt)
            assertThat(after.updatedAt).isEqualTo(before.updatedAt)
            assertThat(after.pinned).isEqualTo(before.pinned)
            assertThat(after.archived).isEqualTo(before.archived)
            assertThat(after.bubbleColorArgb).isEqualTo(before.bubbleColorArgb)
            assertThat(after.lastMessageAt).isEqualTo(before.lastMessageAt)
            assertThat(after.lastMessagePreview).isEqualTo(before.lastMessagePreview)
        }
        val beforeNoteUuidById = beforeNotes.associate { it.id to it.uuid }
        val afterNoteUuidById = afterNotes.associate { it.id to it.uuid }
        val afterMessages = repository.listAllMessages()
        assertThat(afterMessages.map { it.uuid })
            .containsExactlyElementsIn(beforeMessages.map { it.uuid })
        for (before in beforeMessages) {
            val after = afterMessages.first { it.uuid == before.uuid }
            assertThat(afterNoteUuidById[after.noteId]).isEqualTo(beforeNoteUuidById[before.noteId])
            assertThat(after.body).isEqualTo(before.body)
            assertThat(after.date).isEqualTo(before.date)
            assertThat(after.attachments.size).isEqualTo(before.attachments.size)
        }
        val afterAttachmentEntities = noteAttachmentDao.listAll()
        assertThat(afterAttachmentEntities.map { it.uuid })
            .containsExactlyElementsIn(beforeAttachmentEntities.map { it.uuid })
        for (before in beforeAttachmentEntities) {
            val after = afterAttachmentEntities.first { it.uuid == before.uuid }
            assertThat(after.mimeType).isEqualTo(before.mimeType)
            assertThat(after.fileName).isEqualTo(before.fileName)
            assertThat(after.sizeBytes).isEqualTo(before.sizeBytes)
            assertThat(after.width).isEqualTo(before.width)
            assertThat(after.height).isEqualTo(before.height)
            assertThat(after.durationMs).isEqualTo(before.durationMs)
            val decrypted = resolver.decryptToMemory(after.toDomain())
            assertThat(decrypted).isInstanceOf(Outcome.Success::class.java)
            assertThat((decrypted as Outcome.Success).value).isEqualTo(beforeBytesByUuid[before.uuid])
        }
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() = runTest {
        seedNotes()
        assertThat(service().writeNotesBackup(uri, "passphrase".toCharArray()))
            .isEqualTo(Outcome.Success(uri))
        store.clear()

        val first = service().readNotesBackup(uri, "passphrase".toCharArray()) as Outcome.Success
        assertThat(first.value.notesCreated).isEqualTo(3)
        assertThat(first.value.messagesImported).isEqualTo(4)
        assertThat(first.value.attachmentsImported).isEqualTo(2)
        val notesAfterFirst = repository.listAllNotes().size
        val messagesAfterFirst = repository.listAllMessages().size
        val attachmentsAfterFirst = repository.listAllAttachments().size

        val second = service().readNotesBackup(uri, "passphrase".toCharArray()) as Outcome.Success
        assertThat(second.value.notesCreated).isEqualTo(0)
        assertThat(second.value.messagesImported).isEqualTo(0)
        assertThat(second.value.attachmentsImported).isEqualTo(0)
        assertThat(second.value.notesReused).isEqualTo(3)
        assertThat(second.value.messagesSkipped).isEqualTo(4)
        assertThat(second.value.attachmentsSkipped).isEqualTo(2)
        assertThat(repository.listAllNotes().size).isEqualTo(notesAfterFirst)
        assertThat(repository.listAllMessages().size).isEqualTo(messagesAfterFirst)
        assertThat(repository.listAllAttachments().size).isEqualTo(attachmentsAfterFirst)
    }

    @Test
    fun `import merges messages into an existing note and keeps its row`() = runTest {
        // A note with the same UUID already exists locally, with a different title.
        val local = repository.createNote("Local title", "note-groceries", 100L, 100L)
        assertThat(local.title).isEqualTo("Local title")

        val payload = NotesBackupService.NotesBackupPayload(
            header = NotesBackupService.NotesBackupHeader(1L, "Relay", 1),
            notes = listOf(
                NotesBackupService.NoteBackupEntry(
                    "note-groceries",
                    "Backup title",
                    1_000L,
                    2_000L,
                    false,
                    false,
                    null,
                ),
            ),
            messages = listOf(
                NotesBackupService.NoteMessageBackupEntry(
                    "msg-new",
                    "note-groceries",
                    "Merged entry",
                    3_000L,
                ),
            ),
            attachments = emptyList(),
        )
        written.write(payloadBytes(payload, "passphrase".toCharArray()))

        val imported = service().readNotesBackup(uri, "passphrase".toCharArray()) as Outcome.Success
        assertThat(imported.value.notesReused).isEqualTo(1)
        assertThat(imported.value.notesCreated).isEqualTo(0)
        assertThat(imported.value.messagesImported).isEqualTo(1)

        val note = repository.findNoteByUuid("note-groceries")
        assertThat(note?.title).isEqualTo("Local title")
        assertThat(note?.createdAt).isEqualTo(100L)
        val messages = repository.listAllMessages()
        assertThat(messages.map { it.uuid }).containsExactly("msg-new")
        assertThat(messages.first().noteId).isEqualTo(local.id)
    }

    @Test
    fun `import with the wrong passphrase fails`() = runTest {
        seedNotes()
        assertThat(service().writeNotesBackup(uri, "right".toCharArray()))
            .isEqualTo(Outcome.Success(uri))
        store.clear()

        val imported = service().readNotesBackup(uri, "wrong".toCharArray())

        assertThat(imported).isInstanceOf(Outcome.Failure::class.java)
        val error = (imported as Outcome.Failure).error
        assertThat(error).isInstanceOf(AppError.Validation::class.java)
        assertThat((error as AppError.Validation).message).contains("decrypt failed")
        assertThat(repository.listAllNotes()).isEmpty()
    }

    @Test
    fun `a tampered ciphertext fails to import`() = runTest {
        seedNotes()
        assertThat(service().writeNotesBackup(uri, "passphrase".toCharArray()))
            .isEqualTo(Outcome.Success(uri))
        store.clear()

        val bytes = written.toByteArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        written.reset()
        written.write(bytes)

        val imported = service().readNotesBackup(uri, "passphrase".toCharArray())

        assertThat(imported).isInstanceOf(Outcome.Failure::class.java)
        val error = (imported as Outcome.Failure).error
        assertThat(error).isInstanceOf(AppError.Validation::class.java)
        assertThat((error as AppError.Validation).message).contains("decrypt failed")
        assertThat(repository.listAllNotes()).isEmpty()
    }

    @Test
    fun `a tampered salt fails to import because the KDF params are bound via AAD`() = runTest {
        seedNotes()
        assertThat(service().writeNotesBackup(uri, "passphrase".toCharArray()))
            .isEqualTo(Outcome.Success(uri))
        store.clear()

        // First salt byte: 4 magic bytes + 1 version byte in.
        val bytes = written.toByteArray()
        bytes[5] = (bytes[5].toInt() xor 0xFF).toByte()
        written.reset()
        written.write(bytes)

        val imported = service().readNotesBackup(uri, "passphrase".toCharArray())

        assertThat(imported).isInstanceOf(Outcome.Failure::class.java)
        val error = (imported as Outcome.Failure).error
        assertThat(error).isInstanceOf(AppError.Validation::class.java)
        assertThat((error as AppError.Validation).message).contains("decrypt failed")
        assertThat(repository.listAllNotes()).isEmpty()
    }

    @Test
    fun `an empty passphrase is refused on export`() = runTest {
        seedNotes()

        val exported = service().writeNotesBackup(uri, CharArray(0))

        assertThat(exported).isInstanceOf(Outcome.Failure::class.java)
        assertThat((exported as Outcome.Failure).error).isInstanceOf(AppError.Storage::class.java)
        assertThat(written.size()).isEqualTo(0)
    }

    @Test
    fun `an empty passphrase is refused on import`() = runTest {
        seedNotes()
        assertThat(service().writeNotesBackup(uri, "passphrase".toCharArray()))
            .isEqualTo(Outcome.Success(uri))
        store.clear()

        val imported = service().readNotesBackup(uri, CharArray(0))

        assertThat(imported).isInstanceOf(Outcome.Failure::class.java)
        assertThat(repository.listAllNotes()).isEmpty()
    }

    @Test
    fun `an oversize attachment is refused on export and nothing is written`() = runTest {
        val note = repository.createNote("Big", "note-big", 1L, 1L)
        val message = repository.insertMessage(note.id, "", "msg-big", 2L)
        val added = repository.addAttachment(
            message.id,
            "video/mp4",
            "big.mp4",
            "tiny".toByteArray(),
            uuid = "att-big",
        )
        assertThat(added).isInstanceOf(Outcome.Success::class.java)
        // Inflate the declared size past the 25 MB cap. The service checks the
        // declared size before decrypting, so no 26 MB array is ever allocated here.
        val entity = store.attachments.first { it.uuid == "att-big" }
        store.attachments[store.attachments.indexOf(entity)] =
            entity.copy(sizeBytes = 26L * 1024 * 1024)

        val exported = service().writeNotesBackup(uri, "passphrase".toCharArray())

        assertThat(exported).isInstanceOf(Outcome.Failure::class.java)
        assertThat(written.size()).isEqualTo(0)
    }

    @Test
    fun `export is refused in a panic-decoy session and the passphrase is wiped`() = runTest {
        seedNotes()
        val passphrase = "secret".toCharArray()

        val exported = service(AppLockManager.LockState.PanicDecoy)
            .writeNotesBackup(uri, passphrase)

        assertThat(exported).isEqualTo(Outcome.Failure(AppError.Locked()))
        assertThat(passphrase.all { it == Char(0) }).isTrue()
        assertThat(written.size()).isEqualTo(0)
    }

    @Test
    fun `import is refused in a panic-decoy session and the passphrase is wiped`() = runTest {
        seedNotes()
        assertThat(service().writeNotesBackup(uri, "passphrase".toCharArray()))
            .isEqualTo(Outcome.Success(uri))
        store.clear()
        val passphrase = "passphrase".toCharArray()

        val imported = service(AppLockManager.LockState.PanicDecoy)
            .readNotesBackup(uri, passphrase)

        assertThat(imported).isEqualTo(Outcome.Failure(AppError.Locked()))
        assertThat(passphrase.all { it == Char(0) }).isTrue()
        assertThat(repository.listAllNotes()).isEmpty()
    }

    @Test
    fun `an unknown JSON key is rejected by the strict parser`() = runTest {
        val payload = NotesBackupService.NotesBackupPayload(
            header = NotesBackupService.NotesBackupHeader(1L, "Relay", 1),
            notes = emptyList(),
            messages = emptyList(),
            attachments = emptyList(),
        )
        val jsonText = json.encodeToString(
            NotesBackupService.NotesBackupPayload.serializer(),
            payload,
        )
        // Forge one unknown field into the payload: the strict parser must refuse it.
        val forged = jsonText.replaceFirst("\"header\"", "\"forgedField\":true,\"header\"")
        written.write(notebkBytes(forged, "passphrase".toCharArray()))

        val imported = service().readNotesBackup(uri, "passphrase".toCharArray())

        assertThat(imported).isInstanceOf(Outcome.Failure::class.java)
        val error = (imported as Outcome.Failure).error
        assertThat(error).isInstanceOf(AppError.Validation::class.java)
        assertThat((error as AppError.Validation).message).contains("invalid JSON")
    }

    @Test
    fun `an oversize attachment in the payload is refused on import`() = runTest {
        val payload = NotesBackupService.NotesBackupPayload(
            header = NotesBackupService.NotesBackupHeader(1L, "Relay", 1),
            notes = listOf(
                NotesBackupService.NoteBackupEntry("note-big", "Big", 1L, 1L, false, false, null),
            ),
            messages = listOf(
                NotesBackupService.NoteMessageBackupEntry("msg-big", "note-big", "", 2L),
            ),
            attachments = listOf(
                NotesBackupService.NoteAttachmentBackupEntry(
                    uuid = "att-big",
                    messageUuid = "msg-big",
                    mimeType = "video/mp4",
                    fileName = "big.mp4",
                    sizeBytes = 26L * 1024 * 1024,
                    width = null,
                    height = null,
                    durationMs = null,
                    dataBase64 = Base64.getEncoder().encodeToString("tiny".toByteArray()),
                ),
            ),
        )
        written.write(payloadBytes(payload, "passphrase".toCharArray()))

        val imported = service().readNotesBackup(uri, "passphrase".toCharArray())

        assertThat(imported).isInstanceOf(Outcome.Failure::class.java)
        assertThat((imported as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(repository.listAllNotes()).isEmpty()
    }

    @Test
    fun `an orphan message is skipped without creating a phantom note`() = runTest {
        val payload = NotesBackupService.NotesBackupPayload(
            header = NotesBackupService.NotesBackupHeader(1L, "Relay", 1),
            notes = emptyList(),
            messages = listOf(
                NotesBackupService.NoteMessageBackupEntry(
                    "msg-orphan",
                    "note-missing",
                    "Orphan",
                    2L,
                ),
            ),
            attachments = emptyList(),
        )
        written.write(payloadBytes(payload, "passphrase".toCharArray()))

        val imported = service().readNotesBackup(uri, "passphrase".toCharArray()) as Outcome.Success

        assertThat(imported.value.messagesImported).isEqualTo(0)
        assertThat(imported.value.messagesSkipped).isEqualTo(1)
        assertThat(repository.listAllNotes()).isEmpty()
        assertThat(repository.listAllMessages()).isEmpty()
    }

    @Test
    fun `the exported file carries the NTBK magic and version 1`() = runTest {
        seedNotes()

        val exported = service().writeNotesBackup(uri, "passphrase".toCharArray())

        assertThat(exported).isEqualTo(Outcome.Success(uri))
        val bytes = written.toByteArray()
        assertThat(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)).isEqualTo("NTBK")
        assertThat(bytes[4].toInt()).isEqualTo(1)
    }

    @Test
    fun `the restore use case delegates to the restorer`() = runTest {
        val expected = RestoreNotesResult(1, 2, 3, 4, 5, 6)
        val restorer = object : NotesBackupRestorer {
            override suspend fun restore(
                uriString: String,
                password: CharArray,
            ): Outcome<RestoreNotesResult> = Outcome.Success(expected)
        }
        val useCase = RestoreNotesBackupUseCase(restorer)

        val result = useCase("content://notes/backup.notebk", "passphrase".toCharArray())

        assertThat(result).isEqualTo(Outcome.Success(expected))
    }
}

/** In-memory notes store shared by the fakes below, standing in for Room. */
private class FakeNotesStore {
    val notes = ArrayList<NoteEntity>()
    val messages = ArrayList<NoteMessageEntity>()
    val attachments = ArrayList<NoteAttachmentEntity>()

    /** Attachment UUID -> PLAINTEXT bytes (the fake resolver's "decryption"). */
    val attachmentBytes = HashMap<String, ByteArray>()
    var nextNoteId = 1L
    var nextMessageId = 1L
    var nextAttachmentId = 1L

    /** Simulates a fresh install: every row and every byte gone. */
    fun clear() {
        notes.clear()
        messages.clear()
        attachments.clear()
        attachmentBytes.clear()
        nextNoteId = 1L
        nextMessageId = 1L
        nextAttachmentId = 1L
    }
}

private class FakeNoteRepository(private val store: FakeNotesStore) : NoteRepository {

    override fun observeAllNotes(): Flow<List<Note>> =
        flowOf(store.notes.map { it.toDomain() })

    override fun observeNote(noteId: Long): Flow<Note?> =
        flowOf(store.notes.firstOrNull { it.id == noteId }?.toDomain())

    override fun observeMessagesWindow(noteId: Long, limit: Flow<Int>): Flow<NoteMessageWindow> =
        flowOf(NoteMessageWindow())

    override fun observeMessages(noteId: Long): Flow<List<NoteMessage>> =
        flowOf(messagesFor(noteId))

    override suspend fun findNoteById(noteId: Long): Note? =
        store.notes.firstOrNull { it.id == noteId }?.toDomain()

    override suspend fun findNoteByUuid(uuid: String): Note? =
        store.notes.firstOrNull { it.uuid == uuid }?.toDomain()

    override suspend fun findMessageById(messageId: Long): NoteMessage? {
        val entity = store.messages.firstOrNull { it.id == messageId } ?: return null
        return entity.toDomain(attachmentsFor(entity.id))
    }

    override suspend fun findMessageByUuid(uuid: String): NoteMessage? {
        val entity = store.messages.firstOrNull { it.uuid == uuid } ?: return null
        return entity.toDomain(attachmentsFor(entity.id))
    }

    override suspend fun findAttachmentById(attachmentId: Long): Attachment? =
        store.attachments.firstOrNull { it.id == attachmentId }?.toDomain()

    override suspend fun findAttachmentByUuid(uuid: String): Attachment? =
        store.attachments.firstOrNull { it.uuid == uuid }?.toDomain()

    override suspend fun attachmentsForMessage(messageId: Long): List<Attachment> =
        attachmentsFor(messageId)

    override suspend fun attachmentsForNote(noteId: Long): List<Attachment> {
        val messageIds = store.messages.filter { it.noteId == noteId }.map { it.id }.toSet()
        return store.attachments.filter { it.noteMessageId in messageIds }.map { it.toDomain() }
    }

    override suspend fun createNote(
        title: String,
        uuid: String,
        createdAt: Long,
        updatedAt: Long,
    ): Note {
        val entity = NoteEntity(
            id = store.nextNoteId++,
            uuid = uuid,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessageAt = null,
            lastMessagePreview = null,
            draft = null,
        )
        store.notes += entity
        return entity.toDomain()
    }

    override suspend fun renameNote(noteId: Long, title: String) {
        replaceNote(noteId) { it.copy(title = title, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun deleteNote(noteId: Long) {
        val messageIds = store.messages.filter { it.noteId == noteId }.map { it.id }.toSet()
        store.attachments.removeAll { it.noteMessageId in messageIds }
        store.messages.removeAll { it.noteId == noteId }
        store.notes.removeAll { it.id == noteId }
    }

    override suspend fun setPinned(noteId: Long, pinned: Boolean) {
        replaceNote(noteId) { it.copy(pinned = pinned, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun setArchived(noteId: Long, archived: Boolean) {
        replaceNote(noteId) { it.copy(archived = archived, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun setDraft(noteId: Long, draft: String?) {
        replaceNote(noteId) { it.copy(draft = draft) }
    }

    override suspend fun setBubbleColor(noteId: Long, colorArgb: Int?) {
        replaceNote(noteId) {
            it.copy(bubbleColorArgb = colorArgb, updatedAt = System.currentTimeMillis())
        }
    }

    override suspend fun insertMessage(
        noteId: Long,
        body: String,
        uuid: String,
        date: Long,
    ): NoteMessage {
        val entity = NoteMessageEntity(
            id = store.nextMessageId++,
            uuid = uuid,
            noteId = noteId,
            body = body,
            date = date,
        )
        store.messages += entity
        refreshPreview(noteId)
        return entity.toDomain()
    }

    override suspend fun deleteMessage(messageId: Long) {
        val message = store.messages.firstOrNull { it.id == messageId } ?: return
        store.attachments.removeAll { it.noteMessageId == messageId }
        store.messages.removeAll { it.id == messageId }
        refreshPreview(message.noteId)
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
        val message = store.messages.firstOrNull { it.id == messageId }
            ?: return Outcome.Failure(AppError.NotFound("note message"))
        val entity = NoteAttachmentEntity(
            id = store.nextAttachmentId++,
            uuid = uuid,
            noteMessageId = messageId,
            mimeType = mimeType,
            fileName = fileName,
            sizeBytes = plaintext.size.toLong(),
            localUri = "/fake/notes/$uuid",
            width = width,
            height = height,
            durationMs = durationMs,
        )
        store.attachments += entity
        store.attachmentBytes[uuid] = plaintext.copyOf()
        refreshPreview(message.noteId)
        return Outcome.Success(entity.toDomain())
    }

    override suspend fun deleteAttachment(attachmentId: Long) {
        val attachment = store.attachments.firstOrNull { it.id == attachmentId } ?: return
        store.attachments.removeAll { it.id == attachmentId }
        store.attachmentBytes.remove(attachment.uuid)
    }

    override suspend fun listAllNotes(): List<Note> =
        store.notes.sortedBy { it.id }.map { it.toDomain() }

    override suspend fun listAllMessages(): List<NoteMessage> =
        store.messages.sortedBy { it.id }.map { it.toDomain(attachmentsFor(it.id)) }

    override suspend fun listAllAttachments(): List<Attachment> =
        store.attachments.sortedBy { it.id }.map { it.toDomain() }

    private fun attachmentsFor(messageId: Long): List<Attachment> =
        store.attachments.filter { it.noteMessageId == messageId }.map { it.toDomain() }

    private fun messagesFor(noteId: Long): List<NoteMessage> =
        store.messages.filter { it.noteId == noteId }.map { it.toDomain(attachmentsFor(it.id)) }

    private fun replaceNote(noteId: Long, transform: (NoteEntity) -> NoteEntity) {
        val index = store.notes.indexOfFirst { it.id == noteId }
        if (index >= 0) store.notes[index] = transform(store.notes[index])
    }

    /**
     * Preview recompute, mirroring the real repository's rule closely enough for the
     * round-trip test: the newest entry wins, body first, else an attachment label.
     */
    private fun refreshPreview(noteId: Long) {
        val latest = store.messages.filter { it.noteId == noteId }.maxByOrNull { it.date }
        replaceNote(noteId) { note ->
            if (latest == null) {
                note.copy(lastMessageAt = null, lastMessagePreview = null)
            } else {
                val preview = if (latest.body.isNotBlank()) {
                    latest.body
                } else {
                    val first = attachmentsFor(latest.id).firstOrNull()
                    if (first == null) null else "file: ${first.fileName ?: first.mimeType}"
                }
                note.copy(lastMessageAt = latest.date, lastMessagePreview = preview)
            }
        }
    }
}

private class FakeNoteDao(private val store: FakeNotesStore) : NoteDao {

    override fun observeAll(): Flow<List<NoteEntity>> = flowOf(store.notes)

    override fun observeById(id: Long): Flow<NoteEntity?> =
        flowOf(store.notes.firstOrNull { it.id == id })

    override suspend fun findById(id: Long): NoteEntity? =
        store.notes.firstOrNull { it.id == id }

    override suspend fun findByUuid(uuid: String): NoteEntity? =
        store.notes.firstOrNull { it.uuid == uuid }

    override suspend fun listAll(): List<NoteEntity> = store.notes.toList()

    override suspend fun insert(note: NoteEntity): Long {
        val entity = note.copy(id = store.nextNoteId++)
        store.notes += entity
        return entity.id
    }

    override suspend fun update(note: NoteEntity) {
        val index = store.notes.indexOfFirst { it.id == note.id }
        if (index >= 0) store.notes[index] = note
    }

    override suspend fun deleteById(id: Long) {
        store.notes.removeAll { it.id == id }
    }

    override suspend fun rename(id: Long, title: String, updatedAt: Long) {
        replace(id) { it.copy(title = title, updatedAt = updatedAt) }
    }

    override suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long) {
        replace(id) { it.copy(pinned = pinned, updatedAt = updatedAt) }
    }

    override suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long) {
        replace(id) { it.copy(archived = archived, updatedAt = updatedAt) }
    }

    override suspend fun setDraft(id: Long, draft: String?) {
        replace(id) { it.copy(draft = draft) }
    }

    override suspend fun setBubbleColor(id: Long, colorArgb: Int?, updatedAt: Long) {
        replace(id) { it.copy(bubbleColorArgb = colorArgb, updatedAt = updatedAt) }
    }

    override suspend fun setPreview(id: Long, at: Long?, preview: String?) {
        replace(id) { it.copy(lastMessageAt = at, lastMessagePreview = preview) }
    }

    private fun replace(id: Long, transform: (NoteEntity) -> NoteEntity) {
        val index = store.notes.indexOfFirst { it.id == id }
        if (index >= 0) store.notes[index] = transform(store.notes[index])
    }
}

private class FakeNoteAttachmentDao(private val store: FakeNotesStore) : NoteAttachmentDao {

    override suspend fun insert(attachment: NoteAttachmentEntity): Long {
        val entity = attachment.copy(id = store.nextAttachmentId++)
        store.attachments += entity
        return entity.id
    }

    override suspend fun findForMessage(messageId: Long): List<NoteAttachmentEntity> =
        store.attachments.filter { it.noteMessageId == messageId }

    override suspend fun findForMessages(messageIds: List<Long>): List<NoteAttachmentEntity> =
        store.attachments.filter { it.noteMessageId in messageIds }

    override suspend fun findById(id: Long): NoteAttachmentEntity? =
        store.attachments.firstOrNull { it.id == id }

    override suspend fun findByUuid(uuid: String): NoteAttachmentEntity? =
        store.attachments.firstOrNull { it.uuid == uuid }

    override suspend fun listAll(): List<NoteAttachmentEntity> =
        store.attachments.sortedBy { it.id }

    override suspend fun findForNote(noteId: Long): List<NoteAttachmentEntity> {
        val messageIds = store.messages.filter { it.noteId == noteId }.map { it.id }.toSet()
        return store.attachments.filter { it.noteMessageId in messageIds }
    }

    override suspend fun findLocalUrisForNote(noteId: Long): List<String> =
        findForNote(noteId).map { it.localUri }

    override suspend fun deleteById(id: Long) {
        store.attachments.removeAll { it.id == id }
    }

    override suspend fun deleteForMessage(messageId: Long) {
        store.attachments.removeAll { it.noteMessageId == messageId }
    }
}

private class FakeNoteAttachmentResolver(private val store: FakeNotesStore) : NoteAttachmentResolver {

    override suspend fun decryptToMemory(attachment: Attachment): Outcome<ByteArray> {
        val entity = store.attachments.firstOrNull { it.id == attachment.id }
            ?: return Outcome.Failure(AppError.NotFound("note attachment"))
        val bytes = store.attachmentBytes[entity.uuid]
            ?: return Outcome.Failure(AppError.NotFound("note attachment bytes"))
        return Outcome.Success(bytes.copyOf())
    }

    override suspend fun decryptToPlaybackFile(attachment: Attachment): Outcome<File> =
        Outcome.Failure(AppError.NotFound("playback not supported in tests"))

    override fun releasePlaybackFile(file: File) = Unit

    override suspend fun decryptToStream(
        attachment: Attachment,
        output: OutputStream,
    ): Outcome<Unit> {
        return when (val decrypted = decryptToMemory(attachment)) {
            is Outcome.Success -> {
                output.write(decrypted.value)
                output.flush()
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> decrypted
        }
    }
}
