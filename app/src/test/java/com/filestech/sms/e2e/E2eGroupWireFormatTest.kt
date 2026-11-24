package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2eGroupSenderKeyMessage.SenderKeyDistribution
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Encrypted-groups wire format tests (round 49, design §13):
 * the group id derivation, the group message envelope, the
 * sender-key distribution / key-request JSON, and the GROUP
 * message-type pairing in [E2eWireFormat].
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose body returns a value.
 */
class E2eGroupWireFormatTest {

    private companion object {
        const val ALICE = "+1 (610) 842-3232"
        const val BOB = "6108423233"
        const val CAROL = "+16108423234"
        val MEMBERS = listOf(ALICE, BOB, CAROL)
        val KEY = ByteArray(32) { it.toByte() }
        const val GROUP_ID = "0123456789abcdef0123456789abcdef"
        const val KEY_ID = "0123456789abcdef"
    }

    private fun distribution(
        groupId: String = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS),
        keyId: String = KEY_ID,
        key: ByteArray = KEY,
        members: List<String> = MEMBERS,
        generation: Int = 1,
    ) = SenderKeyDistribution(
        groupId = groupId,
        keyId = keyId,
        key = key,
        members = members,
        generation = generation,
    )

    // ──────────────────────────── Group id ────────────────────────────

    @Test
    fun `the group id is order-independent`() {
        val forward = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL))
        val reversed = E2eGroupWireFormat.deriveGroupIdHex(listOf(CAROL, BOB, ALICE))

        assertThat(forward).isEqualTo(reversed)
    }

    @Test
    fun `the group id is form-independent (E164 vs national)`() {
        val e164 = E2eGroupWireFormat.deriveGroupIdHex(listOf("+16108423232", BOB, CAROL))
        val national = E2eGroupWireFormat.deriveGroupIdHex(listOf("6108423232", BOB, CAROL))

        assertThat(e164).isEqualTo(national)
    }

    @Test
    fun `a different member list derives a different group id`() {
        val three = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS)
        val twoPlusDana = E2eGroupWireFormat.deriveGroupIdHex(
            listOf(ALICE, BOB, "+16108423235"),
        )
        val four = E2eGroupWireFormat.deriveGroupIdHex(
            listOf(ALICE, BOB, CAROL, "+16108423235"),
        )

        assertThat(three).isNotEqualTo(twoPlusDana)
        assertThat(three).isNotEqualTo(four)
    }

    @Test
    fun `the group id is 32 lowercase hex chars`() {
        val id = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS)

        assertThat(id.length).isEqualTo(32)
        assertThat(E2eGroupWireFormat.isValidGroupIdHex(id)).isTrue()
        assertThat(E2eGroupWireFormat.isValidGroupIdHex(id.uppercase())).isFalse()
        assertThat(E2eGroupWireFormat.isValidGroupIdHex("zzz")).isFalse()
    }

    @Test
    fun `canonicalMembers sorts, dedupes, and canonicalizes`() {
        val members = E2eGroupWireFormat.canonicalMembers(
            listOf(CAROL, ALICE, "6108423232", BOB),
        )

        assertThat(members).containsExactly("6108423232", "6108423233", "6108423234").inOrder()
    }

    // ──────────────────────────── Envelope ────────────────────────────

    @Test
    fun `an envelope round-trips through encode and parse`() {
        val blob = ByteArray(48) { (it * 3).toByte() }

        val encoded = E2eGroupWireFormat.encodeEnvelope(GROUP_ID, KEY_ID, blob)
        val parsed = E2eGroupWireFormat.parseEnvelope(encoded!!)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.groupIdHex).isEqualTo(GROUP_ID)
        assertThat(parsed.keyIdHex).isEqualTo(KEY_ID)
        assertThat(parsed.aeadBlob).isEqualTo(blob)
    }

    @Test
    fun `the envelope header is the group id then the key id in the clear`() {
        val blob = byteArrayOf(0x01, 0x02, 0x03)

        val encoded = E2eGroupWireFormat.encodeEnvelope(GROUP_ID, KEY_ID, blob)!!

        // The header is 24 bytes (16 + 8) before the blob.
        assertThat(encoded.size).isEqualTo(24 + 3)
        assertThat(encoded.copyOfRange(24, 27)).isEqualTo(blob)
    }

    @Test
    fun `parseEnvelope rejects a truncated envelope`() {
        assertThat(E2eGroupWireFormat.parseEnvelope(ByteArray(10))).isNull()
        assertThat(E2eGroupWireFormat.parseEnvelope(ByteArray(24))).isNull()
        assertThat(E2eGroupWireFormat.parseEnvelope(ByteArray(0))).isNull()
    }

    @Test
    fun `encodeEnvelope rejects malformed ids`() {
        val blob = byteArrayOf(0x01)

        assertThat(E2eGroupWireFormat.encodeEnvelope("not-hex", KEY_ID, blob)).isNull()
        assertThat(E2eGroupWireFormat.encodeEnvelope(GROUP_ID, "nope", blob)).isNull()
    }

    @Test
    fun `the AAD binds the group, the sender, and the key`() {
        val aad = E2eGroupWireFormat.aadFor(GROUP_ID, ALICE, KEY_ID)!!
        val otherGroup = E2eGroupWireFormat.aadFor(
            "ffffffffffffffffffffffffffffffff",
            ALICE,
            KEY_ID,
        )!!
        val otherSender = E2eGroupWireFormat.aadFor(GROUP_ID, BOB, KEY_ID)!!
        val otherKey = E2eGroupWireFormat.aadFor(GROUP_ID, ALICE, "ffffffffffffffff")!!

        assertThat(aad).isNotEqualTo(otherGroup)
        assertThat(aad).isNotEqualTo(otherSender)
        assertThat(aad).isNotEqualTo(otherKey)
        // The sender address canonicalizes: E.164 and national
        // spellings of the same number give the same AAD.
        assertThat(E2eGroupWireFormat.aadFor(GROUP_ID, "6108423232", KEY_ID))
            .isEqualTo(aad)
    }

    @Test
    fun `generated sender keys and key ids are well-formed and distinct`() {
        val key1 = E2eGroupWireFormat.generateSenderKey()
        val key2 = E2eGroupWireFormat.generateSenderKey()
        val id1 = E2eGroupWireFormat.generateKeyId()
        val id2 = E2eGroupWireFormat.generateKeyId()

        assertThat(key1.size).isEqualTo(32)
        assertThat(key1).isNotEqualTo(key2)
        assertThat(E2eGroupWireFormat.isValidKeyIdHex(id1)).isTrue()
        assertThat(id1).isNotEqualTo(id2)
    }

    // ──────────────────────────── Distribution JSON ────────────────────────────

    @Test
    fun `a distribution round-trips through encode and decode`() {
        // Decode canonicalizes the member list (the sender always
        // distributes canonical members), so the round-trip input
        // must be canonical too — the raw MEMBERS list carries
        // CAROL in +1 form, which decode strips.
        val original = distribution(members = E2eGroupWireFormat.canonicalMembers(MEMBERS))

        val decoded = E2eGroupSenderKeyMessage.decodeDistribution(
            E2eGroupSenderKeyMessage.encodeDistribution(original),
        )

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decodeDistribution rejects a group id that does not hash from the members`() {
        // The id is for the 3-member list, but the members are a
        // different list — a stale or forged list must not route
        // messages into the wrong conversation. (The constructor
        // does not cross-check the id against the list — only
        // decode does.)
        val json = E2eGroupSenderKeyMessage.encodeDistribution(
            distribution(members = listOf(ALICE, BOB, "+16108423235")),
        )

        assertThat(E2eGroupSenderKeyMessage.decodeDistribution(json)).isNull()
    }

    @Test
    fun `decodeDistribution rejects malformed distributions`() {
        val good = E2eGroupSenderKeyMessage.encodeDistribution(distribution())

        assertThat(E2eGroupSenderKeyMessage.decodeDistribution("not json")).isNull()
        assertThat(E2eGroupSenderKeyMessage.decodeDistribution("")).isNull()
        assertThat(
            E2eGroupSenderKeyMessage.decodeDistribution(good.replace("\"v\":1", "\"v\":2")),
        ).isNull()
        assertThat(
            E2eGroupSenderKeyMessage.decodeDistribution(good.replace("\"gen\":1", "\"gen\":0")),
        ).isNull()
        assertThat(
            E2eGroupSenderKeyMessage.decodeDistribution(
                good.replace(
                    "\"key\":\"${Base64.getEncoder().encodeToString(KEY)}\"",
                    "\"key\":\"AAAA\"",
                ),
            ),
        ).isNull()
    }

    @Test
    fun `decodeDistribution rejects fewer than three members`() {
        // The constructor itself rejects fewer than 3 members, so
        // the JSON can only be built by hand — decode must reject
        // it too (a peer running a different build could send it).
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB))
        val two = "{\"v\":1,\"group\":\"$groupId\",\"kid\":\"$KEY_ID\"," +
            "\"key\":\"${Base64.getEncoder().encodeToString(KEY)}\"," +
            "\"members\":[\"$ALICE\",\"$BOB\"],\"gen\":1}"

        assertThat(E2eGroupSenderKeyMessage.decodeDistribution(two)).isNull()
    }

    @Test
    fun `the distribution constructor rejects malformed fields`() {
        assertThat(
            runCatching { distribution(groupId = "nope") }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { distribution(keyId = "nope") }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { distribution(key = ByteArray(16)) }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { distribution(generation = 0) }.isFailure,
        ).isTrue()
        assertThat(
            runCatching { distribution(members = listOf(ALICE, BOB)) }.isFailure,
        ).isTrue()
    }

    @Test
    fun `toString never prints the key bytes`() {
        val text = distribution().toString()

        assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(KEY))
        assertThat(text).contains("<32-byte key>")
    }

    // ──────────────────────────── Key request JSON ────────────────────────────

    @Test
    fun `a key request round-trips through encode and decode`() {
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS)

        val decoded = E2eGroupSenderKeyMessage.decodeKeyRequest(
            E2eGroupSenderKeyMessage.encodeKeyRequest(groupId),
        )

        assertThat(decoded).isEqualTo(groupId)
    }

    @Test
    fun `decodeKeyRequest rejects malformed requests`() {
        val good = E2eGroupSenderKeyMessage.encodeKeyRequest(GROUP_ID)

        assertThat(E2eGroupSenderKeyMessage.decodeKeyRequest("not json")).isNull()
        assertThat(E2eGroupSenderKeyMessage.decodeKeyRequest("")).isNull()
        assertThat(
            E2eGroupSenderKeyMessage.decodeKeyRequest(good.replace("\"v\":1", "\"v\":2")),
        ).isNull()
        assertThat(
            E2eGroupSenderKeyMessage.decodeKeyRequest(good.replace(GROUP_ID, "nope")),
        ).isNull()
    }

    // ──────────────────────────── Wire pairing ────────────────────────────

    @Test
    fun `GROUP pairs only with the group payload types`() {
        assertThat(
            E2eWireFormat.isValidPairing(OlmMessageType.GROUP, E2eWireFormat.PayloadType.GROUP_TEXT),
        ).isTrue()
        assertThat(
            E2eWireFormat.isValidPairing(
                OlmMessageType.GROUP,
                E2eWireFormat.PayloadType.GROUP_REACTION,
            ),
        ).isTrue()
        assertThat(
            E2eWireFormat.isValidPairing(OlmMessageType.GROUP, E2eWireFormat.PayloadType.TEXT),
        ).isFalse()
        assertThat(
            E2eWireFormat.isValidPairing(OlmMessageType.GROUP, E2eWireFormat.PayloadType.MEDIA_KEY),
        ).isFalse()
        assertThat(
            E2eWireFormat.isValidPairing(OlmMessageType.NORMAL, E2eWireFormat.PayloadType.GROUP_TEXT),
        ).isFalse()
        assertThat(
            E2eWireFormat.isValidPairing(OlmMessageType.PRE_KEY, E2eWireFormat.PayloadType.SENDER_KEY),
        ).isTrue()
        assertThat(
            E2eWireFormat.isValidPairing(
                OlmMessageType.NORMAL,
                E2eWireFormat.PayloadType.GROUP_KEY_REQUEST,
            ),
        ).isTrue()
    }

    @Test
    fun `a GROUP message round-trips through encode, fragment, assemble, and parse`() {
        val envelope = E2eGroupWireFormat.encodeEnvelope(GROUP_ID, KEY_ID, ByteArray(40) { it.toByte() })!!
        val message = EncryptedMessage(OlmMessageType.GROUP, envelope)

        val fragments = E2eWireFormat.encode("abcdef", message, E2eWireFormat.PayloadType.GROUP_TEXT)
        val parsed = fragments.map { E2eWireFormat.parseFragment(it)!! }
        val decoded = E2eWireFormat.parsePayload(E2eWireFormat.assemble(parsed))

        assertThat(decoded.message.type).isEqualTo(OlmMessageType.GROUP)
        assertThat(decoded.payloadType).isEqualTo(E2eWireFormat.PayloadType.GROUP_TEXT)
        assertThat(decoded.message.bytes).isEqualTo(envelope)
    }

    @Test
    fun `a crossed GROUP pairing is rejected at encode and at parse`() {
        val envelope = E2eGroupWireFormat.encodeEnvelope(GROUP_ID, KEY_ID, ByteArray(40))!!
        val groupMessage = EncryptedMessage(OlmMessageType.GROUP, envelope)

        assertThat(
            runCatching {
                E2eWireFormat.encodePayload(groupMessage, E2eWireFormat.PayloadType.TEXT)
            }.isFailure,
        ).isTrue()
        // A crossed payload built by hand (header bytes only) is
        // rejected at parse: GROUP type (0x03) + TEXT payload (0x01).
        val crossed = byteArrayOf(0x01, 0x03, 0x01) + envelope
        assertThat(
            runCatching { E2eWireFormat.parsePayload(crossed) }.isFailure,
        ).isTrue()
    }
}
