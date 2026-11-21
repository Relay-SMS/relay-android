package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eFragmentDao
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E — fragment reassembly: a set is complete only when every index `0..total-1`
 * is present, retries dedupe, and expired fragments prune after 24 hours.
 *
 * Strict MockK DAO. The SQL itself (dedupe on the composite key, the expiry
 * delete) is proven against a real database by the CI-only `E2eDaoDbTest`.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * `runTest` body returns a value.
 */
class E2eFragmentStoreTest {

    private val sender = "+33612345678"
    private val msgId = "ab12cd"
    private val now = 1_800_000_000_000L

    private fun fragment(idx: Int, total: Int = 3, chunk: String = "chunk$idx") =
        E2eFragmentEntity(
            sender = sender,
            msgId = msgId,
            idx = idx,
            total = total,
            chunk = chunk,
            receivedAt = now,
        )

    @Test
    fun `insert reports stored versus duplicate`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.insert(any()) } returnsMany listOf(1L, -1L)
        val store = E2eFragmentStore(dao)

        assertThat(store.insert(sender, msgId, 0, 3, "chunk0", now)).isTrue()
        // The same (sender, msgId, index) again: the DAO's IGNORE returns -1.
        assertThat(store.insert(sender, msgId, 0, 3, "chunk0", now)).isFalse()
    }

    @Test
    fun `completeSet returns null while fragments are missing`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.fragmentsFor(any(), any()) } returns listOf(fragment(0), fragment(2))
        val store = E2eFragmentStore(dao)

        assertThat(store.completeSet(sender, msgId)).isNull()
    }

    @Test
    fun `completeSet returns null when the count matches but an index is wrong`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        // Three rows for a 3-fragment set, but index 1 is missing and 3 is out of range:
        // a count-only check would wrongly accept this set.
        coEvery { dao.fragmentsFor(any(), any()) } returns
            listOf(fragment(0), fragment(2), fragment(3))
        val store = E2eFragmentStore(dao)

        assertThat(store.completeSet(sender, msgId)).isNull()
    }

    @Test
    fun `completeSet returns the fragments in index order when the set is whole`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.fragmentsFor(any(), any()) } returns
            listOf(fragment(0), fragment(1), fragment(2))
        val store = E2eFragmentStore(dao)

        val set = store.completeSet(sender, msgId)

        assertThat(set).isNotNull()
        assertThat(set?.map { it.idx }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `reassemble concatenates the chunks in index order`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.fragmentsFor(any(), any()) } returns listOf(
            fragment(0, chunk = "QUJD"),
            fragment(1, chunk = "REVG"),
            fragment(2, chunk = "R0hJ"),
        )
        val store = E2eFragmentStore(dao)

        assertThat(store.reassemble(sender, msgId)).isEqualTo("QUJDREVGR0hJ")
    }

    @Test
    fun `reassemble returns null for an incomplete set`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.fragmentsFor(any(), any()) } returns listOf(fragment(0))
        val store = E2eFragmentStore(dao)

        assertThat(store.reassemble(sender, msgId)).isNull()
    }

    @Test
    fun `pruneExpired deletes fragments older than 24 hours and reports the count`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.deleteOlderThan(any()) } returns 2
        val store = E2eFragmentStore(dao)

        val pruned = store.pruneExpired(now)

        assertThat(pruned).isEqualTo(2)
        coVerify(exactly = 1) { dao.deleteOlderThan(now - E2eFragmentStore.FRAGMENT_TTL_MS) }
    }

    @Test
    fun `deleteSet removes the whole set after reassembly`() = runTest {
        val dao = mockk<E2eFragmentDao>()
        coEvery { dao.deleteSet(any(), any()) } returns 3
        val store = E2eFragmentStore(dao)

        assertThat(store.deleteSet(sender, msgId)).isEqualTo(3)
    }
}
