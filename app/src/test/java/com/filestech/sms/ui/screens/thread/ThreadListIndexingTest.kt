package com.filestech.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 43 — the reversed thread list's index mapping: the newest
 * message is LazyColumn index 0 (the bottom), the oldest is the last
 * index, and the trailing E2E notice item shifts every message by one
 * when it leads the list.
 */
class ThreadListIndexingTest {

    /** No trailing notice: the newest message is index 0, the oldest is last. */
    @Test
    fun `no trailing notice maps newest to zero and oldest to last`() {
        assertThat(ThreadListIndexing.lazyIndexForMessage(49, 50, 0)).isEqualTo(0)
        assertThat(ThreadListIndexing.lazyIndexForMessage(0, 50, 0)).isEqualTo(49)
        assertThat(ThreadListIndexing.lazyIndexForMessage(25, 50, 0)).isEqualTo(24)
    }

    /** With a trailing notice leading: every message shifts by one. */
    @Test
    fun `trailing notice shifts every message by one`() {
        assertThat(ThreadListIndexing.lazyIndexForMessage(49, 50, 1)).isEqualTo(1)
        assertThat(ThreadListIndexing.lazyIndexForMessage(0, 50, 1)).isEqualTo(50)
        assertThat(ThreadListIndexing.lazyIndexForMessage(25, 50, 1)).isEqualTo(25)
    }

    /** The reverse mapping recovers the chronological index, with and without the shift. */
    @Test
    fun `chrono index round-trips in both directions`() {
        for (leading in 0..1) {
            for (chrono in 0 until 50) {
                val lazy = ThreadListIndexing.lazyIndexForMessage(chrono, 50, leading)
                assertThat(ThreadListIndexing.chronoIndexForLazy(lazy, 50, leading))
                    .isEqualTo(chrono)
            }
        }
    }

    /** The leading notice item (LazyColumn index 0 with a trailing notice) is not a message. */
    @Test
    fun `leading notice item has no chronological index`() {
        assertThat(ThreadListIndexing.chronoIndexForLazy(0, 50, 1)).isNull()
    }

    /** Indices past the end of the list (or negative) are not messages. */
    @Test
    fun `out of range lazy indices return null`() {
        assertThat(ThreadListIndexing.chronoIndexForLazy(50, 50, 0)).isNull()
        assertThat(ThreadListIndexing.chronoIndexForLazy(51, 50, 1)).isNull()
        assertThat(ThreadListIndexing.chronoIndexForLazy(-1, 50, 0)).isNull()
    }

    /** An empty window has no messages at any index. */
    @Test
    fun `empty window maps nothing`() {
        assertThat(ThreadListIndexing.chronoIndexForLazy(0, 0, 0)).isNull()
        assertThat(ThreadListIndexing.chronoIndexForLazy(0, 0, 1)).isNull()
    }

    /** A single message sits at the bottom with or without the notice item. */
    @Test
    fun `single message is at the bottom`() {
        assertThat(ThreadListIndexing.lazyIndexForMessage(0, 1, 0)).isEqualTo(0)
        assertThat(ThreadListIndexing.lazyIndexForMessage(0, 1, 1)).isEqualTo(1)
        assertThat(ThreadListIndexing.chronoIndexForLazy(0, 1, 0)).isEqualTo(0)
        assertThat(ThreadListIndexing.chronoIndexForLazy(1, 1, 1)).isEqualTo(0)
    }

    /** Round 44 — at the bottom, an incoming message pulls the thread down to it. */
    @Test
    fun `at bottom incoming message auto-scrolls`() {
        assertThat(
            ThreadListIndexing.shouldAutoScrollToBottom(
                wasAtBottom = true,
                newestIsOutgoing = false,
            ),
        ).isTrue()
    }

    /** Round 44 — scrolled up, an incoming message does NOT yank the reading position. */
    @Test
    fun `scrolled up incoming message does not auto-scroll`() {
        assertThat(
            ThreadListIndexing.shouldAutoScrollToBottom(
                wasAtBottom = false,
                newestIsOutgoing = false,
            ),
        ).isFalse()
    }

    /** Round 44 — a sent message always pulls the thread down, even sent while scrolled up. */
    @Test
    fun `scrolled up outgoing message auto-scrolls`() {
        assertThat(
            ThreadListIndexing.shouldAutoScrollToBottom(
                wasAtBottom = false,
                newestIsOutgoing = true,
            ),
        ).isTrue()
    }

    /** Round 44 — at the bottom, a sent message auto-scrolls (both reasons hold). */
    @Test
    fun `at bottom outgoing message auto-scrolls`() {
        assertThat(
            ThreadListIndexing.shouldAutoScrollToBottom(
                wasAtBottom = true,
                newestIsOutgoing = true,
            ),
        ).isTrue()
    }
}
