package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 42 — the wire shapes of `e2e_safety_numbers`,
 * `e2e_notices`, `set_e2e_verified` and `retry_e2e_probe`, plus
 * the `address` echo on `action_result`. The phone is the source
 * of truth for the safety-number + notice sets; the desktop
 * replaces its own sets wholesale.
 */
class SyncProtocolE2eTest {

    @Test fun `e2e_safety_numbers encodes the full peer set`() {
        val encoded = SyncProtocol.encodeE2eSafetyNumbers(
            SyncProtocol.E2eSafetyNumbers(
                peers = listOf(
                    SyncedE2ePeer(
                        address = "+33612345678",
                        state = "encrypted",
                        safetyNumber = "12345 67890 12345 67890 12345 67890 " +
                            "12345 67890 12345 67890 12345 67890",
                        verified = true,
                    ),
                    SyncedE2ePeer(
                        address = "+33687654321",
                        state = "probing",
                        safetyNumber = null,
                        verified = false,
                    ),
                ),
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("e2e_safety_numbers")
        val peers = obj["peers"]!!.jsonArray.map { it.jsonObject }
        assertThat(peers).hasSize(2)
        assertThat(peers[0]["address"]?.jsonPrimitive?.content).isEqualTo("+33612345678")
        assertThat(peers[0]["state"]?.jsonPrimitive?.content).isEqualTo("encrypted")
        assertThat(peers[0]["safety_number"]?.jsonPrimitive?.content).startsWith("12345 67890")
        assertThat(peers[0]["verified"]?.jsonPrimitive?.content).isEqualTo("true")
        // A peer without keys carries no number: nulls are omitted
        // on encode (the protocol-wide convention), so the key is
        // absent — never an explicit null.
        assertThat(peers[1].containsKey("safety_number")).isFalse()
        assertThat(peers[1]["state"]?.jsonPrimitive?.content).isEqualTo("probing")
    }

    @Test fun `e2e_safety_numbers encodes an empty set`() {
        val encoded = SyncProtocol.encodeE2eSafetyNumbers(
            SyncProtocol.E2eSafetyNumbers(peers = emptyList()),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("e2e_safety_numbers")
        assertThat(obj["peers"]!!.jsonArray).isEmpty()
    }

    @Test fun `e2e_notices encodes notices with decimal-string ids`() {
        val encoded = SyncProtocol.encodeE2eNotices(
            SyncProtocol.E2eNotices(
                notices = listOf(
                    SyncedE2eNotice(
                        id = "12",
                        conversationId = "123",
                        kind = "activated",
                        date = 1_793_000_000_000L,
                    ),
                    SyncedE2eNotice(
                        id = "13",
                        conversationId = "123",
                        kind = "key_changed_verified",
                        date = 1_793_000_100_000L,
                    ),
                ),
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("e2e_notices")
        val notices = obj["notices"]!!.jsonArray.map { it.jsonObject }
        assertThat(notices).hasSize(2)
        assertThat(notices[0]["id"]?.jsonPrimitive?.content).isEqualTo("12")
        assertThat(notices[0]["conversation_id"]?.jsonPrimitive?.content).isEqualTo("123")
        assertThat(notices[0]["kind"]?.jsonPrimitive?.content).isEqualTo("activated")
        assertThat(notices[0]["date"]?.jsonPrimitive?.content).isEqualTo("1793000000000")
        assertThat(notices[1]["kind"]?.jsonPrimitive?.content).isEqualTo("key_changed_verified")
    }

    @Test fun `e2e_notices encodes an empty set`() {
        val encoded = SyncProtocol.encodeE2eNotices(
            SyncProtocol.E2eNotices(notices = emptyList()),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("e2e_notices")
        assertThat(obj["notices"]!!.jsonArray).isEmpty()
    }

    @Test fun `set_e2e_verified parses`() {
        assertThat(SyncProtocol.parse("""{"type":"set_e2e_verified","address":"+33612345678","verified":true}"""))
            .isEqualTo(
                SyncProtocol.Parsed.SetE2eVerified(
                    SyncProtocol.SetE2eVerified(address = "+33612345678", verified = true),
                ),
            )
    }

    @Test fun `set_e2e_verified without verified is malformed`() {
        // Like `set_muted`'s `value`, the flag is required — a
        // missing flag is never a silent unverify.
        assertThat(SyncProtocol.parse("""{"type":"set_e2e_verified","address":"+33612345678"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `set_e2e_verified without an address is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"set_e2e_verified","verified":true}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `retry_e2e_probe parses`() {
        assertThat(SyncProtocol.parse("""{"type":"retry_e2e_probe","address":"+33612345678"}"""))
            .isEqualTo(
                SyncProtocol.Parsed.RetryE2eProbe(
                    SyncProtocol.RetryE2eProbe(address = "+33612345678"),
                ),
            )
    }

    @Test fun `retry_e2e_probe without an address is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"retry_e2e_probe"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `action_result echoes the address for the E2E commands`() {
        val encoded = SyncProtocol.encodeActionResult(
            SyncProtocol.ActionResult(
                action = "set_e2e_verified",
                address = "+33612345678",
                ok = true,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("action_result")
        assertThat(obj["action"]?.jsonPrimitive?.content).isEqualTo("set_e2e_verified")
        assertThat(obj["address"]?.jsonPrimitive?.content).isEqualTo("+33612345678")
        assertThat(obj["ok"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(obj.containsKey("conversation_id")).isFalse()
    }

    @Test fun `e2e_safety_numbers from the client side is unknown`() {
        // The phone only SENDS `e2e_safety_numbers` — an inbound
        // one is not a frame the server accepts.
        assertThat(SyncProtocol.parse("""{"type":"e2e_safety_numbers","peers":[]}"""))
            .isEqualTo(SyncProtocol.Parsed.Unknown("e2e_safety_numbers"))
    }

    @Test fun `e2e_notices from the client side is unknown`() {
        // The phone only SENDS `e2e_notices` — an inbound one is
        // not a frame the server accepts.
        assertThat(SyncProtocol.parse("""{"type":"e2e_notices","notices":[]}"""))
            .isEqualTo(SyncProtocol.Parsed.Unknown("e2e_notices"))
    }

    @Test fun `an explicit null safety_number still decodes as absent`() {
        // Belt and braces for the desktop's lenient reader: a
        // `null` where the phone omits the key decodes the same
        // way (the field is nullable with a null default).
        val decoded = Json.decodeFromString<SyncedE2ePeer>(
            """{"address":"+33612345678","state":"unknown","safety_number":null,"verified":false}""",
        )
        assertThat(decoded.safetyNumber).isNull()
        assertThat(decoded).isEqualTo(
            Json.decodeFromString<SyncedE2ePeer>(
                """{"address":"+33612345678","state":"unknown","verified":false}""",
            ),
        )
    }
}
