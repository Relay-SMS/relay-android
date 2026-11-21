package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * E2E — the fingerprint and safety-number display formats (design
 * §5.2, §7.1).
 *
 * Pure Kotlin, no coroutines — block bodies, so every assertion
 * ends the test with a void statement (JUnit silently skips a
 * `@Test` whose body returns a value).
 */
class E2eDisplayFormatTest {

    @Test
    fun `fingerprint groups 32 zero bytes as sixteen groups of four`() {
        val formatted = E2eDisplayFormat.formatFingerprint(ByteArray(32))

        assertThat(formatted).isEqualTo(
            "0000 0000 0000 0000 0000 0000 0000 0000 " +
                "0000 0000 0000 0000 0000 0000 0000 0000",
        )
    }

    @Test
    fun `fingerprint renders bytes zero to thirty-one in order`() {
        val key = ByteArray(32) { it.toByte() }

        val formatted = E2eDisplayFormat.formatFingerprint(key)

        assertThat(formatted).isEqualTo(
            "0001 0203 0405 0607 0809 0a0b 0c0d 0e0f " +
                "1011 1213 1415 1617 1819 1a1b 1c1d 1e1f",
        )
    }

    @Test
    fun `fingerprint does not sign-extend high bytes`() {
        // 0x80 and 0xFF are negative as signed bytes: `%02x` without
        // the unsigned mask would render "ffffff80" / "ffffffff".
        val key = ByteArray(32) { 0xFF.toByte() }
        key[0] = 0x80.toByte()

        val formatted = E2eDisplayFormat.formatFingerprint(key)

        assertThat(formatted).isEqualTo(
            "80ff ffff ffff ffff ffff ffff ffff ffff " +
                "ffff ffff ffff ffff ffff ffff ffff ffff",
        )
    }

    @Test
    fun `fingerprint rejects a short key`() {
        val thrown = assertThrows<IllegalArgumentException> {
            E2eDisplayFormat.formatFingerprint(ByteArray(16))
        }

        assertThat(thrown.message).isEqualTo("curve key must be 32 bytes")
    }

    @Test
    fun `safety number lays out as three lines of four groups`() {
        val number = (1..12).joinToString(" ") { "%05d".format(it) }

        val formatted = E2eDisplayFormat.formatSafetyNumberLines(number)

        assertThat(formatted).isEqualTo(
            "00001 00002 00003 00004\n" +
                "00005 00006 00007 00008\n" +
                "00009 00010 00011 00012",
        )
    }

    @Test
    fun `malformed safety number passes through unchanged`() {
        val malformed = "12345 67890"

        val formatted = E2eDisplayFormat.formatSafetyNumberLines(malformed)

        assertThat(formatted).isEqualTo(malformed)
    }
}
