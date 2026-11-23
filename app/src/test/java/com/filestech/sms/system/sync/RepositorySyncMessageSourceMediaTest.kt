package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.e2e.E2eMediaKeyMessage
import com.filestech.sms.e2e.E2eMediaKeyStore
import com.filestech.sms.e2e.E2eMediaResolver
import com.filestech.sms.e2e.FakeE2eMediaKeyDao
import com.filestech.sms.sync.AttachmentOpenResult
import com.filestech.sms.sync.SyncMessageSource
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Round 48 — the sync source's encrypted-media behavior: pending
 * messages are WITHHELD (the desktop must never see a ciphertext
 * part), a withheld message re-appears once its key lands, a
 * failed message syncs with `decrypt_failed`, a keyed message
 * syncs with the ORIGINAL attachment metadata, and
 * [AttachmentOpenResult] serves DECRYPTED bytes. The resolver is
 * real (real cipher, real store over the fake DAO); the
 * repository is mocked, mirroring the Slice 4 harness.
 */
class RepositorySyncMessageSourceMediaTest {

    @TempDir
    lateinit var tempDir: File

    private val context = mockk<Context>()
    private val repository = mockk<ConversationRepository>()
    private val messageDao = mockk<MessageDao>()
    private val mediaKeyDao = FakeE2eMediaKeyDao()
    private val mediaKeys = E2eMediaKeyStore(mediaKeyDao)
    private val resolver = E2eMediaResolver(
        context = context,
        mediaKeys = mediaKeys,
        cipher = AeadCipher(),
        messageDao = messageDao,
        io = Dispatchers.Unconfined,
    )

    private val source: SyncMessageSource = RepositorySyncMessageSource(
        context = context,
        repository = repository,
        defaultSmsAppChecker = mockk(),
        envoyerMessage = mockk(),
        retrySend = mockk(),
        sendReaction = mockk(),
        settings = mockk(),
        scheduledMessageRepository = mockk(),
        scheduleMessageUseCase = mockk(),
        deleteScheduledMessage = mockk(),
        contactsRepository = mockk(),
        blockNumber = mockk(),
        unblockNumber = mockk(),
        mediaResolver = resolver,
    )

    @BeforeEach
    fun stubDirs() {
        every { context.cacheDir } returns tempDir
        coEvery { messageDao.markMediaDecryptFailed(any()) } just Runs
    }

    private fun mediaAttachment(
        localUri: String = File(tempDir, "relay-media-$FILE_ID.bin").absolutePath,
        sizeBytes: Long = 999L,
    ) = Attachment(
        id = 12L,
        messageId = 789L,
        mimeType = "application/octet-stream",
        fileName = "relay-media-$FILE_ID.bin",
        sizeBytes = sizeBytes,
        localUri = localUri,
        width = null,
        height = null,
        durationMs = null,
    )

    private fun mediaMessage(
        id: Long = 789L,
        encrypted: Boolean = true,
        decryptFailed: Boolean = false,
        attachments: List<Attachment> = listOf(mediaAttachment()),
    ) = Message(
        id = id,
        conversationId = 123L,
        address = "+15551234567",
        body = "",
        type = Message.Type.MMS,
        direction = Message.Direction.INCOMING,
        date = 1_700_000_000_000L,
        dateSent = null,
        read = false,
        starred = false,
        status = Message.Status.RECEIVED,
        errorCode = null,
        attachmentsCount = attachments.size,
        subId = null,
        scheduledAt = null,
        attachments = attachments,
        encrypted = encrypted,
        decryptFailed = decryptFailed,
    )

    private suspend fun storeKey(plaintext: ByteArray = PHOTO_BYTES) {
        mediaKeys.store(
            E2eMediaKeyMessage.MediaKey(
                id = FILE_ID,
                key = KEY_BYTES,
                mime = "image/jpeg",
                sizeBytes = plaintext.size.toLong(),
                sha256Hex = sha256Hex(plaintext),
                fileName = "photo.jpg",
            ),
        )
    }

    @Test
    fun `messagesAfter withholds a pending media message`() = runTest {
        coEvery { repository.messagesAfter(123L, 0L) } returns listOf(mediaMessage())

        val synced = source.messagesAfter(123L, 0L)

        assertThat(synced).isEmpty()
    }

    @Test
    fun `messagesBefore withholds a pending media message`() = runTest {
        coEvery { repository.messagesBefore(123L, 0L, 50) } returns listOf(mediaMessage())

        val synced = source.messagesBefore(123L, 0L, 50)

        assertThat(synced).isEmpty()
    }

    @Test
    fun `messagesAfter re-includes a withheld message once its key arrives`() = runTest {
        coEvery { repository.messagesAfter(123L, 0L) } returns listOf(mediaMessage())
        coEvery { repository.messagesBefore(123L, 790L, 1) } returns listOf(mediaMessage())

        // First pass: withheld (the withhold is recorded).
        assertThat(source.messagesAfter(123L, 0L)).isEmpty()

        // The key lands; no new messages arrive — the fresh
        // query is now empty, so only the re-inclusion can
        // surface the message.
        storeKey()
        coEvery { repository.messagesAfter(123L, 0L) } returns emptyList()

        val synced = source.messagesAfter(123L, 0L)
        assertThat(synced).hasSize(1)
        assertThat(synced[0].id).isEqualTo("789")
        assertThat(synced[0].encrypted).isTrue()
        assertThat(synced[0].decryptFailed).isFalse()
        assertThat(synced[0].attachments[0].mimeType).isEqualTo("image/jpeg")
        assertThat(synced[0].attachments[0].fileName).isEqualTo("photo.jpg")
        assertThat(synced[0].attachments[0].sizeBytes).isEqualTo(PHOTO_BYTES.size.toLong())
    }

    @Test
    fun `a failed media message syncs with decryptFailed set`() = runTest {
        coEvery { repository.messagesAfter(123L, 0L) } returns
            listOf(mediaMessage(decryptFailed = true))

        val synced = source.messagesAfter(123L, 0L)

        assertThat(synced).hasSize(1)
        assertThat(synced[0].encrypted).isTrue()
        assertThat(synced[0].decryptFailed).isTrue()
        // No key: the stored ciphertext metadata maps as-is.
        assertThat(synced[0].attachments[0].mimeType).isEqualTo("application/octet-stream")
    }

    @Test
    fun `a keyed media message syncs with the original attachment metadata`() = runTest {
        storeKey()
        coEvery { repository.messagesAfter(123L, 0L) } returns listOf(mediaMessage())

        val synced = source.messagesAfter(123L, 0L)

        assertThat(synced).hasSize(1)
        assertThat(synced[0].attachments[0].mimeType).isEqualTo("image/jpeg")
        assertThat(synced[0].attachments[0].fileName).isEqualTo("photo.jpg")
        assertThat(synced[0].attachments[0].sizeBytes).isEqualTo(PHOTO_BYTES.size.toLong())
    }

    @Test
    fun `openAttachment on an encrypted part serves plaintext bytes`() = runTest {
        val cipher = AeadCipher()
        val ciphertext = when (val encrypted = cipher.encryptRaw(KEY_BYTES, PHOTO_BYTES)) {
            is Outcome.Success -> encrypted.value
            is Outcome.Failure -> error("test setup: encrypt failed: ${encrypted.error}")
        }
        val file = File(tempDir, "relay-media-$FILE_ID.bin").apply { writeBytes(ciphertext) }
        storeKey()
        coEvery { repository.findAttachmentById(12L) } returns
            mediaAttachment(localUri = file.absolutePath, sizeBytes = ciphertext.size.toLong())
        coEvery { repository.findMessageById(789L) } returns mediaMessage()
        coEvery { repository.findById(123L) } returns conversation()

        val opened = source.openAttachment(12L, 789L)

        assertThat(opened).isInstanceOf(AttachmentOpenResult.Opened::class.java)
        val data = (opened as AttachmentOpenResult.Opened).data
        // The PLAINTEXT size: the stream cap is enforced on it.
        assertThat(data.sizeBytes).isEqualTo(PHOTO_BYTES.size.toLong())
        assertThat(data.stream.use { it.readBytes() }).isEqualTo(PHOTO_BYTES)
    }

    @Test
    fun `openAttachment on a keyless part answers Unreadable`() = runTest {
        val file = File(tempDir, "relay-media-$FILE_ID.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        coEvery { repository.findAttachmentById(12L) } returns mediaAttachment(localUri = file.absolutePath)
        coEvery { repository.findMessageById(789L) } returns mediaMessage()
        coEvery { repository.findById(123L) } returns conversation()

        assertThat(source.openAttachment(12L, 789L))
            .isEqualTo(AttachmentOpenResult.Unreadable)
    }

    private companion object {
        const val FILE_ID = "0123456789abcdef"
        val KEY_BYTES: ByteArray = ByteArray(32) { it.toByte() }
        val PHOTO_BYTES: ByteArray = "photo bytes, encrypted on the wire".toByteArray(Charsets.UTF_8)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
