package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2eHandshake.HandshakePacket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Media-capability declaration packet tests (round 48, design §2):
 * the 0x03 packet, its exact 70-byte size (the old-build swallow
 * property), and old-build drop behavior for unknown types.
 *
 * Byte layout (the type/flag constants are private to the codec,
 * so the tests use the documented offsets): [0..3] `RLYH` magic,
 * [4] version 1, [5] type 0x03, [6] flags (bit 0 = media capable).
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose body returns a value.
 */
class E2eHandshakeMediaCapabilityTest {

    private companion object {
        const val TYPE_OFFSET = 5
        const val FLAGS_OFFSET = 6
        const val MEDIA_CAPABLE_TYPE: Byte = 0x03
    }

    @Test
    fun `a capability packet encodes to exactly 70 bytes like a probe`() {
        val bytes = E2eHandshake.encodeMediaCapable()

        assertThat(bytes.size).isEqualTo(70)
        assertThat(bytes.size).isEqualTo(E2eHandshake.PROBE_SIZE)
        assertThat(E2eHandshake.MEDIA_CAPABLE_SIZE).isEqualTo(E2eHandshake.PROBE_SIZE)
    }

    @Test
    fun `a capability packet decodes back with the flag set`() {
        val bytes = E2eHandshake.encodeMediaCapable()

        val decoded = E2eHandshake.decode(bytes)

        assertThat(decoded).isInstanceOf(HandshakePacket.MediaCapable::class.java)
        assertThat((decoded as HandshakePacket.MediaCapable).mediaCapable).isTrue()
    }

    @Test
    fun `a capability packet with the flag clear decodes as not capable`() {
        val bytes = E2eHandshake.encodeMediaCapable()
        bytes[FLAGS_OFFSET] = 0x00

        val decoded = E2eHandshake.decode(bytes) as HandshakePacket.MediaCapable

        assertThat(decoded.mediaCapable).isFalse()
    }

    @Test
    fun `other flag bits are ignored on decode`() {
        val bitsSet = E2eHandshake.encodeMediaCapable()
        bitsSet[FLAGS_OFFSET] = 0xFE.toByte() // every bit but bit 0
        val allSet = E2eHandshake.encodeMediaCapable()
        allSet[FLAGS_OFFSET] = 0xFF.toByte() // bit 0 + every other bit

        assertThat((E2eHandshake.decode(bitsSet) as HandshakePacket.MediaCapable).mediaCapable).isFalse()
        assertThat((E2eHandshake.decode(allSet) as HandshakePacket.MediaCapable).mediaCapable).isTrue()
    }

    @Test
    fun `the RLYH text form passes the old-build gate`() {
        // The old-build gate (E2eHandshakeSmsGate) swallows a
        // `RLYH:` text only when it decodes to 70 or 102 bytes — a
        // 70-byte capability packet is therefore invisible on an
        // old build (it decodes the type, finds 0x03 unknown, and
        // drops it), instead of surfacing as a visible bubble.
        val text = E2eHandshakeSms.encode(E2eHandshake.encodeMediaCapable())

        assertThat(E2eHandshakeSms.isHandshakeSms(text)).isTrue()
        assertThat(E2eHandshakeSms.parse(text)).isNotNull()
    }

    @Test
    fun `a short capability packet would NOT pass the old-build gate`() {
        // The counterfactual that fixes the packet at 70 bytes: a
        // 6-byte 0x03 packet is a valid-looking `RLYH:` text to a
        // human, but the old build's length check rejects it and
        // the gibberish would surface as a visible bubble.
        // Built by hand: `encode` itself refuses anything but a
        // 70- or 102-byte packet, so the short text can only be
        // assembled directly.
        val short = ByteArray(6)
        short[0] = 'R'.code.toByte()
        short[1] = 'L'.code.toByte()
        short[2] = 'Y'.code.toByte()
        short[3] = 'H'.code.toByte()
        short[4] = 0x01
        short[5] = MEDIA_CAPABLE_TYPE

        val text = E2eHandshakeSms.MAGIC + Base64.getEncoder().encodeToString(short)

        assertThat(E2eHandshakeSms.isHandshakeSms(text)).isFalse()
    }

    @Test
    fun `decode drops an unknown packet type silently`() {
        // Old-build behavior for a type this build does not know:
        // null, no exception — the packet is dropped, never a
        // placeholder, never a crash.
        val bytes = E2eHandshake.encodeMediaCapable()
        bytes[TYPE_OFFSET] = 0x04

        assertThat(E2eHandshake.decode(bytes)).isNull()
    }

    @Test
    fun `decode drops a wrong-size capability packet`() {
        val bytes = E2eHandshake.encodeMediaCapable()
        val truncated = bytes.copyOf(69)
        val extended = bytes + 0x00.toByte()

        assertThat(E2eHandshake.decode(truncated)).isNull()
        assertThat(E2eHandshake.decode(extended)).isNull()
    }

    @Test
    fun `the capability type is 0x03 and distinct from probe and bundle`() {
        val curveKey = ByteArray(32) { it.toByte() }
        val edKey = ByteArray(32) { (it + 32).toByte() }
        val oneTimeKey = ByteArray(32) { (it + 64).toByte() }

        assertThat(E2eHandshake.encodeMediaCapable()[TYPE_OFFSET]).isEqualTo(MEDIA_CAPABLE_TYPE)
        assertThat(E2eHandshake.encodeProbe(curveKey, edKey)[TYPE_OFFSET]).isEqualTo(0x01.toByte())
        assertThat(E2eHandshake.encodeBundle(curveKey, edKey, oneTimeKey)[TYPE_OFFSET]).isEqualTo(0x02.toByte())
    }

    @Test
    fun `the capability packet carries the RLYH magic and version 1`() {
        val bytes = E2eHandshake.encodeMediaCapable()

        assertThat(bytes.sliceArray(0..3).toString(Charsets.UTF_8)).isEqualTo("RLYH")
        assertThat(bytes[4]).isEqualTo(0x01.toByte())
        assertThat(bytes[TYPE_OFFSET]).isEqualTo(MEDIA_CAPABLE_TYPE)
    }
}
