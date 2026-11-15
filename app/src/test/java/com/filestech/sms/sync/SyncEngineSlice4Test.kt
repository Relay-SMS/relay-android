package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections

/**
 * Slice 4 — the engine's attachment-byte handlers, driven without a
 * socket: the fake source holds the attachment bytes (download) or
 * records the upload calls, and the harness records every outbound
 * frame. The download chunking, the final digest, and the error
 * codes are the contract the desktop downloads against, so they are
 * pinned here.
 */
class SyncEngineSlice4Test {

    // -- Slice 4: attachment download -----------------------------------

    @Test fun `download streams 64 KiB chunks and the final chunk carries the digest`() {
        val bytes = ByteArray(150 * 1024) { (it % 251).toByte() }
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Opened(
            AttachmentData(sizeBytes = bytes.size.toLong(), stream = ByteArrayInputStream(bytes)),
        )
        runEngineReal(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            sent.awaitFrames(3)
            assertThat(sent.map { it.frameType() }).containsExactly(
                "attachment_chunk",
                "attachment_chunk",
                "attachment_chunk",
            ).inOrder()
            val chunks = sent.map { it.asObject() }
            assertThat(chunks[0]["offset"]?.jsonPrimitive?.content).isEqualTo("0")
            assertThat(chunks[0]["final"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(chunks[0].containsKey("sha256")).isFalse()
            assertThat(chunks[1]["offset"]?.jsonPrimitive?.content).isEqualTo("65536")
            assertThat(chunks[1]["final"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(chunks[2]["offset"]?.jsonPrimitive?.content).isEqualTo("131072")
            assertThat(chunks[2]["final"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(chunks[2]["sha256"]?.jsonPrimitive?.content).isEqualTo(sha256Hex(bytes))
            assertThat(chunks[2]["size_bytes"]?.jsonPrimitive?.content).isEqualTo("153600")
            // The chunks reassemble to the original bytes.
            val reassembled = chunks.flatMap { chunk ->
                Base64.getUrlDecoder()
                    .decode(QrPairing.padBase64(chunk["data_base64url"]!!.jsonPrimitive.content))
                    .toList()
            }.toByteArray()
            assertThat(reassembled).isEqualTo(bytes)
            assertThat(source.attachmentRequests).containsExactly(12L to 789L)
        }
    }

    @Test fun `download of an exact multiple ends with one empty final chunk`() {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Opened(
            AttachmentData(sizeBytes = bytes.size.toLong(), stream = ByteArrayInputStream(bytes)),
        )
        runEngineReal(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            sent.awaitFrames(3)
            val chunks = sent.map { it.asObject() }
            assertThat(chunks[2]["offset"]?.jsonPrimitive?.content).isEqualTo("131072")
            assertThat(chunks[2]["final"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(chunks[2]["data_base64url"]?.jsonPrimitive?.content).isEmpty()
            assertThat(chunks[2]["sha256"]?.jsonPrimitive?.content).isEqualTo(sha256Hex(bytes))
            assertThat(chunks[2]["size_bytes"]?.jsonPrimitive?.content).isEqualTo("131072")
        }
    }

    @Test fun `download of an empty attachment sends one empty final chunk`() {
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Opened(
            AttachmentData(sizeBytes = 0L, stream = ByteArrayInputStream(ByteArray(0))),
        )
        runEngineReal(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            sent.awaitFrames(1)
            val chunk = sent.single().asObject()
            assertThat(chunk["type"]?.jsonPrimitive?.content).isEqualTo("attachment_chunk")
            assertThat(chunk["final"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(chunk["data_base64url"]?.jsonPrimitive?.content).isEmpty()
            assertThat(chunk["sha256"]?.jsonPrimitive?.content)
                .isEqualTo(sha256Hex(ByteArray(0)))
            assertThat(chunk["size_bytes"]?.jsonPrimitive?.content).isEqualTo("0")
        }
    }

    @Test fun `a stream that outgrows the cap mid-transfer is cut off with too_large`() {
        // The row claims 100 bytes; the stream keeps going past 25 MB.
        // ZeroStream yields the bytes without allocating them.
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Opened(
            AttachmentData(
                sizeBytes = 100L,
                stream = ZeroStream(SyncProtocol.MAX_ATTACHMENT_DOWNLOAD_BYTES + 1),
            ),
        )
        runEngineReal(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            // 400 full chunks (25 MB exactly), then the error — no final chunk.
            sent.awaitFrames(401)
            val last = sent.last().asObject()
            assertThat(last["type"]?.jsonPrimitive?.content).isEqualTo("attachment_error")
            assertThat(last["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.ATTACHMENT_TOO_LARGE)
            assertThat(sent.dropLast(1).map { it.frameType() })
                .containsExactlyElementsIn(List(400) { "attachment_chunk" })
            assertThat(sent.none { it.asObject()["final"]?.jsonPrimitive?.content == "true" })
                .isTrue()
        }
    }

    @Test fun `a stream that fails mid-read answers attachment_error unreadable`() {
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Opened(
            AttachmentData(sizeBytes = 1000L, stream = FailingStream(ByteArray(100))),
        )
        runEngineReal(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            sent.awaitFrames(1)
            val error = sent.single().asObject()
            assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("attachment_error")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.ATTACHMENT_UNREADABLE)
            assertThat(error["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    @Test fun `unknown attachment answers attachment_error unknown_attachment`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("attachment_error")
            assertThat(error["attachment_id"]?.jsonPrimitive?.content).isEqualTo("12")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT)
        }
    }

    @Test fun `unreadable attachment answers attachment_error attachment_unreadable`() = runTest {
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Unreadable
        runEngine(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.ATTACHMENT_UNREADABLE)
        }
    }

    @Test fun `oversized download is refused before any chunk`() = runTest {
        val source = FakeSyncMessageSource()
        source.openAttachmentResult = AttachmentOpenResult.Opened(
            AttachmentData(
                sizeBytes = SyncProtocol.MAX_ATTACHMENT_DOWNLOAD_BYTES + 1,
                stream = ByteArrayInputStream(ByteArray(10)),
            ),
        )
        runEngine(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("attachment_error")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.ATTACHMENT_TOO_LARGE)
        }
    }

    @Test fun `an unparseable attachment id answers unknown_attachment without touching the source`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "nope", messageId = "789"),
            )
            advanceUntilIdle()
            assertThat(source.attachmentRequests).isEmpty()
            val error = sent.single().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT)
        }
    }

    @Test fun `a throwing source answers attachment_error internal`() = runTest {
        val source = FakeSyncMessageSource()
        source.openAttachmentError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.INTERNAL)
            assertThat(error["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    // -- Slice 4: attachment upload -------------------------------------

    @Test fun `upload start answers ready and records the start`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onUploadStart(
                SyncProtocol.AttachmentUploadStart(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    mimeType = "image/jpeg",
                    fileName = "photo.jpg",
                    sizeBytes = 198402,
                    sha256 = "ab12",
                ),
            )
            advanceUntilIdle()
            assertThat(source.uploadStarts).containsExactly(
                UploadStartCall(CLIENT_KEY, 0, "image/jpeg", "photo.jpg", 198402, "ab12"),
            )
            val ready = sent.single().asObject()
            assertThat(ready["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_ready")
            assertThat(ready["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
            assertThat(ready["index"]?.jsonPrimitive?.content).isEqualTo("0")
        }
    }

    @Test fun `upload start refusal answers upload_error with the code`() = runTest {
        val source = FakeSyncMessageSource()
        source.uploadStartResult = UploadResult.Failed(
            SyncProtocol.AttachmentUploadError.UPLOAD_TOO_LARGE,
            "This file is too big to send.",
        )
        runEngine(source) { engine, sent ->
            engine.onUploadStart(
                SyncProtocol.AttachmentUploadStart(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    mimeType = "image/jpeg",
                    fileName = "photo.jpg",
                    sizeBytes = 300 * 1024,
                    sha256 = "ab12",
                ),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_error")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentUploadError.UPLOAD_TOO_LARGE)
            assertThat(error["message"]?.jsonPrimitive?.content)
                .isEqualTo("This file is too big to send.")
        }
    }

    @Test fun `non-final upload chunks get no ack, the final chunk answers done`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onUploadChunk(
                SyncProtocol.AttachmentUploadChunk(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    offset = 0,
                    dataBase64Url = base64Url("hello ".toByteArray()),
                    isFinal = false,
                ),
            )
            engine.onUploadChunk(
                SyncProtocol.AttachmentUploadChunk(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    offset = 6,
                    dataBase64Url = base64Url("world".toByteArray()),
                    isFinal = true,
                ),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("attachment_upload_done")
            assertThat(source.uploadChunks).containsExactly(
                UploadChunkCall(CLIENT_KEY, 0, 0, "hello ".toByteArray(), false),
                UploadChunkCall(CLIENT_KEY, 0, 6, "world".toByteArray(), true),
            ).inOrder()
            val done = sent.single().asObject()
            assertThat(done["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_done")
            assertThat(done["index"]?.jsonPrimitive?.content).isEqualTo("0")
        }
    }

    @Test fun `a failed upload chunk answers upload_error with the code`() = runTest {
        val source = FakeSyncMessageSource()
        source.uploadChunkResult = UploadResult.Failed(
            SyncProtocol.AttachmentUploadError.UPLOAD_BAD_CHECKSUM,
            "This file didn't arrive intact. Try again.",
        )
        runEngine(source) { engine, sent ->
            engine.onUploadChunk(
                SyncProtocol.AttachmentUploadChunk(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    offset = 0,
                    dataBase64Url = base64Url("hello".toByteArray()),
                    isFinal = true,
                ),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_error")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentUploadError.UPLOAD_BAD_CHECKSUM)
        }
    }

    @Test fun `an upload chunk with bad base64 answers upload_error internal`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onUploadChunk(
                SyncProtocol.AttachmentUploadChunk(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    offset = 0,
                    dataBase64Url = "!!!not-base64!!!",
                    isFinal = false,
                ),
            )
            advanceUntilIdle()
            assertThat(source.uploadChunks).isEmpty()
            val error = sent.single().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentUploadError.INTERNAL)
        }
    }

    @Test fun `a throwing upload start answers upload_error internal`() = runTest {
        val source = FakeSyncMessageSource()
        source.uploadStartError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onUploadStart(
                SyncProtocol.AttachmentUploadStart(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    mimeType = "image/jpeg",
                    fileName = null,
                    sizeBytes = 1024,
                    sha256 = "ab12",
                ),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentUploadError.INTERNAL)
            assertThat(error["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    @Test fun `a throwing upload chunk answers upload_error internal`() = runTest {
        val source = FakeSyncMessageSource()
        source.uploadChunkError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onUploadChunk(
                SyncProtocol.AttachmentUploadChunk(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    offset = 0,
                    dataBase64Url = base64Url("hello".toByteArray()),
                    isFinal = true,
                ),
            )
            advanceUntilIdle()
            val error = sent.single().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentUploadError.INTERNAL)
            assertThat(error["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    // -- Slice 4: send with attachments ---------------------------------

    @Test fun `send_message passes attachments through to the source`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onSendMessage(
                SyncProtocol.SendMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "123",
                    body = "Look at this",
                    attachments = listOf(
                        SyncProtocol.SendAttachment(
                            uploadIndex = 0,
                            mimeType = "image/jpeg",
                            fileName = "photo.jpg",
                            width = 1600,
                            height = 1200,
                        ),
                        SyncProtocol.SendAttachment(stickerEmoji = "😂"),
                    ),
                ),
            )
            advanceUntilIdle()
            assertThat(source.sends).containsExactly(
                SendCall(
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
                        SendAttachmentInput(stickerEmoji = "😂"),
                    ),
                ),
            )
            val ack = sent.first { it.frameType() == "message_sent" }.asObject()
            assertThat(ack["message_id"]?.jsonPrimitive?.content).isEqualTo("999")
        }
    }

    @Test fun `send_message without attachments passes null through`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, _ ->
            engine.onSendMessage(
                SyncProtocol.SendMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "123",
                    body = "Hi",
                ),
            )
            advanceUntilIdle()
            assertThat(source.sends).containsExactly(
                SendCall(CLIENT_KEY, 123L, "Hi", null, null),
            )
        }
    }

    /**
     * Runs the engine on a real scope (not the test scheduler): the
     * download stream reads on `Dispatchers.IO`, which virtual time
     * can't wait for. Frames are awaited in real time (see
     * [MutableList.awaitFrames]). The sent list is synchronized —
     * the engine job appends from an IO thread.
     */
    private fun runEngineReal(
        source: FakeSyncMessageSource,
        block: (SyncEngine, MutableList<String>) -> Unit,
    ) {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val engine = SyncEngine(source, scope, send = { sent += it })
        try {
            block(engine, sent)
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /** Polls (real time) until [count] frames arrived, or fails after 10 s. */
    private fun MutableList<String>.awaitFrames(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (size < count) {
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $count frames, got $size"
            }
            Thread.sleep(10)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private const val CLIENT_KEY = "3f2a1b00-0000-4000-8000-00000000c41d"
    }
}

/** An [InputStream] yielding [total] zero bytes without allocating them. */
private class ZeroStream(private val total: Long) : InputStream() {
    private var remaining = total

    override fun read(): Int {
        if (remaining <= 0) return -1
        remaining--
        return 0
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
        b.fill(0, off, off + n)
        remaining -= n
        return n
    }
}

/** An [InputStream] yielding [first], then failing every further read. */
private class FailingStream(private val first: ByteArray) : InputStream() {
    private var pos = 0
    private var failed = false

    override fun read(): Int {
        if (failed) throw IOException("boom")
        if (pos >= first.size) {
            failed = true
            throw IOException("boom")
        }
        return first[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (failed) throw IOException("boom")
        if (pos >= first.size) {
            failed = true
            throw IOException("boom")
        }
        val n = minOf(len, first.size - pos)
        first.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }
}
