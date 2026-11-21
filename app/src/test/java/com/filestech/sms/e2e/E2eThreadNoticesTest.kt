package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.domain.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * E2E — the in-thread notice merge (design §6.4): a notice renders
 * above the first message dated at or after it, or after the last
 * message when it is newer than everything loaded.
 *
 * Pure Kotlin, no coroutines — block bodies, so every assertion
 * ends the test with a void statement (JUnit silently skips a
 * `@Test` whose body returns a value).
 */
class E2eThreadNoticesTest {

    private fun message(id: Long, date: Long) = Message(
        id = id,
        conversationId = 1L,
        address = "+33612345678",
        body = "x",
        type = Message.Type.SMS,
        direction = Message.Direction.INCOMING,
        date = date,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.DELIVERED,
        errorCode = null,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    private fun notice(id: Long, date: Long, kind: E2eNoticeKind = E2eNoticeKind.ACTIVATED) =
        E2eNoticeUi(id = id, kind = kind, date = date)

    @Test
    fun `notice before the first message renders above it`() {
        val messages = listOf(message(1, 2000L), message(2, 3000L))
        val notices = listOf(notice(1, 1000L))

        val placement = E2eThreadNotices.place(messages, notices)

        assertThat(placement.before).containsExactly(
            listOf(notices[0]),
            emptyList<E2eNoticeUi>(),
        ).inOrder()
        assertThat(placement.trailing).isEmpty()
    }

    @Test
    fun `notice between two messages renders above the later one`() {
        val messages = listOf(message(1, 1000L), message(2, 3000L), message(3, 5000L))
        val notices = listOf(notice(1, 2000L))

        val placement = E2eThreadNotices.place(messages, notices)

        assertThat(placement.before[1]).containsExactly(notices[0]).inOrder()
        assertThat(placement.trailing).isEmpty()
    }

    @Test
    fun `notice newer than every message is trailing`() {
        val messages = listOf(message(1, 1000L), message(2, 2000L))
        val notices = listOf(notice(1, 9000L))

        val placement = E2eThreadNotices.place(messages, notices)

        assertThat(placement.before).containsExactly(
            emptyList<E2eNoticeUi>(),
            emptyList<E2eNoticeUi>(),
        ).inOrder()
        assertThat(placement.trailing).containsExactly(notices[0]).inOrder()
    }

    @Test
    fun `notice dated exactly at a message renders above that message`() {
        val messages = listOf(message(1, 1000L), message(2, 2000L))
        val notices = listOf(notice(1, 2000L))

        val placement = E2eThreadNotices.place(messages, notices)

        assertThat(placement.before[1]).containsExactly(notices[0]).inOrder()
        assertThat(placement.trailing).isEmpty()
    }

    @Test
    fun `empty messages leave every notice trailing`() {
        val notices = listOf(notice(1, 1000L), notice(2, 2000L))

        val placement = E2eThreadNotices.place(emptyList(), notices)

        assertThat(placement.before).isEmpty()
        assertThat(placement.trailing).containsExactly(notices[0], notices[1]).inOrder()
    }

    @Test
    fun `no notices place nothing`() {
        val messages = listOf(message(1, 1000L), message(2, 2000L))

        val placement = E2eThreadNotices.place(messages, emptyList())

        assertThat(placement.before).containsExactly(
            emptyList<E2eNoticeUi>(),
            emptyList<E2eNoticeUi>(),
        ).inOrder()
        assertThat(placement.trailing).isEmpty()
    }

    @Test
    fun `unsorted notices are placed in date order`() {
        val messages = listOf(message(1, 1000L), message(2, 3000L))
        val early = notice(1, 500L)
        val late = notice(2, 2000L)

        val placement = E2eThreadNotices.place(messages, listOf(late, early))

        assertThat(placement.before[0]).containsExactly(early).inOrder()
        assertThat(placement.before[1]).containsExactly(late).inOrder()
        assertThat(placement.trailing).isEmpty()
    }

    @Test
    fun `several notices before one message keep date order`() {
        val messages = listOf(message(1, 5000L))
        val first = notice(1, 1000L, E2eNoticeKind.ACTIVATED)
        val second = notice(2, 2000L, E2eNoticeKind.KEY_CHANGED)

        val placement = E2eThreadNotices.place(messages, listOf(second, first))

        assertThat(placement.before[0]).containsExactly(first, second).inOrder()
        assertThat(placement.trailing).isEmpty()
    }
}
