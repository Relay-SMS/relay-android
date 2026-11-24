package com.filestech.sms.ui.screens.details

import com.filestech.sms.e2e.GroupEncryptionState
import com.filestech.sms.e2e.PlainReason
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 49 — the group encryption state → Details row display
 * mapping ([groupEncryptionDisplay], design §7.2). The mapping is
 * pure (the name resolver is a parameter), so the table is
 * unit-testable without a Compose harness.
 */
class GroupEncryptionDisplayTest {

    private val names = mapOf(
        "6105551234" to "Alice",
        "6105555678" to "Bob",
        "6105559999" to "Carol",
    )
    private val resolve: (String) -> String = { address -> names[address] ?: address }

    @Test
    fun `encrypted shows the encrypted display`() {
        assertThat(groupEncryptionDisplay(GroupEncryptionState.Encrypted, resolve))
            .isEqualTo(GroupEncryptionDisplay.Encrypted)
    }

    @Test
    fun `setting up shows the checking display`() {
        val state = GroupEncryptionState.SettingUp(pendingMembers = listOf("6105551234"))

        assertThat(groupEncryptionDisplay(state, resolve))
            .isEqualTo(GroupEncryptionDisplay.SettingUp)
    }

    @Test
    fun `one member without relay names them with no others`() {
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.MEMBER_NOT_RELAY,
            blockingMembers = listOf("6105551234"),
        )

        assertThat(groupEncryptionDisplay(state, resolve))
            .isEqualTo(GroupEncryptionDisplay.NotRelay(firstName = "Alice", others = 0))
    }

    @Test
    fun `several members without relay name the first with the rest counted`() {
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.MEMBER_NOT_RELAY,
            blockingMembers = listOf("6105551234", "6105555678", "6105559999"),
        )

        assertThat(groupEncryptionDisplay(state, resolve))
            .isEqualTo(GroupEncryptionDisplay.NotRelay(firstName = "Alice", others = 2))
    }

    @Test
    fun `out-of-date member names them`() {
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.MEMBER_NOT_CAPABLE,
            blockingMembers = listOf("6105555678"),
        )

        assertThat(groupEncryptionDisplay(state, resolve))
            .isEqualTo(GroupEncryptionDisplay.NotCapable(firstName = "Bob"))
    }

    @Test
    fun `blocked member names them`() {
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.MEMBER_BLOCKED,
            blockingMembers = listOf("6105559999"),
        )

        assertThat(groupEncryptionDisplay(state, resolve))
            .isEqualTo(GroupEncryptionDisplay.Blocked(firstName = "Carol"))
    }

    @Test
    fun `unknown member falls back to the number`() {
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.MEMBER_NOT_RELAY,
            blockingMembers = listOf("6105550000"),
        )

        assertThat(groupEncryptionDisplay(state, resolve))
            .isEqualTo(GroupEncryptionDisplay.NotRelay(firstName = "6105550000", others = 0))
    }

    @Test
    fun `not-a-group maps to no row`() {
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.NOT_A_GROUP,
            blockingMembers = emptyList(),
        )

        assertThat(groupEncryptionDisplay(state, resolve)).isNull()
    }

    @Test
    fun `plain with no blocking member maps to no row`() {
        // Unreachable in practice — the provider always names at
        // least one — but a row with no name would be worse than
        // none.
        val state = GroupEncryptionState.Plain(
            reason = PlainReason.MEMBER_NOT_RELAY,
            blockingMembers = emptyList(),
        )

        assertThat(groupEncryptionDisplay(state, resolve)).isNull()
    }
}
