package com.filestech.sms.e2e

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Group encryption state tests (round 49, design §13): the pure
 * derivation matrix ([E2eGroupState.derive]) and the live
 * provider ([E2eGroupStateProvider]).
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eGroupStateTest {

    private companion object {
        // Canonical (10-digit) addresses — the derivation
        // canonicalizes, so the tests also cover the raw forms.
        const val BOB = "6108423233"
        const val CAROL = "6108423234"
        const val DANA = "6108423235"
        const val NOW = 1_800_000_000_000L
    }

    private fun peer(
        address: String,
        state: E2ePeerState,
        groupCapable: Boolean = true,
    ) = E2ePeerEntity(
        address = address,
        state = state,
        groupCapable = groupCapable,
        updatedAt = NOW,
    )

    private fun derive(
        members: List<String>,
        peers: List<E2ePeerEntity?> = emptyList(),
        blocked: Set<String> = emptySet(),
    ): GroupEncryptionState {
        val byAddress = peers.filterNotNull().associateBy { it.address }
        return E2eGroupState.derive(members, byAddress, blocked)
    }

    // ──────────────────────────── Derivation ────────────────────────────

    @Test
    fun `all members ENCRYPTED and capable is Encrypted`() {
        val state = derive(
            listOf(BOB, CAROL),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED), peer(CAROL, E2ePeerState.ENCRYPTED)),
        )

        assertThat(state).isEqualTo(GroupEncryptionState.Encrypted)
    }

    @Test
    fun `a single member is NOT_A_GROUP`() {
        val state = derive(
            listOf(BOB),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED)),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.NOT_A_GROUP, emptyList()),
        )
    }

    @Test
    fun `an empty member list is NOT_A_GROUP`() {
        assertThat(derive(emptyList())).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.NOT_A_GROUP, emptyList()),
        )
    }

    @Test
    fun `a NOT_RELAY member keeps the group plain and names them`() {
        val state = derive(
            listOf(BOB, CAROL),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED), peer(CAROL, E2ePeerState.NOT_RELAY)),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.MEMBER_NOT_RELAY, listOf(CAROL)),
        )
    }

    @Test
    fun `a short code keeps the group plain (it can never run Relay)`() {
        val state = derive(
            listOf(BOB, "12345"),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED)),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.MEMBER_NOT_RELAY, listOf("12345")),
        )
    }

    @Test
    fun `an ENCRYPTED member without capability keeps the group plain`() {
        val state = derive(
            listOf(BOB, CAROL),
            listOf(
                peer(BOB, E2ePeerState.ENCRYPTED),
                peer(CAROL, E2ePeerState.ENCRYPTED, groupCapable = false),
            ),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.MEMBER_NOT_CAPABLE, listOf(CAROL)),
        )
    }

    @Test
    fun `a PROBING member is SettingUp, not not-capable`() {
        // The capability flag is false while no declaration has
        // arrived — but a member with no session yet is pending,
        // not an old build.
        val state = derive(
            listOf(BOB, CAROL),
            listOf(
                peer(BOB, E2ePeerState.ENCRYPTED),
                peer(CAROL, E2ePeerState.PROBING, groupCapable = false),
            ),
        )

        assertThat(state).isEqualTo(GroupEncryptionState.SettingUp(listOf(CAROL)))
    }

    @Test
    fun `a member with no peer row is SettingUp`() {
        val state = derive(
            listOf(BOB, CAROL),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED)),
        )

        assertThat(state).isEqualTo(GroupEncryptionState.SettingUp(listOf(CAROL)))
    }

    @Test
    fun `a blocked member keeps the group plain and beats the other reasons`() {
        val state = derive(
            listOf(BOB, CAROL, DANA),
            listOf(
                peer(BOB, E2ePeerState.ENCRYPTED),
                peer(CAROL, E2ePeerState.NOT_RELAY),
                peer(DANA, E2ePeerState.ENCRYPTED, groupCapable = false),
            ),
            blocked = setOf(BOB),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.MEMBER_BLOCKED, listOf(BOB)),
        )
    }

    @Test
    fun `NOT_RELAY beats NOT_CAPABLE when both apply`() {
        val state = derive(
            listOf(BOB, CAROL),
            listOf(
                peer(BOB, E2ePeerState.NOT_RELAY),
                peer(CAROL, E2ePeerState.ENCRYPTED, groupCapable = false),
            ),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.MEMBER_NOT_RELAY, listOf(BOB)),
        )
    }

    @Test
    fun `the derivation canonicalizes member addresses`() {
        // E.164 + formatting in, 10-digit out: the state is the
        // same as for the canonical forms.
        val state = derive(
            listOf("+1 (610) 842-3233", "6108423234"),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED), peer(CAROL, E2ePeerState.ENCRYPTED)),
        )

        assertThat(state).isEqualTo(GroupEncryptionState.Encrypted)
    }

    @Test
    fun `duplicate members collapse before the count`() {
        val state = derive(
            listOf(BOB, BOB),
            listOf(peer(BOB, E2ePeerState.ENCRYPTED)),
        )

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.NOT_A_GROUP, emptyList()),
        )
    }

    // ──────────────────────────── Provider ────────────────────────────

    /** A [BlockedNumberRepository] whose Flow observes the blocked set. */
    private class ObservingBlockedRepo(private val blocked: Set<String>) : BlockedNumberRepository {
        override fun observe(): Flow<List<BlockedNumber>> = flowOf(
            blocked.mapIndexed { index, number ->
                BlockedNumber(index.toLong(), number, number, null, NOW)
            },
        )

        override suspend fun isBlocked(rawNumber: String): Boolean = rawNumber in blocked
        override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
            Outcome.Success(Unit)

        override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
            Outcome.Success(Unit)

        override suspend fun blockedNormalizedSnapshot(): Set<String> = blocked
        override suspend fun blockedRawSnapshot(): List<String> = blocked.toList()
    }

    private fun provider(
        peers: List<E2ePeerEntity> = emptyList(),
        blocked: Set<String> = emptySet(),
    ): E2eGroupStateProvider {
        val peerDao = FakeE2ePeerDao()
        peers.forEach { peerDao.peers[it.address] = it }
        return E2eGroupStateProvider(
            E2ePeerRepository(peerDao),
            ObservingBlockedRepo(blocked),
        )
    }

    @Test
    fun `the provider observes Encrypted for a fully encrypted group`() = runTest {
        val stateProvider = provider(
            listOf(peer(BOB, E2ePeerState.ENCRYPTED), peer(CAROL, E2ePeerState.ENCRYPTED)),
        )

        val state = stateProvider.observeGroupState(listOf(BOB, CAROL)).first()

        assertThat(state).isEqualTo(GroupEncryptionState.Encrypted)
    }

    @Test
    fun `the provider observes the blocked member from the blocklist Flow`() = runTest {
        val stateProvider = provider(
            listOf(peer(BOB, E2ePeerState.ENCRYPTED), peer(CAROL, E2ePeerState.ENCRYPTED)),
            blocked = setOf(CAROL),
        )

        val state = stateProvider.observeGroupState(listOf(BOB, CAROL)).first()

        assertThat(state).isEqualTo(
            GroupEncryptionState.Plain(PlainReason.MEMBER_BLOCKED, listOf(CAROL)),
        )
    }

    @Test
    fun `the one-shot read matches the derivation`() = runTest {
        val stateProvider = provider(
            listOf(peer(BOB, E2ePeerState.ENCRYPTED), peer(CAROL, E2ePeerState.PROBING)),
        )

        val state = stateProvider.groupState(listOf(BOB, CAROL))

        assertThat(state).isEqualTo(GroupEncryptionState.SettingUp(listOf(CAROL)))
    }
}
