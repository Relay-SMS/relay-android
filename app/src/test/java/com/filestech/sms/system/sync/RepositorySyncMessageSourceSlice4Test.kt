package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.usecase.BlockNumberUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.domain.usecase.UnblockNumberUseCase
import com.filestech.sms.sync.AttachmentOpenResult
import com.filestech.sms.sync.SendAttachmentInput
import com.filestech.sms.sync.SendResult
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.UploadResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Slice 4 — the glue's attachment-byte behavior: download resolve +
 * guards, upload staging (verify, sanitization, TTL sweep), and the
 * media-send mapping (dedup-first ordering, `upload_expired`). The
 * staging tests use a real temp `cacheDir` — file IO is plain JVM.
 * The `content://` download branch needs a `ContentResolver` and is
 * device-only, like the sticker render (which returns null here, so
 * only its failure mapping is pinned).
 */
class RepositorySyncMessageSourceSlice4Test {

    @TempDir
    lateinit var tempDir: File

    private val context = mockk<Context>()
    private val repository = mockk<ConversationRepository>()
    private val defaultSmsAppChecker = mockk<DefaultSmsAppChecker>()
    private val envoyer = mockk<EnvoyerMessageUseCase>()
    private val retrySend = mockk<RetrySendUseCase>()
    private val sendReaction = mockk<SendReactionUseCase>()
    private val settings = mockk<AppSettingsSource>()
    private val scheduledMessages = mockk<ScheduledMessageRepository>()
    private val scheduleUseCase = mockk<ScheduleMessageUseCase>()
    private val deleteScheduled = mockk<DeleteScheduledMessageUseCase>()
    private val contactsRepository = mockk<ContactRepository>()
    private val blockNumber = mockk<BlockNumberUseCase>()
    private val unblockNumber = mockk<UnblockNumberUseCase>()

    // Typed as the interface: the 4-arg `sendMessage` calls below
    // pick up the interface's `attachments = null` default (an
    // override may not redeclare it).
    private val source: SyncMessageSource = RepositorySyncMessageSource(
        context = context,
        repository = repository,
        defaultSmsAppChecker = defaultSmsAppChecker,
        envoyerMessage = envoyer,
        retrySend = retrySend,
        sendReaction = sendReaction,
        settings = settings,
        scheduledMessageRepository = scheduledMessages,
        scheduleMessageUseCase = scheduleUseCase,
        deleteScheduledMessage = deleteScheduled,
        contactsRepository = contactsRepository,
        blockNumber = blockNumber,
        unblockNumber = unblockNumber,
    )

    @BeforeEach
    fun stubCacheDir() {
        every { context.cacheDir } returns tempDir
    }

    /** The router call the glue makes, with the key passed through. */
    private fun stubRouter(result: Outcome<SendReport>) {
        coEvery {
            envoyer.invoke(any(), any(), any(), any(), any(), any())
        } returns result
    }

    // -- Slice 4: download resolve + guards -----------------------------

    private fun attachment(
        id: Long = 12L,
        messageId: Long = 789L,
        localUri: String,
        sizeBytes: Long = PHOTO_BYTES.size.toLong(),
    ) = Attachment(
        id = id,
        messageId = messageId,
        mimeType = "image/jpeg",
        fileName = "photo.jpg",
        sizeBytes = sizeBytes,
        localUri = localUri,
        width = 1600,
        height = 1200,
        durationMs = null,
    )

    @Test fun `openAttachment opens a file-backed attachment and returns its bytes`() = runTest {
        val file = File(tempDir, "photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        coEvery { repository.findAttachmentById(12L) } returns attachment(localUri = file.absolutePath)
        coEvery { repository.findMessageById(789L) } returns message()
        coEvery { repository.findById(123L) } returns conversation()

        val opened = source.openAttachment(12L, 789L)

        assertThat(opened).isInstanceOf(AttachmentOpenResult.Opened::class.java)
        val data = (opened as AttachmentOpenResult.Opened).data
        assertThat(data.sizeBytes).isEqualTo(PHOTO_BYTES.size.toLong())
        assertThat(data.stream.use { it.readBytes() }).isEqualTo(PHOTO_BYTES)
    }

    @Test fun `openAttachment answers Unknown for a missing attachment`() = runTest {
        coEvery { repository.findAttachmentById(12L) } returns null

        assertThat(source.openAttachment(12L, 789L))
            .isEqualTo(AttachmentOpenResult.Unknown)
        coVerify(exactly = 0) { repository.findMessageById(any()) }
    }

    @Test fun `openAttachment answers Unknown when the attachment belongs to another message`() = runTest {
        coEvery { repository.findAttachmentById(12L) } returns
            attachment(messageId = 555L, localUri = "/tmp/photo.jpg")

        assertThat(source.openAttachment(12L, 789L))
            .isEqualTo(AttachmentOpenResult.Unknown)
        coVerify(exactly = 0) { repository.findMessageById(any()) }
    }

    @Test fun `openAttachment answers Unknown when the message is gone`() = runTest {
        coEvery { repository.findAttachmentById(12L) } returns
            attachment(localUri = "/tmp/photo.jpg")
        coEvery { repository.findMessageById(789L) } returns null

        assertThat(source.openAttachment(12L, 789L))
            .isEqualTo(AttachmentOpenResult.Unknown)
        coVerify(exactly = 0) { repository.findById(any()) }
    }

    @Test fun `openAttachment answers Unknown for a vault conversation`() = runTest {
        coEvery { repository.findAttachmentById(12L) } returns
            attachment(localUri = "/tmp/photo.jpg")
        coEvery { repository.findMessageById(789L) } returns message()
        coEvery { repository.findById(123L) } returns conversation(inVault = true)

        assertThat(source.openAttachment(12L, 789L))
            .isEqualTo(AttachmentOpenResult.Unknown)
    }

    @Test fun `openAttachment answers Unreadable when the file is gone`() = runTest {
        coEvery { repository.findAttachmentById(12L) } returns
            attachment(localUri = File(tempDir, "missing.jpg").absolutePath)
        coEvery { repository.findMessageById(789L) } returns message()
        coEvery { repository.findById(123L) } returns conversation()

        assertThat(source.openAttachment(12L, 789L))
            .isEqualTo(AttachmentOpenResult.Unreadable)
    }

    // -- Slice 4: upload staging ----------------------------------------

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun stagedFile(key: String, name: String): File =
        File(File(tempDir, "sync_uploads/$key"), name)

    /** Stages [bytes] for [key]/[index] in two chunks; returns the final result. */
    private suspend fun stageUpload(
        key: String = CLIENT_KEY,
        index: Int = 0,
        bytes: ByteArray = PHOTO_BYTES,
        mimeType: String = "image/jpeg",
        fileName: String? = "photo.jpg",
        sha256: String = sha256Hex(bytes),
    ): UploadResult {
        val started = source.beginUpload(key, index, mimeType, fileName, bytes.size.toLong(), sha256)
        assertThat(started).isEqualTo(UploadResult.Ok)
        val half = bytes.size / 2
        val first = source.appendUploadChunk(key, index, 0, bytes.copyOfRange(0, half), false)
        assertThat(first).isEqualTo(UploadResult.Ok)
        return source.appendUploadChunk(key, index, half.toLong(), bytes.copyOfRange(half, bytes.size), true)
    }

    @Test fun `beginUpload plus chunks stages a verified file under its sanitized name`() = runTest {
        assertThat(stageUpload()).isEqualTo(UploadResult.Ok)

        val staged = stagedFile(CLIENT_KEY, "0-photo.jpg")
        assertThat(staged.exists()).isTrue()
        assertThat(staged.readBytes()).isEqualTo(PHOTO_BYTES)
        assertThat(stagedFile(CLIENT_KEY, "0.part").exists()).isFalse()
    }

    @Test fun `beginUpload refuses a file over the 280 KB cap`() = runTest {
        val result = source.beginUpload(
            CLIENT_KEY,
            0,
            "image/jpeg",
            "photo.jpg",
            280L * 1024 + 1,
            "ab12",
        )

        assertThat(result).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.UPLOAD_TOO_LARGE,
                message = "This file is too big to send.",
            ),
        )
        assertThat(File(tempDir, "sync_uploads").exists()).isFalse()
    }

    @Test fun `beginUpload refuses an empty file, a blank mime, and a negative index`() = runTest {
        assertThat(
            source.beginUpload(CLIENT_KEY, 0, "image/jpeg", "photo.jpg", 0L, "ab12"),
        ).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.INTERNAL,
                message = "This file couldn't be uploaded.",
            ),
        )
        assertThat(
            source.beginUpload(CLIENT_KEY, 0, "  ", "photo.jpg", 1024L, "ab12"),
        ).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.INTERNAL,
                message = "This file couldn't be uploaded.",
            ),
        )
        assertThat(
            source.beginUpload(CLIENT_KEY, -1, "image/jpeg", "photo.jpg", 1024L, "ab12"),
        ).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.INTERNAL,
                message = "This file couldn't be uploaded.",
            ),
        )
    }

    @Test fun `a chunk at the wrong offset fails the upload`() = runTest {
        assertThat(
            source.beginUpload(
                CLIENT_KEY,
                0,
                "image/jpeg",
                "photo.jpg",
                PHOTO_BYTES.size.toLong(),
                sha256Hex(PHOTO_BYTES),
            ),
        ).isEqualTo(UploadResult.Ok)

        val result = source.appendUploadChunk(CLIENT_KEY, 0, 5L, PHOTO_BYTES, false)

        assertThat(result).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.INTERNAL,
                message = "This file couldn't be uploaded.",
            ),
        )
    }

    @Test fun `a checksum mismatch deletes the partial and fails with upload_bad_checksum`() = runTest {
        val tampered = PHOTO_BYTES.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val result = stageUpload(bytes = tampered, sha256 = sha256Hex(PHOTO_BYTES))

        assertThat(result).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.UPLOAD_BAD_CHECKSUM,
                message = "This file didn't arrive intact. Try again.",
            ),
        )
        assertThat(stagedFile(CLIENT_KEY, "0.part").exists()).isFalse()
        assertThat(stagedFile(CLIENT_KEY, "0-photo.jpg").exists()).isFalse()
    }

    @Test fun `a size mismatch fails with upload_bad_checksum`() = runTest {
        assertThat(
            source.beginUpload(
                CLIENT_KEY,
                0,
                "image/jpeg",
                "photo.jpg",
                PHOTO_BYTES.size.toLong(),
                sha256Hex(PHOTO_BYTES),
            ),
        ).isEqualTo(UploadResult.Ok)

        // Half the promised bytes, marked final.
        val half = PHOTO_BYTES.copyOfRange(0, PHOTO_BYTES.size / 2)
        val result = source.appendUploadChunk(CLIENT_KEY, 0, 0, half, true)

        assertThat(result).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.UPLOAD_BAD_CHECKSUM,
                message = "This file didn't arrive intact. Try again.",
            ),
        )
        assertThat(stagedFile(CLIENT_KEY, "0.part").exists()).isFalse()
    }

    @Test fun `a chunk for an upload never begun fails`() = runTest {
        val result = source.appendUploadChunk(CLIENT_KEY, 0, 0, PHOTO_BYTES, true)

        assertThat(result).isEqualTo(
            UploadResult.Failed(
                code = SyncProtocol.AttachmentUploadError.INTERNAL,
                message = "This file couldn't be uploaded.",
            ),
        )
    }

    @Test fun `a path-traversal file name is reduced to its basename`() = runTest {
        assertThat(stageUpload(fileName = "../../evil.jpg")).isEqualTo(UploadResult.Ok)

        val staged = stagedFile(CLIENT_KEY, "0-evil.jpg")
        assertThat(staged.exists()).isTrue()
        assertThat(staged.readBytes()).isEqualTo(PHOTO_BYTES)
        // Nothing escaped the staging root.
        assertThat(File(tempDir, "evil.jpg").exists()).isFalse()
        assertThat(File(tempDir.parentFile, "evil.jpg").exists()).isFalse()
    }

    @Test fun `a missing file name falls back to attachment-index plus the mime extension`() = runTest {
        assertThat(stageUpload(fileName = null)).isEqualTo(UploadResult.Ok)

        val staged = stagedFile(CLIENT_KEY, "attachment-0.jpg")
        assertThat(staged.exists()).isTrue()
        assertThat(staged.readBytes()).isEqualTo(PHOTO_BYTES)
    }

    @Test fun `two attachments with the same name in one send do not overwrite each other`() = runTest {
        val other = ByteArray(512) { (it % 7).toByte() }
        assertThat(stageUpload(index = 0, bytes = PHOTO_BYTES)).isEqualTo(UploadResult.Ok)
        assertThat(stageUpload(index = 1, bytes = other)).isEqualTo(UploadResult.Ok)

        assertThat(stagedFile(CLIENT_KEY, "0-photo.jpg").readBytes()).isEqualTo(PHOTO_BYTES)
        assertThat(stagedFile(CLIENT_KEY, "1-photo.jpg").readBytes()).isEqualTo(other)
    }

    @Test fun `a traversal client key cannot escape the staging root`() = runTest {
        assertThat(stageUpload(key = "../../escape")).isEqualTo(UploadResult.Ok)

        assertThat(File(tempDir, "escape").exists()).isFalse()
        assertThat(File(tempDir.parentFile, "escape").exists()).isFalse()
        // Every unsafe char became `_`: the upload landed in a flat
        // dir under the staging root, not outside it.
        val staged = stagedFile("______escape", "0-photo.jpg")
        assertThat(staged.exists()).isTrue()
    }

    @Test fun `beginUpload sweeps staging dirs older than 24 h`() = runTest {
        val oldDir = File(tempDir, "sync_uploads/old-key").apply {
            mkdirs()
            File(this, "0-photo.jpg").writeBytes(PHOTO_BYTES)
            setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        }
        val freshDir = File(tempDir, "sync_uploads/fresh-key").apply {
            mkdirs()
            File(this, "0-photo.jpg").writeBytes(PHOTO_BYTES)
        }

        assertThat(
            source.beginUpload(
                CLIENT_KEY,
                0,
                "image/jpeg",
                "photo.jpg",
                PHOTO_BYTES.size.toLong(),
                sha256Hex(PHOTO_BYTES),
            ),
        ).isEqualTo(UploadResult.Ok)

        assertThat(oldDir.exists()).isFalse()
        assertThat(freshDir.exists()).isTrue()
        assertThat(File(freshDir, "0-photo.jpg").exists()).isTrue()
    }

    @Test fun `a re-begin discards the previous upload`() = runTest {
        assertThat(stageUpload()).isEqualTo(UploadResult.Ok)
        assertThat(stagedFile(CLIENT_KEY, "0-photo.jpg").exists()).isTrue()

        assertThat(
            source.beginUpload(
                CLIENT_KEY,
                0,
                "image/jpeg",
                "photo.jpg",
                PHOTO_BYTES.size.toLong(),
                sha256Hex(PHOTO_BYTES),
            ),
        ).isEqualTo(UploadResult.Ok)

        // The completed file is gone until the upload completes again.
        assertThat(stagedFile(CLIENT_KEY, "0-photo.jpg").exists()).isFalse()
    }

    // -- Slice 4: media send --------------------------------------------

    @Test fun `a media send maps staged uploads to payloads and dispatches`() = runTest {
        assertThat(stageUpload()).isEqualTo(UploadResult.Ok)
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageById(789L) } returns message(status = Message.Status.PENDING)
        stubRouter(Outcome.Success(report()))

        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Look at this",
            null,
            listOf(
                SendAttachmentInput(
                    uploadIndex = 0,
                    mimeType = "image/jpeg",
                    fileName = "photo.jpg",
                    width = 1600,
                    height = 1200,
                ),
            ),
        )

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 1) {
            envoyer.invoke(
                any(),
                "Look at this",
                match { payloads ->
                    payloads.size == 1 &&
                        payloads[0].mimeType == "image/jpeg" &&
                        payloads[0].width == 1600 &&
                        payloads[0].height == 1200 &&
                        payloads[0].file.readBytes().contentEquals(PHOTO_BYTES)
                },
                null,
                null,
                CLIENT_KEY,
            )
        }
    }

    @Test fun `a media send with a missing staging file fails upload_expired without touching the router`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null

        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Look at this",
            null,
            listOf(SendAttachmentInput(uploadIndex = 0, mimeType = "image/jpeg", fileName = "photo.jpg")),
        )

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.UPLOAD_EXPIRED,
                message = "The photo didn't reach the phone — send it again.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a seen key returns the stored result without consulting the uploads`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(status = Message.Status.PENDING)

        // No staging exists for this key — the dedup must answer
        // before any upload validation, never `upload_expired`.
        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Look at this",
            null,
            listOf(SendAttachmentInput(uploadIndex = 0, mimeType = "image/jpeg", fileName = "photo.jpg")),
        )

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a seen failed media key re-dispatches the existing row instead of sending anew`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns
            message(status = Message.Status.FAILED)
        coEvery { retrySend.invoke(789L) } returns Outcome.Success(Unit)

        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Look at this",
            null,
            listOf(SendAttachmentInput(uploadIndex = 0, mimeType = "image/jpeg", fileName = "photo.jpg")),
        )

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 1) { retrySend.invoke(789L) }
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a blank body with attachments is allowed`() = runTest {
        assertThat(stageUpload()).isEqualTo(UploadResult.Ok)
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null
        coEvery { repository.findMessageById(789L) } returns message(status = Message.Status.PENDING)
        stubRouter(Outcome.Success(report()))

        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "",
            null,
            listOf(SendAttachmentInput(uploadIndex = 0, mimeType = "image/jpeg", fileName = "photo.jpg")),
        )

        assertThat(result).isEqualTo(SendResult.Sent(messageId = 789L, status = "pending"))
        coVerify(exactly = 1) { envoyer.invoke(any(), "", any(), any(), any(), CLIENT_KEY) }
    }

    @Test fun `an attachment entry with neither kind fails internal`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null

        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Hi",
            null,
            listOf(SendAttachmentInput()),
        )

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The message could not be sent.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `an attachment entry with both kinds fails internal`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null

        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Hi",
            null,
            listOf(SendAttachmentInput(uploadIndex = 0, stickerEmoji = "😂")),
        )

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The message could not be sent.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `a sticker that cannot render fails send_failed`() = runTest {
        coEvery { repository.findById(123L) } returns conversation()
        coEvery { repository.findMessageByClientMessageId(CLIENT_KEY) } returns null

        // On the JVM the sticker render can't run (no Android
        // runtime), so `stickerSendUri` returns null — the failure
        // mapping is what's pinned here, not the render.
        val result = source.sendMessage(
            CLIENT_KEY,
            123L,
            "Hi",
            null,
            listOf(SendAttachmentInput(stickerEmoji = "😂")),
        )

        assertThat(result).isEqualTo(
            SendResult.Failed(
                code = SyncProtocol.MessageFailed.SEND_FAILED,
                message = "That sticker isn't available on the phone.",
            ),
        )
        coVerify(exactly = 0) { envoyer.invoke(any(), any(), any(), any(), any(), any()) }
    }
}

private val PHOTO_BYTES = ByteArray(1024) { (it % 251).toByte() }
