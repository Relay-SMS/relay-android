package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Probe-policy tests (design §2.4): eligibility is 1:1 + a real phone
 * number + not blocked. Short codes and alphanumeric senders can never
 * run Relay, so they are never probed. Pure JVM.
 */
class E2eProbePolicyTest {

    @Test
    fun `a 1-1 conversation with a real number is probeable`() {
        assertThat(E2eProbePolicy.canProbe(listOf("+15551234567"), isBlocked = false)).isTrue()
    }

    @Test
    fun `a group conversation is never probeable`() {
        assertThat(E2eProbePolicy.canProbe(listOf("+15551234567", "+15557654321"), false)).isFalse()
        assertThat(E2eProbePolicy.canProbe(emptyList(), false)).isFalse()
    }

    @Test
    fun `a blocked contact is never probeable`() {
        assertThat(E2eProbePolicy.canProbe(listOf("+15551234567"), isBlocked = true)).isFalse()
    }

    @Test
    fun `formatted numbers are probeable`() {
        assertThat(E2eProbePolicy.isProbeableAddress("+1 555-123-4567")).isTrue()
        assertThat(E2eProbePolicy.isProbeableAddress("(555) 123-4567")).isTrue()
        assertThat(E2eProbePolicy.isProbeableAddress("555.123.4567")).isTrue()
        assertThat(E2eProbePolicy.isProbeableAddress("+1 (555) 123-4567")).isTrue()
        assertThat(E2eProbePolicy.isProbeableAddress("5551234567")).isTrue()
        assertThat(E2eProbePolicy.isProbeableAddress("+442071234567")).isTrue()
    }

    @Test
    fun `short codes are not probeable`() {
        assertThat(E2eProbePolicy.isProbeableAddress("12345")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("123456")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("911")).isFalse()
    }

    @Test
    fun `a 7-digit number is the shortest probeable number`() {
        assertThat(E2eProbePolicy.isProbeableAddress("1234567")).isTrue()
        assertThat(E2eProbePolicy.MIN_PHONE_DIGITS).isEqualTo(7)
    }

    @Test
    fun `numbers longer than E-164 are not probeable`() {
        assertThat(E2eProbePolicy.isProbeableAddress("1".repeat(15))).isTrue()
        assertThat(E2eProbePolicy.isProbeableAddress("1".repeat(16))).isFalse()
        assertThat(E2eProbePolicy.MAX_PHONE_DIGITS).isEqualTo(15)
    }

    @Test
    fun `alphanumeric senders are not probeable`() {
        assertThat(E2eProbePolicy.isProbeableAddress("BANK")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("RelaySMS")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("123ABC")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("555-REL-AY1")).isFalse()
    }

    @Test
    fun `empty and malformed addresses are not probeable`() {
        assertThat(E2eProbePolicy.isProbeableAddress("")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("+")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("++15551234567")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("555+1234567")).isFalse()
        assertThat(E2eProbePolicy.isProbeableAddress("   ")).isFalse()
    }

    @Test
    fun `bundle replies are rate-limited to one per sender per day`() {
        val now = 1_800_000_000_000L
        val dayMs = E2eProbePolicy.MIN_BUNDLE_REPLY_SPACING_MS
        assertThat(E2eProbePolicy.canReplyWithBundle(null, now)).isTrue()
        assertThat(E2eProbePolicy.canReplyWithBundle(now - dayMs + 1, now)).isFalse()
        assertThat(E2eProbePolicy.canReplyWithBundle(now - dayMs, now)).isTrue()
        assertThat(E2eProbePolicy.canReplyWithBundle(now - 2 * dayMs, now)).isTrue()
    }
}
