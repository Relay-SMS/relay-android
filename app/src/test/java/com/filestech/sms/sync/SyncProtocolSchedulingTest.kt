package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Phase 2 — the scheduled-send wire types: `schedule_message` parses
 * (the desktop's request) and `message_scheduled` encodes (the
 * phone's ack). The frame shapes are the contract the desktop
 * schedules against, so the field names and the required fields are
 * pinned here.
 */
class SyncProtocolSchedulingTest {

    @Test fun `schedule_message parses with all fields`() {
        val frame = """{"type":"schedule_message","client_message_id":"$CLIENT_KEY","conversation_id":"123",""" +
            """"body":"Happy birthday!","send_at":1800000000000}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(
            SyncProtocol.Parsed.ScheduleMessage(
                SyncProtocol.ScheduleMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "123",
                    body = "Happy birthday!",
                    sendAt = 1_800_000_000_000L,
                ),
            ),
        )
    }

    @Test fun `schedule_message without send_at is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"schedule_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","body":"Hi"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `schedule_message without a client key is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"schedule_message","conversation_id":"123","body":"Hi","send_at":1800000000000}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `schedule_message without a body is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"schedule_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","send_at":1800000000000}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `schedule_message with a non-numeric send_at is malformed`() {
        val frame = """{"type":"schedule_message","client_message_id":"$CLIENT_KEY","conversation_id":"123",""" +
            """"body":"Hi","send_at":"tomorrow"}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `message_scheduled encodes type and all fields`() {
        val json = SyncProtocol.encodeMessageScheduled(
            SyncProtocol.MessageScheduled(
                clientMessageId = CLIENT_KEY,
                messageId = "4242",
                conversationId = "123",
                sendAt = 1_800_000_000_000L,
            ),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("message_scheduled")
        assertThat(obj["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
        assertThat(obj["message_id"]?.jsonPrimitive?.content).isEqualTo("4242")
        assertThat(obj["conversation_id"]?.jsonPrimitive?.content).isEqualTo("123")
        assertThat(obj["send_at"]?.jsonPrimitive?.content).isEqualTo("1800000000000")
    }

    @Test fun `cancel_scheduled parses with the client key`() {
        assertThat(
            SyncProtocol.parse("""{"type":"cancel_scheduled","client_message_id":"$CLIENT_KEY"}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.CancelScheduled(
                SyncProtocol.CancelScheduled(clientMessageId = CLIENT_KEY),
            ),
        )
    }

    @Test fun `cancel_scheduled without a client key is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"cancel_scheduled"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `schedule_cancelled encodes type and the client key`() {
        val json = SyncProtocol.encodeScheduleCancelled(
            SyncProtocol.ScheduleCancelled(clientMessageId = CLIENT_KEY),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("schedule_cancelled")
        assertThat(obj["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
    }

    private companion object {
        private const val CLIENT_KEY = "3f2a1b00-0000-4000-8000-00000000c41d"
    }
}
