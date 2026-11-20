package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.settings.SortMode
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Notes — the merged list sort ([trierListItems]): pinned notes
 * sort with pinned conversations, notes interleave by recency
 * (an empty note sorts by its creation time), and under
 * `UNREAD_FIRST` a note counts as read.
 */
class TrierListItemsTest {

    private fun conv(id: Long, at: Long, pinned: Boolean = false, unread: Int = 0) =
        ConversationListItem.ConversationItem(
            Conversation(
                id = id,
                threadId = id,
                addresses = emptyList(),
                displayName = "c$id",
                lastMessageAt = at,
                lastMessagePreview = null,
                unreadCount = unread,
                pinned = pinned,
                archived = false,
                muted = false,
                inVault = false,
                draft = null,
            ),
        )

    private fun note(
        id: Long,
        at: Long?,
        pinned: Boolean = false,
        createdAt: Long = 0L,
    ) = ConversationListItem.NoteItem(
        Note(
            id = id,
            uuid = "note-$id",
            title = "n$id",
            createdAt = createdAt,
            updatedAt = createdAt,
            lastMessageAt = at,
            lastMessagePreview = null,
            draft = null,
            pinned = pinned,
            archived = false,
            bubbleColorArgb = null,
        ),
    )

    private fun List<ConversationListItem>.tags(): List<String> = map {
        when (it) {
            is ConversationListItem.ConversationItem -> "c${it.conversation.id}"
            is ConversationListItem.NoteItem -> "n${it.note.id}"
        }
    }

    @Test
    fun `pinned note sorts with pinned conversations`() {
        val rows = listOf(conv(1, at = 300), note(2, at = 100, pinned = true), conv(3, at = 200))

        assertThat(trierListItems(rows, SortMode.DATE).tags())
            .containsExactly("n2", "c1", "c3").inOrder()
    }

    @Test
    fun `notes interleave with conversations by recency`() {
        val rows = listOf(conv(1, at = 300), conv(3, at = 200), note(2, at = 250))

        assertThat(trierListItems(rows, SortMode.DATE).tags())
            .containsExactly("c1", "n2", "c3").inOrder()
    }

    @Test
    fun `empty note sorts by its creation time`() {
        val rows = listOf(conv(1, at = 300), conv(3, at = 200), note(2, at = null, createdAt = 250))

        assertThat(trierListItems(rows, SortMode.DATE).tags())
            .containsExactly("c1", "n2", "c3").inOrder()
    }

    @Test
    fun `unread-first treats notes as read`() {
        val rows = listOf(
            conv(1, at = 100, unread = 2),
            note(2, at = 300),
            conv(3, at = 200),
        )

        assertThat(trierListItems(rows, SortMode.UNREAD_FIRST).tags())
            .containsExactly("c1", "n2", "c3").inOrder()
    }
}
