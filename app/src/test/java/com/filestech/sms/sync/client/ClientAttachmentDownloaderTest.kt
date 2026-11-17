package com.filestech.sms.sync.client

import android.content.Context
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Client mode C5 — the attachment downloader: chunk reassembly +
 * verify, stall/retry, the 3-concurrency queue, the cache check,
 * disconnect/resume, and the empty-transfer state. The DAOs are
 * mocks (the Room SQL itself is standard `@Query` — CI-only to
 * exercise against a real database); the files land in a temp
 * `filesDir`. The downloader's scope is a separate
 * `TestScope(testScheduler)` — the coroutines-test trap (a stall
 * timer in the runTest scope would hang teardown) — cancelled when
 * the test's scope completes.
 */
class ClientAttachmentDownloaderTest {

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

    private class Fixture(val scope: TestScope) {
        val channel = FakeChannel()
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val filesDir: File = Files.createTempDirectory("c5-downloads").toFile()
        val context = mockk<Context>()

        init {
            every { context.filesDir } returns filesDir
        }

        val downloader = ClientAttachmentDownloader(channel, attachmentDao, messageDao, context, scope)

        /** Seeds one synced attachment row + its message row (both lookups the downloader needs). */
        fun seed(
            localId: Long,
            hubId: Long,
            messageLocalId: Long = 10L,
            hubMessageId: Long = 7L,
            mime: String = "image/jpeg",
            size: Long = 5L,
            localUri: String = "",
        ): AttachmentEntity {
            val entity = AttachmentEntity(
                id = localId,
                messageId = messageLocalId,
                mimeType = mime,
                fileName = "photo.jpg",
                sizeBytes = size,
                localUri = localUri,
                hubAttachmentId = hubId,
            )
            coEvery { attachmentDao.findById(localId) } returns entity
            coEvery { messageDao.findById(messageLocalId) } returns messageEntity(messageLocalId, hubMessageId)
            return entity
        }

        fun messageEntity(id: Long, hubId: Long?) = MessageEntity(
            id = id,
            conversationId = 7L,
            telephonyUri = null,
            address = "+33612345678",
            body = "",
            type = MessageType.MMS,
            direction = MessageDirection.INCOMING,
            date = 1700000000000,
            dateSent = null,
            read = true,
            status = MessageStatus.RECEIVED,
            hubMessageId = hubId,
        )

        /** The `attachment_request` frames sent so far, as JSON objects. */
        fun requestFrames(): List<JsonObject> = channel.sent
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "attachment_request" }

        fun state(localId: Long): ClientDownloadState? = downloader.states.value[localId]

        fun downloadedFile(hubId: Long, ext: String = "jpg"): File =
            File(filesDir, "mms_attachments/$hubId.$ext")
    }

    private fun TestScope.fixture(): Fixture {
        val scope = TestScope(testScheduler)
        coroutineContext[Job]?.invokeOnCompletion { scope.cancel() }
        return Fixture(scope)
    }

    private fun chunk(
        hubId: String,
        offset: Long,
        bytes: ByteArray,
        final: Boolean,
        sha: String? = null,
        size: Long? = null,
    ) = SyncProtocol.AttachmentChunk(hubId, offset, Base64Url.encode(bytes), final, sha, size)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val hello = "hello".toByteArray(Charsets.UTF_8)

    // -- Reassembly + verify ----------------------------------------

    @Test fun `chunk reassembly writes the verified file and sets localUri`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)

        f.downloader.request(1L)

        assertThat(f.requestFrames()).hasSize(1)
        assertThat(f.requestFrames().single()["attachment_id"]?.jsonPrimitive?.content).isEqualTo("42")
        assertThat(f.requestFrames().single()["message_id"]?.jsonPrimitive?.content).isEqualTo("7")
        f.downloader.onChunk(chunk("42", 0, "hel".toByteArray(), final = false))
        f.downloader.onChunk(
            chunk("42", 3, "lo".toByteArray(), final = true, sha = sha256Hex(hello), size = 5L),
        )

        assertThat(f.downloadedFile(42L).readBytes()).isEqualTo(hello)
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloaded)
        coVerify { f.attachmentDao.updateLocalUri(1L, f.downloadedFile(42L).absolutePath) }
    }

    @Test fun `sequential offsets are enforced`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)

        // The first chunk must start at 0 — an offset gap is a
        // protocol error: fail at once, no auto-retry.
        f.downloader.onChunk(chunk("42", 5, hello, final = true, sha = sha256Hex(hello), size = 5L))

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
        assertThat(f.requestFrames()).hasSize(1)
        assertThat(f.downloadedFile(42L).exists()).isFalse()
    }

    @Test fun `bad base64 fails at once`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)

        f.downloader.onChunk(
            SyncProtocol.AttachmentChunk("42", 0, "!!!not-base64!!!", isFinal = false),
        )

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
        assertThat(f.requestFrames()).hasSize(1)
    }

    @Test fun `SHA-256 mismatch retries once then fails`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)
        val wrongSha = sha256Hex("world".toByteArray())

        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = wrongSha, size = 5L))

        // One automatic retry: a second request went out, the state
        // is Downloading again (not Failed yet).
        assertThat(f.requestFrames()).hasSize(2)
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloading(0, 5L))
        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = wrongSha, size = 5L))

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
        assertThat(f.requestFrames()).hasSize(2)
        assertThat(f.downloadedFile(42L).exists()).isFalse()
    }

    @Test fun `final chunk size mismatch retries once then fails`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)

        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = sha256Hex(hello), size = 99L))
        assertThat(f.requestFrames()).hasSize(2)
        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = sha256Hex(hello), size = 99L))

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
        assertThat(f.requestFrames()).hasSize(2)
    }

    @Test fun `size-0 metadata means unknown`() = runTest {
        val f = fixture()
        // Imported attachments carry no size: any non-empty verified
        // transfer counts, and the metadata cross-check is skipped.
        f.seed(localId = 1L, hubId = 42L, size = 0L)
        f.downloader.request(1L)

        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = sha256Hex(hello), size = 5L))

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloaded)
        assertThat(f.downloadedFile(42L).readBytes()).isEqualTo(hello)
    }

    @Test fun `verified empty transfer reports Empty and does not retry`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L, size = 0L)
        f.downloader.request(1L)

        f.downloader.onChunk(
            chunk("42", 0, byteArrayOf(), final = true, sha = sha256Hex(byteArrayOf()), size = 0L),
        )

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Empty)
        assertThat(f.downloadedFile(42L).exists()).isFalse()
        // No retry loop: a second request is a no-op (the id is in
        // the failed set — only a manual retry revives it).
        f.downloader.request(1L)
        assertThat(f.requestFrames()).hasSize(1)
    }

    @Test fun `metadata size cross-check mismatch retries then fails`() = runTest {
        val f = fixture()
        // The transfer itself is self-consistent (correct SHA + size
        // for the 5 bytes sent) but the metadata says 10: the phone
        // truncated the stream — retry, then fail.
        f.seed(localId = 1L, hubId = 42L, size = 10L)
        f.downloader.request(1L)

        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = sha256Hex(hello), size = 5L))
        assertThat(f.requestFrames()).hasSize(2)
        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = sha256Hex(hello), size = 5L))

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
    }

    // -- Stall + queue ----------------------------------------------

    @Test fun `stall retries once then fails`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)
        assertThat(f.requestFrames()).hasSize(1)

        advanceTimeBy(30_000)
        runCurrent()

        // The stall re-queued + restarted the download (second request).
        assertThat(f.requestFrames()).hasSize(2)
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloading(0, 5L))
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
        assertThat(f.requestFrames()).hasSize(2)
    }

    @Test fun `max 3 concurrent, the 4th queues until a slot frees`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 41L, messageLocalId = 11L)
        f.seed(localId = 2L, hubId = 42L, messageLocalId = 12L)
        f.seed(localId = 3L, hubId = 43L, messageLocalId = 13L)
        f.seed(localId = 4L, hubId = 44L, messageLocalId = 14L)

        f.downloader.request(1L)
        f.downloader.request(2L)
        f.downloader.request(3L)
        f.downloader.request(4L)

        assertThat(f.requestFrames()).hasSize(3)
        assertThat(f.state(4L)).isNull()
        // Completing one frees a slot: the 4th starts.
        f.downloader.onChunk(chunk("41", 0, hello, final = true, sha = sha256Hex(hello), size = 5L))

        assertThat(f.requestFrames()).hasSize(4)
        assertThat(f.requestFrames().last()["attachment_id"]?.jsonPrimitive?.content).isEqualTo("44")
        assertThat(f.state(4L)).isEqualTo(ClientDownloadState.Downloading(0, 5L))
    }

    @Test fun `unknown attachment id chunk is ignored`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)

        // A late chunk for a download that never existed (or already
        // finished): ignored, no crash, the real download untouched.
        f.downloader.onChunk(chunk("99", 0, hello, final = true, sha = sha256Hex(hello), size = 5L))

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloading(0, 5L))
        assertThat(f.requestFrames()).hasSize(1)
    }

    @Test fun `attachment_error fails at once with no retry`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)

        f.downloader.onError(
            SyncProtocol.AttachmentError("42", "unknown_attachment", "no such attachment"),
        )

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)
        assertThat(f.requestFrames()).hasSize(1)
    }

    // -- Cache ------------------------------------------------------

    @Test fun `cached file short-circuits with no frame`() = runTest {
        val f = fixture()
        val cached = File(f.filesDir, "mms_attachments/42.jpg").also {
            it.parentFile?.mkdirs()
            it.writeBytes(hello)
        }
        f.seed(localId = 1L, hubId = 42L, localUri = cached.absolutePath)

        f.downloader.request(1L)

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloaded)
        assertThat(f.requestFrames()).isEmpty()
    }

    @Test fun `stale cached path is cleared and the download starts`() = runTest {
        val f = fixture()
        val stale = File(f.filesDir, "mms_attachments/42.jpg").also {
            it.parentFile?.mkdirs()
            it.writeBytes("hel".toByteArray())
        }
        f.seed(localId = 1L, hubId = 42L, localUri = stale.absolutePath)

        f.downloader.request(1L)

        assertThat(stale.exists()).isFalse()
        coVerify { f.attachmentDao.updateLocalUri(1L, "") }
        assertThat(f.requestFrames()).hasSize(1)
    }

    // -- Progress + session -----------------------------------------

    @Test fun `progress states are emitted`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)

        f.downloader.request(1L)
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloading(0, 5L))
        f.downloader.onChunk(chunk("42", 0, "hel".toByteArray(), final = false))
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloading(3, 5L))
        f.downloader.onChunk(
            chunk("42", 3, "lo".toByteArray(), final = true, sha = sha256Hex(hello), size = 5L),
        )

        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloaded)
    }

    @Test fun `disconnect interrupts and reconnect re-queues`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)
        assertThat(f.requestFrames()).hasSize(1)

        f.downloader.onSessionEnded()
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.NotDownloaded)
        f.downloader.onSessionResumed()

        assertThat(f.requestFrames()).hasSize(2)
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloading(0, 5L))
    }

    @Test fun `retry after failure re-downloads`() = runTest {
        val f = fixture()
        f.seed(localId = 1L, hubId = 42L)
        f.downloader.request(1L)
        f.downloader.onError(
            SyncProtocol.AttachmentError("42", "internal", "boom"),
        )
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Failed)

        f.downloader.retry(1L)

        assertThat(f.requestFrames()).hasSize(2)
        f.downloader.onChunk(chunk("42", 0, hello, final = true, sha = sha256Hex(hello), size = 5L))
        assertThat(f.state(1L)).isEqualTo(ClientDownloadState.Downloaded)
    }

    // -- No-ops -----------------------------------------------------

    @Test fun `hub-mode row is a no-op`() = runTest {
        val f = fixture()
        val entity = AttachmentEntity(
            id = 1L,
            messageId = 10L,
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
            sizeBytes = 5L,
            localUri = "",
            hubAttachmentId = null,
        )
        coEvery { f.attachmentDao.findById(1L) } returns entity

        f.downloader.request(1L)

        assertThat(f.requestFrames()).isEmpty()
        assertThat(f.state(1L)).isNull()
    }

    @Test fun `unknown local id is a no-op`() = runTest {
        val f = fixture()
        coEvery { f.attachmentDao.findById(999L) } returns null

        f.downloader.request(999L)

        assertThat(f.requestFrames()).isEmpty()
        assertThat(f.state(999L)).isNull()
    }
}
