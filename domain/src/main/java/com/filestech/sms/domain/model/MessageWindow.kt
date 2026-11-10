package com.filestech.sms.domain.model

/**
 * A bounded slice of a conversation: the [messages] currently loaded, ordered oldest-first, plus
 * enough context for the UI to describe what it is *not* showing.
 *
 * [totalCount], [firstMessageAt] and [lastMessageAt] describe the **whole** thread, not the loaded
 * window — the conversation info panel would otherwise report the window's own bounds as the
 * conversation's.
 *
 * @property messages the loaded window, oldest first — the order the thread renders in.
 * @property totalCount every displayable message in the thread, window included.
 * @property hasMore whether older messages exist beyond the window.
 * @property firstMessageAt timestamp of the oldest message in the thread, null when empty.
 * @property lastMessageAt timestamp of the newest message in the thread, null when empty.
 */
data class MessageWindow(
    val messages: List<Message> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val firstMessageAt: Long? = null,
    val lastMessageAt: Long? = null,
)

/**
 * Feedback round 5 — which slice of a thread
 * [com.filestech.sms.domain.repository.ConversationRepository.observeMessagesWindow]
 * loads. The tail is the default view; [EndingAt] is the detour a search jump
 * takes to a match in old, unloaded history — the window ends at (and includes)
 * that message, with its preceding messages as context, instead of loading the
 * whole thread.
 */
sealed interface MessageWindowSpec {
    /** The [limit] most recent messages — the default tail window. */
    data class Tail(val limit: Int) : MessageWindowSpec

    /**
     * The [limit] most recent messages ending at (and including) the message
     * [anchorId] (dated [anchorDate]).
     */
    data class EndingAt(val anchorId: Long, val anchorDate: Long, val limit: Int) : MessageWindowSpec
}
