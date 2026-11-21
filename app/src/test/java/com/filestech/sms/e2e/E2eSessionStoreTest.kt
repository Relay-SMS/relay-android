package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eSessionDao
import com.filestech.sms.data.local.db.entity.E2eSessionEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E — session pickle persistence: new sessions become current, re-pickles keep
 * the flag, loads restore from the DAO, and the 3-session cap prunes fallbacks.
 *
 * Strict MockK DAO; [FakeE2eCrypto] holds the live sessions (no native code).
 * The key manager is mocked to return a fresh copy of a fixed key per call — the
 * store wipes the copy after use.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * `runTest` body returns a value.
 */
class E2eSessionStoreTest {

    private val address = "+33612345678"
    private val pickleKey = ByteArray(32) { (it + 3).toByte() }

    private fun keyManager(): E2eKeyManager {
        val manager = mockk<E2eKeyManager>()
        every { manager.getOrCreatePickleKey() } answers { pickleKey.copyOf() }
        return manager
    }

    private fun store(dao: E2eSessionDao, crypto: E2eCrypto = FakeE2eCrypto()) =
        E2eSessionStore(dao, crypto, keyManager())

    private fun session(
        id: Long = 0,
        sessionId: String,
        pickle: String = "stored-pickle",
        isCurrent: Boolean = false,
        lastUsedAt: Long = 1_700_000_000_000L,
    ) = E2eSessionEntity(
        id = id,
        address = address,
        sessionId = sessionId,
        pickle = pickle,
        isCurrent = isCurrent,
        createdAt = 1_700_000_000_000L,
        lastUsedAt = lastUsedAt,
    )

    /** A genuine fake pickle for a live session, locked with the test pickle key. */
    private fun livePickle(): Pair<String, String> {
        val seeder = FakeE2eCrypto()
        val sessionId = seeder.createOutboundSession(ByteArray(32), ByteArray(32))
        return sessionId to seeder.pickleSession(sessionId, pickleKey)
    }

    @Test
    fun `saveNewSession inserts as current and prunes fallbacks`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val crypto = FakeE2eCrypto()
        val sessionId = crypto.createOutboundSession(ByteArray(32), ByteArray(32))
        coEvery { dao.clearCurrent(any()) } just Runs
        coEvery { dao.findByAddressAndSessionId(any(), any()) } returns null
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.pruneFallbacks(any(), any()) } returns 0

        store(dao, crypto).saveNewSession(address, sessionId)

        coVerify(exactly = 1) { dao.clearCurrent(address) }
        coVerify(exactly = 1) {
            dao.insert(match { it.sessionId == sessionId && it.isCurrent })
        }
        // Cap is 3 total: current + 2 fallbacks kept.
        coVerify(exactly = 1) { dao.pruneFallbacks(address, 2) }
    }

    @Test
    fun `saveNewSession updates an existing row instead of inserting twice`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val crypto = FakeE2eCrypto()
        val sessionId = crypto.createOutboundSession(ByteArray(32), ByteArray(32))
        coEvery { dao.clearCurrent(any()) } just Runs
        coEvery { dao.findByAddressAndSessionId(any(), any()) } returns session(sessionId = sessionId)
        coEvery { dao.update(any()) } just Runs
        coEvery { dao.pruneFallbacks(any(), any()) } returns 0

        store(dao, crypto).saveNewSession(address, sessionId)

        coVerify(exactly = 1) { dao.update(match { it.isCurrent && it.id == 0L }) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `refreshSession re-pickles in place and keeps the current flag`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val crypto = FakeE2eCrypto()
        val sessionId = crypto.createOutboundSession(ByteArray(32), ByteArray(32))
        crypto.encrypt(sessionId, "hello".toByteArray())
        coEvery { dao.findByAddressAndSessionId(any(), any()) } returns
            session(sessionId = sessionId, isCurrent = true)
        coEvery { dao.updatePickle(any(), any(), any(), any()) } just Runs

        store(dao, crypto).refreshSession(address, sessionId, now = 1_700_000_100_000L)

        // The re-pickle reflects the advanced ratchet: counter 1, no longer new.
        coVerify(exactly = 1) {
            dao.updatePickle(
                address,
                sessionId,
                match { it.startsWith("FAKE-SESSION-v1:$sessionId:1:false:") },
                1_700_000_100_000L,
            )
        }
    }

    @Test
    fun `refreshSession inserts an unstored session as a fallback instead of dropping it`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val crypto = FakeE2eCrypto()
        val sessionId = crypto.createOutboundSession(ByteArray(32), ByteArray(32))
        coEvery { dao.findByAddressAndSessionId(any(), any()) } returns null
        coEvery { dao.insert(any()) } returns 1L

        store(dao, crypto).refreshSession(address, sessionId)

        coVerify(exactly = 1) { dao.insert(match { it.sessionId == sessionId && !it.isCurrent }) }
    }

    @Test
    fun `loadSession unpickles a stored session into the wrapper`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val (sessionId, pickle) = livePickle()
        coEvery { dao.findByAddressAndSessionId(any(), any()) } returns session(sessionId = sessionId, pickle = pickle)
        val crypto = FakeE2eCrypto()

        val loaded = store(dao, crypto).loadSession(address, sessionId)

        assertThat(loaded).isTrue()
        assertThat(crypto.hasSession(sessionId)).isTrue()
    }

    @Test
    fun `loadSession is a no-op when the session is already live`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val crypto = FakeE2eCrypto()
        val sessionId = crypto.createOutboundSession(ByteArray(32), ByteArray(32))

        val loaded = store(dao, crypto).loadSession(address, sessionId)

        assertThat(loaded).isTrue()
        coVerify(exactly = 0) { dao.findByAddressAndSessionId(any(), any()) }
    }

    @Test
    fun `loadSession returns false when nothing is stored and when the pickle is corrupt`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val crypto = FakeE2eCrypto()
        coEvery { dao.findByAddressAndSessionId(address, "missing") } returns null
        coEvery { dao.findByAddressAndSessionId(address, "corrupt") } returns
            session(sessionId = "corrupt").copy(pickle = "not-a-pickle")

        val store = store(dao, crypto)
        assertThat(store.loadSession(address, "missing")).isFalse()
        assertThat(store.loadSession(address, "corrupt")).isFalse()
        assertThat(crypto.hasSession("corrupt")).isFalse()
    }

    @Test
    fun `currentSessionId and sessionIdsNewestFirst read through the DAO order`() = runTest {
        val dao = mockk<E2eSessionDao>()
        val current = session(id = 1, sessionId = "s-current", isCurrent = true)
        val fallbackNew = session(id = 2, sessionId = "s-new", lastUsedAt = 1_700_000_200_000L)
        val fallbackOld = session(id = 3, sessionId = "s-old", lastUsedAt = 1_700_000_100_000L)
        coEvery { dao.currentSession(any()) } returns current
        coEvery { dao.sessionsForPeer(any()) } returns listOf(current, fallbackNew, fallbackOld)

        val store = store(dao)
        assertThat(store.currentSessionId(address)).isEqualTo("s-current")
        assertThat(store.sessionIdsNewestFirst(address))
            .containsExactly("s-current", "s-new", "s-old").inOrder()
    }

    @Test
    fun `markCurrent clears the old current first`() = runTest {
        val dao = mockk<E2eSessionDao>()
        coEvery { dao.clearCurrent(any()) } just Runs
        coEvery { dao.markCurrent(any(), any()) } just Runs

        store(dao).markCurrent(address, "s-new")

        coVerify(exactly = 1) { dao.clearCurrent(address) }
        coVerify(exactly = 1) { dao.markCurrent(address, "s-new") }
    }
}
