package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 3 — the contact-photo wire types: `set_contact_photo`
 * parses (the desktop's request) and `action_result` carries the
 * address echo (the phone's ack). The frame shapes are the contract
 * the desktop creates against, so the field names and the required
 * fields are pinned here.
 */
class SyncProtocolContactPhotoTest {

    @Test fun `set_contact_photo parses with address and photo bytes`() {
        val frame = """{"type":"set_contact_photo","address":"+15551234567","photo_base64url":"_9j_4AAQ"}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(
            SyncProtocol.Parsed.SetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = "_9j_4AAQ",
                ),
            ),
        )
    }

    @Test fun `set_contact_photo without an address is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"set_contact_photo","photo_base64url":"_9j_4AAQ"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `set_contact_photo without photo bytes is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"set_contact_photo","address":"+15551234567"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `set_contact_photo with a non-string address is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"set_contact_photo","address":123,"photo_base64url":"_9j_4AAQ"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `action_result encodes the address echo for set_contact_photo`() {
        val json = SyncProtocol.encodeActionResult(
            SyncProtocol.ActionResult(
                action = "set_contact_photo",
                address = "+15551234567",
                ok = true,
            ),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("action_result")
        assertThat(obj["action"]?.jsonPrimitive?.content).isEqualTo("set_contact_photo")
        assertThat(obj["address"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        assertThat(obj["ok"]?.jsonPrimitive?.content).isEqualTo("true")
        // No conversation exists for a photo set — the frame carries no id.
        assertThat(obj.containsKey("conversation_id")).isFalse()
    }

    @Test fun `action_result omits the address when it is absent (older action types unchanged)`() {
        val json = SyncProtocol.encodeActionResult(
            SyncProtocol.ActionResult(
                action = "set_pin",
                conversationId = "123",
                ok = true,
            ),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj.containsKey("address")).isFalse()
        assertThat(obj["conversation_id"]?.jsonPrimitive?.content).isEqualTo("123")
    }
}
