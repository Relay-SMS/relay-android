package com.filestech.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 41 — the frame-locked IME tracker: the scroll delta for each inset move,
 * and the no-drift invariant (a full open-then-close sums to zero).
 */
class ImeDeltaTrackerTest {

    /** No previous inset to measure against — a thread opening with the keyboard already up must not jump. */
    @Test
    fun `first observation returns zero`() {
        val tracker = ImeDeltaTracker()
        assertThat(tracker.onInsetChanged(800)).isEqualTo(0f)
    }

    /** A rising inset scrolls the content up by the same amount, frame by frame. */
    @Test
    fun `rising inset returns positive deltas`() {
        val tracker = ImeDeltaTracker()
        tracker.onInsetChanged(0)
        assertThat(tracker.onInsetChanged(10)).isEqualTo(10f)
        assertThat(tracker.onInsetChanged(50)).isEqualTo(40f)
        assertThat(tracker.onInsetChanged(300)).isEqualTo(250f)
    }

    /** A falling inset scrolls the content back down by the same amount. */
    @Test
    fun `falling inset returns negative deltas`() {
        val tracker = ImeDeltaTracker()
        tracker.onInsetChanged(300)
        assertThat(tracker.onInsetChanged(200)).isEqualTo(-100f)
        assertThat(tracker.onInsetChanged(0)).isEqualTo(-200f)
    }

    /** A settled keyboard (same inset twice) scrolls nothing. */
    @Test
    fun `unchanged inset returns zero`() {
        val tracker = ImeDeltaTracker()
        tracker.onInsetChanged(300)
        assertThat(tracker.onInsetChanged(300)).isEqualTo(0f)
    }

    /** A keyboard-height jump (e.g. switching to the symbols layout) is one full delta, not a restart. */
    @Test
    fun `height change mid-session returns the full jump`() {
        val tracker = ImeDeltaTracker()
        tracker.onInsetChanged(0)
        tracker.onInsetChanged(300)
        assertThat(tracker.onInsetChanged(420)).isEqualTo(120f)
    }

    /** No drift: a full open-then-close sums to zero, whatever the frame sizes. */
    @Test
    fun `open then close sums to zero`() {
        val tracker = ImeDeltaTracker()
        val insets = listOf(0, 12, 47, 120, 260, 300, 300, 210, 90, 0)
        val deltas = insets.map { tracker.onInsetChanged(it) }
        assertThat(deltas.sum()).isEqualTo(0f)
        assertThat(deltas).containsExactly(0f, 12f, 35f, 73f, 140f, 40f, 0f, -90f, -120f, -90f)
    }
}
