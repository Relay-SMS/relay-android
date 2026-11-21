package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.dao.E2ePeerDao
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E — the UI observation paths (design §6, §7): the repository
 * and store `observe…` functions the thread, the conversation list
 * and Settings collect. Strict MockK DAOs, Flow pass-through.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eUiObservationTest {

    private val address = "+33612345678"
    private val now = 1_800_000_000_000L

    private fun peer(state: E2ePeerState, address: String = this.address) =
        E2ePeerEntity(address = address, state = state, updatedAt = now)

    @Test
    fun `observePeer passes the DAO flow through`() = runTest {
        val dao = mockk<E2ePeerDao>()
        val expected = peer(E2ePeerState.ENCRYPTED)
        every { dao.observeByAddress(address) } returns flowOf(expected)
        val repository = E2ePeerRepository(dao)

        assertThat(repository.observePeer(address).first()).isEqualTo(expected)
    }

    @Test
    fun `observePeer emits null when no row exists`() = runTest {
        val dao = mockk<E2ePeerDao>()
        every { dao.observeByAddress(address) } returns flowOf(null)
        val repository = E2ePeerRepository(dao)

        assertThat(repository.observePeer(address).first()).isNull()
    }

    @Test
    fun `observeEncryptedAddresses maps the DAO list to a set`() = runTest {
        val dao = mockk<E2ePeerDao>()
        every { dao.observeEncryptedAddresses() } returns
            flowOf(listOf("+33611111111", "+33622222222", "+33611111111"))
        val repository = E2ePeerRepository(dao)

        assertThat(repository.observeEncryptedAddresses().first())
            .isEqualTo(setOf("+33611111111", "+33622222222"))
    }

    @Test
    fun `observeVerifiedPeers passes the DAO flow through`() = runTest {
        val dao = mockk<E2ePeerDao>()
        val expected = listOf(
            peer(E2ePeerState.ENCRYPTED, "+33611111111"),
            peer(E2ePeerState.ENCRYPTED, "+33622222222"),
        )
        every { dao.observeVerifiedPeers() } returns flowOf(expected)
        val repository = E2ePeerRepository(dao)

        assertThat(repository.observeVerifiedPeers().first()).isEqualTo(expected)
    }

    @Test
    fun `observeNotices passes the DAO flow through`() = runTest {
        val dao = mockk<E2eNoticeDao>()
        val expected = listOf(
            E2eNoticeEntity(
                id = 1L,
                conversationId = 7L,
                kind = E2eNoticeKind.ACTIVATED,
                date = now,
            ),
        )
        every { dao.observeForConversation(7L) } returns flowOf(expected)
        val store = E2eNoticeStore(dao)

        assertThat(store.observeNotices(7L).first()).isEqualTo(expected)
    }
}
