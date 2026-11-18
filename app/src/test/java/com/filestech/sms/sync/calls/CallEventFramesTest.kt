package com.filestech.sms.sync.calls

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Live calls — the wire shape of one call event: a ringing phone
 * sends BOTH `incoming_call` and `call_state` `ringing`; every
 * other transition sends only its `call_state`. The frame shapes
 * are the contract the desktop creates against, so the field
 * names are pinned here.
 */
class CallEventFramesTest {

    @Test fun `ringing encodes incoming_call plus call_state ringing`() {
        val frames = encodeCallEventFrames(
            CallEvent.Ringing(number = "+15551234567", timestampMs = 1794000000000L),
        )
        assertThat(frames).hasSize(2)
        val incoming = Json.parseToJsonElement(frames[0]).jsonObject
        assertThat(incoming["type"]?.jsonPrimitive?.content).isEqualTo("incoming_call")
        assertThat(incoming["number"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        assertThat(incoming["timestamp"]?.jsonPrimitive?.content).isEqualTo("1794000000000")
        val state = Json.parseToJsonElement(frames[1]).jsonObject
        assertThat(state["type"]?.jsonPrimitive?.content).isEqualTo("call_state")
        assertThat(state["state"]?.jsonPrimitive?.content).isEqualTo("ringing")
        assertThat(state["number"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
    }

    @Test fun `active encodes one call_state active`() {
        val frames = encodeCallEventFrames(
            CallEvent.Active(number = "+15551234567", timestampMs = 1794000001000L),
        )
        assertThat(frames).hasSize(1)
        val state = Json.parseToJsonElement(frames.first()).jsonObject
        assertThat(state["type"]?.jsonPrimitive?.content).isEqualTo("call_state")
        assertThat(state["state"]?.jsonPrimitive?.content).isEqualTo("active")
        assertThat(state["number"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
    }

    @Test fun `ended encodes one call_state ended`() {
        val frames = encodeCallEventFrames(
            CallEvent.Ended(number = "+15551234567", timestampMs = 1794000002000L),
        )
        assertThat(frames).hasSize(1)
        val state = Json.parseToJsonElement(frames.first()).jsonObject
        assertThat(state["type"]?.jsonPrimitive?.content).isEqualTo("call_state")
        assertThat(state["state"]?.jsonPrimitive?.content).isEqualTo("ended")
        assertThat(state["number"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
    }

    @Test fun `an unknown number rides as an empty string, not a missing field`() {
        val frames = encodeCallEventFrames(
            CallEvent.Ringing(number = "", timestampMs = 1794000000000L),
        )
        val incoming = Json.parseToJsonElement(frames[0]).jsonObject
        assertThat(incoming.containsKey("number")).isTrue()
        assertThat(incoming["number"]?.jsonPrimitive?.content).isEmpty()
    }
}
