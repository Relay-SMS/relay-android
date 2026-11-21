package com.filestech.sms.system.scheduler

import com.filestech.sms.data.local.db.dao.MessageDao
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

    companion object {
        /**
         * A PENDING row older than this is stale — the same 15
         * minutes as the [TelephonySyncWorker] backstop. Keep the
         * two in sync: the foreground trigger and the worker are
         * the same watchdog on two cadences.
         */
        const val PENDING_TIMEOUT_MS = 15L * 60L * 1_000L
    }
}
