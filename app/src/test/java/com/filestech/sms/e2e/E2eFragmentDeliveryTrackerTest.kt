package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The fragment-delivery tracker (design §3.4): a message is DELIVERED
 * only once ALL its fragments report delivered, per (message, attempt).
 */
class E2eFragmentDeliveryTrackerTest {

    @Test
    fun `a single-fragment message completes on its only report`() {
        val tracker = E2eFragmentDeliveryTracker()

        val complete = tracker.recordDelivered(localId = 1L, attempt = 0, index = 0, total = 1)

        assertThat(complete).isTrue()
    }

    @Test
    fun `out-of-order reports complete only on the last fragment`() {
        val tracker = E2eFragmentDeliveryTracker()

        assertThat(tracker.recordDelivered(1L, 0, 2, 3)).isFalse()
        assertThat(tracker.recordDelivered(1L, 0, 0, 3)).isFalse()
        assertThat(tracker.recordDelivered(1L, 0, 1, 3)).isTrue()
    }

    @Test
    fun `duplicate reports do not complete early`() {
        val tracker = E2eFragmentDeliveryTracker()

        assertThat(tracker.recordDelivered(1L, 0, 0, 2)).isFalse()
        // The same fragment reporting twice is still one fragment.
        assertThat(tracker.recordDelivered(1L, 0, 0, 2)).isFalse()
        assertThat(tracker.recordDelivered(1L, 0, 1, 2)).isTrue()
    }

    @Test
    fun `attempts are isolated from each other`() {
        val tracker = E2eFragmentDeliveryTracker()

        assertThat(tracker.recordDelivered(1L, 0, 0, 2)).isFalse()
        // Attempt 1 is a fresh fragment set: attempt 0's report does
        // not count toward it.
        assertThat(tracker.recordDelivered(1L, 1, 0, 2)).isFalse()
        assertThat(tracker.recordDelivered(1L, 1, 1, 2)).isTrue()
        // And attempt 0 still needs its second fragment.
        assertThat(tracker.recordDelivered(1L, 0, 1, 2)).isTrue()
    }

    @Test
    fun `malformed reports are dropped, never completing`() {
        val tracker = E2eFragmentDeliveryTracker()

        assertThat(tracker.recordDelivered(1L, 0, -1, 2)).isFalse()
        assertThat(tracker.recordDelivered(1L, 0, 0, 0)).isFalse()
        assertThat(tracker.recordDelivered(1L, 0, 2, 2)).isFalse()
        // The entry was never created: a valid report starts fresh.
        assertThat(tracker.recordDelivered(1L, 0, 0, 2)).isFalse()
        assertThat(tracker.recordDelivered(1L, 0, 1, 2)).isTrue()
    }

    @Test
    fun `a completed set is forgotten, so a late duplicate starts fresh`() {
        val tracker = E2eFragmentDeliveryTracker()

        assertThat(tracker.recordDelivered(1L, 0, 0, 1)).isTrue()
        // The set completed and was removed; a late duplicate report
        // for the same single fragment starts (and completes) a fresh
        // count rather than corrupting a later set.
        assertThat(tracker.recordDelivered(1L, 0, 0, 1)).isTrue()
    }
}
