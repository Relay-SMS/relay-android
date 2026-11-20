package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Note

/**
 * Notes — one row of the conversation list. A sealed type, not an
 * `isNote` flag on [Conversation]: notes and conversations share
 * the list's shape (title, preview, date, pin, archive) but nothing
 * else — no unread, no mute, no block, no members, no phone
 * numbers — and a flag would let a note leak into a conversation
 * path (or the reverse) by forgetting one check. The sort keys live
 * here so the merged sort ([trierListItems]) cannot drift from the
 * row data.
 *
 * Every row's [sortKey] is the timestamp the recency sort reads:
 * the last message for a conversation, the last entry for a note,
 * falling back to the creation time for an empty note (so a new
 * empty note sits at the top, like a new conversation draft).
 */
sealed interface ConversationListItem {
    /** The timestamp the recency sort reads (last message, or creation for an empty note). */
    val sortKey: Long

    /** Pinned rows sort above unpinned ones, notes and conversations together. */
    val pinned: Boolean

    /** Archived rows live in the archived list, notes and conversations together. */
    val archived: Boolean

    /** Unread only ever applies to conversations; notes are always read. */
    val isUnread: Boolean

    data class ConversationItem(val conversation: Conversation) : ConversationListItem {
        override val sortKey: Long get() = conversation.lastMessageAt
        override val pinned: Boolean get() = conversation.pinned
        override val archived: Boolean get() = conversation.archived
        override val isUnread: Boolean get() = conversation.unreadCount > 0
    }

    data class NoteItem(val note: Note) : ConversationListItem {
        override val sortKey: Long get() = note.lastMessageAt ?: note.createdAt
        override val pinned: Boolean get() = note.pinned
        override val archived: Boolean get() = note.archived
        override val isUnread: Boolean get() = false
    }
}
