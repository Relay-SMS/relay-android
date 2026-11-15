package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Phase 2 — the engine's scheduled-send handler, driven without a
 * socket: the fake source records the schedule call and answers from
 * a configurable result, and the harness records every outbound
 * frame. The routing and the ack shape are the contract the desktop
 * schedules against, so they are pinned here. The dedup and the
 * validation live in the glue
 * ([com.filestech.sms.system.sync.RepositorySyncMessageSource]) and
 * are pinned in its own tests — the engine only forwards.
 */
class SyncEngineSchedulingTest {

    @Test fun `schedule_message routes to the source and answers message_scheduled`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onScheduleMessage(
                SyncProtocol.ScheduleMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "123",
                    body = "Happy birthday!",
                    sendAt = 1_800_000_000_000L,
                ),
            )
            advanceUntilIdle()
            assertThat(source.schedules).containsExactly(
                ScheduleCall(CLIENT_KEY, 123L, "Happy birthday!", 1_800_000_000_000L),
            )
            assertThat(sent.map { it.frameType() }).containsExactly("message_scheduled")
            val ack = sent.first().asObject()
            assertThat(ack["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
            assertThat(ack["message_id"]?.jsonPrimitive?.content).isEqualTo("4242")
            assertThat(ack["conversation_id"]?.jsonPrimitive?.content).isEqualTo("123")
            assertThat(ack["send_at"]?.jsonPrimitive?.content).isEqualTo("1800000000000")
        }
    }

    @Test fun `a refused schedule answers message_failed with the source code`() = runTest {
        val source = FakeSyncMessageSource()
        source.scheduleResult = ScheduleResult.Failed(
            code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
            message = "Conversation not found.",
        )
        runEngine(source) { engine, sent ->
            engine.onScheduleMessage(
                SyncProtocol.ScheduleMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "999",
                    body = "Hi",
                    sendAt = 1_800_000_000_000L,
                ),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("message_failed")
            val failed = sent.first().asObject()
            assertThat(failed["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
            assertThat(failed["conversation_id"]?.jsonPrimitive?.content).isEqualTo("999")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("unknown_conversation")
        }
    }

    @Test fun `schedule_message with an unparseable conversation id never reaches the source`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onScheduleMessage(
                SyncProtocol.ScheduleMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "not-a-number",
                    body = "Hi",
                    sendAt = 1_800_000_000_000L,
                ),
            )
            advanceUntilIdle()
            assertThat(source.schedules).isEmpty()
            assertThat(sent.map { it.frameType() }).containsExactly("message_failed")
            val failed = sent.first().asObject()
            assertThat(failed["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION)
        }
    }

    @Test fun `a throwing source answers message_failed internal and keeps the socket up`() = runTest {
        val source = FakeSyncMessageSource()
        source.scheduleError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onScheduleMessage(
                SyncProtocol.ScheduleMessage(
                    clientMessageId = CLIENT_KEY,
                    conversationId = "123",
                    body = "Hi",
                    sendAt = 1_800_000_000_000L,
                ),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("message_failed")
            val failed = sent.first().asObject()
            assertThat(failed["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.MessageFailed.INTERNAL)
            // The message stays generic — never the exception text.
            assertThat(failed["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    // -- Phase 2: scheduled cancel --------------------------------------

    @Test fun `cancel_scheduled routes to the source and answers schedule_cancelled`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onCancelScheduled(SyncProtocol.CancelScheduled(clientMessageId = CLIENT_KEY))
            advanceUntilIdle()
            assertThat(source.cancels).containsExactly(CLIENT_KEY)
            assertThat(sent.map { it.frameType() }).containsExactly("schedule_cancelled")
            val ack = sent.first().asObject()
            assertThat(ack["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
        }
    }

    @Test fun `a failed cancel answers message_failed with an empty conversation id`() = runTest {
        val source = FakeSyncMessageSource()
        source.cancelResult = CancelResult.Failed(
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The scheduled message could not be cancelled.",
        )
        runEngine(source) { engine, sent ->
            engine.onCancelScheduled(SyncProtocol.CancelScheduled(clientMessageId = CLIENT_KEY))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("message_failed")
            val failed = sent.first().asObject()
            assertThat(failed["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
            // A cancel carries no conversation — the desktop matches
            // a cancel failure by client_message_id.
            assertThat(failed["conversation_id"]?.jsonPrimitive?.content).isEqualTo("")
            assertThat(failed["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.MessageFailed.INTERNAL)
        }
    }

    @Test fun `a throwing source on cancel answers message_failed internal and keeps the socket up`() = runTest {
        val source = FakeSyncMessageSource()
        source.cancelError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onCancelScheduled(SyncProtocol.CancelScheduled(clientMessageId = CLIENT_KEY))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("message_failed")
            val failed = sent.first().asObject()
            assertThat(failed["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.MessageFailed.INTERNAL)
            assertThat(failed["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    private companion object {
        private const val CLIENT_KEY = "3f2a1b00-0000-4000-8000-00000000c41d"
    }
}
