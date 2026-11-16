package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The multicast lock's acquire/release lifecycle: idempotent
 * acquire and release, no-op without a lock, and a throwing lock
 * never takes the caller down. The real Wi-Fi lock sits behind
 * [MulticastLockGuard.Lock]; the fake below counts calls.
 */
class MulticastLockGuardTest {

    private class FakeLock : MulticastLockGuard.Lock {
        var held = false
        var acquires = 0
        var releases = 0
        var throwOnAcquire = false

        override fun acquire() {
            if (throwOnAcquire) throw SecurityException("no permission")
            acquires++
            held = true
        }

        override fun release() {
            releases++
            held = false
        }

        override val isHeld: Boolean get() = held
    }

    @Test fun `acquire holds the lock`() {
        val fake = FakeLock()
        val guard = MulticastLockGuard.withLock(fake)
        guard.acquire()
        assertThat(guard.isHeld).isTrue()
        assertThat(fake.acquires).isEqualTo(1)
    }

    @Test fun `double acquire acquires once`() {
        val fake = FakeLock()
        val guard = MulticastLockGuard.withLock(fake)
        guard.acquire()
        guard.acquire()
        assertThat(fake.acquires).isEqualTo(1)
    }

    @Test fun `release frees the lock`() {
        val fake = FakeLock()
        val guard = MulticastLockGuard.withLock(fake)
        guard.acquire()
        guard.release()
        assertThat(guard.isHeld).isFalse()
        assertThat(fake.releases).isEqualTo(1)
    }

    @Test fun `release without acquire is a no-op`() {
        val fake = FakeLock()
        val guard = MulticastLockGuard.withLock(fake)
        guard.release()
        assertThat(fake.releases).isEqualTo(0)
    }

    @Test fun `double release releases once`() {
        val fake = FakeLock()
        val guard = MulticastLockGuard.withLock(fake)
        guard.acquire()
        guard.release()
        guard.release()
        assertThat(fake.releases).isEqualTo(1)
    }

    @Test fun `no lock is a no-op`() {
        val guard = MulticastLockGuard.withLock(null)
        guard.acquire()
        guard.release()
        assertThat(guard.isHeld).isFalse()
    }

    @Test fun `a throwing acquire does not throw`() {
        val fake = FakeLock().apply { throwOnAcquire = true }
        val guard = MulticastLockGuard.withLock(fake)
        guard.acquire()
        assertThat(guard.isHeld).isFalse()
    }
}
