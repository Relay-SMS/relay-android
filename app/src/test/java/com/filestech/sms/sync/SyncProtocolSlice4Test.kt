package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Slice 4 — the attachment-byte wire types: every new message type
 * encodes with its `type` field and parses back to the same value,
 * and the extended `send_message` keeps a text send byte-identical
 * to Slice 3.
 */
class SyncProtocolSlice4Test {

    // -- Slice 4: attachment bytes --------------------------------------

    @Test fun `attachment_request parses attachment and message ids`() {
        assertThat(
            SyncProtocol.parse("""{"type":"attachment_request","attachment_id":"12","message_id":"789"}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.AttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "12", messageId = "789"),
            ),
        )
    }

    @Test fun `attachment_request without a message id is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"attachment_request","attachment_id":"12"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `attachment_chunk encodes a non-final chunk without digest or size`() {
        val encoded = SyncProtocol.encodeAttachmentChunk(
            SyncProtocol.AttachmentChunk(
                attachmentId = "12",
                offset = 0,
                dataBase64Url = "aGVsbG8",
                isFinal = false,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("attachment_chunk")
        assertThat(obj["attachment_id"]?.jsonPrimitive?.content).isEqualTo("12")
        assertThat(obj["offset"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(obj["data_base64url"]?.jsonPrimitive?.content).isEqualTo("aGVsbG8")
        assertThat(obj["final"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(obj.containsKey("sha256")).isFalse()
        assertThat(obj.containsKey("size_bytes")).isFalse()
    }

    @Test fun `attachment_chunk encodes the final chunk with digest and size`() {
        val encoded = SyncProtocol.encodeAttachmentChunk(
            SyncProtocol.AttachmentChunk(
                attachmentId = "12",
                offset = 131072,
                dataBase64Url = "aGVsbG8",
                isFinal = true,
                sha256 = "ab12",
                sizeBytes = 198402,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["final"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(obj["sha256"]?.jsonPrimitive?.content).isEqualTo("ab12")
        assertThat(obj["size_bytes"]?.jsonPrimitive?.content).isEqualTo("198402")
    }

    @Test fun `attachment_error encodes code and message`() {
        val encoded = SyncProtocol.encodeAttachmentError(
            SyncProtocol.AttachmentError(
                attachmentId = "12",
                code = SyncProtocol.AttachmentError.ATTACHMENT_UNREADABLE,
                message = "This photo couldn't be read on the phone.",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("attachment_error")
        assertThat(obj["attachment_id"]?.jsonPrimitive?.content).isEqualTo("12")
        assertThat(obj["code"]?.jsonPrimitive?.content).isEqualTo("attachment_unreadable")
        assertThat(obj["message"]?.jsonPrimitive?.content)
            .isEqualTo("This photo couldn't be read on the phone.")
    }

    @Test fun `attachment error codes are the spec strings`() {
        assertThat(SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT).isEqualTo("unknown_attachment")
        assertThat(SyncProtocol.AttachmentError.ATTACHMENT_UNREADABLE).isEqualTo("attachment_unreadable")
        assertThat(SyncProtocol.AttachmentError.ATTACHMENT_TOO_LARGE).isEqualTo("attachment_too_large")
        assertThat(SyncProtocol.AttachmentError.INTERNAL).isEqualTo("internal")
    }

    @Test fun `attachment_upload_start parses the full start frame`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"attachment_upload_start","client_message_id":"$CLIENT_KEY","index":0,""" +
                    """"mime_type":"image/jpeg","file_name":"photo.jpg","size_bytes":198402,"sha256":"ab12"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.AttachmentUploadStart(
                SyncProtocol.AttachmentUploadStart(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    mimeType = "image/jpeg",
                    fileName = "photo.jpg",
                    sizeBytes = 198402,
                    sha256 = "ab12",
                ),
            ),
        )
    }

    @Test fun `attachment_upload_start without a file name parses to null`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"attachment_upload_start","client_message_id":"$CLIENT_KEY","index":1,""" +
                    """"mime_type":"image/gif","size_bytes":1024,"sha256":"cd34"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.AttachmentUploadStart(
                SyncProtocol.AttachmentUploadStart(
                    clientMessageId = CLIENT_KEY,
                    index = 1,
                    mimeType = "image/gif",
                    fileName = null,
                    sizeBytes = 1024,
                    sha256 = "cd34",
                ),
            ),
        )
    }

    @Test fun `attachment_upload_chunk parses offset data and final`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"attachment_upload_chunk","client_message_id":"$CLIENT_KEY","index":0,""" +
                    """"offset":65536,"data_base64url":"aGVsbG8","final":true}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.AttachmentUploadChunk(
                SyncProtocol.AttachmentUploadChunk(
                    clientMessageId = CLIENT_KEY,
                    index = 0,
                    offset = 65536,
                    dataBase64Url = "aGVsbG8",
                    isFinal = true,
                ),
            ),
        )
    }

    @Test fun `attachment_upload_ready and done encode key and index`() {
        val ready = Json.parseToJsonElement(
            SyncProtocol.encodeAttachmentUploadReady(
                SyncProtocol.AttachmentUploadReady(clientMessageId = CLIENT_KEY, index = 0),
            ),
        ).jsonObject
        assertThat(ready["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_ready")
        assertThat(ready["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
        assertThat(ready["index"]?.jsonPrimitive?.content).isEqualTo("0")

        val done = Json.parseToJsonElement(
            SyncProtocol.encodeAttachmentUploadDone(
                SyncProtocol.AttachmentUploadDone(clientMessageId = CLIENT_KEY, index = 0),
            ),
        ).jsonObject
        assertThat(done["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_done")
        assertThat(done["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
        assertThat(done["index"]?.jsonPrimitive?.content).isEqualTo("0")
    }

    @Test fun `attachment_upload_error encodes the code`() {
        val encoded = SyncProtocol.encodeAttachmentUploadError(
            SyncProtocol.AttachmentUploadError(
                clientMessageId = CLIENT_KEY,
                index = 0,
                code = SyncProtocol.AttachmentUploadError.UPLOAD_TOO_LARGE,
                message = "This file is too big to send.",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_error")
        assertThat(obj["code"]?.jsonPrimitive?.content).isEqualTo("upload_too_large")
        assertThat(SyncProtocol.AttachmentUploadError.UPLOAD_BAD_CHECKSUM)
            .isEqualTo("upload_bad_checksum")
        assertThat(SyncProtocol.AttachmentUploadError.INTERNAL).isEqualTo("internal")
    }

    @Test fun `send_message parses attachments with an upload reference and a sticker`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"send_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","body":"Look at this",""" +
                    """"attachments":[{"upload_index":0,"mime_type":"image/jpeg","file_name":"photo.jpg","width":1600,""" +
                    """"height":1200},{"sticker_emoji":"😂"}]}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SendMessage(
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
            ),
        )
    }

    @Test fun `send_message without attachments parses to null`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"send_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","body":"Hi"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SendMessage(
                SyncProtocol.SendMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "123",
                    body = "Hi",
                    attachments = null,
                ),
            ),
        )
    }

    @Test fun `message_failed upload_expired is the spec string`() {
        assertThat(SyncProtocol.MessageFailed.UPLOAD_EXPIRED).isEqualTo("upload_expired")
    }

    @Test fun `attachment chunk size and download cap are the spec values`() {
        assertThat(SyncProtocol.ATTACHMENT_CHUNK_BYTES).isEqualTo(64 * 1024)
        assertThat(SyncProtocol.MAX_ATTACHMENT_DOWNLOAD_BYTES).isEqualTo(25L * 1024 * 1024)
    }
    companion object {
        private const val CLIENT_KEY = "3f2a1b00-0000-4000-8000-00000000c41d"
    }
}
