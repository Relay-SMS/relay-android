package com.filestech.sms.sync.client

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.clientmode.NoteSaveAttachment
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.domain.usecase.SendMediaMmsUseCase
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * Client mode C3 — the send manager, driven without a socket: a fake
 * channel records every outbound frame, mock DAOs hold the local rows
 * (the database mock stubs Room 2.8's transaction machinery — executor,
 * suspending-transaction ThreadLocal, begin/setSuccessful/end — so
 * `withTransaction` runs its block directly), and a mock schedule
 * repository holds the client-created schedules.
 */
class ClientSendManagerTest {

    private class FakeChannel : ClientSyncChannel {
        override val state: StateFlow<ClientConnectionState> =
            MutableStateFlow(ClientConnectionState.Disconnected)
        override val frames: SharedFlow<ServerFrame> =
            MutableSharedFlow(extraBufferCapacity = 64)
        val sent = mutableListOf<String>()
        var sendResult = true

        override suspend fun send(text: String): Boolean {
            sent += text
            return sendResult
        }
    }

    private class Harness(
        val manager: ClientSendManager,
        val channel: FakeChannel,
        val conversationDao: ConversationDao,
        val messageDao: MessageDao,
        val scheduledRepo: ScheduledMessageRepository,
        /** Client mode C5 — the media uploader (a relaxed mock: uploads succeed unless stubbed). */
        val uploader: ClientAttachmentUploader,
        /** Client mode C5 — the optimistic attachment rows + the media retry lookup. */
        val attachmentDao: AttachmentDao,
        /** Client mode C5 — the temp `filesDir` the durable copies land in. */
        val filesDir: File,
        /** Round 36 phase 2 — the note store + resolver (the note save tests seed them). */
        val noteRepo: NoteRepository,
        val noteAttachmentResolver: NoteAttachmentResolver,
    ) {
        /** A staged file (the composer's copy) — outside the durable root, so the send copies it. */
        fun stagedFile(name: String, sizeBytes: Int): File =
            File(filesDir, "staging/$name").also {
                it.parentFile?.mkdirs()
                it.writeBytes(ByteArray(sizeBytes) { 7 })
            }
    }

    // Client mode C5 — a TestScope extension now: the manager's
    // application scope IS the test scope, so the `upload_expired`
    // re-upload (launched there) runs under `advanceUntilIdle`.
    private fun TestScope.harness(
        channelOnline: Boolean = true,
        mode: DeviceMode = DeviceMode.CLIENT,
    ): Harness {
        val channel = FakeChannel().also { it.sendResult = channelOnline }
        val database = mockk<AppDatabase>()
        // Room 2.8's `withTransaction` (verified from the 2.8.5 jar)
        // does NOT consult `inTransaction()` on the database: it
        // resolves the transaction executor, reads the suspending
        // transaction ThreadLocal, then runs
        // begin/setSuccessful/end around the block on the executor.
        // A strict mock needs all five stubbed, or the first
        // transaction path throws MockKException. The direct executor
        // runs the block on the calling thread.
        every { database.transactionExecutor } returns Executor { it.run() }
        every { database.suspendingTransactionContext } returns ThreadLocal()
        every { database.beginTransaction() } just Runs
        every { database.setTransactionSuccessful() } just Runs
        every { database.endTransaction() } just Runs
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val scheduledRepo = mockk<ScheduledMessageRepository>(relaxed = true)
        val settings = mockk<AppSettingsSource>()
        coEvery { settings.hydratedOrNull() } returns AppSettings(mode = mode)
        val uploader = mockk<ClientAttachmentUploader>(relaxed = true)
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val filesDir = Files.createTempDirectory("c5-send").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        // Round 36 phase 2 — the note store + resolver (the note save tests seed them).
        val noteRepo = mockk<NoteRepository>(relaxed = true)
        val noteAttachmentResolver = mockk<NoteAttachmentResolver>(relaxed = true)
        val manager = ClientSendManager(
            channel,
            database,
            conversationDao,
            messageDao,
            scheduledRepo,
            settings,
            uploader,
            attachmentDao,
            context,
            this,
            noteRepo,
            noteAttachmentResolver,
        )
        return Harness(
            manager,
            channel,
            conversationDao,
            messageDao,
            scheduledRepo,
            uploader,
            attachmentDao,
            filesDir,
            noteRepo,
            noteAttachmentResolver,
        )
    }

    private fun conversation(
        id: Long = 7L,
        hubId: Long? = 7L,
        lastMessageAt: Long = 1000L,
    ) = ConversationEntity(
        id = id,
        threadId = null,
        addressesCsv = "+33612345678",
        displayName = null,
        hubConversationId = hubId,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = null,
    )

    private fun message(
        id: Long = 50L,
        conversationId: Long = 7L,
        hubId: Long? = null,
        key: String? = null,
        status: MessageStatus = MessageStatus.PENDING,
        direction: MessageDirection = MessageDirection.OUTGOING,
        replyTo: Long? = null,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        telephonyUri = null,
        address = "+33612345678",
        body = "hello",
        type = MessageType.SMS,
        direction = direction,
        date = 1700000000000,
        dateSent = null,
        read = true,
        status = status,
        hubMessageId = hubId,
        replyToMessageId = replyTo,
        clientMessageId = key,
    )

    private fun schedule(
        id: Long = 9L,
        key: String = "sched-key",
        state: ScheduledMessage.State = ScheduledMessage.State.PENDING,
    ) = ScheduledMessage(
        id = id,
        conversationId = 7L,
        addresses = listOf(PhoneAddress.of("+33612345678")),
        body = "later",
        scheduledAt = 1700000000000,
        subId = null,
        state = state,
        createdAt = 1699999999999,
        clientMessageId = key,
    )

    // -- send -------------------------------------------------------

    @Test fun `sendText emits a send_message frame with the key, hub id and body`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L

        val result = h.manager.sendText(7L, emptyList(), "hello", null, "key-1")

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        assertThat(h.channel.sent).hasSize(1)
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("send_message")
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        assertThat(frame["conversation_id"]?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(frame["body"]?.jsonPrimitive?.content).isEqualTo("hello")
        assertThat(frame.containsKey("reply_to_message_id")).isFalse()
    }

    @Test fun `sendText inserts an optimistic PENDING row carrying the key`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        val inserted = slot<MessageEntity>()
        coEvery { h.messageDao.insert(capture(inserted)) } returns 50L

        h.manager.sendText(7L, emptyList(), "hello", null, "key-1")

        assertThat(inserted.captured.conversationId).isEqualTo(7L)
        assertThat(inserted.captured.direction).isEqualTo(MessageDirection.OUTGOING)
        assertThat(inserted.captured.status).isEqualTo(MessageStatus.PENDING)
        assertThat(inserted.captured.clientMessageId).isEqualTo("key-1")
        assertThat(inserted.captured.hubMessageId).isNull()
        assertThat(inserted.captured.telephonyUri).isNull()
    }

    @Test fun `sendText touches the conversation preview with the new message`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation(lastMessageAt = 1000L)
        coEvery { h.messageDao.insert(any()) } returns 50L
        val updated = slot<ConversationEntity>()
        coEvery { h.conversationDao.update(capture(updated)) } returns Unit

        h.manager.sendText(7L, emptyList(), "hello", null, "key-1")

        assertThat(updated.captured.lastMessagePreview).isEqualTo("hello")
        assertThat(updated.captured.unreadCount).isEqualTo(0)
    }

    @Test fun `sendText resolves the conversation by recipients when no id is given`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findByAddressesCsv(any()) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L

        val result = h.manager.sendText(
            null,
            listOf(PhoneAddress.of("+33612345678")),
            "hello",
            null,
            "key-1",
        )

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        coVerify { h.conversationDao.findByAddressesCsv(any()) }
        assertThat(h.channel.sent.single().asObject()["conversation_id"]?.jsonPrimitive?.content)
            .isEqualTo("7")
    }

    @Test fun `sendText resolves a reply target to its hub id`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.findById(60L) } returns message(id = 60L, hubId = 60L)
        coEvery { h.messageDao.insert(any()) } returns 50L

        h.manager.sendText(7L, emptyList(), "hello", 60L, "key-1")

        val frame = h.channel.sent.single().asObject()
        assertThat(frame["reply_to_message_id"]?.jsonPrimitive?.content).isEqualTo("60")
    }

    @Test fun `sendText omits a reply target that has no hub id`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.findById(60L) } returns message(id = 60L, hubId = null)
        coEvery { h.messageDao.insert(any()) } returns 50L

        h.manager.sendText(7L, emptyList(), "hello", 60L, "key-1")

        assertThat(h.channel.sent.single().asObject().containsKey("reply_to_message_id")).isFalse()
    }

    @Test fun `sendText fails fast offline and marks the row FAILED`() = runTest {
        val h = harness(channelOnline = false)
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L
        coEvery { h.messageDao.findById(50L) } returns message(id = 50L, key = "key-1")
        val updated = slot<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit

        val result = h.manager.sendText(7L, emptyList(), "hello", null, "key-1")

        assertThat(result).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        assertThat(updated.captured.status).isEqualTo(MessageStatus.FAILED)
        assertThat(updated.captured.errorCode).isEqualTo(SendErrorCode.SYNCHRONOUS)
    }

    @Test fun `sendText fails clearly for an unknown conversation and sends no frame`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns null

        val result = h.manager.sendText(7L, emptyList(), "hello", null, "key-1")

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
        coVerify(exactly = 0) { h.messageDao.insert(any()) }
    }

    @Test fun `sendText fails clearly for a conversation that is not synced`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation(hubId = null)

        val result = h.manager.sendText(7L, emptyList(), "hello", null, "key-1")

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
    }

    // -- media sends (C5) -------------------------------------------

    @Test fun `sendMedia uploads then sends send_message with the attachments array`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L
        val photo = h.stagedFile("photo.png", 1024)
        val uploaded = slot<ByteArray>()
        coEvery {
            h.uploader.upload(any(), any(), capture(uploaded), any(), any())
        } returns Unit

        val result = h.manager.sendMedia(
            7L,
            emptyList(),
            "hi",
            listOf(
                SendMediaMmsUseCase.AttachmentPayload(photo, "image/png", width = 100, height = 200),
            ),
            null,
            "key-1",
        )

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        // The already-small photo uploads as-is: the exact staged bytes.
        assertThat(uploaded.captured).isEqualTo(photo.readBytes())
        coVerify { h.uploader.upload("key-1", 0, any(), "image/png", "photo.png") }
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("send_message")
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        val attachments = frame["attachments"]?.jsonArray
        assertThat(attachments).hasSize(1)
        val entry = attachments!!.single().jsonObject
        assertThat(entry["upload_index"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(entry["mime_type"]?.jsonPrimitive?.content).isEqualTo("image/png")
        assertThat(entry["file_name"]?.jsonPrimitive?.content).isEqualTo("photo.png")
        assertThat(entry["width"]?.jsonPrimitive?.content).isEqualTo("100")
        assertThat(entry["height"]?.jsonPrimitive?.content).isEqualTo("200")
    }

    @Test fun `sendMedia inserts an optimistic MMS row and attachment rows with durable localUris`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        val inserted = slot<MessageEntity>()
        coEvery { h.messageDao.insert(capture(inserted)) } returns 50L
        val insertedAttachments = slot<List<AttachmentEntity>>()
        coEvery { h.attachmentDao.insertAll(capture(insertedAttachments)) } returns Unit
        val updatedConv = slot<ConversationEntity>()
        coEvery { h.conversationDao.update(capture(updatedConv)) } returns Unit
        val photo = h.stagedFile("photo.png", 1024)

        h.manager.sendMedia(
            7L,
            emptyList(),
            "",
            listOf(SendMediaMmsUseCase.AttachmentPayload(photo, "image/png")),
            null,
            "key-1",
        )

        assertThat(inserted.captured.type).isEqualTo(MessageType.MMS)
        assertThat(inserted.captured.attachmentsCount).isEqualTo(1)
        assertThat(inserted.captured.status).isEqualTo(MessageStatus.PENDING)
        assertThat(inserted.captured.clientMessageId).isEqualTo("key-1")
        val row = insertedAttachments.captured.single()
        assertThat(row.messageId).isEqualTo(50L)
        assertThat(row.mimeType).isEqualTo("image/png")
        assertThat(row.fileName).isEqualTo("photo.png")
        assertThat(row.sizeBytes).isEqualTo(1024L)
        // A durable COPY under mms_attachments — not the staged file
        // (the composer keeps it for retry), with the same bytes.
        assertThat(row.localUri).startsWith(File(h.filesDir, "mms_attachments").absolutePath)
        assertThat(File(row.localUri).readBytes()).isEqualTo(photo.readBytes())
        assertThat(photo.exists()).isTrue()
        // Caption-less: the preview is the media label, with the
        // ORIGINAL file name (not the durable copy's `out-…` name).
        assertThat(updatedConv.captured.lastMessagePreview).isEqualTo("🖼️ photo.png")
    }

    @Test fun `sendMedia budget refusal fails before any row exists`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        val video = h.stagedFile("huge.mp4", 300 * 1024)

        val result = h.manager.sendMedia(
            7L,
            emptyList(),
            "",
            listOf(SendMediaMmsUseCase.AttachmentPayload(video, "video/mp4")),
            null,
            "key-1",
        )

        assertThat(result).isEqualTo(Outcome.Failure(AppError.ClientMmsCapReached))
        coVerify(exactly = 0) { h.messageDao.insert(any()) }
        coVerify(exactly = 0) { h.uploader.upload(any(), any(), any(), any(), any()) }
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `sendMedia upload failure fails the row and still succeeds`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L
        coEvery { h.messageDao.findById(50L) } returns message(id = 50L, key = "key-1")
        val updated = slot<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit
        coEvery { h.uploader.upload(any(), any(), any(), any(), any()) } throws
            ClientAttachmentUploader.UploadError("upload_failed", "phone refused")
        val photo = h.stagedFile("photo.png", 1024)

        val result = h.manager.sendMedia(
            7L,
            emptyList(),
            "hi",
            listOf(SendMediaMmsUseCase.AttachmentPayload(photo, "image/png")),
            null,
            "key-1",
        )

        // The row exists now: it fails (the bubble offers the retry)
        // and the return is still a success — the composer clears.
        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        assertThat(updated.captured.status).isEqualTo(MessageStatus.FAILED)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `sendMedia offline at send_message fails the row and reports offline`() = runTest {
        val h = harness(channelOnline = false)
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L
        coEvery { h.messageDao.findById(50L) } returns message(id = 50L, key = "key-1")
        val updated = slot<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit
        val photo = h.stagedFile("photo.png", 1024)

        val result = h.manager.sendMedia(
            7L,
            emptyList(),
            "hi",
            listOf(SendMediaMmsUseCase.AttachmentPayload(photo, "image/png")),
            null,
            "key-1",
        )

        assertThat(result).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        assertThat(updated.captured.status).isEqualTo(MessageStatus.FAILED)
        // The upload happened (the mock uploader does not use the
        // channel) — only the send_message frame was lost.
        coVerify { h.uploader.upload("key-1", 0, any(), "image/png", "photo.png") }
    }

    @Test fun `sendMedia with empty attachments delegates to sendText`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L

        val result = h.manager.sendMedia(7L, emptyList(), "hello", emptyList(), null, "key-1")

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("send_message")
        assertThat(frame.containsKey("attachments")).isFalse()
        coVerify(exactly = 0) { h.uploader.upload(any(), any(), any(), any(), any()) }
    }

    @Test fun `sendMedia fails clearly for an unknown conversation and uploads nothing`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns null
        val photo = h.stagedFile("photo.png", 1024)

        val result = h.manager.sendMedia(
            7L,
            emptyList(),
            "hi",
            listOf(SendMediaMmsUseCase.AttachmentPayload(photo, "image/png")),
            null,
            "key-1",
        )

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 0) { h.uploader.upload(any(), any(), any(), any(), any()) }
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `resend of a media row re-sends the attachments array without re-uploading`() = runTest {
        val h = harness()
        val failed = message(id = 50L, key = "key-1", status = MessageStatus.FAILED)
        coEvery { h.messageDao.findById(50L) } returns failed
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.attachmentDao.findForMessage(50L) } returns listOf(
            AttachmentEntity(
                id = 5L,
                messageId = 50L,
                mimeType = "image/png",
                fileName = "photo.png",
                sizeBytes = 1024L,
                localUri = "/tmp/mms_attachments/out-1.png",
                width = 100,
                height = 200,
            ),
        )

        val result = h.manager.resend(50L)

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        val frame = h.channel.sent.single().asObject()
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        val attachments = frame["attachments"]?.jsonArray
        assertThat(attachments).hasSize(1)
        val entry = attachments!!.single().jsonObject
        assertThat(entry["upload_index"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(entry["mime_type"]?.jsonPrimitive?.content).isEqualTo("image/png")
        assertThat(entry["file_name"]?.jsonPrimitive?.content).isEqualTo("photo.png")
        assertThat(entry["width"]?.jsonPrimitive?.content).isEqualTo("100")
        assertThat(entry["height"]?.jsonPrimitive?.content).isEqualTo("200")
        // No re-upload first: the phone finds its staged upload, or
        // answers `upload_expired` and the failure handler re-uploads.
        coVerify(exactly = 0) { h.uploader.upload(any(), any(), any(), any(), any()) }
    }

    @Test fun `upload_expired re-uploads once and re-sends, then fails on the second`() = runTest {
        val h = harness()
        val pending = message(id = 50L, key = "key-1", status = MessageStatus.PENDING)
        val durable = h.stagedFile("out-1.png", 1024)
        coEvery { h.messageDao.findByClientMessageId("key-1") } returns pending
        coEvery { h.messageDao.findById(50L) } returns pending
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.scheduledRepo.findByClientMessageId("key-1") } returns null
        coEvery { h.attachmentDao.findForMessage(50L) } returns listOf(
            AttachmentEntity(
                id = 5L,
                messageId = 50L,
                mimeType = "image/png",
                fileName = "photo.png",
                sizeBytes = 1024L,
                localUri = durable.absolutePath,
                width = 100,
                height = 200,
            ),
        )
        val updated = mutableListOf<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit

        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("key-1", "7", "upload_expired", "staged upload is gone"),
        )
        advanceUntilIdle()

        // One automatic re-upload + re-send with the same key; the
        // row stays PENDING (no failure update yet).
        coVerify(exactly = 1) { h.uploader.upload("key-1", 0, any(), "image/png", "photo.png") }
        assertThat(h.channel.sent).hasSize(1)
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("send_message")
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        assertThat(frame["attachments"]?.jsonArray).hasSize(1)
        assertThat(updated).isEmpty()
        // The second `upload_expired` spends nothing: the retry is
        // gone, the row fails.
        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("key-1", "7", "upload_expired", "staged upload is gone"),
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { h.uploader.upload(any(), any(), any(), any(), any()) }
        assertThat(updated).hasSize(1)
        assertThat(updated.single().status).isEqualTo(MessageStatus.FAILED)
    }

    @Test fun `audio attachment carries duration_ms in the wire entry`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L
        val audio = h.stagedFile("voice.3gp", 10 * 1024)

        h.manager.sendMedia(
            7L,
            emptyList(),
            "",
            listOf(
                SendMediaMmsUseCase.AttachmentPayload(audio, "audio/3gpp", durationMs = 5_000L),
            ),
            null,
            "key-1",
        )

        val frame = h.channel.sent.single().asObject()
        val entry = frame["attachments"]?.jsonArray!!.single().jsonObject
        assertThat(entry["mime_type"]?.jsonPrimitive?.content).isEqualTo("audio/3gpp")
        assertThat(entry["duration_ms"]?.jsonPrimitive?.content).isEqualTo("5000")
    }

    @Test fun `sendMedia with a caption uses the caption as the conversation preview`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.messageDao.insert(any()) } returns 50L
        val updatedConv = slot<ConversationEntity>()
        coEvery { h.conversationDao.update(capture(updatedConv)) } returns Unit
        val photo = h.stagedFile("photo.png", 1024)

        h.manager.sendMedia(
            7L,
            emptyList(),
            "look!",
            listOf(SendMediaMmsUseCase.AttachmentPayload(photo, "image/png")),
            null,
            "key-1",
        )

        assertThat(updatedConv.captured.lastMessagePreview).isEqualTo("look!")
    }

    // -- acks + failures --------------------------------------------

    @Test fun `onMessageSent stamps the hub id and status on the optimistic row`() = runTest {
        val h = harness()
        coEvery { h.messageDao.findByClientMessageId("key-1") } returns
            message(id = 50L, key = "key-1", status = MessageStatus.PENDING)
        val updated = slot<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit

        h.manager.onMessageSent(SyncProtocol.MessageSent("key-1", "55", "7", "delivered"))

        assertThat(updated.captured.hubMessageId).isEqualTo(55L)
        assertThat(updated.captured.status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(updated.captured.clientMessageId).isEqualTo("key-1")
    }

    @Test fun `onMessageFailed maps the hub_not_default_sms code`() = runTest {
        val h = harness()
        coEvery { h.messageDao.findByClientMessageId("key-1") } returns
            message(id = 50L, key = "key-1", status = MessageStatus.PENDING)
        coEvery { h.scheduledRepo.findByClientMessageId("key-1") } returns null
        val updated = slot<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit

        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("key-1", "7", "hub_not_default_sms", "hub is not the default SMS app"),
        )

        assertThat(updated.captured.status).isEqualTo(MessageStatus.FAILED)
        assertThat(updated.captured.errorCode).isEqualTo(SendErrorCode.CLIENT_HUB_NOT_DEFAULT_SMS)
    }

    @Test fun `onMessageFailed maps blocked_recipient and falls back for unknown codes`() = runTest {
        val h = harness()
        coEvery { h.messageDao.findByClientMessageId("key-1") } returns
            message(id = 50L, key = "key-1", status = MessageStatus.PENDING)
        coEvery { h.messageDao.findByClientMessageId("key-2") } returns
            message(id = 51L, key = "key-2", status = MessageStatus.PENDING)
        coEvery { h.scheduledRepo.findByClientMessageId(any()) } returns null
        val updated = mutableListOf<MessageEntity>()
        coEvery { h.messageDao.update(capture(updated)) } returns Unit

        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("key-1", "7", "blocked_recipient", "recipient is blocked"),
        )
        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("key-2", "7", "internal", "boom"),
        )

        assertThat(updated).hasSize(2)
        assertThat(updated[0].errorCode).isEqualTo(SendErrorCode.RECIPIENT_BLOCKED)
        assertThat(updated[1].errorCode).isEqualTo(SendErrorCode.SYNCHRONOUS)
    }

    // -- retry ------------------------------------------------------

    @Test fun `resend reuses the same client key`() = runTest {
        val h = harness()
        val failed = message(id = 50L, key = "key-1", status = MessageStatus.FAILED)
        coEvery { h.messageDao.findById(50L) } returns failed
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        val updates = mutableListOf<MessageEntity>()
        coEvery { h.messageDao.update(capture(updates)) } returns Unit

        val result = h.manager.resend(50L)

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        assertThat(updates.first().status).isEqualTo(MessageStatus.PENDING)
        assertThat(updates.first().errorCode).isNull()
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("send_message")
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
    }

    @Test fun `resend without a key fails clearly and sends no frame`() = runTest {
        val h = harness()
        coEvery { h.messageDao.findById(50L) } returns
            message(id = 50L, key = null, status = MessageStatus.FAILED)

        val result = h.manager.resend(50L)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
    }

    // -- schedules --------------------------------------------------

    @Test fun `scheduleMessage emits a schedule_message frame`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()

        val sent = h.manager.scheduleMessage(7L, emptyList(), "later", 1700000000000, "sched-key")

        assertThat(sent).isTrue()
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("schedule_message")
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("sched-key")
        assertThat(frame["conversation_id"]?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(frame["send_at"]?.jsonPrimitive?.content).isEqualTo("1700000000000")
    }

    @Test fun `a failed schedule marks the local row FAILED, pending or confirmed`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(7L) } returns conversation()
        coEvery { h.scheduledRepo.findByClientMessageId("sched-key") } returns schedule()

        h.manager.scheduleMessage(7L, emptyList(), "later", 1700000000000, "sched-key")
        h.manager.onMessageScheduled(
            SyncProtocol.MessageScheduled("sched-key", "9", "7", 1700000000000),
        )
        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("sched-key", "7", "send_failed", "radio refused"),
        )

        coVerify { h.scheduledRepo.markFailed(9L) }
    }

    @Test fun `cancelScheduled tracks the cancel and a failed cancel restores the row`() = runTest {
        val h = harness()
        coEvery { h.scheduledRepo.findByClientMessageId("sched-key") } returns schedule()

        val sent = h.manager.cancelScheduled("sched-key")

        assertThat(sent).isTrue()
        assertThat(h.channel.sent.single().asObject().frameType()).isEqualTo("cancel_scheduled")
        h.manager.onMessageFailed(
            SyncProtocol.MessageFailed("sched-key", "7", "internal", "boom"),
        )

        coVerify { h.scheduledRepo.rearmPending(9L, 1700000000000) }
        coVerify(exactly = 0) { h.scheduledRepo.markFailed(any()) }
    }

    @Test fun `onLiveMessage marks a fired schedule SENT`() = runTest {
        val h = harness()
        coEvery { h.scheduledRepo.findByClientMessageId("sched-key") } returns schedule()

        h.manager.onLiveMessage("sched-key")

        coVerify { h.scheduledRepo.markSent(9L) }
    }

    @Test fun `onLiveMessage leaves a non-pending schedule alone`() = runTest {
        val h = harness()
        coEvery { h.scheduledRepo.findByClientMessageId("sched-key") } returns
            schedule(state = ScheduledMessage.State.SENT)

        h.manager.onLiveMessage("sched-key")

        coVerify(exactly = 0) { h.scheduledRepo.markSent(any()) }
    }

    // -- Round 36 phase 2: note saves ---------------------------------

    private fun noteMessage(id: Long = 11L, noteId: Long = 3L, body: String = "hello") =
        NoteMessage(
            id = id,
            uuid = "msg-uuid",
            noteId = noteId,
            body = body,
            date = 1000L,
        )

    private fun seedNoteSave(h: Harness, message: NoteMessage = noteMessage()) {
        coEvery { h.noteRepo.noteHubId(3L) } returns 30L
        coEvery {
            h.noteRepo.insertMessage(any(), any(), any(), any(), any(), any())
        } returns message
    }

    @Test fun `saveNoteMessage sends save_note_message and completes on the ack`() = runTest {
        val h = harness()
        seedNoteSave(h)

        val deferred = async {
            h.manager.saveNoteMessage(3L, "hello", emptyList(), "uuid-1")
        }
        runCurrent()
        assertThat(h.channel.sent).hasSize(1)
        val frame = h.channel.sent.single().asObject()
        assertThat(frame.frameType()).isEqualTo("save_note_message")
        assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("uuid-1")
        assertThat(frame["note_id"]?.jsonPrimitive?.content).isEqualTo("30")
        assertThat(frame["body"]?.jsonPrimitive?.content).isEqualTo("hello")
        // No attachments: the array encodes empty (the manager passes
        // the list, not null — the hub treats both the same).
        assertThat(frame["attachments"]?.jsonArray).isEmpty()

        h.manager.onNoteMessageSaved(
            SyncProtocol.NoteMessageSaved("uuid-1", "42", "7"),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Success(11L))
        coVerify { h.noteRepo.linkMessageToHub("uuid-1", 42L) }
        coVerify(exactly = 0) { h.noteRepo.deleteMessage(any()) }
    }

    @Test fun `saveNoteMessage to an unsynced note fails without a frame`() = runTest {
        val h = harness()
        // The relaxed mock answers 0L for a `Long?` return — the
        // unsynced case must be stubbed to null explicitly.
        coEvery { h.noteRepo.noteHubId(3L) } returns null

        val result = h.manager.saveNoteMessage(3L, "hello", emptyList(), "uuid-1")

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
        coVerify(exactly = 0) { h.noteRepo.insertMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `saveNoteMessage failure deletes the optimistic row`() = runTest {
        val h = harness()
        seedNoteSave(h)

        val deferred = async {
            h.manager.saveNoteMessage(3L, "hello", emptyList(), "uuid-1")
        }
        runCurrent()
        h.manager.onNoteMessageFailed(
            SyncProtocol.NoteMessageFailed("uuid-1", "7", "unknown_note", "gone"),
        )
        advanceUntilIdle()

        assertThat(deferred.await())
            .isEqualTo(Outcome.Failure(AppError.Validation("note doesn't exist anymore")))
        coVerify { h.noteRepo.deleteMessage(11L) }
    }

    @Test fun `saveNoteMessage with an attachment uploads with kind note`() = runTest {
        val h = harness()
        seedNoteSave(h)
        val bytes = byteArrayOf(1, 2, 3)
        val staged = File(h.filesDir, "a.jpg").also { it.writeBytes(bytes) }
        coEvery {
            h.noteRepo.addAttachment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Success(
            Attachment(
                id = 5L,
                messageId = 11L,
                mimeType = "image/jpeg",
                fileName = "a.jpg",
                sizeBytes = 3L,
                localUri = "note://5",
                width = 800,
                height = 600,
                durationMs = null,
            ),
        )
        val uploaded = slot<ByteArray>()

        val deferred = async {
            h.manager.saveNoteMessage(
                3L,
                "hello",
                listOf(NoteSaveAttachment(staged, "image/jpeg", "a.jpg", 800, 600)),
                "uuid-1",
            )
        }
        runCurrent()
        coVerify {
            h.uploader.upload("uuid-1", 0, capture(uploaded), "image/jpeg", "a.jpg", "note")
        }
        assertThat(uploaded.captured).isEqualTo(bytes)
        val frame = h.channel.sent.single().asObject()
        val attachment = frame["attachments"]?.jsonArray?.single()?.jsonObject
        assertThat(attachment?.get("upload_index")?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(attachment?.get("mime_type")?.jsonPrimitive?.content).isEqualTo("image/jpeg")

        h.manager.onNoteMessageSaved(SyncProtocol.NoteMessageSaved("uuid-1", "42", "7"))
        advanceUntilIdle()
        assertThat(deferred.await()).isEqualTo(Outcome.Success(11L))
    }

    @Test fun `upload_expired re-uploads once and the second ack completes the save`() = runTest {
        val h = harness()
        seedNoteSave(h)
        val bytes = byteArrayOf(1, 2, 3)
        val staged = File(h.filesDir, "a.jpg").also { it.writeBytes(bytes) }
        val attachment = Attachment(
            id = 5L,
            messageId = 11L,
            mimeType = "image/jpeg",
            fileName = "a.jpg",
            sizeBytes = 3L,
            localUri = "note://5",
            width = null,
            height = null,
            durationMs = null,
        )
        coEvery {
            h.noteRepo.addAttachment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Success(attachment)
        coEvery { h.noteRepo.attachmentsForMessage(11L) } returns listOf(attachment)
        coEvery { h.noteRepo.findMessageById(11L) } returns noteMessage()
        coEvery { h.noteAttachmentResolver.decryptToMemory(attachment) } returns
            Outcome.Success(bytes)

        val deferred = async {
            h.manager.saveNoteMessage(
                3L,
                "hello",
                listOf(NoteSaveAttachment(staged, "image/jpeg", "a.jpg")),
                "uuid-1",
            )
        }
        runCurrent()
        assertThat(h.channel.sent).hasSize(1)

        h.manager.onNoteMessageFailed(
            SyncProtocol.NoteMessageFailed("uuid-1", "7", "upload_expired", "stale"),
        )
        // runCurrent, not advanceUntilIdle: the save's 20 s timeout
        // is still pending, and advancing the clock would fire it
        // before the second ack arrives.
        runCurrent()
        // The retry re-uploaded (same key, kind note) and re-sent the frame.
        assertThat(h.channel.sent).hasSize(2)
        val retry = h.channel.sent[1].asObject()
        assertThat(retry.frameType()).isEqualTo("save_note_message")
        assertThat(retry["client_message_id"]?.jsonPrimitive?.content).isEqualTo("uuid-1")
        assertThat(deferred.isCompleted).isFalse()

        h.manager.onNoteMessageSaved(SyncProtocol.NoteMessageSaved("uuid-1", "42", "7"))
        advanceUntilIdle()
        assertThat(deferred.await()).isEqualTo(Outcome.Success(11L))
        coVerify(exactly = 0) { h.noteRepo.deleteMessage(any()) }
    }

    @Test fun `upload_expired on a text entry fails the save (nothing to re-upload)`() = runTest {
        val h = harness()
        seedNoteSave(h)
        coEvery { h.noteRepo.attachmentsForMessage(11L) } returns emptyList()

        val deferred = async {
            h.manager.saveNoteMessage(3L, "hello", emptyList(), "uuid-1")
        }
        runCurrent()
        h.manager.onNoteMessageFailed(
            SyncProtocol.NoteMessageFailed("uuid-1", "7", "upload_expired", "stale"),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.Network()))
        coVerify { h.noteRepo.deleteMessage(11L) }
    }

    // -- mode -------------------------------------------------------

    @Test fun `isClientMode reflects the stored mode`() = runTest {
        assertThat(harness(mode = DeviceMode.CLIENT).manager.isClientMode()).isTrue()
        assertThat(harness(mode = DeviceMode.HUB).manager.isClientMode()).isFalse()
    }
}

private fun String.asObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.frameType(): String? = this["type"]?.jsonPrimitive?.content
