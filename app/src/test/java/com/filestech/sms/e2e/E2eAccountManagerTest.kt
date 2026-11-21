package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eAccountDao
import com.filestech.sms.data.local.db.entity.E2eAccountEntity
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
 * E2E — the lazy Account lifecycle: first use creates + persists a fresh Account,
 * later uses load it from its pickle, and pool changes re-save it.
 *
 * Strict MockK DAO (an unexpected call throws); [FakeE2eCrypto] stands in for the
 * real wrapper (no native code on the JVM). The key manager is mocked to return a
 * fresh copy of a fixed key per call — the manager wipes the copy after use, so
 * returning the same array twice would hand the second call a zeroed key.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * `runTest` body returns a value.
 */
class E2eAccountManagerTest {

    private val pickleKey = ByteArray(32) { (it + 3).toByte() }

    private fun keyManager(): E2eKeyManager {
        val manager = mockk<E2eKeyManager>()
        every { manager.getOrCreatePickleKey() } answers { pickleKey.copyOf() }
        return manager
    }

    private fun manager(
        dao: E2eAccountDao,
        crypto: E2eCrypto = FakeE2eCrypto(),
    ) = E2eAccountManager(dao, crypto, keyManager())

    /** A genuine fake pickle whose one-time-key pool is drained (one key handed out). */
    private fun drainedPoolPickle(): String {
        val seeder = FakeE2eCrypto()
        seeder.nextOneTimeKey()
        return seeder.pickleAccount(pickleKey)
    }

    @Test
    fun `first ensureAccount persists a fresh pickle and returns the identity keys`() = runTest {
        val dao = mockk<E2eAccountDao>()
        coEvery { dao.get() } returns null
        coEvery { dao.upsert(any()) } just Runs
        val accountManager = manager(dao)

        val keys = accountManager.ensureAccount()

        assertThat(keys.curve25519).hasLength(32)
        assertThat(keys.ed25519).hasLength(32)
        coVerify(exactly = 1) { dao.upsert(match { it.pickle.startsWith("FAKE-ACCOUNT-v1:") }) }
    }

    @Test
    fun `second ensureAccount does not touch the DAO again`() = runTest {
        val dao = mockk<E2eAccountDao>()
        coEvery { dao.get() } returns null
        coEvery { dao.upsert(any()) } just Runs
        val accountManager = manager(dao)
        accountManager.ensureAccount()

        val keys = accountManager.ensureAccount()

        assertThat(keys).isEqualTo(FakeE2eCrypto().identityKeys())
        coVerify(exactly = 1) { dao.get() }
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    @Test
    fun `a fresh manager loads the stored pickle instead of creating an account`() = runTest {
        val dao = mockk<E2eAccountDao>()
        coEvery { dao.get() } returns E2eAccountEntity(pickle = drainedPoolPickle())
        val accountManager = manager(dao)

        accountManager.ensureAccount()

        // The pool state came from the pickle: no one-time key is left to hand out.
        assertThat(accountManager.nextOneTimeKey()).isNull()
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `generateOneTimeKeys tops the pool up and re-saves the pickle`() = runTest {
        val dao = mockk<E2eAccountDao>()
        coEvery { dao.get() } returns E2eAccountEntity(pickle = drainedPoolPickle())
        coEvery { dao.upsert(any()) } just Runs
        val accountManager = manager(dao)
        accountManager.ensureAccount()

        accountManager.generateOneTimeKeys()

        assertThat(accountManager.nextOneTimeKey()).isNotNull()
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    @Test
    fun `saveAccount is a no-op before the account is loaded`() = runTest {
        val dao = mockk<E2eAccountDao>()
        val accountManager = manager(dao)

        accountManager.saveAccount()

        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.get() }
    }

    @Test
    fun `hasAccount reflects the stored row without loading it`() = runTest {
        val dao = mockk<E2eAccountDao>()
        coEvery { dao.get() } returnsMany listOf(
            null,
            E2eAccountEntity(pickle = drainedPoolPickle()),
        )
        val accountManager = manager(dao)

        assertThat(accountManager.hasAccount()).isFalse()
        assertThat(accountManager.hasAccount()).isTrue()
        // Loading never happened: no upsert, no crypto state touched.
        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
