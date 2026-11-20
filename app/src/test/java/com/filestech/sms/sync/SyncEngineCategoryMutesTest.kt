package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Category mutes — the engine's half of the muted-category sync:
 * the full set rides after every snapshot, changes on the phone
 * push the full set live, and a `set_category_muted` from the
 * desktop applies through the source and broadcasts back. The
 * phone is the source of truth — the desktop replaces its own
 * list with the synced set wholesale.
 */
class SyncEngineCategoryMutesTest {

    @Test fun `a sync sends the full muted set right after sync_complete`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        source.mutedCategoriesSnapshot = setOf("promotional", "otp")
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val types = sent.map { it.frameType() }
            assertThat(types).contains("category_mutes")
            assertThat(types.indexOf("category_mutes")).isEqualTo(types.indexOf("sync_complete") + 1)
            val mutes = sent.first { it.frameType() == "category_mutes" }.asObject()
            val muted = mutes["muted_categories"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertThat(muted).containsExactly("otp", "promotional").inOrder()
        }
    }

    @Test fun `a sync sends an empty muted set when nothing is muted`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val mutes = sent.first { it.frameType() == "category_mutes" }.asObject()
            assertThat(mutes["muted_categories"]!!.jsonArray).isEmpty()
        }
    }

    @Test fun `an unreadable settings read skips the frame after sync_complete`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        source.mutedCategoriesSnapshot = null
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).contains("sync_complete")
            assertThat(sent.map { it.frameType() }).doesNotContain("category_mutes")
        }
    }

    @Test fun `a category change on the phone pushes the full set`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            assertThat(source.mutedCategoriesFlow.tryEmit(setOf("otp"))).isTrue()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("category_mutes")
            val mutes = sent.first().asObject()
            val muted = mutes["muted_categories"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertThat(muted).containsExactly("otp").inOrder()
        }
    }

    @Test fun `re-emitting an unchanged set sends nothing`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            assertThat(source.mutedCategoriesFlow.tryEmit(setOf("otp"))).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(1)

            // An equal set, re-emitted (an unrelated settings write
            // re-emits the flow upstream) — the collector dedupes it.
            assertThat(source.mutedCategoriesFlow.tryEmit(setOf("otp"))).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(1)
        }
    }

    @Test fun `set_category_muted applies, answers with the category echo, and broadcasts the updated set`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            engine.onSetCategoryMuted(SyncProtocol.SetCategoryMuted(category = "otp", muted = true))
            advanceUntilIdle()
            assertThat(source.categoryMuteSets).containsExactly(CategoryMuteCall("otp", true))

            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["action"]?.jsonPrimitive?.content).isEqualTo("set_category_muted")
            assertThat(result["category"]?.jsonPrimitive?.content).isEqualTo("otp")
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(result.containsKey("conversation_id")).isFalse()

            // The apply updated the settings flow, so the collector
            // broadcast the updated set back to this session.
            val mutes = sent.first { it.frameType() == "category_mutes" }.asObject()
            val muted = mutes["muted_categories"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertThat(muted).containsExactly("otp").inOrder()
        }
    }

    @Test fun `a refused set_category_muted answers ok false and broadcasts nothing`() = runTest {
        val source = FakeSyncMessageSource()
        source.categoryMuteResult = ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.INVALID_REQUEST,
            message = "Unknown category.",
        )
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            engine.onSetCategoryMuted(SyncProtocol.SetCategoryMuted(category = "spam", muted = true))
            advanceUntilIdle()
            assertThat(source.categoryMuteSets).containsExactly(CategoryMuteCall("spam", true))
            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(result["code"]?.jsonPrimitive?.content).isEqualTo("invalid_request")
            assertThat(sent.map { it.frameType() }).doesNotContain("category_mutes")
        }
    }
}
