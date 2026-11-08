package com.filestech.sms.ui.screens.thread

import com.filestech.sms.domain.model.Message
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Feedback round 4 — the timestamp rule is one timestamp per same-sender run,
 * on its LAST message (replacing the old 1-minute gap rule), and the delivery
 * status is shown independently of the timestamp, on the last outgoing message
 * of a run.
 *
 * Covers: 3-message same-sender burst → time only on the last; sender change →
 * time on the reply; alternating senders → time on each; waits between messages
 * do not split a run; status only on the last outgoing message of a run.
 */
class ThreadTimestampRuleTest {

    private fun msg(
        id: Long,
        date: Long,
        outgoing: Boolean,
        address: String = if (outgoing) "me" else "+33612345678",
    ) = Message(
        id = id,
        conversationId = 1L,
        address = address,
        body = "msg $id",
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

    @Test
    fun `three-message same-sender burst shows time only on the last`() {
        val m1 = msg(1, date = 1_000L, outgoing = true)
        val m2 = msg(2, date = 21_000L, outgoing = true)
        val m3 = msg(3, date = 41_000L, outgoing = true)
        assertThat(isLastOfSenderRun(m1, m2)).isFalse()
        assertThat(isLastOfSenderRun(m2, m3)).isFalse()
        assertThat(isLastOfSenderRun(m3, null)).isTrue()
    }

    @Test
    fun `texting waiting texting again within a minute is one run`() {
        // Kristin's scenario: text, wait, text, wait, text — all within about a
        // minute, same sender, no reply in between → exactly one timestamp, on
        // the last message. The waits do not split the run.
        val m1 = msg(1, date = 1_000L, outgoing = true)
        val m2 = msg(2, date = 25_000L, outgoing = true)
        val m3 = msg(3, date = 50_000L, outgoing = true)
        assertThat(isLastOfSenderRun(m1, m2)).isFalse()
        assertThat(isLastOfSenderRun(m2, m3)).isFalse()
        assertThat(isLastOfSenderRun(m3, null)).isTrue()
    }

    @Test
    fun `sender change shows time on the reply`() {
        val mine = msg(1, date = 1_000L, outgoing = true)
        val theirs = msg(2, date = 2_000L, outgoing = false)
        val mineAgain = msg(3, date = 3_000L, outgoing = true)
        assertThat(isLastOfSenderRun(mine, theirs)).isTrue()
        assertThat(isLastOfSenderRun(theirs, mineAgain)).isTrue()
        assertThat(isLastOfSenderRun(mineAgain, null)).isTrue()
    }

    @Test
    fun `alternating senders show time on every message`() {
        val messages = listOf(
            msg(1, date = 1_000L, outgoing = true),
            msg(2, date = 2_000L, outgoing = false),
            msg(3, date = 3_000L, outgoing = true),
            msg(4, date = 4_000L, outgoing = false),
        )
        messages.forEachIndexed { index, message ->
            assertThat(isLastOfSenderRun(message, messages.getOrNull(index + 1))).isTrue()
        }
    }

    @Test
    fun `status shows only on the last outgoing message of a run`() {
        val m1 = msg(1, date = 1_000L, outgoing = true)
        val m2 = msg(2, date = 2_000L, outgoing = true)
        val m3 = msg(3, date = 3_000L, outgoing = true)
        val reply = msg(4, date = 4_000L, outgoing = false)
        assertThat(isLastOutgoingOfRun(m1, m2)).isFalse()
        assertThat(isLastOutgoingOfRun(m2, m3)).isFalse()
        assertThat(isLastOutgoingOfRun(m3, reply)).isTrue()
        assertThat(isLastOutgoingOfRun(m3, null)).isTrue()
        // Incoming messages never carry a delivery status.
        assertThat(isLastOutgoingOfRun(reply, null)).isFalse()
        assertThat(isLastOutgoingOfRun(reply, m1)).isFalse()
    }

    @Test
    fun `incoming messages from two people share one run like the shape grouping`() {
        // Sender is the direction here, the same notion computeBurstPosition
        // uses: in a group thread, consecutive incoming messages from two
        // different people form one run (and one burst shape).
        val marie = msg(1, date = 1_000L, outgoing = false, address = "+33611111111")
        val paul = msg(2, date = 2_000L, outgoing = false, address = "+33622222222")
        assertThat(isLastOfSenderRun(marie, paul)).isFalse()
        assertThat(isLastOfSenderRun(paul, null)).isTrue()
    }
}
