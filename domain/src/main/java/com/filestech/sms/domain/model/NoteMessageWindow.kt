package com.filestech.sms.domain.model

/**
 * A bounded slice of a note: the [messages] currently loaded, ordered oldest-first, plus
 * enough context for the UI to describe what it is *not* showing. Mirrors [MessageWindow].
 *
 * [totalCount], [firstMessageAt] and [lastMessageAt] describe the **whole** note, not the
 * loaded window — deriving them from the window would quietly report the window's own
 * bounds as the note's.
 *
 * @property messages the loaded window, oldest first — the order the thread renders in.
 * @property totalCount every entry in the note, window included.
 * @property hasMore whether older entries exist beyond the window.
 * @property firstMessageAt timestamp of the oldest entry in the note, null when empty.
 * @property lastMessageAt timestamp of the newest entry in the note, null when empty.
 */
data class NoteMessageWindow(
    val messages: List<NoteMessage> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val firstMessageAt: Long? = null,
    val lastMessageAt: Long? = null,
)
