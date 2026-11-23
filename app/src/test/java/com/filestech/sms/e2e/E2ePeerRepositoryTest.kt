package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2ePeerDao
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.purge.MS_PER_DAY
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E — peer state CRUD + the probe budget (3 attempts / 30 days, 24 h apart).
 *
 * Strict MockK DAO. `now` is passed explicitly everywhere: the window math is the
 * thing under test, and a wall clock would make the boundaries untestable.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * `runTest` body returns a value.
 */
class E2ePeerRepositoryTest {

    private val address = "+33612345678"
    private val now = 1_800_000_000_000L

    private fun peer(
        state: E2ePeerState = E2ePeerState.UNKNOWN,
        attempts: Int = 0,
        windowStartedAt: Long? = null,
        lastProbeAt: Long? = null,
    ) = E2ePeerEntity(
        address = address,
        state = state,
        probeAttempts = attempts,
        windowStartedAt = windowStartedAt,
        lastProbeAt = lastProbeAt,
        updatedAt = now,
    )

    private fun daoReturning(found: E2ePeerEntity?): E2ePeerDao {
        val dao = mockk<E2ePeerDao>()
        coEvery { dao.findByAddress(any()) } returns found
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.updateState(any(), any(), any()) } just Runs
        coEvery { dao.recordProbe(any(), any(), any(), any(), any()) } just Runs
        coEvery { dao.updateKeys(any(), any(), any(), any()) } just Runs
        coEvery { dao.setVerified(any(), any(), any()) } just Runs
        coEvery { dao.recordBundleReply(any(), any(), any()) } just Runs
        coEvery { dao.resetTiming(any()) } just Runs
        return dao
    }

    @Test
    fun `state is UNKNOWN when no row exists`() = runTest {
        val repository = E2ePeerRepository(daoReturning(null))

        assertThat(repository.state(address)).isEqualTo(E2ePeerState.UNKNOWN)
    }

    @Test
    fun `getOrCreateUnknown inserts then returns the row`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        val found = repository.getOrCreateUnknown(address, now)

        assertThat(found.address).isEqualTo(address)
        coVerify(exactly = 1) { dao.insert(match { it.state == E2ePeerState.UNKNOWN }) }
    }

    @Test
    fun `canProbe is true with no row and after the window elapses`() = runTest {
        val fresh = E2ePeerRepository(daoReturning(null))
        assertThat(fresh.canProbe(address, now)).isTrue()

        val expired = E2ePeerRepository(
            daoReturning(
                peer(
                    state = E2ePeerState.NOT_RELAY,
                    attempts = 3,
                    windowStartedAt = now - 30 * MS_PER_DAY,
                    lastProbeAt = now - 30 * MS_PER_DAY,
                ),
            ),
        )
        assertThat(expired.canProbe(address, now)).isTrue()
    }

    @Test
    fun `canProbe is false once the budget is spent inside the window`() = runTest {
        val repository = E2ePeerRepository(
            daoReturning(
                peer(
                    state = E2ePeerState.PROBING,
                    attempts = 3,
                    windowStartedAt = now - 2 * MS_PER_DAY,
                    lastProbeAt = now - 2 * MS_PER_DAY,
                ),
            ),
        )

        assertThat(repository.canProbe(address, now)).isFalse()
    }

    @Test
    fun `canProbe enforces the 24-hour spacing inside the window`() = runTest {
        val tooSoon = E2ePeerRepository(
            daoReturning(
                peer(
                    attempts = 1,
                    windowStartedAt = now - 2 * MS_PER_DAY,
                    lastProbeAt = now - 60 * 60 * 1000L,
                ),
            ),
        )
        assertThat(tooSoon.canProbe(address, now)).isFalse()

        val spaced = E2ePeerRepository(
            daoReturning(
                peer(
                    attempts = 1,
                    windowStartedAt = now - 2 * MS_PER_DAY,
                    lastProbeAt = now - 25 * 60 * 60 * 1000L,
                ),
            ),
        )
        assertThat(spaced.canProbe(address, now)).isTrue()
    }

    @Test
    fun `first probe starts the window with one attempt and moves to PROBING`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        repository.recordProbeSent(address, now)

        coVerify(exactly = 1) { dao.recordProbe(address, 1, now, now, now) }
        coVerify(exactly = 1) { dao.updateState(address, E2ePeerState.PROBING, now) }
    }

    @Test
    fun `second probe inside the window bumps the count and keeps the window`() = runTest {
        val windowStart = now - 2 * MS_PER_DAY
        val dao = daoReturning(
            peer(attempts = 1, windowStartedAt = windowStart, lastProbeAt = windowStart),
        )
        val repository = E2ePeerRepository(dao)

        repository.recordProbeSent(address, now)

        coVerify(exactly = 1) { dao.recordProbe(address, 2, windowStart, now, now) }
    }

    @Test
    fun `a probe after the window resets the count and restarts the window`() = runTest {
        val dao = daoReturning(
            peer(
                state = E2ePeerState.NOT_RELAY,
                attempts = 3,
                windowStartedAt = now - 31 * MS_PER_DAY,
                lastProbeAt = now - 31 * MS_PER_DAY,
            ),
        )
        val repository = E2ePeerRepository(dao)

        repository.recordProbeSent(address, now)

        coVerify(exactly = 1) { dao.recordProbe(address, 1, now, now, now) }
        coVerify(exactly = 1) { dao.updateState(address, E2ePeerState.PROBING, now) }
    }

    @Test
    fun `recordKeys and setVerified delegate with the new values`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        repository.recordKeys(address, "Y3VydmU=", "ZWQ=", now)
        repository.setVerified(address, true, now)
        repository.setState(address, E2ePeerState.ENCRYPTED, now)

        coVerify(exactly = 1) { dao.updateKeys(address, "Y3VydmU=", "ZWQ=", now) }
        coVerify(exactly = 1) { dao.setVerified(address, true, now) }
        coVerify(exactly = 1) { dao.updateState(address, E2ePeerState.ENCRYPTED, now) }
    }

    @Test
    fun `recordBundleReply persists the reply time`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        repository.recordBundleReply(address, now)

        coVerify(exactly = 1) { dao.recordBundleReply(address, now, now) }
    }

    // -- Round 42: the device-link snapshot source ----------------------

    @Test
    fun `allPeers returns every row in address order`() = runTest {
        val dao = FakeE2ePeerDao()
        dao.peers["+33687654321"] = peer().copy(address = "+33687654321")
        dao.peers[address] = peer()
        val repository = E2ePeerRepository(dao)

        val all = repository.allPeers()

        assertThat(all.map { it.address }).containsExactly("+33612345678", "+33687654321").inOrder()
    }

    @Test
    fun `observeAllPeers emits every row`() = runTest {
        val dao = FakeE2ePeerDao()
        dao.peers[address] = peer()
        val repository = E2ePeerRepository(dao)

        var emitted = 0
        repository.observeAllPeers().collect { all ->
            emitted++
            assertThat(all).hasSize(1)
            assertThat(all[0].address).isEqualTo(address)
        }
        assertThat(emitted).isEqualTo(1)
    }

    // ─────────── Round 42: the address is canonicalized ───────────
    // Every method normalizes its address before touching the DAO:
    // the thread UI observes the normalized form while the probe
    // path wrote the raw conversation form and the handshake
    // handler wrote the carrier form, so a peer whose number was
    // stored with formatting never showed its state change.

    @Test
    fun `state normalizes a formatted address before querying`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        assertThat(repository.state("+33 6 12 34 56 78")).isEqualTo(E2ePeerState.UNKNOWN)
        coVerify(exactly = 1) { dao.findByAddress("+33612345678") }
    }

    @Test
    fun `setState writes under the normalized key`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        repository.setState("+33 6 12 34 56 78", E2ePeerState.ENCRYPTED, now)

        coVerify(exactly = 1) { dao.insert(match { it.address == "+33612345678" }) }
        coVerify(exactly = 1) {
            dao.updateState("+33612345678", E2ePeerState.ENCRYPTED, now)
        }
    }

    @Test
    fun `recordProbeSent writes under the normalized key`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        repository.recordProbeSent("06 12 34 56 78", now)

        coVerify(exactly = 1) { dao.recordProbe("0612345678", 1, now, now, now) }
        coVerify(exactly = 1) {
            dao.updateState("0612345678", E2ePeerState.PROBING, now)
        }
    }

    @Test
    fun `observePeer normalizes before subscribing`() = runTest {
        val dao = mockk<E2ePeerDao>()
        every { dao.observeByAddress(any()) } returns flowOf(peer())
        val repository = E2ePeerRepository(dao)

        val found = repository.observePeer("+33 6 12 34 56 78").first()

        assertThat(found?.address).isEqualTo(address)
        verify(exactly = 1) { dao.observeByAddress("+33612345678") }
    }

    @Test
    fun `an alphanumeric sender keeps its trimmed raw form`() = runTest {
        val dao = daoReturning(null)
        val repository = E2ePeerRepository(dao)

        repository.state("  Free  ")

        coVerify(exactly = 1) { dao.findByAddress("Free") }
    }

    @Test
    fun `resetEncryptionTiming delegates to the DAO with the timestamp`() = runTest {
        val dao = daoReturning(peer())
        val repository = E2ePeerRepository(dao)

        repository.resetEncryptionTiming(now)

        coVerify(exactly = 1) { dao.resetTiming(now) }
    }
}
