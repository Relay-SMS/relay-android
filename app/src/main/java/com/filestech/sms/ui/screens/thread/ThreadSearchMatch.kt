package com.filestech.sms.ui.screens.thread

import com.filestech.sms.domain.model.Message

/**
 * Feedback round 5 — one matching message in the thread: the message's
 * position in `state.messages` (so the screen can scroll to it) and every
 * non-overlapping occurrence of the query in its body, as inclusive ranges.
 * The count shown ("2 of 5") counts matching *messages*, not occurrences:
 * every occurrence in a message lights up together, and the current
 * message's occurrences use the stronger colour.
 *
 * [messageIndex] is -1 when the match is outside the loaded window (older
 * history not loaded yet) — the screen loads the window around it before
 * scrolling to it. [ranges] is then empty until the message is loaded.
 */
data class ThreadSearchMatch(
    val messageId: Long,
    val messageIndex: Int,
    val ranges: List<IntRange>,
)

/**
 * Feedback round 5 — the repository's answer for one search: the debounced
 * query and the ids of every message in the thread whose body contains it,
 * oldest first, over the WHOLE history — not just the loaded window.
 */
data class ThreadSearchResults(
    val query: String,
    val messageIds: List<Long>,
)

/**
 * Feedback round 5 — maps full-history search results onto the loaded
 * window, in thread order (oldest first, as listed in `state.messages`).
 *
 * Literal, case-insensitive substring search — no regex, so a query like
 * "C++" or "(urgent)" matches itself. A match already loaded gets its
 * position in [messages] and its highlight ranges (computed from the loaded
 * body, so the ranges are Unicode-aware even where the SQL `LIKE` is
 * ASCII-only); a match outside the window gets index -1 and no ranges — the
 * screen jumps the window to it first.
 */
internal fun mapThreadSearchMatches(
    messageIds: List<Long>,
    messages: List<Message>,
    query: String,
): List<ThreadSearchMatch> {
    if (query.isBlank()) return emptyList()
    val indexById = messages.withIndex().associate { (index, message) -> message.id to index }
    return messageIds.map { id ->
        val index = indexById[id]
        ThreadSearchMatch(
            messageId = id,
            messageIndex = index ?: -1,
            ranges = if (index != null) findQueryRanges(messages[index].body, query) else emptyList(),
        )
    }
}

/**
 * Feedback round 5 — all non-overlapping occurrences of [query] in [text],
 * as inclusive ranges. Blank bodies and empty queries have no occurrences.
 */
internal fun findQueryRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - query.length) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        ranges += at until at + query.length
        from = at + query.length
    }
    return ranges
}

/**
 * Feedback round 5 — next matching message, wrapping from the last back to
 * the first. Returns -1 when there is nothing to jump to.
 */
internal fun nextThreadSearchIndex(current: Int, count: Int): Int {
    if (count <= 0) return -1
    if (current < 0) return 0
    return (current + 1) % count
}

/**
 * Feedback round 5 — previous matching message, wrapping from the first back
 * to the last. Returns -1 when there is nothing to jump to.
 */
internal fun previousThreadSearchIndex(current: Int, count: Int): Int {
    if (count <= 0) return -1
    if (current <= 0) return count - 1
    return current - 1
}
