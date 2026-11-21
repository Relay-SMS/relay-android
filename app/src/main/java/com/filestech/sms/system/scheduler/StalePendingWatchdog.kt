package com.filestech.sms.system.scheduler

import com.filestech.sms.data.local.db.dao.MessageDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 36 — the stale-PENDING watchdog, foreground trigger.
 *
 * A lost send acknowledgement leaves a message showing
 * "Sending…" — the ack never arrives, so nothing flips the row
 * out of PENDING. The 12-hour [TelephonySyncWorker] is the
 * backstop for that (it runs the same timeout), but 12 hours is
 * a long time to stare at a spinner: this watchdog runs the
 * timeout when the app comes to the foreground, so a lost ack
 * flips the message to failed promptly instead.
 *
 * The rule is the worker's rule, unchanged: only OUTGOING rows
 * in PENDING older than 15 minutes flip (see
 * [MessageDao.timeoutStalePending] — a real carrier roundtrip is
 * usually under 30 s, so anything past 15 minutes is almost
 * certainly a missed sent-broadcast, and a genuinely in-flight
 * send is never touched). The query is idempotent (already
 * failed rows no longer match), so a rapid foreground/background
 * oscillation costs one cheap UPDATE per resume — no throttle,
 * no single-flight.
 *
 * No client-mode gate: a linked device has optimistic PENDING
 * rows of its own (its sends await the hub's ack), and the
 * worker backstop does not run there — the foreground trigger is
 * the only watchdog those rows get. A retry reuses the same
 * client key, so the hub dedups it; flipping a lost-ack row to
 * FAILED is safe on both modes.
 *
 * Round 38 — the foreground tick ([tickForever]): a message can
 * go stale while the app is already open (a send made minutes
 * ago whose ack never came), and without a tick it would sit on
 * "Sending…" until the next resume or the 12-hour worker. The
 * tick runs the same timeout every 5 minutes while the app is
 * in the foreground ([MainActivity] binds it to RESUMED), so a
 * stale row flips within ~5 minutes of going stale. The first
 * tick lands one interval after the loop starts — the resume
 * trigger already ran the check at foreground time, so there is
 * no double run.
 */
@Singleton
class StalePendingWatchdog @Inject constructor(
    private val messageDao: MessageDao,
) {

    /**
     * Flips every stale PENDING row to FAILED. Returns how many
     * flipped (0 when nothing was stale).
     */
    suspend fun runOnce(): Int {
        val cutoff = System.currentTimeMillis() - PENDING_TIMEOUT_MS
        return messageDao.timeoutStalePending(cutoff)
    }

    /**
     * Runs [runOnce] every [TICK_INTERVAL_MS] until cancelled.
     * The first tick lands one interval after the loop starts
     * (the resume trigger covers the foreground moment itself).
     * A failed tick is logged and the loop carries on — a
     * transient database error must not silence the watchdog
     * for the rest of the session. Cancellation is honoured
     * (the [delay] throws; a cancellation landing inside
     * [runOnce] is rethrown, never logged as a failure).
     */
    suspend fun tickForever() {
        while (true) {
            delay(TICK_INTERVAL_MS)
            try {
                val flipped = runOnce()
                if (flipped > 0) {
                    Timber.i("Watchdog: %d stale PENDING -> FAILED (foreground tick)", flipped)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Foreground stale-PENDING tick failed")
            }
        }
    }

    companion object {
        /**
         * A PENDING row older than this is stale — the same 15
         * minutes as the [TelephonySyncWorker] backstop. Keep the
         * two in sync: the foreground trigger and the worker are
         * the same watchdog on two cadences.
         */
        const val PENDING_TIMEOUT_MS = 15L * 60L * 1_000L

        /**
         * How often the foreground tick re-runs the timeout
         * while the app is open. A row goes stale at most one
         * interval late, and the query is a cheap indexed
         * UPDATE, so 5 minutes costs nothing measurable.
         */
        const val TICK_INTERVAL_MS = 5L * 60L * 1_000L
    }
}
