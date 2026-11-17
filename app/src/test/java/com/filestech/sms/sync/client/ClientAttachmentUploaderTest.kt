package com.filestech.sms.sync.client

import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Client mode C5 — the attachment uploader: start (sha256 + size)
 * → ready → 64 KiB chunks → done, with the timeout / offline /
 * refusal failures. The upload runs in a child coroutine of the
 * test scope (it awaits acks the test delivers); the fake channel
 * records every outbound frame.
 */
class ClientAttachmentUploaderTest {

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

    private class Fixture {
        val channel = FakeChannel()
        val uploader = ClientAttachmentUploader(channel)

        /** The outbound frames so far, as JSON objects. */
        fun frames(): List<JsonObject> =
            channel.sent.map { Json.parseToJsonElement(it).jsonObject }

        fun framesOf(type: String): List<JsonObject> =
            frames().filter { it["type"]?.jsonPrimitive?.content == type }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val hello = "hello".toByteArray(Charsets.UTF_8)

    @Test fun `upload sends start with sha256 and size, then chunks, then awaits done`() = runTest {
        val f = Fixture()
        var completed = false
        val job = launch {
            runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .onSuccess { completed = true }
        }
        runCurrent()

        // The start frame went out first: sha256 + size, so the phone
        // can refuse a too-large file before any chunk crosses.
        val start = f.framesOf("attachment_upload_start").single()
        assertThat(start["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        assertThat(start["index"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(start["mime_type"]?.jsonPrimitive?.content).isEqualTo("image/jpeg")
        assertThat(start["file_name"]?.jsonPrimitive?.content).isEqualTo("photo.jpg")
        assertThat(start["size_bytes"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(start["sha256"]?.jsonPrimitive?.content).isEqualTo(sha256Hex(hello))
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        runCurrent()

        val chunks = f.framesOf("attachment_upload_chunk")
        assertThat(chunks).hasSize(1)
        assertThat(chunks.single()["offset"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(chunks.single()["final"]?.jsonPrimitive?.content).isEqualTo("true")
        val decoded = Base64Url.decode(chunks.single()["data_base64url"]?.jsonPrimitive?.content!!)
        assertThat(decoded).isEqualTo(hello)
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("key-1", 0))
        job.join()

        assertThat(completed).isTrue()
    }

    @Test fun `a 150 KB file splits into 3 chunks with sequential offsets`() = runTest {
        val f = Fixture()
        val bytes = ByteArray(150 * 1024) { (it % 251).toByte() }
        var completed = false
        val job = launch {
            runCatching { f.uploader.upload("key-1", 0, bytes, "video/mp4", "clip.mp4") }
                .onSuccess { completed = true }
        }
        runCurrent()
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        runCurrent()

        val chunks = f.framesOf("attachment_upload_chunk")
        assertThat(chunks).hasSize(3)
        assertThat(chunks.map { it["offset"]?.jsonPrimitive?.content }).containsExactly(
            "0",
            "65536",
            "131072",
        ).inOrder()
        assertThat(chunks.map { it["final"]?.jsonPrimitive?.content }).containsExactly(
            "false",
            "false",
            "true",
        ).inOrder()
        val reassembled = chunks.flatMap {
            Base64Url.decode(it["data_base64url"]?.jsonPrimitive?.content!!)!!.toList()
        }.toByteArray()
        assertThat(reassembled).isEqualTo(bytes)
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("key-1", 0))
        job.join()

        assertThat(completed).isTrue()
    }

    @Test fun `empty file sends one empty final chunk`() = runTest {
        val f = Fixture()
        var completed = false
        val job = launch {
            runCatching { f.uploader.upload("key-1", 0, byteArrayOf(), "text/plain", "empty.txt") }
                .onSuccess { completed = true }
        }
        runCurrent()
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        runCurrent()

        // The phone waits for `final` before it verifies — an empty
        // file still sends one (empty) chunk.
        val chunks = f.framesOf("attachment_upload_chunk")
        assertThat(chunks).hasSize(1)
        assertThat(chunks.single()["offset"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(chunks.single()["final"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(chunks.single()["data_base64url"]?.jsonPrimitive?.content).isEqualTo("")
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("key-1", 0))
        job.join()

        assertThat(completed).isTrue()
    }

    @Test fun `upload_error fails with the phone's code`() = runTest {
        val f = Fixture()
        var error: Throwable? = null
        val job = launch {
            error = runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .exceptionOrNull()
        }
        runCurrent()
        f.uploader.onError(
            SyncProtocol.AttachmentUploadError("key-1", 0, "upload_too_large", "over the cap"),
        )
        job.join()

        assertThat(error).isInstanceOf(ClientAttachmentUploader.UploadError::class.java)
        assertThat((error as ClientAttachmentUploader.UploadError).code).isEqualTo("upload_too_large")
    }

    @Test fun `ready timeout fails after 30 seconds`() = runTest {
        val f = Fixture()
        var error: Throwable? = null
        val job = launch {
            error = runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .exceptionOrNull()
        }
        runCurrent()
        assertThat(f.framesOf("attachment_upload_start")).hasSize(1)

        advanceTimeBy(30_000)
        runCurrent()
        job.join()

        assertThat(error).isInstanceOf(ClientAttachmentUploader.UploadError::class.java)
        assertThat((error as ClientAttachmentUploader.UploadError).code)
            .isEqualTo(ClientAttachmentUploader.TIMEOUT)
    }

    @Test fun `done timeout fails after 30 seconds`() = runTest {
        val f = Fixture()
        var error: Throwable? = null
        val job = launch {
            error = runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .exceptionOrNull()
        }
        runCurrent()
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        runCurrent()
        assertThat(f.framesOf("attachment_upload_chunk")).hasSize(1)

        advanceTimeBy(30_000)
        runCurrent()
        job.join()

        assertThat(error).isInstanceOf(ClientAttachmentUploader.UploadError::class.java)
        assertThat((error as ClientAttachmentUploader.UploadError).code)
            .isEqualTo(ClientAttachmentUploader.TIMEOUT)
    }

    @Test fun `offline at start fails with not_connected`() = runTest {
        val f = Fixture()
        f.channel.sendResult = false

        val error = runCatching {
            f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ClientAttachmentUploader.UploadError::class.java)
        assertThat((error as ClientAttachmentUploader.UploadError).code)
            .isEqualTo(ClientAttachmentUploader.NOT_CONNECTED)
    }

    @Test fun `offline mid-chunks fails with not_connected`() = runTest {
        val f = Fixture()
        var error: Throwable? = null
        val job = launch {
            error = runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .exceptionOrNull()
        }
        runCurrent()
        assertThat(f.framesOf("attachment_upload_start")).hasSize(1)
        // The session dies between the start and the ready: the chunk
        // send fails.
        f.channel.sendResult = false
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        job.join()

        assertThat(error).isInstanceOf(ClientAttachmentUploader.UploadError::class.java)
        assertThat((error as ClientAttachmentUploader.UploadError).code)
            .isEqualTo(ClientAttachmentUploader.NOT_CONNECTED)
    }

    @Test fun `unknown ack is ignored`() = runTest {
        val f = Fixture()

        // Late acks for a finished (or never-started) upload: ignored,
        // no crash — and a real upload still works after them.
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("nope", 0))
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("nope", 0))
        f.uploader.onError(SyncProtocol.AttachmentUploadError("nope", 0, "internal", "boom"))
        var completed = false
        val job = launch {
            runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .onSuccess { completed = true }
        }
        runCurrent()
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        runCurrent()
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("key-1", 0))
        job.join()

        assertThat(completed).isTrue()
    }

    @Test fun `onSessionEnded fails the pending upload`() = runTest {
        val f = Fixture()
        var error: Throwable? = null
        val job = launch {
            error = runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .exceptionOrNull()
        }
        runCurrent()

        f.uploader.onSessionEnded()
        job.join()

        assertThat(error).isInstanceOf(ClientAttachmentUploader.UploadError::class.java)
        assertThat((error as ClientAttachmentUploader.UploadError).code)
            .isEqualTo(ClientAttachmentUploader.NOT_CONNECTED)
    }

    @Test fun `done before ready is ignored`() = runTest {
        val f = Fixture()
        var completed = false
        val job = launch {
            runCatching { f.uploader.upload("key-1", 0, hello, "image/jpeg", "photo.jpg") }
                .onSuccess { completed = true }
        }
        runCurrent()

        // The phase gate: a `done` while waiting for `ready` is a
        // stale ack — the upload keeps waiting for its ready.
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("key-1", 0))
        runCurrent()
        assertThat(job.isActive).isTrue()
        assertThat(completed).isFalse()
        f.uploader.onReady(SyncProtocol.AttachmentUploadReady("key-1", 0))
        runCurrent()
        f.uploader.onDone(SyncProtocol.AttachmentUploadDone("key-1", 0))
        job.join()

        assertThat(completed).isTrue()
    }
}
