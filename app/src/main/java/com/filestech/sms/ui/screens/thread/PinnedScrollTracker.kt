package com.filestech.sms.ui.screens.thread

/**
 * One layout emission of the thread list, reduced to the values the
 * pinned-to-bottom decision needs. Plain data — no Compose types — so
 * the decision logic stays testable on plain JVM (see
 * [PinnedScrollTrackerTest]).
 *
 * @param firstIndex index of the first visible item, or null when the list is empty.
 * @param firstOffset scroll offset of the first visible item.
 * @param lastIndex index of the last visible item, or null when the list is empty.
 * @param lastBottom bottom edge (offset + size) of the last visible item.
 * @param viewportEndOffset the viewport's bottom edge.
 */
data class PinnedScrollLayout(
    val firstIndex: Int?,
    val firstOffset: Int?,
    val lastIndex: Int?,
    val lastBottom: Int?,
    val viewportEndOffset: Int,
)

/**
 * Round 42 — the pinned-to-bottom state machine for the thread list,
 * extracted from the pinned-scroll effect in `ThreadScreen` so the
 * decision survives effect restarts and is unit-testable.
 *
 * Round 18 introduced the pin: while the tail is pinned and the list
 * is not scrolling, re-scroll whenever the bottom item is no longer
 * fully visible (link previews and images load asynchronously and grow
 * bubbles after the initial scroll-to-bottom has landed). A scroll
 * gesture unpins immediately, so reading older messages is never
 * yanked back; reaching the bottom again re-pins. The pin starts
 * false and is only set by actually seeing the bottom item fully
 * visible — merely sitting one item up never pins.
 *
 * Phase 2 (2026-11-15) — the unpin rule is a first-visible-anchor
 * discriminator, not a last-visible threshold. The round-18 rule
 * unpinned whenever the last visible item sat more than one position
 * above the bottom — but a ~300dp image preview card pops in whole
 * and jumps it 4–6 messages in one layout pass, so the pin disengaged
 * instead of re-pinning. Now: if the first visible item (index +
 * offset) moved since the previous emission, the user moved — keep
 * the old behavior (pin only when the bottom is fully visible, unpin
 * otherwise). If the anchor is unchanged but the last visible item
 * moved back, that is content growth below the anchor, not user
 * movement — keep the pin and re-scroll, whatever the jump size.
 *
 * Round 42 — the state lives HERE, in one instance remembered by the
 * screen, instead of as locals inside the `LaunchedEffect`: the
 * effect restarts whenever its keys change (a new message, the picker
 * opening), and the locals reset the pin to false on every restart.
 * After the keyboard closed, the bottom was NOT fully visible (the
 * frame-locked tracker had dropped a delta or two), so the pin could
 * never re-engage from that state and no re-scroll ever happened —
 * the last message stayed covered until the user scrolled manually.
 * With the state hoisted, a pin earned before the keyboard animation
 * survives it, and the first post-settle emission re-scrolls.
 *
 * One instance per screen — the pin and the anchor are the screen's
 * own memory, not shared state.
 */
class PinnedScrollTracker {

    /** Whether the tail is currently pinned to the bottom. */
    var isPinned: Boolean = false
        private set

    private var prevFirstIndex: Int? = null
    private var prevFirstOffset: Int? = null

    /**
     * Processes one layout emission. Returns true when the caller
     * should scroll to the bottom item ([bottomItemIndex]).
     *
     * The anchor is the previous emission's first visible item,
     * whatever the branch below did — a re-scroll's own next emission
     * compares against the layout it produced, and a scroll gesture
     * leaves the anchor at the settled position.
     */
    fun onLayout(
        layout: PinnedScrollLayout,
        bottomItemIndex: Int,
        isScrollInProgress: Boolean,
    ): Boolean {
        var shouldScroll = false
        if (isScrollInProgress) {
            isPinned = false
        } else if (layout.lastIndex != null && layout.lastBottom != null) {
            val bottomFullyVisible = layout.lastIndex == bottomItemIndex &&
                layout.lastBottom <= layout.viewportEndOffset
            if (bottomFullyVisible) {
                isPinned = true
            } else {
                // Anchor moved (or no anchor yet) → the user moved:
                // unpin past one position, as before. Anchor unchanged
                // → content grew below it: keep the pin and re-scroll,
                // any jump size.
                val anchorMoved = layout.firstIndex == null ||
                    prevFirstIndex == null ||
                    layout.firstIndex != prevFirstIndex ||
                    layout.firstOffset != prevFirstOffset
                if (anchorMoved && layout.lastIndex < bottomItemIndex - 1) {
                    isPinned = false
                }
                shouldScroll = isPinned
            }
        }
        prevFirstIndex = layout.firstIndex
        prevFirstOffset = layout.firstOffset
        return shouldScroll
    }
}
