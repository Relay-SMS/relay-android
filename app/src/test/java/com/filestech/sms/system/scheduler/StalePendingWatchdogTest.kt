package com.filestech.sms.system.scheduler

import com.filestech.sms.data.local.db.dao.MessageDao
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 36 — the stale-PENDING watchdog's delegation: the
 * foreground trigger calls [MessageDao.timeoutStalePending] with
 * a cutoff 15 minutes in the past and returns how many rows
 * flipped. The SQL behavior itself (a stale PENDING flips, a
 * fresh one does not) is proven against a real database by
 * [StalePendingWatchdogDbTest] — a fake DAO here would only test
 * the fake.
 */
class StalePendingWatchdogTest {

    @Test fun `runOnce passes a 15-minute cutoff to the DAO and returns its count`() = runTest {
        val dao = mockk<MessageDao>()
        coEvery { dao.timeoutStalePending(any()) } returns 2
        val watchdog = StalePendingWatchdog(dao)

        val before = System.currentTimeMillis()
        val flipped = watchdog.runOnce()
        val after = System.currentTimeMillis()

        assertThat(flipped).isEqualTo(2)
        coVerify(exactly = 1) { dao.timeoutStalePending(any()) }
        // The cutoff is "now minus 15 minutes", measured within
        // the call's own window (the two clocks bracket it: the
        // call ran after `before` and before `after`).
        coVerify {
            dao.timeoutStalePending(
                withArg { cutoff ->
                    assertThat(cutoff).isAtLeast(before - StalePendingWatchdog.PENDING_TIMEOUT_MS)
                    assertThat(cutoff).isAtMost(after - StalePendingWatchdog.PENDING_TIMEOUT_MS)
                },
            )
        }
    }

    @Test fun `runOnce returns zero when nothing was stale`() = runTest {
        val dao = mockk<MessageDao>()
        coEvery { dao.timeoutStalePending(any()) } returns 0
        val watchdog = StalePendingWatchdog(dao)

        assertThat(watchdog.runOnce()).isEqualTo(0)
        coVerify(exactly = 1) { dao.timeoutStalePending(any()) }
    }
}
