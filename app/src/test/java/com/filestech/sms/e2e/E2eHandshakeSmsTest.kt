package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2eHandshake.HandshakePacket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Handshake-over-regular-SMS wire-format tests (round 45): the
 * `RLYH:` + base64 envelope around the existing 70/102-byte packets,
 * the single-segment char counts (101 / 141), invalid-input
 * rejection, and the two-direction disjointness from the `RLY1:`
 * ciphertext fragments. Pure JVM — no telephony, no Android APIs.
 */
class E2eHandshakeSmsTest {

    private val curveKey: ByteArray = ByteArray(32) { it.toByte() }
    private val edKey: ByteArray = ByteArray(32) { (it + 32).toByte() }
    private val oneTimeKey: ByteArray = ByteArray(32) { (it + 64).toByte() }

    @Test
    fun `a probe encodes to a 101-char text and round-trips`() {
        val text = E2eHandshakeSms.encode(E2eHandshake.encodeProbe(curveKey, edKey))

        assertThat(E2eHandshakeSms.PROBE_TEXT_LENGTH).isEqualTo(101)
        assertThat(text.length).isEqualTo(E2eHandshakeSms.PROBE_TEXT_LENGTH)
        assertThat(text).startsWith("RLYH:")
        val decoded = E2eHandshake.decode(E2eHandshakeSms.parse(text)!!)
        assertThat(decoded).isInstanceOf(HandshakePacket.Probe::class.java)
        val probe = decoded as HandshakePacket.Probe
        assertThat(probe.curveKey).isEqualTo(curveKey)
        assertThat(probe.edKey).isEqualTo(edKey)
    }

    @Test
    fun `a manual probe survives the text round-trip at the same length`() {
        val text = E2eHandshakeSms.encode(
            E2eHandshake.encodeProbe(curveKey, edKey, manual = true),
        )

        // The flag rides inside the same 70 bytes — the text is
        // still 101 chars, still one segment.
        assertThat(text.length).isEqualTo(E2eHandshakeSms.PROBE_TEXT_LENGTH)
        val decoded = E2eHandshake.decode(E2eHandshakeSms.parse(text)!!)
        assertThat(decoded).isInstanceOf(HandshakePacket.Probe::class.java)
        val probe = decoded as HandshakePacket.Probe
        assertThat(probe.manual).isTrue()
        assertThat(probe.curveKey).isEqualTo(curveKey)
        assertThat(probe.edKey).isEqualTo(edKey)
    }

    @Test
    fun `a bundle encodes to a 141-char text and round-trips`() {
        val text = E2eHandshakeSms.encode(
            E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey),
        )

        assertThat(E2eHandshakeSms.BUNDLE_TEXT_LENGTH).isEqualTo(141)
        assertThat(text.length).isEqualTo(E2eHandshakeSms.BUNDLE_TEXT_LENGTH)
        assertThat(text).startsWith("RLYH:")
        val decoded = E2eHandshake.decode(E2eHandshakeSms.parse(text)!!)
        assertThat(decoded).isInstanceOf(HandshakePacket.Bundle::class.java)
        val bundle = decoded as HandshakePacket.Bundle
        assertThat(bundle.curveKey).isEqualTo(curveKey)
        assertThat(bundle.edKey).isEqualTo(edKey)
        assertThat(bundle.oneTimeKey).isEqualTo(oneTimeKey)
    }

    @Test
    fun `both texts fit in one 160-char GSM-7 segment`() {
        val probe = E2eHandshakeSms.encode(E2eHandshake.encodeProbe(curveKey, edKey))
        val bundle = E2eHandshakeSms.encode(
            E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey),
        )

        assertThat(probe.length).isLessThan(160)
        assertThat(bundle.length).isLessThan(160)
        // The base64 alphabet is entirely GSM-7: no UCS-2 fallback,
        // no multi-part sending.
        assertThat(probe).matches("RLYH:[A-Za-z0-9+/=]+")
        assertThat(bundle).matches("RLYH:[A-Za-z0-9+/=]+")
    }

    @Test
    fun `parse returns null for plain text`() {
        assertThat(E2eHandshakeSms.parse("hello")).isNull()
        assertThat(E2eHandshakeSms.parse("")).isNull()
        assertThat(E2eHandshakeSms.parse("RLYH")).isNull()
    }

    @Test
    fun `parse returns null for a ciphertext fragment`() {
        assertThat(E2eHandshakeSms.parse("RLY1:abc123:01/01:AQEB3q2+7w==")).isNull()
    }

    @Test
    fun `parse returns null for bad base64`() {
        assertThat(E2eHandshakeSms.parse("RLYH:!!!")).isNull()
        assertThat(E2eHandshakeSms.parse("RLYH:AB C")).isNull()
    }

    @Test
    fun `parse returns null for a truncated payload`() {
        val text = "RLYH:" + Base64.getEncoder().encodeToString(ByteArray(10))

        assertThat(E2eHandshakeSms.parse(text)).isNull()
    }

    @Test
    fun `parse returns null for an oversized payload`() {
        val text = "RLYH:" + Base64.getEncoder().encodeToString(ByteArray(200))

        assertThat(E2eHandshakeSms.parse(text)).isNull()
    }

    @Test
    fun `isHandshakeSms is true only for the magic`() {
        val probe = E2eHandshakeSms.encode(E2eHandshake.encodeProbe(curveKey, edKey))

        assertThat(E2eHandshakeSms.isHandshakeSms(probe)).isTrue()
        assertThat(E2eHandshakeSms.isHandshakeSms("hello")).isFalse()
        assertThat(E2eHandshakeSms.isHandshakeSms("")).isFalse()
        assertThat(E2eHandshakeSms.isHandshakeSms("RLY1:abc123:01/01:AQEB3q2+7w==")).isFalse()
        assertThat(E2eHandshakeSms.isHandshakeSms("RLYH:!!!")).isFalse()
    }

    @Test
    fun `a handshake SMS never parses as a fragment`() {
        val probe = E2eHandshakeSms.encode(E2eHandshake.encodeProbe(curveKey, edKey))
        val bundle = E2eHandshakeSms.encode(
            E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey),
        )

        assertThat(E2eWireFormat.parseFragment(probe)).isNull()
        assertThat(E2eWireFormat.parseFragment(bundle)).isNull()
    }

    @Test
    fun `encode rejects a packet of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            E2eHandshakeSms.encode(ByteArray(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eHandshakeSms.encode(ByteArray(200))
        }
    }
}
