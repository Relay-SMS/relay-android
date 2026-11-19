package com.filestech.sms.sync.client

import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Client mode — the server→client frame parser: every frame the hub
 * sends must land in the right [ServerFrame], and anything broken
 * must come back [ServerFrame.Malformed] (never throw — the channel
 * stays up). The auth encoder ([ClientFrames.encodeAuth]) must carry
 * the `type` the hub's parser needs plus the auth fields.
 */
class ServerFrameParserTest {

    @Test fun `challenge parses`() {
        val frame = ServerFrameParser.parse("""{"type":"challenge","challenge":"AAECAwQ"}""")
        assertThat(frame).isInstanceOf(ServerFrame.Challenge::class.java)
        assertThat((frame as ServerFrame.Challenge).value.challenge).isEqualTo("AAECAwQ")
    }

    @Test fun `auth_ok parses with hub identity`() {
        val frame = ServerFrameParser.parse(
            """{"type":"auth_ok","hub_id":"hub-1","hub_name":"Kristin's Pixel"}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.AuthOk::class.java)
        val value = (frame as ServerFrame.AuthOk).value
        assertThat(value.hubId).isEqualTo("hub-1")
        assertThat(value.hubName).isEqualTo("Kristin's Pixel")
    }

    @Test fun `auth_failed parses with reason`() {
        val frame = ServerFrameParser.parse("""{"type":"auth_failed","reason":"unknown_device"}""")
        assertThat(frame).isInstanceOf(ServerFrame.AuthFailed::class.java)
        val value = (frame as ServerFrame.AuthFailed).value
        assertThat(value.reason).isEqualTo(SyncProtocol.AuthFailed.UNKNOWN_DEVICE)
    }

    @Test fun `ping and pong parse with the timestamp`() {
        val ping = ServerFrameParser.parse("""{"type":"ping","t":12345}""")
        assertThat(ping).isInstanceOf(ServerFrame.Ping::class.java)
        assertThat((ping as ServerFrame.Ping).value.t).isEqualTo(12345L)
        val pong = ServerFrameParser.parse("""{"type":"pong","t":12345}""")
        assertThat(pong).isInstanceOf(ServerFrame.Pong::class.java)
        assertThat((pong as ServerFrame.Pong).value.t).isEqualTo(12345L)
    }

    @Test fun `revoked parses without a payload`() {
        val frame = ServerFrameParser.parse("""{"type":"revoked"}""")
        assertThat(frame).isEqualTo(ServerFrame.Revoked)
    }

    @Test fun `error parses with the message`() {
        val frame = ServerFrameParser.parse("""{"type":"error","message":"boom"}""")
        assertThat(frame).isInstanceOf(ServerFrame.Error::class.java)
        assertThat((frame as ServerFrame.Error).value.message).isEqualTo("boom")
    }

    @Test fun `sync_started parses`() {
        val frame = ServerFrameParser.parse(
            """{"type":"sync_started","mode":"full","total_conversations":3,""" +
                """"total_messages":42,"cursor_message_id":"","hub_is_default_sms":true}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.SyncStarted::class.java)
        val value = (frame as ServerFrame.SyncStarted).value
        assertThat(value.mode).isEqualTo("full")
        assertThat(value.totalConversations).isEqualTo(3)
        assertThat(value.totalMessages).isEqualTo(42L)
        assertThat(value.hubIsDefaultSms).isTrue()
    }

    @Test fun `sync_complete and hub_status parse`() {
        val complete = ServerFrameParser.parse("""{"type":"sync_complete","cursor_message_id":"m-9"}""")
        assertThat(complete).isInstanceOf(ServerFrame.SyncComplete::class.java)
        assertThat((complete as ServerFrame.SyncComplete).value.cursorMessageId).isEqualTo("m-9")
        val status = ServerFrameParser.parse("""{"type":"hub_status","is_default_sms":false}""")
        assertThat(status).isInstanceOf(ServerFrame.HubStatus::class.java)
        assertThat((status as ServerFrame.HubStatus).value.isDefaultSms).isFalse()
    }

    @Test fun `unknown type is Unknown not Malformed`() {
        val frame = ServerFrameParser.parse("""{"type":"future_frame","x":1}""")
        assertThat(frame).isEqualTo(ServerFrame.Unknown("future_frame"))
    }

    @Test fun `malformed json is Malformed`() {
        assertThat(ServerFrameParser.parse("not json")).isEqualTo(ServerFrame.Malformed)
        assertThat(ServerFrameParser.parse("""{"type":"challenge"""")).isEqualTo(ServerFrame.Malformed)
        assertThat(ServerFrameParser.parse("")).isEqualTo(ServerFrame.Malformed)
    }

    @Test fun `missing type is Malformed`() {
        val frame = ServerFrameParser.parse("""{"challenge":"AAECAwQ"}""")
        assertThat(frame).isEqualTo(ServerFrame.Malformed)
    }

    @Test fun `wrongly shaped known frame is Malformed`() {
        val frame = ServerFrameParser.parse("""{"type":"challenge"}""")
        assertThat(frame).isEqualTo(ServerFrame.Malformed)
    }

    @Test fun `non object json is Malformed`() {
        assertThat(ServerFrameParser.parse("[1,2,3]")).isEqualTo(ServerFrame.Malformed)
        assertThat(ServerFrameParser.parse("42")).isEqualTo(ServerFrame.Malformed)
    }

    @Test fun `encodeAuth carries type and auth fields`() {
        val json = ClientFrames.encodeAuth(
            SyncProtocol.Auth(deviceId = "dev-1", signature = "c2lnbmF0dXJl"),
        )
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("auth")
        val auth = SyncProtocol.json.decodeFromJsonElement<SyncProtocol.Auth>(
            SyncProtocol.Auth.serializer(),
            obj,
        )
        assertThat(auth.deviceId).isEqualTo("dev-1")
        assertThat(auth.signature).isEqualTo("c2lnbmF0dXJl")
    }

    // -- Client mode C4: contacts + avatar frames --------------------

    @Test fun `contacts batch parses`() {
        val frame = ServerFrameParser.parse(
            """{"type":"contacts","has_more":false,"contacts":[""" +
                """{"display_name":"Alice","phones":["+33611111111"],"has_photo":true,"photo_updated_at":7}]}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.Contacts::class.java)
        val value = (frame as ServerFrame.Contacts).value
        assertThat(value.hasMore).isFalse()
        assertThat(value.contacts.single().displayName).isEqualTo("Alice")
        assertThat(value.contacts.single().photoUpdatedAt).isEqualTo(7L)
    }

    @Test fun `contact_updated parses with removed phones`() {
        val frame = ServerFrameParser.parse(
            """{"type":"contact_updated","contact":{"display_name":"Alice","phones":["+33611111111"],"has_photo":false},""" +
                """"removed_phones":["+33622222222"]}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.ContactUpdated::class.java)
        val value = (frame as ServerFrame.ContactUpdated).value
        assertThat(value.contact.displayName).isEqualTo("Alice")
        assertThat(value.removedPhones).containsExactly("+33622222222").inOrder()
    }

    @Test fun `contact_deleted parses`() {
        val frame = ServerFrameParser.parse(
            """{"type":"contact_deleted","phones":["+33611111111"]}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.ContactDeleted::class.java)
        val value = (frame as ServerFrame.ContactDeleted).value
        assertThat(value.phones).containsExactly("+33611111111").inOrder()
    }

    @Test fun `contacts_error parses`() {
        val frame = ServerFrameParser.parse(
            """{"type":"contacts_error","code":"hub_no_contacts_access","message":"no permission"}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.ContactsError::class.java)
        val value = (frame as ServerFrame.ContactsError).value
        assertThat(value.code).isEqualTo(SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS)
        assertThat(value.message).isEqualTo("no permission")
    }

    @Test fun `attachment_chunk parses`() {
        val frame = ServerFrameParser.parse(
            """{"type":"attachment_chunk","attachment_id":"avatar:+33611111111","offset":0,""" +
                """"data_base64url":"AAEC","final":true,"sha256":"deadbeef","size_bytes":3}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.AttachmentChunk::class.java)
        val value = (frame as ServerFrame.AttachmentChunk).value
        assertThat(value.attachmentId).isEqualTo("avatar:+33611111111")
        assertThat(value.isFinal).isTrue()
        assertThat(value.sizeBytes).isEqualTo(3L)
    }

    @Test fun `encodeContactsRequest carries only the type`() {
        val json = ClientFrames.encodeContactsRequest()
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("contacts_request")
        assertThat(obj.size).isEqualTo(1)
    }

    @Test fun `encodeAvatarRequest carries type and address`() {
        val json = ClientFrames.encodeAvatarRequest(SyncProtocol.AvatarRequest("+33611111111"))
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("avatar_request")
        assertThat(obj["address"]?.jsonPrimitive?.content).isEqualTo("+33611111111")
    }

    @Test fun `encodeAttachmentRequest carries type, attachment id and message id`() {
        val json = ClientFrames.encodeAttachmentRequest(SyncProtocol.AttachmentRequest("42", "7"))
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("attachment_request")
        assertThat(obj["attachment_id"]?.jsonPrimitive?.content).isEqualTo("42")
        assertThat(obj["message_id"]?.jsonPrimitive?.content).isEqualTo("7")
    }

    @Test fun `encodeAttachmentUploadStart carries type and upload fields`() {
        val json = ClientFrames.encodeAttachmentUploadStart(
            SyncProtocol.AttachmentUploadStart(
                clientMessageId = "key-1",
                index = 0,
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                sizeBytes = 5L,
                sha256 = "deadbeef",
            ),
        )
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_start")
        assertThat(obj["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        assertThat(obj["index"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(obj["mime_type"]?.jsonPrimitive?.content).isEqualTo("image/jpeg")
        assertThat(obj["file_name"]?.jsonPrimitive?.content).isEqualTo("photo.jpg")
        assertThat(obj["size_bytes"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(obj["sha256"]?.jsonPrimitive?.content).isEqualTo("deadbeef")
    }

    @Test fun `encodeAttachmentUploadChunk carries type and chunk fields`() {
        val json = ClientFrames.encodeAttachmentUploadChunk(
            SyncProtocol.AttachmentUploadChunk(
                clientMessageId = "key-1",
                index = 0,
                offset = 0L,
                dataBase64Url = "aGVsbG8",
                isFinal = true,
            ),
        )
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("attachment_upload_chunk")
        assertThat(obj["client_message_id"]?.jsonPrimitive?.content).isEqualTo("key-1")
        assertThat(obj["index"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(obj["offset"]?.jsonPrimitive?.content).isEqualTo("0")
        assertThat(obj["data_base64url"]?.jsonPrimitive?.content).isEqualTo("aGVsbG8")
        assertThat(obj["final"]?.jsonPrimitive?.content).isEqualTo("true")
    }

    @Test fun `incoming_call parses with number and timestamp`() {
        val frame = ServerFrameParser.parse(
            """{"type":"incoming_call","number":"+33612345678","timestamp":1700000000000}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.IncomingCall::class.java)
        val value = (frame as ServerFrame.IncomingCall).value
        assertThat(value.number).isEqualTo("+33612345678")
        assertThat(value.timestamp).isEqualTo(1700000000000L)
    }

    @Test fun `call_state parses with state and number`() {
        val frame = ServerFrameParser.parse(
            """{"type":"call_state","state":"ringing","number":"+33612345678"}""",
        )
        assertThat(frame).isInstanceOf(ServerFrame.CallState::class.java)
        val value = (frame as ServerFrame.CallState).value
        assertThat(value.state).isEqualTo(SyncProtocol.CallState.RINGING)
        assertThat(value.number).isEqualTo("+33612345678")
    }

    @Test fun `dial_result parses ok and error`() {
        val ok = ServerFrameParser.parse("""{"type":"dial_result","ok":true}""")
        assertThat(ok).isInstanceOf(ServerFrame.DialResult::class.java)
        assertThat((ok as ServerFrame.DialResult).value.ok).isTrue()
        val refused = ServerFrameParser.parse(
            """{"type":"dial_result","ok":false,"error":"permission_denied"}""",
        )
        assertThat(refused).isInstanceOf(ServerFrame.DialResult::class.java)
        val value = (refused as ServerFrame.DialResult).value
        assertThat(value.ok).isFalse()
        assertThat(value.error).isEqualTo(SyncProtocol.DialResult.PERMISSION_DENIED)
    }

    @Test fun `a malformed incoming_call is Malformed`() {
        val frame = ServerFrameParser.parse("""{"type":"incoming_call","number":"+33612345678"}""")
        assertThat(frame).isEqualTo(ServerFrame.Malformed)
    }

    @Test fun `encodeDialRequest carries type and number`() {
        val json = ClientFrames.encodeDialRequest(SyncProtocol.DialRequest("+33612345678"))
        val obj: JsonObject = SyncProtocol.json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("dial_request")
        assertThat(obj["number"]?.jsonPrimitive?.content).isEqualTo("+33612345678")
    }
}
