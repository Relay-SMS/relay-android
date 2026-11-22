package com.filestech.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 42 — the pinned-to-bottom state machine: the pin engages only
 * by seeing the bottom fully visible, survives effect restarts (the
 * state lives in the tracker instance, not in the effect), and
 * re-scrolls from a not-fully-visible bottom when pinned — including
 * the keyboard-close residual that round 41 left covered.
 *
 * Layouts use a 1000px viewport unless the test says otherwise; items
 * are ~100px tall. `bottomItemIndex` is the last message's index.
 */
class PinnedScrollTrackerTest {

    private fun layout(
        firstIndex: Int?,
        firstOffset: Int?,
        lastIndex: Int?,
        lastBottom: Int?,
        viewportEndOffset: Int = 1000,
    ) = PinnedScrollLayout(
        firstIndex = firstIndex,
        firstOffset = firstOffset,
        lastIndex = lastIndex,
        lastBottom = lastBottom,
        viewportEndOffset = viewportEndOffset,
    )

    /** Seeing the bottom item fully visible pins — and needs no scroll. */
    @Test
    fun `bottom fully visible pins`() {
        val tracker = PinnedScrollTracker()

        val shouldScroll = tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isFalse()
        assertThat(tracker.isPinned).isTrue()
    }

    /** Merely sitting one item up never pins — the pin needs the bottom fully visible. */
    @Test
    fun `one item up does not pin`() {
        val tracker = PinnedScrollTracker()

        val shouldScroll = tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 48, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isFalse()
        assertThat(tracker.isPinned).isFalse()
    }

    /** A scroll gesture unpins immediately — reading older messages is never yanked back. */
    @Test
    fun `scroll in progress unpins`() {
        val tracker = PinnedScrollTracker()
        tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )
        assertThat(tracker.isPinned).isTrue()

        val shouldScroll = tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = true,
        )

        assertThat(shouldScroll).isFalse()
        assertThat(tracker.isPinned).isFalse()
    }

    /** Content growth below an unchanged anchor keeps the pin and re-scrolls, any jump size. */
    @Test
    fun `content growth below the anchor re-scrolls`() {
        val tracker = PinnedScrollTracker()
        tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        // A preview card pops in whole and pushes the last visible item
        // 4 messages back; the first visible item never moved.
        val shouldScroll = tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 45, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isTrue()
        assertThat(tracker.isPinned).isTrue()
    }

    /** Round 42 — the keyboard-close residual: pinned, the anchor moved
     * (the frame-locked tracker scrolled), the last message is cut off
     * by the shrunken viewport but still the last visible item — the pin
     * survives and re-scrolls. Before round 42 the pin reset to false
     * on the effect restart and this state never recovered. */
    @Test
    fun `pinned with the tail cut off by one viewport re-scrolls`() {
        val tracker = PinnedScrollTracker()
        tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        val shouldScroll = tracker.onLayout(
            layout(firstIndex = 41, firstOffset = 20, lastIndex = 49, lastBottom = 1050),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isTrue()
        assertThat(tracker.isPinned).isTrue()
    }

    /** The user scrolling up unpins: the anchor moved and the last visible item is 2+ above the bottom. */
    @Test
    fun `user scrolled up unpins and does not scroll`() {
        val tracker = PinnedScrollTracker()
        tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        val shouldScroll = tracker.onLayout(
            layout(firstIndex = 20, firstOffset = 0, lastIndex = 29, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isFalse()
        assertThat(tracker.isPinned).isFalse()
    }

    /** Reaching the bottom again re-pins after an unpin. */
    @Test
    fun `reaching the bottom again re-pins`() {
        val tracker = PinnedScrollTracker()
        tracker.onLayout(
            layout(firstIndex = 20, firstOffset = 0, lastIndex = 29, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )
        assertThat(tracker.isPinned).isFalse()

        tracker.onLayout(
            layout(firstIndex = 40, firstOffset = 0, lastIndex = 49, lastBottom = 990),
            bottomItemIndex = 49,
            isScrollInProgress = false,
        )

        assertThat(tracker.isPinned).isTrue()
    }

    /** Short thread (3 messages): the pin engages, and when the keyboard
     * shrinks the viewport below the content the cut-off tail re-scrolls. */
    @Test
    fun `short thread re-scrolls when the keyboard shrinks the viewport`() {
        val tracker = PinnedScrollTracker()
        tracker.onLayout(
            layout(firstIndex = 0, firstOffset = 0, lastIndex = 2, lastBottom = 300),
            bottomItemIndex = 2,
            isScrollInProgress = false,
        )
        assertThat(tracker.isPinned).isTrue()

        // The keyboard opens: the viewport shrinks to 250px and the last
        // message's bottom (300) no longer fits.
        val shouldScroll = tracker.onLayout(
            layout(
                firstIndex = 0,
                firstOffset = 0,
                lastIndex = 2,
                lastBottom = 300,
                viewportEndOffset = 250,
            ),
            bottomItemIndex = 2,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isTrue()
        assertThat(tracker.isPinned).isTrue()
    }

    /** An empty list neither pins nor scrolls, and never crashes. */
    @Test
    fun `empty list is a no-op`() {
        val tracker = PinnedScrollTracker()

        val shouldScroll = tracker.onLayout(
            layout(firstIndex = null, firstOffset = null, lastIndex = null, lastBottom = null),
            bottomItemIndex = -1,
            isScrollInProgress = false,
        )

        assertThat(shouldScroll).isFalse()
        assertThat(tracker.isPinned).isFalse()
    }
}
