package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2eGroupSenderKeyMessage.SenderKeyDistribution
import com.filestech.sms.e2e.E2eGroupSenderKeyStore.StoreResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Sender-key store tests (round 49, design §13): the current /
 * generation rules, the identity-change bypass, first-key-wins,
 * the generation prune, the distribution ledger, and the
 * own-number fallback lookup.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eGroupSenderKeyStoreTest {

    private companion object {
        const val ALICE = "6108423232"
        const val BOB = "6108423233"
        const val CAROL = "6108423234"
        val MEMBERS = listOf(ALICE, BOB, CAROL)
        const val NOW = 1_800_000_000_000L
        const val CURVE_OLD = "old-curve-key-b64"
        const val CURVE_NEW = "new-curve-key-b64"
    }

    private class Harness {
        val keyDao = FakeE2eGroupKeyDao()
        val distributionDao = FakeE2eGroupDistributionDao()
        val store = E2eGroupSenderKeyStore(keyDao, distributionDao)
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS)

        fun distribution(
            senderKeyId: String,
            generation: Int,
            members: List<String> = MEMBERS,
        ) = SenderKeyDistribution(
            groupId = groupId,
            keyId = senderKeyId,
            key = ByteArray(32) { it.toByte() },
            members = members,
            generation = generation,
        )
    }

    @Test
    fun `an own key becomes current immediately`() = runTest {
        val harness = Harness()

        val key = harness.store.storeOwnKey(
            groupId = harness.groupId,
            ownAddress = ALICE,
            keyId = "aaaaaaaaaaaaaaaa",
            key = ByteArray(32) { 7 },
            members = MEMBERS,
            generation = 1,
            ownCurveKeyB64 = null,
            now = NOW,
        )

        assertThat(key.isCurrent).isTrue()
        assertThat(key.isOwn).isTrue()
        assertThat(harness.store.ownCurrentKey(harness.groupId, ALICE)).isEqualTo(key)
    }

    @Test
    fun `a first peer distribution becomes current`() = runTest {
        val harness = Harness()

        val result = harness.store.storePeerKey(
            harness.distribution("aaaaaaaaaaaaaaaa", 1),
            senderAddress = BOB,
            senderCurveKeyB64 = CURVE_OLD,
            now = NOW,
        )

        assertThat(result).isEqualTo(
            StoreResult.Stored(
                harness.store.peerCurrentKey(harness.groupId, BOB)!!,
                isCurrent = true,
            ),
        )
    }

    @Test
    fun `a re-sent distribution is AlreadyStored and keeps the first key`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("aaaaaaaaaaaaaaaa", 1), BOB, CURVE_OLD, NOW)
        // Same triple, different key bytes: first key wins.
        val resend = harness.distribution("aaaaaaaaaaaaaaaa", 1).let {
            SenderKeyDistribution(it.groupId, it.keyId, ByteArray(32) { 9 }, it.members, it.generation)
        }

        val result = harness.store.storePeerKey(resend, BOB, CURVE_OLD, NOW)

        assertThat(result).isEqualTo(StoreResult.AlreadyStored)
        val stored = harness.store.keyFor(harness.groupId, BOB, "aaaaaaaaaaaaaaaa")!!
        assertThat(stored.key).isEqualTo(ByteArray(32) { it.toByte() })
    }

    @Test
    fun `a higher generation becomes current, the old key stays for in-flight decrypt`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("aaaaaaaaaaaaaaaa", 1), BOB, CURVE_OLD, NOW)

        val result = harness.store.storePeerKey(
            harness.distribution("bbbbbbbbbbbbbbbb", 2),
            BOB,
            CURVE_OLD,
            NOW,
        )

        assertThat((result as StoreResult.Stored).isCurrent).isTrue()
        assertThat(harness.store.peerCurrentKey(harness.groupId, BOB)!!.keyId)
            .isEqualTo("bbbbbbbbbbbbbbbb")
        // The old key is still stored (in-flight messages under
        // it must still decrypt), just no longer current.
        val old = harness.store.keyFor(harness.groupId, BOB, "aaaaaaaaaaaaaaaa")
        assertThat(old).isNotNull()
        assertThat(old!!.isCurrent).isFalse()
    }

    @Test
    fun `a lower generation does not become current`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("bbbbbbbbbbbbbbbb", 2), BOB, CURVE_OLD, NOW)

        val result = harness.store.storePeerKey(
            harness.distribution("aaaaaaaaaaaaaaaa", 1),
            BOB,
            CURVE_OLD,
            NOW,
        )

        assertThat((result as StoreResult.Stored).isCurrent).isFalse()
        assertThat(harness.store.peerCurrentKey(harness.groupId, BOB)!!.keyId)
            .isEqualTo("bbbbbbbbbbbbbbbb")
    }

    @Test
    fun `an identity change accepts a generation-1 key as current`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("aaaaaaaaaaaaaaaa", 2), BOB, CURVE_OLD, NOW)

        // A reinstalled sender distributes from generation 1
        // again, with a new identity key: the generation check
        // alone would reject it, but the changed identity
        // accepts it.
        val result = harness.store.storePeerKey(
            harness.distribution("cccccccccccccccc", 1),
            BOB,
            CURVE_NEW,
            NOW,
        )

        assertThat((result as StoreResult.Stored).isCurrent).isTrue()
        assertThat(harness.store.peerCurrentKey(harness.groupId, BOB)!!.keyId)
            .isEqualTo("cccccccccccccccc")
    }

    @Test
    fun `a null identity falls back to the generation check alone`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("aaaaaaaaaaaaaaaa", 2), BOB, null, NOW)

        val result = harness.store.storePeerKey(
            harness.distribution("bbbbbbbbbbbbbbbb", 1),
            BOB,
            null,
            NOW,
        )

        // No identity evidence either way: never assume a change.
        assertThat((result as StoreResult.Stored).isCurrent).isFalse()
    }

    @Test
    fun `the generation prune keeps three and never prunes the current key`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("1111111111111111", 1), BOB, CURVE_OLD, NOW)
        harness.store.storePeerKey(harness.distribution("2222222222222222", 2), BOB, CURVE_OLD, NOW)
        harness.store.storePeerKey(harness.distribution("3333333333333333", 3), BOB, CURVE_OLD, NOW)
        // Identity change: generation 1 becomes current (below
        // the pre-change generations — the prune must not eat it).
        // Arrives later, so the (generation, recency) order puts
        // it above the gen-1 original in the keep-set.
        harness.store.storePeerKey(
            harness.distribution("4444444444444444", 1),
            BOB,
            CURVE_NEW,
            NOW + 1_000,
        )

        val kept = harness.keyDao.keysForSender(harness.groupId, BOB)

        assertThat(kept.map { it.keyId }).containsExactly(
            "4444444444444444",
            "3333333333333333",
            "2222222222222222",
        )
        assertThat(harness.store.peerCurrentKey(harness.groupId, BOB)!!.keyId)
            .isEqualTo("4444444444444444")
    }

    @Test
    fun `a new own key prunes the old key's distribution records`() = runTest {
        val harness = Harness()
        val first = harness.store.storeOwnKey(
            harness.groupId,
            ALICE,
            "aaaaaaaaaaaaaaaa",
            ByteArray(32) { 1 },
            MEMBERS,
            1,
            null,
            NOW,
        )
        harness.store.recordDistribution(harness.groupId, BOB, first.keyId, NOW)
        harness.store.recordDistribution(harness.groupId, CAROL, first.keyId, NOW)

        val second = harness.store.storeOwnKey(
            harness.groupId,
            ALICE,
            "bbbbbbbbbbbbbbbb",
            ByteArray(32) { 2 },
            MEMBERS,
            2,
            null,
            NOW,
        )

        assertThat(harness.store.distributedMembers(harness.groupId, first.keyId)).isEmpty()
        assertThat(harness.store.distributedMembers(harness.groupId, second.keyId)).isEmpty()
        assertThat(harness.store.ownCurrentKey(harness.groupId, ALICE)).isEqualTo(second)
    }

    @Test
    fun `distribution records are the rate-limit clock`() = runTest {
        val harness = Harness()
        val key = harness.store.storeOwnKey(
            harness.groupId,
            ALICE,
            "aaaaaaaaaaaaaaaa",
            ByteArray(32) { 1 },
            MEMBERS,
            1,
            null,
            NOW,
        )

        assertThat(harness.store.distributionAt(harness.groupId, BOB, key.keyId)).isNull()
        harness.store.recordDistribution(harness.groupId, BOB, key.keyId, NOW)
        assertThat(harness.store.distributionAt(harness.groupId, BOB, key.keyId)).isEqualTo(NOW)
        // A re-send refreshes the timestamp (REPLACE).
        harness.store.recordDistribution(harness.groupId, BOB, key.keyId, NOW + 1_000)
        assertThat(harness.store.distributionAt(harness.groupId, BOB, key.keyId))
            .isEqualTo(NOW + 1_000)
        assertThat(harness.store.distributedMembers(harness.groupId, key.keyId))
            .containsExactly(BOB)
    }

    @Test
    fun `findMemberListFor finds the list holding the thread plus one more`() = runTest {
        val harness = Harness()
        harness.store.storeOwnKey(
            harness.groupId,
            ALICE,
            "aaaaaaaaaaaaaaaa",
            ByteArray(32) { 1 },
            MEMBERS,
            1,
            null,
            NOW,
        )

        val found = harness.store.findMemberListFor(listOf(BOB, CAROL))

        assertThat(found).isEqualTo(MEMBERS)
        assertThat(harness.store.findMemberListFor(listOf(BOB))).isNull()
        assertThat(harness.store.findMemberListFor(listOf(BOB, CAROL, "6108423235"))).isNull()
    }

    @Test
    fun `groupIdsForSender lists the groups where the sender has a key`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("aaaaaaaaaaaaaaaa", 1), BOB, CURVE_OLD, NOW)
        val otherGroup = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, "6108423235"))
        harness.store.storePeerKey(
            SenderKeyDistribution(
                groupId = otherGroup,
                keyId = "bbbbbbbbbbbbbbbb",
                key = ByteArray(32) { 2 },
                members = listOf(ALICE, BOB, "6108423235"),
                generation = 1,
            ),
            BOB,
            CURVE_OLD,
            NOW,
        )

        assertThat(harness.store.groupIdsForSender(BOB))
            .containsExactly(harness.groupId, otherGroup)
        assertThat(harness.store.groupIdsForSender(CAROL)).isEmpty()
    }

    @Test
    fun `deleteForGroup removes keys and distributions`() = runTest {
        val harness = Harness()
        harness.store.storePeerKey(harness.distribution("aaaaaaaaaaaaaaaa", 1), BOB, CURVE_OLD, NOW)
        harness.store.recordDistribution(harness.groupId, BOB, "aaaaaaaaaaaaaaaa", NOW)

        harness.store.deleteForGroup(harness.groupId)

        assertThat(harness.store.keyFor(harness.groupId, BOB, "aaaaaaaaaaaaaaaa")).isNull()
        assertThat(harness.store.distributedMembers(harness.groupId, "aaaaaaaaaaaaaaaa")).isEmpty()
    }
}
