package com.filestech.sms.ui.screens.thread

/**
 * Round 43 — the index mapping between the thread's chronological
 * message list and the reversed LazyColumn that renders it.
 *
 * The thread list is bottom-anchored (`reverseLayout = true`): the
 * LazyColumn holds the messages newest-first, so LazyColumn index 0 is
 * the bottom of the thread (the newest message) and the highest index
 * is the oldest loaded message. `state.messages` stays chronological
 * (oldest first, as the window query returns it), so every scroll
 * target and every visible-item lookup converts between the two
 * orderings through here — one place, so the conversion cannot drift
 * between the search jump, the pin jump and the sticky date pill.
 *
 * The trailing E2E notice item (notices newer than every message)
 * renders BELOW the newest message, so it leads the LazyColumn at
 * index 0 when present and the messages shift by one — that shift is
 * [leadingItemCount] (1 with a trailing notice, else 0), and the
 * newest message's LazyColumn index.
 *
 * Pure Kotlin, no Android APIs — fully unit testable (see
 * [ThreadListIndexingTest]).
 */
object ThreadListIndexing {

    /**
     * LazyColumn index of the message at chronological [chronoIndex]
     * (its position in `state.messages`, oldest first) in a window of
     * [messageCount] messages.
     */
    fun lazyIndexForMessage(
        chronoIndex: Int,
        messageCount: Int,
        leadingItemCount: Int,
    ): Int = leadingItemCount + (messageCount - 1 - chronoIndex)

    /**
     * Chronological index of the message at LazyColumn [lazyIndex], or
     * null when that item is not a message (the leading notice item,
     * or an index past the end of the list).
     */
    fun chronoIndexForLazy(
        lazyIndex: Int,
        messageCount: Int,
        leadingItemCount: Int,
    ): Int? {
        val messagePosition = lazyIndex - leadingItemCount
        if (messagePosition < 0 || messagePosition >= messageCount) return null
        return messageCount - 1 - messagePosition
    }
}
