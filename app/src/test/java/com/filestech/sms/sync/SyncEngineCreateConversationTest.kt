package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Create conversation — the engine's new-conversation handler,
 * driven without a socket: the fake source records the create call
 * and answers from a configurable result, and the harness records
 * every outbound frame. The routing and the ack shape are the
 * contract the desktop creates against, so they are pinned here.
 * The `findOrCreate` call and the validation live in the glue
 * ([com.filestech.sms.system.sync.RepositorySyncMessageSource])
 * and are pinned in its own tests — the engine only forwards.
 */
class SyncEngineCreateConversationTest {

    @Test fun `create_conversation routes to the source and answers conversation_created`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onCreateConversation(
                SyncProtocol.CreateConversation(
                    addresses = listOf("+15551234567", "+15550001111"),
                    customName = "Family",
                ),
            )
            advanceUntilIdle()
            assertThat(source.creates).containsExactly(
                CreateCall(listOf("+15551234567", "+15550001111"), "Family"),
            )
            assertThat(sent.map { it.frameType() }).containsExactly("conversation_created")
            val ack = sent.first().asObject()
            val conversation = ack["conversation"]!!.jsonObject
            assertThat(conversation["id"]?.jsonPrimitive?.content).isEqualTo("123")
            assertThat(conversation["title"]?.jsonPrimitive?.content).isEqualTo("Family")
        }
    }

    @Test fun `a refused create answers action_result ok false with no conversation id`() = runTest {
        val source = FakeSyncMessageSource()
        source.createResult = CreateConversationResult.Failed(
            code = SyncProtocol.MessageFailed.INVALID_REQUEST,
            message = "Choose at least one contact to start a conversation.",
        )
        runEngine(source) { engine, sent ->
            engine.onCreateConversation(
                SyncProtocol.CreateConversation(addresses = emptyList(), customName = null),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["action"]?.jsonPrimitive?.content).isEqualTo("create_conversation")
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("invalid_request")
            // No conversation exists yet — the frame carries no id.
            assertThat(failed.containsKey("conversation_id")).isFalse()
        }
    }

    @Test fun `a throwing source answers action_result internal and keeps the socket up`() = runTest {
        val source = FakeSyncMessageSource()
        source.createError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onCreateConversation(
                SyncProtocol.CreateConversation(
                    addresses = listOf("+15551234567"),
                    customName = null,
                ),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["action"]?.jsonPrimitive?.content).isEqualTo("create_conversation")
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.MessageFailed.INTERNAL)
            // The message stays generic — never the exception text.
            assertThat(failed["message"]?.jsonPrimitive?.content).doesNotContain("boom")
            assertThat(failed.containsKey("conversation_id")).isFalse()
        }
    }
}
