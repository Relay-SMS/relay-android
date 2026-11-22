package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 42 — the engine's half of the E2E sync: the full
 * safety-number + notice sets ride after every snapshot, changes
 * on the phone push the full sets live, and a `set_e2e_verified`
 * / `retry_e2e_probe` from the desktop applies through the source
 * and answers with the address echoed. The phone is the source of
 * truth — the desktop replaces its own sets with the synced ones
 * wholesale.
 */
class SyncEngineE2eTest {

    /** A controllable [E2eSyncSource] for engine tests, mirroring [FakeSyncMessageSource]. */
    private class FakeE2eSyncSource : E2eSyncSource {
        val peersFlow = MutableSharedFlow<List<SyncedE2ePeer>>(replay = 1)
        val noticesFlow = MutableSharedFlow<List<SyncedE2eNotice>>(replay = 1)

        /** What [safetyNumbers] answers. */
        var peersSnapshot: List<SyncedE2ePeer> = emptyList()

        /** What [notices] answers. */
        var noticesSnapshot: List<SyncedE2eNotice> = emptyList()

        /** When set, [safetyNumbers] throws this instead of answering. */
        var safetyNumbersError: Throwable? = null

        /** When set, [notices] throws this instead of answering. */
        var noticesError: Throwable? = null

        init {
            peersFlow.tryEmit(emptyList())
            noticesFlow.tryEmit(emptyList())
        }

        override suspend fun safetyNumbers(): List<SyncedE2ePeer> {
            safetyNumbersError?.let { throw it }
            return peersSnapshot
        }

        override fun observeSafetyNumbers(): Flow<List<SyncedE2ePeer>> = peersFlow

        override suspend fun notices(): List<SyncedE2eNotice> {
            noticesError?.let { throw it }
            return noticesSnapshot
        }

        override fun observeNotices(): Flow<List<SyncedE2eNotice>> = noticesFlow

        val verifiedSets = mutableListOf<Pair<String, Boolean>>()
        var verifiedResult: ActionResult = ActionResult(ok = true)

        override suspend fun setVerified(address: String, verified: Boolean): ActionResult {
            verifiedSets += address to verified
            if (verifiedResult.ok) {
                val current = peersFlow.replayCache.firstOrNull().orEmpty()
                val updated = current.map {
                    if (it.address == address) it.copy(verified = verified) else it
                }
                // A verified-set for an unknown peer creates the
                // row (like the real glue) — the flow gains a peer.
                val next = if (current.any { it.address == address }) {
                    updated
                } else {
                    updated + SyncedE2ePeer(address, "unknown", null, verified)
                }
                peersFlow.tryEmit(next)
            }
            return verifiedResult
        }

        val probeRetries = mutableListOf<String>()
        var probeResult: ActionResult = ActionResult(ok = true)

        override suspend fun retryProbe(address: String): ActionResult {
            probeRetries += address
            return probeResult
        }
    }

    private companion object {
        const val ALICE = "+33612345678"
        const val NUMBER = "12345 67890 12345 67890 12345 67890 " +
            "12345 67890 12345 67890 12345 67890"

        fun peer(
            address: String = ALICE,
            state: String = "encrypted",
            safetyNumber: String? = NUMBER,
            verified: Boolean = false,
        ) = SyncedE2ePeer(address, state, safetyNumber, verified)

        fun notice(
            id: String = "12",
            conversationId: String = "123",
            kind: String = "activated",
            date: Long = 1_793_000_000_000L,
        ) = SyncedE2eNotice(id, conversationId, kind, date)
    }

    private suspend fun TestScope.runEngineWithE2e(
        source: FakeSyncMessageSource,
        e2e: E2eSyncSource?,
        block: suspend TestScope.(SyncEngine, MutableList<String>) -> Unit,
    ) {
        val sent = mutableListOf<String>()
        val engine = SyncEngine(
            source,
            this,
            send = { sent += it },
            wakelock = NoOpSyncWakelock,
            e2eSource = e2e,
        )
        try {
            block(engine, sent)
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    @Test fun `a sync sends both E2E sets right after sync_complete`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        val e2e = FakeE2eSyncSource()
        e2e.peersSnapshot = listOf(peer())
        e2e.noticesSnapshot = listOf(notice())
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val types = sent.map { it.frameType() }
            assertThat(types).contains("e2e_safety_numbers")
            assertThat(types).contains("e2e_notices")
            // Both sets ride after the snapshot, next to the
            // category mutes: sync_complete, category_mutes, then
            // the E2E sets in order.
            val complete = types.indexOf("sync_complete")
            val mutes = types.indexOf("category_mutes")
            val numbers = types.indexOf("e2e_safety_numbers")
            val notices = types.indexOf("e2e_notices")
            assertThat(mutes).isEqualTo(complete + 1)
            assertThat(numbers).isEqualTo(mutes + 1)
            assertThat(notices).isEqualTo(numbers + 1)

            val numbersFrame = sent.first { it.frameType() == "e2e_safety_numbers" }.asObject()
            val peers = numbersFrame["peers"]!!.jsonArray.map { it.jsonObject }
            assertThat(peers).hasSize(1)
            assertThat(peers[0]["address"]?.jsonPrimitive?.content).isEqualTo(ALICE)
            assertThat(peers[0]["state"]?.jsonPrimitive?.content).isEqualTo("encrypted")
            assertThat(peers[0]["safety_number"]?.jsonPrimitive?.content).isEqualTo(NUMBER)
            assertThat(peers[0]["verified"]?.jsonPrimitive?.content).isEqualTo("false")

            val noticesFrame = sent.first { it.frameType() == "e2e_notices" }.asObject()
            val listed = noticesFrame["notices"]!!.jsonArray.map { it.jsonObject }
            assertThat(listed).hasSize(1)
            assertThat(listed[0]["id"]?.jsonPrimitive?.content).isEqualTo("12")
            assertThat(listed[0]["conversation_id"]?.jsonPrimitive?.content).isEqualTo("123")
            assertThat(listed[0]["kind"]?.jsonPrimitive?.content).isEqualTo("activated")
        }
    }

    @Test fun `a sync sends empty E2E sets when there is nothing to sync`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        val e2e = FakeE2eSyncSource()
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val numbers = sent.first { it.frameType() == "e2e_safety_numbers" }.asObject()
            assertThat(numbers["peers"]!!.jsonArray).isEmpty()
            val notices = sent.first { it.frameType() == "e2e_notices" }.asObject()
            assertThat(notices["notices"]!!.jsonArray).isEmpty()
        }
    }

    @Test fun `a failed E2E read skips that frame but the sync still completes`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        val e2e = FakeE2eSyncSource()
        e2e.safetyNumbersError = IllegalStateException("keystore unhappy")
        e2e.noticesSnapshot = listOf(notice())
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val types = sent.map { it.frameType() }
            assertThat(types).contains("sync_complete")
            assertThat(types).doesNotContain("e2e_safety_numbers")
            // The notices read succeeded, so its frame still rides.
            assertThat(types).contains("e2e_notices")
            assertThat(types).doesNotContain("sync_error")
        }
    }

    @Test fun `a session without an E2E source sends no E2E frames`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        runEngineWithE2e(source, null) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val types = sent.map { it.frameType() }
            assertThat(types).contains("sync_complete")
            assertThat(types).doesNotContain("e2e_safety_numbers")
            assertThat(types).doesNotContain("e2e_notices")
        }
    }

    @Test fun `a peer change on the phone pushes the full set`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            assertThat(e2e.peersFlow.tryEmit(listOf(peer()))).isTrue()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("e2e_safety_numbers")
            val peers = sent.first().asObject()["peers"]!!.jsonArray.map { it.jsonObject }
            assertThat(peers[0]["address"]?.jsonPrimitive?.content).isEqualTo(ALICE)
        }
    }

    @Test fun `a notice change on the phone pushes the full set`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            assertThat(e2e.noticesFlow.tryEmit(listOf(notice()))).isTrue()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("e2e_notices")
        }
    }

    @Test fun `re-emitting an unchanged E2E set sends nothing`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            assertThat(e2e.peersFlow.tryEmit(listOf(peer()))).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(1)

            // An equal set, re-emitted (an unrelated peer-table
            // write re-emits the flow upstream) — the collector
            // dedupes it.
            assertThat(e2e.peersFlow.tryEmit(listOf(peer()))).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(1)

            assertThat(e2e.noticesFlow.tryEmit(listOf(notice()))).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(2)
            assertThat(e2e.noticesFlow.tryEmit(listOf(notice()))).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(2)
        }
    }

    @Test fun `set_e2e_verified applies, answers with the address echo, and broadcasts the updated set`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        e2e.peersFlow.tryEmit(listOf(peer()))
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            engine.onSetE2eVerified(SyncProtocol.SetE2eVerified(address = ALICE, verified = true))
            advanceUntilIdle()
            assertThat(e2e.verifiedSets).containsExactly(ALICE to true)

            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["action"]?.jsonPrimitive?.content).isEqualTo("set_e2e_verified")
            assertThat(result["address"]?.jsonPrimitive?.content).isEqualTo(ALICE)
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(result.containsKey("conversation_id")).isFalse()

            // The apply updated the peers flow, so the collector
            // broadcast the updated set back to this session.
            val numbers = sent.first { it.frameType() == "e2e_safety_numbers" }.asObject()
            val peers = numbers["peers"]!!.jsonArray.map { it.jsonObject }
            assertThat(peers[0]["verified"]?.jsonPrimitive?.content).isEqualTo("true")
        }
    }

    @Test fun `a refused set_e2e_verified answers ok false and broadcasts nothing`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        e2e.verifiedResult = ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.INTERNAL,
            message = "The flag could not be updated.",
        )
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            engine.onSetE2eVerified(SyncProtocol.SetE2eVerified(address = ALICE, verified = true))
            advanceUntilIdle()
            assertThat(e2e.verifiedSets).containsExactly(ALICE to true)
            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(result["code"]?.jsonPrimitive?.content).isEqualTo("internal")
            assertThat(sent.map { it.frameType() }).doesNotContain("e2e_safety_numbers")
        }
    }

    @Test fun `retry_e2e_probe runs the probe and answers with the address echo`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            engine.onRetryE2eProbe(SyncProtocol.RetryE2eProbe(address = ALICE))
            advanceUntilIdle()
            assertThat(e2e.probeRetries).containsExactly(ALICE)
            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["action"]?.jsonPrimitive?.content).isEqualTo("retry_e2e_probe")
            assertThat(result["address"]?.jsonPrimitive?.content).isEqualTo(ALICE)
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("true")
        }
    }

    @Test fun `a failed retry_e2e_probe answers ok false with the source code`() = runTest {
        val source = FakeSyncMessageSource()
        val e2e = FakeE2eSyncSource()
        e2e.probeResult = ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
            message = "Conversation not found.",
        )
        runEngineWithE2e(source, e2e) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            engine.onRetryE2eProbe(SyncProtocol.RetryE2eProbe(address = ALICE))
            advanceUntilIdle()
            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(result["code"]?.jsonPrimitive?.content).isEqualTo("unknown_conversation")
            assertThat(result["address"]?.jsonPrimitive?.content).isEqualTo(ALICE)
        }
    }

    @Test fun `E2E commands without an E2E source answer ok false`() = runTest {
        val source = FakeSyncMessageSource()
        runEngineWithE2e(source, null) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            engine.onSetE2eVerified(SyncProtocol.SetE2eVerified(address = ALICE, verified = true))
            engine.onRetryE2eProbe(SyncProtocol.RetryE2eProbe(address = ALICE))
            advanceUntilIdle()
            val results = sent.filter { it.frameType() == "action_result" }.map { it.asObject() }
            assertThat(results).hasSize(2)
            assertThat(results.map { it["action"]?.jsonPrimitive?.content })
                .containsExactly("set_e2e_verified", "retry_e2e_probe")
            assertThat(results.map { it["ok"]?.jsonPrimitive?.content })
                .containsExactly("false", "false")
            assertThat(results.map { it["code"]?.jsonPrimitive?.content })
                .containsExactly("internal", "internal")
        }
    }
}
