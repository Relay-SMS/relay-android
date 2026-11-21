package com.filestech.sms.e2e

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts delivered fragments per (message, attempt) so the delivery
 * receiver holds the DELIVERED promotion until ALL fragments of an
 * encrypted message report delivered (design §3.4). Any fragment
 * failure marks the message Failed — that half needs no counting: the
 * sent receiver already fails the row on the first failed sent report.
 *
 * In-memory only: a process death mid-delivery loses the count, and a
 * later delivery report then starts a fresh count that may never
 * complete — the message stays SENT, which is the same state a message
 * reaches when delivery reports are off. Deliberately benign.
 *
 * Keyed by (message id, attempt): a retry reuses the Room id with a
 * fresh fragment set, and the previous attempt's reports must not
 * complete the new one.
 */
@Singleton
class E2eFragmentDeliveryTracker @Inject constructor() {

    private data class Delivery(val total: Int, val seen: MutableSet<Int>)

    private val lock = Any()
    private val deliveries = LinkedHashMap<Pair<Long, Int>, Delivery>()

    /**
     * Records one fragment's delivery report. Returns `true` when every
     * fragment of this (message, attempt) has now reported delivered —
     * the caller may promote the row to DELIVERED.
     */
    fun recordDelivered(localId: Long, attempt: Int, index: Int, total: Int): Boolean {
        if (index < 0 || total <= 0 || index >= total) return false
        val key = localId to attempt
        synchronized(lock) {
            val delivery = deliveries.getOrPut(key) { Delivery(total, mutableSetOf()) }
            // A total change mid-count means a stale entry (a retry that
            // re-encrypted under a different fragment count): restart it.
            if (delivery.total != total) {
                deliveries[key] = Delivery(total, mutableSetOf(index))
                return total == 1
            }
            delivery.seen += index
            val complete = delivery.seen.size >= delivery.total
            if (complete) deliveries.remove(key)
            if (deliveries.size > MAX_TRACKED) {
                deliveries.remove(deliveries.keys.first())
            }
            return complete
        }
    }

    companion object {
        /** In-flight fragment sets remembered (oldest evicted first). */
        const val MAX_TRACKED = 64
    }
}
