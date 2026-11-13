package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Slice 1 — the wire protocol: every message type encodes with its
 * `type` field and parses back to the same value; unknown types and
 * malformed frames are classified, never thrown.
 */
class SyncProtocolTest {

    @Test fun `hello round-trips`() {
        val hello = SyncProtocol.Hello(
            protocol = 1,
            deviceId = DEVICE_ID,
            kind = "desktop",
            name = "Kristin's MacBook",
        )
        val parsed = SyncProtocol.parse(SyncProtocol.encodeHello(hello))
        assertThat(parsed).isEqualTo(SyncProtocol.Parsed.Hello(hello))
    }

    @Test fun `challenge encodes with type and challenge fields`() {
        val encoded = SyncProtocol.encodeChallenge(SyncProtocol.Challenge("abc123"))
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("challenge")
        assertThat(obj["challenge"]?.jsonPrimitive?.content).isEqualTo("abc123")
    }

    @Test fun `auth_ok encodes hub id and name`() {
        val encoded = SyncProtocol.encodeAuthOk(
            SyncProtocol.AuthOk(hubId = HUB_ID, hubName = "SMS Relay"),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("auth_ok")
        assertThat(obj["hub_id"]?.jsonPrimitive?.content).isEqualTo(HUB_ID)
        assertThat(obj["hub_name"]?.jsonPrimitive?.content).isEqualTo("SMS Relay")
    }

    @Test fun `auth_failed encodes the reason`() {
        val encoded = SyncProtocol.encodeAuthFailed(
            SyncProtocol.AuthFailed(SyncProtocol.AuthFailed.BAD_SIGNATURE),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("auth_failed")
        assertThat(obj["reason"]?.jsonPrimitive?.content).isEqualTo("bad_signature")
    }

    @Test fun `ping and pong round-trip with the same timestamp`() {
        val ping = SyncProtocol.Ping(t = 1_700_000_000_000L)
        assertThat(SyncProtocol.parse(SyncProtocol.encodePing(ping)))
            .isEqualTo(SyncProtocol.Parsed.Ping(ping))
        assertThat(SyncProtocol.parse(SyncProtocol.encodePong(ping)))
            .isEqualTo(SyncProtocol.Parsed.Pong(ping))
    }

    @Test fun `revoked is exactly the bare type object`() {
        assertThat(SyncProtocol.REVOKED_JSON).isEqualTo("""{"type":"revoked"}""")
    }

    @Test fun `error encodes type and message`() {
        val encoded = SyncProtocol.encodeError("protocol violation")
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(obj["message"]?.jsonPrimitive?.content).isEqualTo("protocol violation")
    }

    @Test fun `unknown type is classified, not malformed`() {
        val parsed = SyncProtocol.parse("""{"type":"future_thing","x":1}""")
        assertThat(parsed).isEqualTo(SyncProtocol.Parsed.Unknown("future_thing"))
    }

    @Test fun `missing type is malformed`() {
        assertThat(SyncProtocol.parse("""{"protocol":1}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `not json at all is malformed`() {
        assertThat(SyncProtocol.parse("this is not json"))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `a json array is malformed`() {
        assertThat(SyncProtocol.parse("""[1, 2, 3]"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `hello with a missing field is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"hello","protocol":1,"device_id":"$DEVICE_ID"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `extra unknown fields are ignored`() {
        val parsed = SyncProtocol.parse(
            """{"type":"ping","t":42,"future_field":"kept out"}""",
        )
        assertThat(parsed).isEqualTo(SyncProtocol.Parsed.Ping(SyncProtocol.Ping(t = 42)))
    }

    @Test fun `linked device list round-trips through json`() {
        val devices = listOf(
            LinkedDevice(
                deviceId = DEVICE_ID,
                kind = DeviceKind.DESKTOP,
                displayName = "Kristin's MacBook",
                publicKeyBase64Url = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                linkedAt = 1_700_000_000_000L,
                lastSeen = null,
            ),
            LinkedDevice(
                deviceId = "123e4567-e89b-12d3-a456-426614174001",
                kind = DeviceKind.TABLET,
                displayName = "iPad",
                publicKeyBase64Url = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                linkedAt = 1_700_000_100_000L,
                lastSeen = 1_700_000_200_000L,
            ),
        )
        val encoded = SyncProtocol.json.encodeToString(devices)
        val decoded: List<LinkedDevice> = SyncProtocol.json.decodeFromString(encoded)
        assertThat(decoded).isEqualTo(devices)
        // The wire names are the snake_case serial names, not the Kotlin ones.
        assertThat(encoded).contains("\"device_id\"")
        assertThat(encoded).contains("\"public_key\"")
        assertThat(encoded).contains("\"desktop\"")
    }

    companion object {
        private const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"
        private const val HUB_ID = "123e4567-e89b-12d3-a456-426614174099"
    }
}
