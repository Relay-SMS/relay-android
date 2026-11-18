package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Live calls — the call-signaling wire types: `dial_request`
 * parses (the desktop's request), and `incoming_call`,
 * `call_state` and `dial_result` encode (the phone's frames).
 * The frame shapes are the contract the desktop creates against,
 * so the field names and the required fields are pinned here.
 */
class SyncProtocolCallsTest {

    @Test fun `dial_request parses with a number`() {
        val frame = """{"type":"dial_request","number":"+15551234567"}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(
            SyncProtocol.Parsed.DialRequest(
                SyncProtocol.DialRequest(number = "+15551234567"),
            ),
        )
    }

    @Test fun `dial_request without a number is malformed`() {
        assertThat(
            SyncProtocol.parse("""{"type":"dial_request"}"""),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `dial_request with a non-string number is malformed`() {
        assertThat(
            SyncProtocol.parse("""{"type":"dial_request","number":123}"""),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `incoming_call encodes number and timestamp`() {
        val json = SyncProtocol.encodeIncomingCall(
            SyncProtocol.IncomingCall(number = "+15551234567", timestamp = 1794000000000L),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("incoming_call")
        assertThat(obj["number"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        assertThat(obj["timestamp"]?.jsonPrimitive?.content).isEqualTo("1794000000000")
    }

    @Test fun `call_state encodes each state`() {
        for (state in listOf("ringing", "active", "ended")) {
            val json = SyncProtocol.encodeCallState(
                SyncProtocol.CallState(state = state, number = "+15551234567"),
            )
            val obj = Json.parseToJsonElement(json).jsonObject
            assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("call_state")
            assertThat(obj["state"]?.jsonPrimitive?.content).isEqualTo(state)
            assertThat(obj["number"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        }
    }

    @Test fun `dial_result ok true omits the error`() {
        val json = SyncProtocol.encodeDialResult(SyncProtocol.DialResult(ok = true))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("dial_result")
        assertThat(obj["ok"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(obj.containsKey("error")).isFalse()
    }

    @Test fun `dial_result ok false carries the snake_case error`() {
        val json = SyncProtocol.encodeDialResult(
            SyncProtocol.DialResult(
                ok = false,
                error = SyncProtocol.DialResult.PERMISSION_DENIED,
            ),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("dial_result")
        assertThat(obj["ok"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(obj["error"]?.jsonPrimitive?.content).isEqualTo("permission_denied")
    }

    @Test fun `dial_result round-trips through parse as unknown (server-only frame)`() {
        // The phone never receives its own ack: a `dial_result`
        // arriving on the socket is an unknown type, not a parse
        // error — the connection stays up.
        assertThat(
            SyncProtocol.parse("""{"type":"dial_result","ok":true}"""),
        ).isEqualTo(SyncProtocol.Parsed.Unknown("dial_result"))
    }
}
