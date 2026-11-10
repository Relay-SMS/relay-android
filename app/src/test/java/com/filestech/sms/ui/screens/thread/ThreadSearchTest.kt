package com.filestech.sms.ui.screens.thread

import com.filestech.sms.domain.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Feedback round 5 — search inside a conversation: mapping full-history
 * results onto the loaded window, and next/previous stepping between
 * matching messages.
 *
 * Covers: blank query matches nothing; case-insensitive literal search
 * (regex characters in the query match themselves); every occurrence in one
 * message is reported; matches come back in thread order with the right
 * message index; blank bodies never match; a match in old, unloaded history
 * is found (index -1, no ranges yet) and still counts; next/previous wrap
 * around.
 */
class ThreadSearchTest {

    private fun msg(
        id: Long,
        body: String,
        date: Long = id * 1_000L,
        outgoing: Boolean = false,
    ) = Message(
        id = id,
        conversationId = 1L,
        address = if (outgoing) "me" else "+33612345678",
        body = body,
        type = Message.Type.SMS,
        direction = if (outgoing) Message.Direction.OUTGOING else Message.Direction.INCOMING,
        date = date,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.SENT,
        errorCode = null,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    @Test fun `blank query matches nothing`() {
        val messages = listOf(msg(1, "hello there"))
        assertThat(mapThreadSearchMatches(listOf(1L), messages, "")).isEmpty()
        assertThat(mapThreadSearchMatches(listOf(1L), messages, "   ")).isEmpty()
    }

    @Test fun `no match returns an empty list`() {
        val messages = listOf(msg(1, "hello there"), msg(2, "goodbye"))
        assertThat(mapThreadSearchMatches(emptyList(), messages, "zzz")).isEmpty()
    }

    @Test fun `match is case-insensitive`() {
        val messages = listOf(msg(1, "See you at the CAFÉ tomorrow"))
        val found = mapThreadSearchMatches(listOf(1L), messages, "café")
        assertThat(found).hasSize(1)
        assertThat(found[0].messageId).isEqualTo(1L)
        assertThat(found[0].messageIndex).isEqualTo(0)
        assertThat(found[0].ranges).containsExactly(15..18)
    }

    @Test fun `every occurrence in one message is reported`() {
        val messages = listOf(msg(1, "rain rain go away, rain again"))
        val found = mapThreadSearchMatches(listOf(1L), messages, "rain")
        assertThat(found).hasSize(1)
        assertThat(found[0].ranges).containsExactly(0..3, 5..8, 19..22).inOrder()
    }

    @Test fun `matches come back in thread order with the right index`() {
        val messages = listOf(
            msg(1, "nothing here"),
            msg(2, "the train leaves at six"),
            msg(3, "also nothing"),
            msg(4, "train tickets, please"),
        )
        val found = mapThreadSearchMatches(listOf(2L, 4L), messages, "train")
        assertThat(found.map { it.messageId }).containsExactly(2L, 4L).inOrder()
        assertThat(found.map { it.messageIndex }).containsExactly(1, 3).inOrder()
    }

    @Test fun `blank bodies never match`() {
        val messages = listOf(msg(1, ""), msg(2, "   "), msg(3, "hello"))
        val found = mapThreadSearchMatches(listOf(3L), messages, "hello")
        assertThat(found.map { it.messageId }).containsExactly(3L)
    }

    @Test fun `query characters are literal, not a regex`() {
        val messages = listOf(msg(1, "I love C++ and (urgent) matters"))
        assertThat(mapThreadSearchMatches(listOf(1L), messages, "C++")).hasSize(1)
        assertThat(mapThreadSearchMatches(listOf(1L), messages, "(urgent)")).hasSize(1)
        // A regex dot would match any character; the literal dot matches only itself.
        assertThat(mapThreadSearchMatches(emptyList(), messages, "C+.")).isEmpty()
    }

    @Test fun `match in old unloaded history is found with index minus one`() {
        // The loaded window holds only the two newest messages; the oldest
        // match (id 1) is in history the user has not loaded yet. It still
        // counts, and the screen jumps the window to it before scrolling.
        val loaded = listOf(
            msg(5, "the train is late"),
            msg(9, "train again"),
        )
        val found = mapThreadSearchMatches(listOf(1L, 5L, 9L), loaded, "train")
        assertThat(found).hasSize(3)
        assertThat(found[0].messageId).isEqualTo(1L)
        assertThat(found[0].messageIndex).isEqualTo(-1)
        assertThat(found[0].ranges).isEmpty()
        assertThat(found[1].messageId).isEqualTo(5L)
        assertThat(found[1].messageIndex).isEqualTo(0)
        assertThat(found[1].ranges).containsExactly(4..8)
        assertThat(found[2].messageId).isEqualTo(9L)
        assertThat(found[2].messageIndex).isEqualTo(1)
        assertThat(found[2].ranges).containsExactly(0..4)
    }

    @Test fun `match count counts the whole thread, not just the loaded window`() {
        val loaded = listOf(msg(3, "train home"))
        val found = mapThreadSearchMatches(listOf(1L, 2L, 3L), loaded, "train")
        assertThat(found).hasSize(3)
        assertThat(found.map { it.messageIndex }).containsExactly(-1, -1, 0).inOrder()
    }

    @Test fun `occurrences do not overlap`() {
        assertThat(findQueryRanges("aaaaaa", "aaa"))
            .containsExactly(0..2, 3..5).inOrder()
    }

    @Test fun `empty query or text has no ranges`() {
        assertThat(findQueryRanges("hello", "")).isEmpty()
        assertThat(findQueryRanges("", "hello")).isEmpty()
    }

    @Test fun `next wraps from the last match back to the first`() {
        assertThat(nextThreadSearchIndex(0, 3)).isEqualTo(1)
        assertThat(nextThreadSearchIndex(2, 3)).isEqualTo(0)
        assertThat(nextThreadSearchIndex(-1, 3)).isEqualTo(0)
        assertThat(nextThreadSearchIndex(0, 0)).isEqualTo(-1)
    }

    @Test fun `previous wraps from the first match back to the last`() {
        assertThat(previousThreadSearchIndex(1, 3)).isEqualTo(0)
        assertThat(previousThreadSearchIndex(0, 3)).isEqualTo(2)
        assertThat(previousThreadSearchIndex(-1, 3)).isEqualTo(2)
        assertThat(previousThreadSearchIndex(0, 0)).isEqualTo(-1)
    }
}
