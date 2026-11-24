package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 49 — the group notice subject resolution ([memberDisplayName],
 * design §13): a group notice names its subject member by contact
 * name, falling back to the number. The subject is a CANONICAL
 * address; the member-name map is keyed by RAW address (as the
 * thread state carries it), so the lookup canonicalizes the keys
 * first — a member stored as `+1…` and a subject stored as the
 * 10-digit form are the same person.
 */
class E2eNoticeSubjectTest {

    @Test
    fun `plus-one-stored member matches the canonical subject`() {
        val names = mapOf("+16105551234" to "Alice", "+16105555678" to "Bob")

        assertThat(memberDisplayName("6105551234", names)).isEqualTo("Alice")
        assertThat(memberDisplayName("6105555678", names)).isEqualTo("Bob")
    }

    @Test
    fun `national-form member matches the canonical subject`() {
        val names = mapOf("6105551234" to "Alice")

        assertThat(memberDisplayName("6105551234", names)).isEqualTo("Alice")
    }

    @Test
    fun `unknown subject falls back to the number`() {
        val names = mapOf("+16105551234" to "Alice")

        assertThat(memberDisplayName("6105559999", names)).isEqualTo("6105559999")
    }

    @Test
    fun `empty member map falls back to the number`() {
        assertThat(memberDisplayName("6105551234", emptyMap())).isEqualTo("6105551234")
    }
}
