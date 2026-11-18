package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity
import com.filestech.sms.data.local.db.entity.ClientSyncStateEntity
import com.filestech.sms.data.repository.SyncedConversationResult
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncWakelock
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Client mode — sync pause/resume: the engine parks a first full
 * sync when the device falls asleep (Doze, unexempted) or the
 * stream stalls 60 s, and resumes it where it stopped — the
 * counter continues, completed conversations are never
 * re-requested, unreached ones are gap-filled from their frontier.
 *
 * Same harness shape as [ClientSyncEngineTest] (a fake channel, a
 * fake mirror, a fake state store, the engine in a separate
 * TestScope on the same scheduler), plus a fake pause store, a
 * fake idle monitor, and a fake wakelock. The fake mirror mirrors
 * production in the two ways the resume depends on: it ensures a
 * frontier row for every upserted conversation (see
 * `ConversationMirrorAdapter`), and it reports `inserted = false`
 * for a re-delivered message.
 *
 * Timing discipline: while a full run is Syncing, these tests use
 * [runCurrent], never [advanceUntilIdle] — the stall watchdog is
 * armed (the real 60 s timeout, unlike [ClientSyncEngineTest]
 * which disables it), and `advanceUntilIdle` would fast-forward
 * virtual time past 60 s and pause the run under test. Only the
 * watchdog tests advance time, deliberately.
 */
class ClientSyncEnginePauseTest {

    // -- Fakes ------------------------------------------------------

    private class FakeChannel : ClientSyncChannel {
        override val state: StateFlow<ClientConnectionState> =
            MutableStateFlow(ClientConnectionState.Disconnected)
        override val frames: SharedFlow<ServerFrame> =
            MutableSharedFlow(extraBufferCapacity = 64)
        val sent = mutableListOf<String>()
        var sendResult = true

        override suspend fun send(text: String): Boolean {
            sent += text
            return sendResult
        }

        fun connect(hubId: String = HUB_ID, hubName: String = HUB_NAME) {
            (state as MutableStateFlow).value =
                ClientConnectionState.Connected(hubId, hubName)
        }

        fun disconnect() {
            (state as MutableStateFlow).value = ClientConnectionState.Disconnected
        }

        suspend fun emit(frame: ServerFrame) {
            (frames as MutableSharedFlow).emit(frame)
        }
    }

    private class FakeMirror(private val store: FakeStateStore) : ClientMirror {
        val conversations = mutableListOf<SyncedConversation>()
        val messages = mutableListOf<SyncedMessage>()
        var prunedWith: Set<Long>? = null
        var nextLocalId = 1L
        private val seenMessageIds = mutableSetOf<String>()
        var conversationResult: (SyncedConversation) -> SyncedConversationResult =
            { SyncedConversationResult(nextLocalId++, created = true) }
        var messageResult: (SyncedMessage) -> SyncedMessageResult =
            { SyncedMessageResult(nextLocalId++, inserted = seenMessageIds.add(it.id)) }

        override suspend fun upsertSyncedConversation(
            wire: SyncedConversation,
        ): SyncedConversationResult? {
            val hubId = wire.id.toLongOrNull() ?: return null
            conversations += wire
            // Production parity: the real adapter ensures a
            // frontier row for every upserted conversation, so
            // gap-fill can find conversations the stream never
            // reached.
            store.ensureConversationState(hubId)
            return conversationResult(wire)
        }

        override suspend fun upsertSyncedMessage(wire: SyncedMessage): SyncedMessageResult? {
            messages += wire
            return messageResult(wire)
        }

        override suspend fun applyMessageUpdated(update: SyncProtocol.MessageUpdated) = Unit

        override suspend fun resolveReplyTarget(localMessageId: Long, localTargetId: Long) = Unit

        override suspend fun deleteSyncedConversation(hubConversationId: Long) = Unit

        override suspend fun pruneSyncedConversations(keepHubIds: Set<Long>): Int {
            prunedWith = keepHubIds
            return 0
        }
    }

    private class FakeStateStore : SyncStateStore {
        val syncStates = mutableMapOf<String, ClientSyncStateEntity>()
        val convStates = mutableMapOf<Long, ClientConversationStateEntity>()

        override suspend fun getSyncState(hubId: String) = syncStates[hubId]

        override suspend fun listHubIds(): List<String> = syncStates.keys.toList()

        override suspend fun saveSyncState(state: ClientSyncStateEntity) {
            syncStates[state.hubId] = state
        }

        override suspend fun deleteSyncState(hubId: String) {
            syncStates.remove(hubId)
        }

        override suspend fun getConversationState(hubConversationId: Long) =
            convStates[hubConversationId]

        override suspend fun saveConversationState(state: ClientConversationStateEntity) {
            convStates[state.hubConversationId] = state
        }

        override suspend fun ensureConversationState(hubConversationId: Long) {
            convStates.getOrPut(hubConversationId) {
                ClientConversationStateEntity(hubConversationId)
            }
        }

        override suspend fun deleteConversationState(hubConversationId: Long) {
            convStates.remove(hubConversationId)
        }

        override suspend fun listIncompleteConversationIds(): List<Long> =
            convStates.values.filter { !it.historyComplete }.map { it.hubConversationId }

        override suspend fun clearAll() {
            syncStates.clear()
            convStates.clear()
        }
    }

    private class FakePauseStore : SyncPauseStore {
        private val flow = MutableStateFlow<PausedRun?>(null)

        override val paused: Flow<PausedRun?> = flow

        override suspend fun snapshot(): PausedRun? = flow.value

        override suspend fun save(run: PausedRun) {
            flow.value = run
        }

        override suspend fun clear() {
            flow.value = null
        }
    }

    private class FakeIdleMonitor : DeviceIdleMonitor {
        var idle = false
        var exempt = false
        private val idleFlow = MutableStateFlow(false)
        private val screenOnFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        override fun isDeviceIdleMode(): Boolean = idle

        override fun isIgnoringBatteryOptimizations(): Boolean = exempt

        override val idleChanges: Flow<Boolean> = idleFlow

        override val screenOn: Flow<Unit> = screenOnFlow

        override fun start() = Unit

        override fun stop() = Unit

        fun enterIdle() {
            idle = true
            idleFlow.value = true
        }

        fun exitIdle() {
            idle = false
            idleFlow.value = false
        }

        fun screenOn() {
            screenOnFlow.tryEmit(Unit)
        }
    }

    private class FakeWakelock : SyncWakelock {
        var acquires = 0
        var releases = 0
        var held = false

        override fun acquire() {
            acquires++
            held = true
        }

        override fun release() {
            releases++
            held = false
        }
    }

    private class Harness(
        val engine: ClientSyncEngine,
        val channel: FakeChannel,
        val mirror: FakeMirror,
        val store: FakeStateStore,
        val pauseStore: FakePauseStore,
        val idleMonitor: FakeIdleMonitor,
        val wakelock: FakeWakelock,
    )

    private fun TestScope.harness(linkedHubId: String? = null): Harness {
        val channel = FakeChannel()
        val store = FakeStateStore()
        val mirror = FakeMirror(store)
        val pauseStore = FakePauseStore()
        val idleMonitor = FakeIdleMonitor()
        val wakelock = FakeWakelock()
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val linkStore = mockk<ClientLinkStore>(relaxed = true)
        coEvery { linkStore.snapshot() } returns linkedHubId?.let {
            ClientLink(
                deviceId = "device-1",
                displayName = "Tablet",
                hubId = it,
                hubName = HUB_NAME,
            )
        }
        val wiper = mockk<ClientCacheWiper>(relaxed = true)
        val sendManager = mockk<ClientSendManager>(relaxed = true)
        val actionSender = mockk<ClientActionSender>(relaxed = true)
        val notificationRelay = mockk<ClientNotificationRelay>(relaxed = true)
        val contactSync = mockk<ClientContactSync>(relaxed = true)
        val downloader = mockk<ClientAttachmentDownloader>(relaxed = true)
        val uploader = mockk<ClientAttachmentUploader>(relaxed = true)
        // The engine's collectors never complete, so they run in a
        // separate TestScope on the same scheduler (see
        // ClientSyncEngineTest for why backgroundScope does not
        // work here). The watchdog keeps its real 60 s timeout.
        val engineScope = TestScope(testScheduler)
        coroutineContext[Job]?.invokeOnCompletion { engineScope.cancel() }
        val engine = ClientSyncEngine(
            channel,
            mirror,
            store,
            conversationDao,
            messageDao,
            linkStore,
            wiper,
            engineScope,
            sendManager,
            actionSender,
            notificationRelay,
            contactSync,
            downloader,
            uploader,
            pauseStore,
            idleMonitor,
            wakelock,
        )
        engine.start()
        return Harness(engine, channel, mirror, store, pauseStore, idleMonitor, wakelock)
    }

    /** Connects and chooses full mode (the run is Syncing, no frames yet). */
    private suspend fun TestScope.promptAndChooseFull(h: Harness) {
        runCurrent()
        h.channel.connect()
        runCurrent()
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        runCurrent()
    }

    private suspend fun TestScope.emitSyncStarted(h: Harness, totalMessages: Long = 10L) {
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 2,
                    totalMessages = totalMessages,
                    cursorMessageId = "21",
                    hubIsDefaultSms = true,
                ),
            ),
        )
        runCurrent()
    }

    private suspend fun TestScope.emitSnapshot(h: Harness, vararg ids: Long) {
        h.channel.emit(
            ServerFrame.Conversations(
                SyncProtocol.Conversations(
                    conversations = ids.map { sampleConversation(it) },
                    hasMore = false,
                ),
            ),
        )
        runCurrent()
    }

    private suspend fun TestScope.emitBatch(
        h: Harness,
        conversationId: Long,
        messages: List<SyncedMessage>,
        hasMore: Boolean,
        nextBeforeId: String? = null,
    ) {
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = conversationId.toString(),
                    messages = messages,
                    hasMore = hasMore,
                    nextBeforeId = nextBeforeId,
                ),
            ),
        )
        runCurrent()
    }

    private fun Harness.messagesRequests(): List<JsonObject> =
        channel.sent.filter { it.frameType() == "messages_request" }.map { it.asObject() }

    private fun Harness.syncRequests(): List<JsonObject> =
        channel.sent.filter { it.frameType() == "sync_request" }.map { it.asObject() }

    // -- Pause triggers ---------------------------------------------

    @Test fun `idle + not-exempt pauses a full sync with DEVICE_ASLEEP`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        emitSnapshot(h, 1, 2)
        emitBatch(h, 1, listOf(sampleMessage(13, 1), sampleMessage(12, 1)), hasMore = false)
        assertThat(h.engine.ui.value.imported).isEqualTo(2L)

        h.idleMonitor.enterIdle()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.engine.ui.value.pauseReason).isEqualTo(SyncPauseReason.DEVICE_ASLEEP)
        assertThat(h.engine.ui.value.imported).isEqualTo(2L)
        assertThat(h.engine.ui.value.total).isEqualTo(10L)
        val record = h.pauseStore.snapshot()
        assertThat(record).isNotNull()
        assertThat(record!!.reason).isEqualTo(SyncPauseReason.DEVICE_ASLEEP)
        assertThat(record.imported).isEqualTo(2L)
        assertThat(record.total).isEqualTo(10L)
        assertThat(record.snapshotComplete).isTrue()
        assertThat(record.maxStoredId).isEqualTo(13L)
        assertThat(h.wakelock.held).isFalse()
    }

    @Test fun `idle + exempt does NOT pause`() = runTest {
        val h = harness()
        h.idleMonitor.exempt = true
        promptAndChooseFull(h)
        emitSyncStarted(h)

        h.idleMonitor.enterIdle()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.pauseStore.snapshot()?.reason).isNull()
    }

    @Test fun `screen-on during a full sync does not pause`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)

        // Screen events alone never pause — only idle mode does.
        h.idleMonitor.screenOn()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
    }

    @Test fun `a delta run never pauses for idle`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        runCurrent()
        h.channel.connect()
        runCurrent()
        assertThat(h.engine.ui.value.isDelta).isTrue()

        h.idleMonitor.enterIdle()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
    }

    @Test fun `new_only never pauses for idle`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        runCurrent()
        h.engine.chooseMode(SyncProtocol.SyncRequest.NEW_ONLY)
        runCurrent()
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "new_only",
                    totalConversations = 2,
                    totalMessages = 500,
                    cursorMessageId = "77",
                    hubIsDefaultSms = false,
                ),
            ),
        )
        runCurrent()

        h.idleMonitor.enterIdle()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
    }

    @Test fun `the watchdog pauses after 60s with no batches`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)

        advanceTimeBy(59_999L)
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        advanceTimeBy(1L)
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.engine.ui.value.pauseReason).isEqualTo(SyncPauseReason.NO_RESPONSE)
        assertThat(h.pauseStore.snapshot()?.reason).isEqualTo(SyncPauseReason.NO_RESPONSE)
    }

    @Test fun `a batch resets the watchdog`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        emitBatch(h, 1, listOf(sampleMessage(13, 1)), hasMore = true, nextBeforeId = "13")

        advanceTimeBy(59_000L)
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        // A batch 59 s in restarts the 60 s window: without the
        // reset the watchdog would fire 1 s from now.
        emitBatch(h, 1, listOf(sampleMessage(12, 1)), hasMore = false)
        advanceTimeBy(59_000L)
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.engine.ui.value.pauseReason).isEqualTo(SyncPauseReason.NO_RESPONSE)
    }

    @Test fun `the watchdog does not fire for deltas`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        runCurrent()
        h.channel.connect()
        runCurrent()
        assertThat(h.engine.ui.value.isDelta).isTrue()

        advanceTimeBy(120_000L)
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
    }

    // -- Counter persistence ----------------------------------------

    @Test fun `each batch persists the counter`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        emitSnapshot(h, 1)
        emitBatch(h, 1, listOf(sampleMessage(13, 1), sampleMessage(12, 1)), hasMore = true, nextBeforeId = "12")

        val afterFirst = h.pauseStore.snapshot()
        assertThat(afterFirst).isNotNull()
        assertThat(afterFirst!!.imported).isEqualTo(2L)
        assertThat(afterFirst.total).isEqualTo(10L)
        assertThat(afterFirst.reason).isNull()
        assertThat(afterFirst.snapshotComplete).isTrue()
        assertThat(afterFirst.maxStoredId).isEqualTo(13L)

        emitBatch(h, 1, listOf(sampleMessage(11, 1), sampleMessage(10, 1)), hasMore = false)

        assertThat(h.pauseStore.snapshot()?.imported).isEqualTo(4L)
        assertThat(h.pauseStore.snapshot()?.reason).isNull()
    }

    @Test fun `re-delivered messages do not re-count imported`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        val batch = listOf(sampleMessage(13, 1), sampleMessage(12, 1))
        emitBatch(h, 1, batch, hasMore = false)
        assertThat(h.engine.ui.value.imported).isEqualTo(2L)

        // The hub re-streams the same batch (a restarted run):
        // the mirror already holds both rows, so neither counts.
        emitBatch(h, 1, batch, hasMore = false)

        assertThat(h.engine.ui.value.imported).isEqualTo(2L)
    }

    // -- Resume -----------------------------------------------------

    @Test fun `resume continues the counter and does not re-request completed conversations`() =
        runTest {
            val h = harness()
            promptAndChooseFull(h)
            emitSyncStarted(h)
            emitSnapshot(h, 1, 2)
            emitBatch(h, 1, listOf(sampleMessage(13, 1), sampleMessage(12, 1)), hasMore = false)
            emitBatch(h, 2, listOf(sampleMessage(21, 2)), hasMore = true, nextBeforeId = "21")
            assertThat(h.engine.ui.value.imported).isEqualTo(3L)
            h.idleMonitor.enterIdle()
            runCurrent()
            assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)

            h.engine.resume()
            runCurrent()

            // The counter continues (3), never restarts at 0.
            assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
            assertThat(h.engine.ui.value.imported).isEqualTo(3L)
            assertThat(h.engine.ui.value.total).isEqualTo(10L)
            // Conversation 1 finished its walk — only conversation
            // 2 is gap-filled, from its stored frontier.
            val requests = h.messagesRequests()
            assertThat(requests).hasSize(1)
            assertThat(requests.single()["conversation_id"]?.jsonPrimitive?.content).isEqualTo("2")
            assertThat(requests.single()["before_id"]?.jsonPrimitive?.content).isEqualTo("21")
            emitBatch(h, 2, listOf(sampleMessage(20, 2), sampleMessage(19, 2)), hasMore = false)
            assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
            assertThat(h.pauseStore.snapshot()).isNull()
            assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("21")
            assertThat(h.store.syncStates[HUB_ID]?.initialSyncDone).isTrue()
            assertThat(h.syncRequests()).hasSize(1)
        }

    @Test fun `resume gap-fills conversations the stream never reached`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        emitSnapshot(h, 1, 2, 3)
        emitBatch(h, 1, listOf(sampleMessage(13, 1)), hasMore = false)
        h.idleMonitor.enterIdle()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)

        h.engine.resume()
        runCurrent()

        // Conversations 2 and 3 were snapshotted but never
        // streamed: their frontier rows (ensured at snapshot
        // time) make gap-fill find them, from "0".
        val first = h.messagesRequests()
        assertThat(first).hasSize(1)
        assertThat(first.single()["conversation_id"]?.jsonPrimitive?.content).isEqualTo("2")
        assertThat(first.single()["before_id"]?.jsonPrimitive?.content).isEqualTo("0")
        emitBatch(h, 2, listOf(sampleMessage(9, 2)), hasMore = false)
        val second = h.messagesRequests()
        assertThat(second).hasSize(2)
        assertThat(second[1]["conversation_id"]?.jsonPrimitive?.content).isEqualTo("3")
        assertThat(second[1]["before_id"]?.jsonPrimitive?.content).isEqualTo("0")
        emitBatch(h, 3, listOf(sampleMessage(7, 3)), hasMore = false)
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        assertThat(h.messagesRequests().map { it["conversation_id"]?.jsonPrimitive?.content })
            .containsExactly("2", "3").inOrder()
    }

    @Test fun `resume re-requests when the snapshot never finished`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        // A batch arrived, but no `conversations` frame: the
        // snapshot never finished, so gap-fill cannot know the
        // conversations it never named.
        val batch = listOf(sampleMessage(13, 1), sampleMessage(12, 1))
        emitBatch(h, 1, batch, hasMore = false)
        assertThat(h.engine.ui.value.imported).isEqualTo(2L)
        h.idleMonitor.enterIdle()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)

        h.engine.resume()
        runCurrent()

        // The run is re-requested (a second sync_request); the
        // counter is restored from the pause record.
        assertThat(h.syncRequests()).hasSize(2)
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        emitSyncStarted(h)
        assertThat(h.engine.ui.value.imported).isEqualTo(2L)
        // The hub re-streams the same batch: re-delivered rows do
        // not re-count.
        emitBatch(h, 1, batch, hasMore = false)
        assertThat(h.engine.ui.value.imported).isEqualTo(2L)
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("13")))
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        assertThat(h.pauseStore.snapshot()).isNull()
    }

    @Test fun `resume is a no-op when not paused`() = runTest {
        val h = harness()
        runCurrent()

        h.engine.resume()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Idle)
        assertThat(h.channel.sent).isEmpty()
        promptAndChooseFull(h)
        h.engine.resume()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.syncRequests()).hasSize(1)
    }

    @Test fun `a reconnect while paused does not restart the sync`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        h.idleMonitor.enterIdle()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)

        h.channel.disconnect()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        h.channel.connect()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.engine.ui.value.pauseReason).isEqualTo(SyncPauseReason.DEVICE_ASLEEP)
        assertThat(h.syncRequests()).hasSize(1)
    }

    @Test fun `screen-on auto-resumes a paused run`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        emitSnapshot(h, 1)
        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.engine.ui.value.pauseReason).isEqualTo(SyncPauseReason.NO_RESPONSE)

        h.idleMonitor.screenOn()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.engine.ui.value.pauseReason).isNull()
        val requests = h.messagesRequests()
        assertThat(requests).hasSize(1)
        assertThat(requests.single()["conversation_id"]?.jsonPrimitive?.content).isEqualTo("1")
    }

    @Test fun `leaving idle auto-resumes a DEVICE_ASLEEP pause`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        emitSnapshot(h, 1)
        h.idleMonitor.enterIdle()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)

        h.idleMonitor.exitIdle()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        val requests = h.messagesRequests()
        assertThat(requests).hasSize(1)
        assertThat(requests.single()["conversation_id"]?.jsonPrimitive?.content).isEqualTo("1")
    }

    // -- Restart after process death --------------------------------

    @Test fun `restart while asleep restores Paused from the record`() = runTest {
        val h = harness(linkedHubId = HUB_ID)
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "0",
            initialSyncDone = false,
            lastSyncAt = null,
        )
        h.pauseStore.save(
            PausedRun(
                hubId = HUB_ID,
                mode = "full",
                imported = 500L,
                total = 21_000L,
                reason = SyncPauseReason.DEVICE_ASLEEP,
                pausedAt = 1_789_000_000_000L,
                snapshotComplete = true,
                maxStoredId = 5_000L,
            ),
        )
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "100",
            historyComplete = false,
        )
        h.idleMonitor.enterIdle()
        runCurrent()
        h.channel.connect()
        runCurrent()

        // Still asleep and unexempted: the Paused UI comes back,
        // and no sync is (re)started.
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.engine.ui.value.pauseReason).isEqualTo(SyncPauseReason.DEVICE_ASLEEP)
        assertThat(h.engine.ui.value.imported).isEqualTo(500L)
        assertThat(h.engine.ui.value.total).isEqualTo(21_000L)
        assertThat(h.channel.sent).isEmpty()

        // The wake auto-resumes: the counter continues, and the
        // incomplete conversation is gap-filled from its frontier.
        h.idleMonitor.exitIdle()
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.engine.ui.value.imported).isEqualTo(500L)
        val requests = h.messagesRequests()
        assertThat(requests).hasSize(1)
        assertThat(requests.single()["conversation_id"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(requests.single()["before_id"]?.jsonPrimitive?.content).isEqualTo("100")
        emitBatch(h, 5, listOf(sampleMessage(99, 5), sampleMessage(98, 5)), hasMore = false)
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        assertThat(h.pauseStore.snapshot()).isNull()
        assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("5000")
        assertThat(h.store.syncStates[HUB_ID]?.initialSyncDone).isTrue()
    }

    @Test fun `restart while awake resumes via gap-fill`() = runTest {
        val h = harness(linkedHubId = HUB_ID)
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "0",
            initialSyncDone = false,
            lastSyncAt = null,
        )
        h.pauseStore.save(
            PausedRun(
                hubId = HUB_ID,
                mode = "full",
                imported = 500L,
                total = 21_000L,
                reason = SyncPauseReason.NO_RESPONSE,
                pausedAt = 1_789_000_000_000L,
                snapshotComplete = true,
                maxStoredId = 5_000L,
            ),
        )
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "100",
            historyComplete = false,
        )
        runCurrent()
        h.channel.connect()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.engine.ui.value.imported).isEqualTo(500L)
        assertThat(h.engine.ui.value.total).isEqualTo(21_000L)
        val requests = h.messagesRequests()
        assertThat(requests).hasSize(1)
        assertThat(requests.single()["conversation_id"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(h.syncRequests()).isEmpty()
    }

    // -- Wakelock -----------------------------------------------------

    @Test fun `the wakelock is acquired on run start and released on complete`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)

        assertThat(h.wakelock.acquires).isEqualTo(1)
        assertThat(h.wakelock.held).isTrue()
        emitSnapshot(h, 1)
        emitBatch(h, 1, listOf(sampleMessage(13, 1)), hasMore = false)
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("13")))
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        assertThat(h.wakelock.releases).isEqualTo(1)
        assertThat(h.wakelock.held).isFalse()
    }

    @Test fun `the wakelock is released on pause`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        assertThat(h.wakelock.acquires).isEqualTo(1)

        h.idleMonitor.enterIdle()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Paused)
        assertThat(h.wakelock.releases).isEqualTo(1)
        assertThat(h.wakelock.held).isFalse()
    }

    @Test fun `the wakelock is released on error`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        assertThat(h.wakelock.acquires).isEqualTo(1)

        h.channel.emit(
            ServerFrame.SyncError(SyncProtocol.SyncError("internal", "boom")),
        )
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Error)
        assertThat(h.wakelock.releases).isEqualTo(1)
        assertThat(h.wakelock.held).isFalse()
    }

    // -- Resync --------------------------------------------------------

    @Test fun `resync clears the pause record`() = runTest {
        val h = harness()
        promptAndChooseFull(h)
        emitSyncStarted(h)
        h.idleMonitor.enterIdle()
        runCurrent()
        assertThat(h.pauseStore.snapshot()).isNotNull()

        h.engine.resync()
        runCurrent()

        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        assertThat(h.pauseStore.snapshot()).isNull()
    }

    private companion object {
        const val HUB_ID = "hub-1"
        const val HUB_NAME = "Phone"
    }
}

private fun sampleConversation(
    id: Long,
    title: String = "Alice",
) = SyncedConversation(
    id = id.toString(),
    threadId = null,
    addresses = listOf("+33612345678"),
    displayName = title,
    customName = null,
    title = title,
    lastMessageAt = 1700000000000,
    lastMessagePreview = "hi",
    unread = 0,
    pinned = false,
    archived = false,
    muted = false,
    blocked = false,
    messageCount = 3,
    pinnedMessageId = null,
    pinnedMessageAt = null,
)

private fun sampleMessage(
    id: Long,
    conversationId: Long,
    body: String = "hello",
) = SyncedMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    address = "+33612345678",
    body = body,
    type = "sms",
    direction = "incoming",
    date = 1700000000000,
    dateSent = null,
    read = true,
    starred = false,
    status = "received",
    errorCode = null,
    replyToMessageId = null,
    reaction = null,
    attachments = emptyList(),
    clientMessageId = null,
)

private fun String.frameType(): String? =
    Json.parseToJsonElement(this).jsonObject["type"]?.jsonPrimitive?.content

private fun String.asObject(): JsonObject = Json.parseToJsonElement(this).jsonObject
