package com.filestech.sms.data.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 13 — the recipient canonicalisation behind [SystemThreadResolver]: two
 * spellings of the same number must resolve to the same thread, or the provider's
 * `canonical_addresses` table splits one group into two threads.
 *
 * Only the normalisation is tested here — [SystemThreadResolver.resolve] itself needs
 * the Telephony provider, so it is covered by the instrumented
 * `IntegrationMmsRecuTest` (thread-ID routing) and by a device test.
 */
class SystemThreadResolverTest {

    @Test
    fun `deux ecritures du meme numero donnent la meme cle`() {
        val cles = canonicalThreadRecipients(listOf("+33 6 12 34 56 78", "+33612345678"))

        assertThat(cles).containsExactly("+33612345678")
    }

    @Test
    fun `tirets et parentheses sont retires`() {
        val cles = canonicalThreadRecipients(listOf("+1 (561) 234-5678"))

        assertThat(cles).containsExactly("+15612345678")
    }

    @Test
    fun `les blancs et les vides sont ignores`() {
        val cles = canonicalThreadRecipients(listOf("  ", "", "+33612345678"))

        assertThat(cles).containsExactly("+33612345678")
    }

    @Test
    fun `un numero court et un alphanumerique passent tels quels`() {
        val cles = canonicalThreadRecipients(listOf("3615", "INFO"))

        assertThat(cles).containsExactly("3615", "INFO")
    }
}
