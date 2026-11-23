package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E — in-thread notice storage: record, the once-per-conversation guard, and
 * per-conversation reads.
 *
 * Strict MockK DAO. The SQL itself is proven against a real database by the
 * CI-only `E2eDaoDbTest`.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * `runTest` body returns a value.
 */
class E2eNoticeStoreTest {

    private val conversationId = 7L
    private val now = 1_800_000_000_000L

    @Test
    fun `record inserts the notice and returns its row id`() = runTest {
        val dao = mockk<E2eNoticeDao>()
        coEvery { dao.insert(any()) } returns 11L
        val store = E2eNoticeStore(dao)

        val id = store.record(conversationId, E2eNoticeKind.ACTIVATED, now)

        assertThat(id).isEqualTo(11L)
        coVerify(exactly = 1) {
            dao.insert(
                match {
                    it.conversationId == conversationId &&
                        it.kind == E2eNoticeKind.ACTIVATED && it.date == now
                },
            )
        }
    }

    @Test
    fun `recordOnce inserts when absent and skips when present`() = runTest {
        val dao = mockk<E2eNoticeDao>()
        coEvery { dao.countByKind(any(), any()) } returnsMany listOf(0, 1)
        coEvery { dao.insert(any()) } returns 11L
        val store = E2eNoticeStore(dao)

        assertThat(store.recordOnce(conversationId, E2eNoticeKind.ACTIVATED, now)).isTrue()
        assertThat(store.recordOnce(conversationId, E2eNoticeKind.ACTIVATED, now)).isFalse()
        coVerify(exactly = 1) { dao.insert(any()) }
    }

    // -- Round 47: ACTIVATED after a teardown ----------------------

    @Test
    fun `recordActivated records the first activation`() = runTest {
        val dao = FakeE2eNoticeDao()
        val store = E2eNoticeStore(dao)

        assertThat(store.recordActivated(conversationId, now)).isTrue()
        assertThat(dao.notices).hasSize(1)
        assertThat(dao.notices.single().kind).isEqualTo(E2eNoticeKind.ACTIVATED)
    }

    @Test
    fun `recordActivated skips a re-establishment while activated is the latest word`() = runTest {
        val dao = FakeE2eNoticeDao()
        val store = E2eNoticeStore(dao)
        store.record(conversationId, E2eNoticeKind.ACTIVATED, now)

        assertThat(store.recordActivated(conversationId, now + 1000)).isFalse()
        assertThat(dao.notices).hasSize(1)
    }

    @Test
    fun `recordActivated records again after a deactivation`() = runTest {
        val dao = FakeE2eNoticeDao()
        val store = E2eNoticeStore(dao)
        store.record(conversationId, E2eNoticeKind.ACTIVATED, now)
        store.record(conversationId, E2eNoticeKind.DEACTIVATED, now + 1000)

        assertThat(store.recordActivated(conversationId, now + 2000)).isTrue()
        assertThat(dao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED }).hasSize(2)
        assertThat(dao.notices.map { it.kind }).containsExactly(
            E2eNoticeKind.ACTIVATED,
            E2eNoticeKind.DEACTIVATED,
            E2eNoticeKind.ACTIVATED,
        ).inOrder()
    }

    @Test
    fun `recordActivated skips again once the second activation is the latest word`() = runTest {
        val dao = FakeE2eNoticeDao()
        val store = E2eNoticeStore(dao)
        store.record(conversationId, E2eNoticeKind.ACTIVATED, now)
        store.record(conversationId, E2eNoticeKind.DEACTIVATED, now + 1000)
        store.record(conversationId, E2eNoticeKind.ACTIVATED, now + 2000)

        assertThat(store.recordActivated(conversationId, now + 3000)).isFalse()
        assertThat(dao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED }).hasSize(2)
    }

    @Test
    fun `hasNotice reflects the stored count`() = runTest {
        val dao = mockk<E2eNoticeDao>()
        coEvery { dao.countByKind(conversationId, E2eNoticeKind.KEY_CHANGED) } returns 1
        coEvery { dao.countByKind(conversationId, E2eNoticeKind.ACTIVATED) } returns 0
        val store = E2eNoticeStore(dao)

        assertThat(store.hasNotice(conversationId, E2eNoticeKind.KEY_CHANGED)).isTrue()
        assertThat(store.hasNotice(conversationId, E2eNoticeKind.ACTIVATED)).isFalse()
    }

    @Test
    fun `noticesForConversation returns the stored notices oldest first`() = runTest {
        val dao = mockk<E2eNoticeDao>()
        val notices = listOf(
            E2eNoticeEntity(
                id = 1,
                conversationId = conversationId,
                kind = E2eNoticeKind.ACTIVATED,
                date = now,
            ),
            E2eNoticeEntity(
                id = 2,
                conversationId = conversationId,
                kind = E2eNoticeKind.KEY_CHANGED,
                date = now + 1000,
            ),
        )
        coEvery { dao.noticesForConversation(any()) } returns notices
        val store = E2eNoticeStore(dao)

        assertThat(store.noticesForConversation(conversationId)).isEqualTo(notices)
    }

    @Test
    fun `deleteForConversation removes the conversation notices and reports the count`() = runTest {
        val dao = mockk<E2eNoticeDao>()
        coEvery { dao.deleteForConversation(any()) } returns 2
        val store = E2eNoticeStore(dao)

        assertThat(store.deleteForConversation(conversationId)).isEqualTo(2)
    }

    // -- Round 42: the device-link snapshot source ----------------------

    @Test
    fun `allNotices returns every notice oldest first`() = runTest {
        val dao = FakeE2eNoticeDao()
        dao.notices += E2eNoticeEntity(
            id = 2,
            conversationId = conversationId,
            kind = E2eNoticeKind.KEY_CHANGED,
            date = now + 1000,
        )
        dao.notices += E2eNoticeEntity(
            id = 1,
            conversationId = conversationId,
            kind = E2eNoticeKind.ACTIVATED,
            date = now,
        )
        val store = E2eNoticeStore(dao)

        val all = store.allNotices()

        assertThat(all.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `observeAllNotices emits every notice`() = runTest {
        val dao = FakeE2eNoticeDao()
        dao.notices += E2eNoticeEntity(
            id = 1,
            conversationId = conversationId,
            kind = E2eNoticeKind.ACTIVATED,
            date = now,
        )
        val store = E2eNoticeStore(dao)

        var emitted = 0
        store.observeAllNotices().collect { all ->
            emitted++
            assertThat(all).hasSize(1)
            assertThat(all[0].kind).isEqualTo(E2eNoticeKind.ACTIVATED)
        }
        assertThat(emitted).isEqualTo(1)
    }
}
