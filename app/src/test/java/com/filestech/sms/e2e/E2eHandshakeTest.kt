package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2eHandshake.HandshakePacket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Handshake codec tests (design §2.2): probe/bundle packets, their
 * exact sizes (70 / 102 bytes, one data-SMS segment each), and
 * invalid-input rejection. Pure JVM — no telephony, no Android APIs.
 */
class E2eHandshakeTest {

    private val curveKey: ByteArray = ByteArray(32) { it.toByte() }
    private val edKey: ByteArray = ByteArray(32) { (it + 32).toByte() }
    private val oneTimeKey: ByteArray = ByteArray(32) { (it + 64).toByte() }

    @Test
    fun `the handshake port is 47832`() {
        assertThat(E2eHandshake.E2E_DATA_SMS_PORT).isEqualTo(47832)
    }

    @Test
    fun `a probe encodes to 70 bytes and decodes back`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey)
        assertThat(bytes.size).isEqualTo(70)
        assertThat(E2eHandshake.PROBE_SIZE).isEqualTo(70)
        val decoded = E2eHandshake.decode(bytes)
        assertThat(decoded).isInstanceOf(HandshakePacket.Probe::class.java)
        val probe = decoded as HandshakePacket.Probe
        assertThat(probe.curveKey).isEqualTo(curveKey)
        assertThat(probe.edKey).isEqualTo(edKey)
    }

    // ──────────────────── Round 47: the manual flag ────────────────────
    // Bit 7 (0x80) of the type byte marks a probe the user sent by
    // tapping Retry: the receiver bypasses its bundle rate limit for
    // it. Same 70-byte packet, same format — one bit, both channels.

    @Test
    fun `a manual probe encodes to 70 bytes with the flag bit set and decodes back as manual`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey, manual = true)

        assertThat(bytes.size).isEqualTo(70)
        // The flag is bit 7 of the type byte: 0x01 (probe) | 0x80.
        // (Masked to unsigned: the byte itself reads as -127.)
        assertThat(bytes[5].toInt() and 0xFF).isEqualTo(0x81)
        val decoded = E2eHandshake.decode(bytes)
        assertThat(decoded).isInstanceOf(HandshakePacket.Probe::class.java)
        val probe = decoded as HandshakePacket.Probe
        assertThat(probe.manual).isTrue()
        assertThat(probe.curveKey).isEqualTo(curveKey)
        assertThat(probe.edKey).isEqualTo(edKey)
    }

    @Test
    fun `an automatic probe decodes as not manual`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey)

        assertThat(bytes[5]).isEqualTo(0x01)
        val probe = E2eHandshake.decode(bytes) as HandshakePacket.Probe
        assertThat(probe.manual).isFalse()
    }

    @Test
    fun `a manual probe and an automatic probe with the same keys are not equal`() {
        val manual = E2eHandshake.decode(E2eHandshake.encodeProbe(curveKey, edKey, manual = true))
        val automatic = E2eHandshake.decode(E2eHandshake.encodeProbe(curveKey, edKey))

        assertThat(manual).isNotEqualTo(automatic)
    }

    @Test
    fun `decode rejects an unknown type even with the flag bit set`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey)
        bytes[5] = 0x83.toByte()

        assertThat(E2eHandshake.decode(bytes)).isNull()
    }

    @Test
    fun `decode ignores the flag bit on a bundle`() {
        val bytes = E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey)
        bytes[5] = 0x82.toByte()

        val decoded = E2eHandshake.decode(bytes)
        assertThat(decoded).isInstanceOf(HandshakePacket.Bundle::class.java)
        val bundle = decoded as HandshakePacket.Bundle
        assertThat(bundle.oneTimeKey).isEqualTo(oneTimeKey)
    }

    @Test
    fun `a bundle encodes to 102 bytes and decodes back`() {
        val bytes = E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey)
        assertThat(bytes.size).isEqualTo(102)
        assertThat(E2eHandshake.BUNDLE_SIZE).isEqualTo(102)
        val decoded = E2eHandshake.decode(bytes)
        assertThat(decoded).isInstanceOf(HandshakePacket.Bundle::class.java)
        val bundle = decoded as HandshakePacket.Bundle
        assertThat(bundle.curveKey).isEqualTo(curveKey)
        assertThat(bundle.edKey).isEqualTo(edKey)
        assertThat(bundle.oneTimeKey).isEqualTo(oneTimeKey)
    }

    @Test
    fun `probe and bundle share the RLYH magic and version`() {
        val probe = E2eHandshake.encodeProbe(curveKey, edKey)
        val bundle = E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey)
        assertThat(probe.copyOfRange(0, 4).toString(Charsets.UTF_8)).isEqualTo("RLYH")
        assertThat(bundle.copyOfRange(0, 4).toString(Charsets.UTF_8)).isEqualTo("RLYH")
        assertThat(probe[4]).isEqualTo(0x01)
        assertThat(bundle[4]).isEqualTo(0x01)
        assertThat(probe[5]).isEqualTo(0x01)
        assertThat(bundle[5]).isEqualTo(0x02)
    }

    @Test
    fun `decode rejects bad magic`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey)
        bytes[0] = 'X'.code.toByte()
        assertThat(E2eHandshake.decode(bytes)).isNull()
    }

    @Test
    fun `decode rejects a bad version`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey)
        bytes[4] = 0x02
        assertThat(E2eHandshake.decode(bytes)).isNull()
    }

    @Test
    fun `decode rejects an unknown type`() {
        val bytes = E2eHandshake.encodeProbe(curveKey, edKey)
        bytes[5] = 0x03
        assertThat(E2eHandshake.decode(bytes)).isNull()
    }

    @Test
    fun `decode rejects wrong lengths`() {
        val probe = E2eHandshake.encodeProbe(curveKey, edKey)
        assertThat(E2eHandshake.decode(probe.copyOfRange(0, 69))).isNull()
        assertThat(E2eHandshake.decode(probe + byteArrayOf(0x00))).isNull()
        val bundle = E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey)
        assertThat(E2eHandshake.decode(bundle.copyOfRange(0, 101))).isNull()
        assertThat(E2eHandshake.decode(bundle + byteArrayOf(0x00))).isNull()
        // A probe-length packet with the bundle type is neither packet.
        val mistyped = E2eHandshake.encodeProbe(curveKey, edKey)
        mistyped[5] = 0x02
        assertThat(E2eHandshake.decode(mistyped)).isNull()
    }

    @Test
    fun `decode rejects empty and truncated input`() {
        assertThat(E2eHandshake.decode(byteArrayOf())).isNull()
        assertThat(E2eHandshake.decode(byteArrayOf(0x52, 0x4C, 0x59, 0x48, 0x01))).isNull()
        assertThat(E2eHandshake.decode("hello".toByteArray())).isNull()
    }

    @Test
    fun `encode rejects keys that are not 32 bytes`() {
        val short = ByteArray(31)
        assertThrows(IllegalArgumentException::class.java) {
            E2eHandshake.encodeProbe(short, edKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eHandshake.encodeProbe(curveKey, short)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eHandshake.encodeBundle(curveKey, edKey, short)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eHandshake.encodeBundle(curveKey, edKey, ByteArray(33))
        }
    }
}
