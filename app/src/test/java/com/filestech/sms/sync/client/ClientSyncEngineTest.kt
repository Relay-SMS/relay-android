package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity
import com.filestech.sms.data.local.db.entity.ClientSyncStateEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.repository.FichiersDePiecesJointes
import com.filestech.sms.data.repository.SyncedConversationResult
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncWakelock
import com.filestech.sms.sync.SyncedContact
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import com.google.common.truth.Truth.assertThat
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Client mode C2 — the sync engine, driven without a socket: a fake
 * channel holds the connection state + frame stream and records
 * every outbound request, a fake mirror records every upsert, and a
 * fake state store holds the per-hub rows and frontiers. The DAOs
 * are relaxed MockK mocks (the engine only reads one conversation
 * row and one count, and pins through the DAO); the wiper is REAL,
 * wired to the fake store, so Re-sync really clears state.
 *
 * The cursor rule under test throughout (the desktop's, §7 of the
 * design): **the cursor is the max hub message id DURABLY STORED —
 * never the server's `sync_complete` cursor** — persisted at
 * `sync_complete` and after each live `message_new`, never per
 * batch.
 */
class ClientSyncEngineTest {

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

        fun remove() {
            (state as MutableStateFlow).value = ClientConnectionState.Removed
        }

        suspend fun emit(frame: ServerFrame) {
            (frames as MutableSharedFlow).emit(frame)
        }
    }

    private class FakeMirror : ClientMirror {
        val conversations = mutableListOf<SyncedConversation>()
        val messages = mutableListOf<SyncedMessage>()
        val updates = mutableListOf<SyncProtocol.MessageUpdated>()
        val deleted = mutableListOf<Long>()
        var prunedWith: Set<Long>? = null
        var pruneResult = 0
        val resolvedReplies = mutableListOf<Pair<Long, Long>>()
        var nextLocalId = 1L
        var conversationResult: (SyncedConversation) -> SyncedConversationResult =
            { SyncedConversationResult(nextLocalId++, created = true) }
        var messageResult: (SyncedMessage) -> SyncedMessageResult =
            { SyncedMessageResult(nextLocalId++, inserted = true) }

        override suspend fun upsertSyncedConversation(
            wire: SyncedConversation,
        ): SyncedConversationResult? {
            conversations += wire
            return conversationResult(wire)
        }

        override suspend fun upsertSyncedMessage(wire: SyncedMessage): SyncedMessageResult? {
            messages += wire
            return messageResult(wire)
        }

        override suspend fun applyMessageUpdated(update: SyncProtocol.MessageUpdated) {
            updates += update
        }

        override suspend fun resolveReplyTarget(localMessageId: Long, localTargetId: Long) {
            resolvedReplies += localMessageId to localTargetId
        }

        override suspend fun deleteSyncedConversation(hubConversationId: Long) {
            deleted += hubConversationId
        }

        override suspend fun pruneSyncedConversations(keepHubIds: Set<Long>): Int {
            prunedWith = keepHubIds
            return pruneResult
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

    private class Harness(
        val engine: ClientSyncEngine,
        val channel: FakeChannel,
        val mirror: FakeMirror,
        val store: FakeStateStore,
        val conversationDao: ConversationDao,
        val messageDao: MessageDao,
        // VPN dial (D7) — the link store is a relaxed mock: the wire
        // freshness tests verify the engine records the tunnel
        // address into it.
        val linkStore: ClientLinkStore,
        // Client mode C3 — the send/action managers are relaxed mocks:
        // the routing tests verify the engine calls them.
        val sendManager: ClientSendManager,
        val actionSender: ClientActionSender,
        // Client mode C4 — same for the notification relay + contact sync.
        val notificationRelay: ClientNotificationRelay,
        val contactSync: ClientContactSync,
        // Client mode C5 — same for the attachment downloader + uploader.
        val downloader: ClientAttachmentDownloader,
        val uploader: ClientAttachmentUploader,
    )

    private fun TestScope.harness(linkedHubId: String? = null): Harness {
        val channel = FakeChannel()
        val mirror = FakeMirror()
        val store = FakeStateStore()
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val linkStore = mockk<ClientLinkStore>(relaxed = true)
        // No link by default: a relaxed mock would return a mock
        // link with a blank hub id, and the engine would read it
        // as a hub change. A linked hub id seeds the engine's
        // hubId at start (the restart path): the first connect
        // then skips the hub-change branch, like production.
        coEvery { linkStore.snapshot() } returns linkedHubId?.let {
            ClientLink(
                deviceId = "device-1",
                displayName = "Tablet",
                hubId = it,
                hubName = HUB_NAME,
            )
        }
        val pinStore = mockk<TofuPinStore>(relaxed = true)
        val contactSync = mockk<ClientContactSync>(relaxed = true)
        val downloader = mockk<ClientAttachmentDownloader>(relaxed = true)
        val uploader = mockk<ClientAttachmentUploader>(relaxed = true)
        // The wiper is real (mock DAOs + the fake store): Re-sync
        // really clears the state the engine reads back.
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val fichiers = mockk<FichiersDePiecesJointes>(relaxed = true)
        val wiper = ClientCacheWiper(
            linkStore,
            pinStore,
            conversationDao,
            messageDao,
            store,
            contactSync,
            attachmentDao,
            fichiers,
            downloader,
        )
        // The engine's collectors never complete (they follow the
        // connection state + frame stream for the app's lifetime),
        // so they run in a separate TestScope on the same
        // scheduler: advanceUntilIdle still drives them, but
        // runTest does not wait for them at teardown (no
        // UncompletedCoroutinesError). NOT backgroundScope: in this
        // coroutines-test version its tasks are not advanced by
        // advanceUntilIdle (verified by repro — the collector never
        // saw emissions past the first). The scope is cancelled
        // when the test's Job completes.
        val engineScope = TestScope(testScheduler)
        coroutineContext[Job]?.invokeOnCompletion { engineScope.cancel() }
        val sendManager = mockk<ClientSendManager>(relaxed = true)
        val actionSender = mockk<ClientActionSender>(relaxed = true)
        val notificationRelay = mockk<ClientNotificationRelay>(relaxed = true)
        // Pause/resume — relaxed mocks: the pause store reads null
        // (no interrupted run), the idle monitor reports awake with
        // silent flows, the wakelock is a no-op. The stall watchdog
        // is disabled here: `advanceUntilIdle` fast-forwards
        // virtual time past 60 s, which would pause every full run
        // under test. The pause tests (ClientSyncEnginePauseTest)
        // keep the real timeout and drive it with `advanceTimeBy`.
        val pauseStore = mockk<SyncPauseStore>(relaxed = true)
        val idleMonitor = mockk<DeviceIdleMonitor>(relaxed = true)
        val wakelock = mockk<SyncWakelock>(relaxed = true)
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
        engine.stallWatchdogTimeoutMs = 0L
        engine.start()
        return Harness(
            engine,
            channel,
            mirror,
            store,
            conversationDao,
            messageDao,
            linkStore,
            sendManager,
            actionSender,
            notificationRelay,
            contactSync,
            downloader,
            uploader,
        )
    }

    // -- Prompt + mode choice ---------------------------------------

    @Test fun `first connect with no stored mode prompts instead of syncing`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `choosing full mode stores the choice and requests a full sync`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        advanceUntilIdle()
        val stored = h.store.syncStates[HUB_ID]
        assertThat(stored).isNotNull()
        assertThat(stored!!.mode).isEqualTo("full")
        assertThat(stored.globalLastMessageId).isEqualTo("0")
        assertThat(stored.initialSyncDone).isFalse()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.engine.ui.value.isDelta).isFalse()
        assertThat(h.channel.sent).hasSize(1)
        val request = h.channel.sent.single().asObject()
        assertThat(request.frameType()).isEqualTo("sync_request")
        assertThat(request["mode"]?.jsonPrimitive?.content).isEqualTo("full")
        assertThat(request.containsKey("since_message_id")).isFalse()
    }

    @Test fun `choosing a mode outside prompting is ignored`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        advanceUntilIdle()
        h.engine.chooseMode(SyncProtocol.SyncRequest.NEW_ONLY)
        advanceUntilIdle()
        assertThat(h.store.syncStates[HUB_ID]?.mode).isEqualTo("full")
        assertThat(h.channel.sent).hasSize(1)
    }

    @Test fun `a different hub wipes the old cache and prompts again`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        runCurrent()
        h.channel.connect(HUB_ID)
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        h.channel.disconnect()
        advanceUntilIdle()
        h.channel.connect("hub-2", "Other phone")
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        assertThat(h.engine.ui.value.hubId).isEqualTo("hub-2")
        // The client holds one hub's cache at a time (§5.2): the old
        // hub's state is gone with its messages.
        assertThat(h.store.syncStates).isEmpty()
        h.engine.chooseMode(SyncProtocol.SyncRequest.NEW_ONLY)
        advanceUntilIdle()
        assertThat(h.store.syncStates[HUB_ID]).isNull()
        assertThat(h.store.syncStates["hub-2"]?.mode).isEqualTo("new_only")
    }

    @Test fun `a restart with the same hub resumes the delta instead of prompting`() = runTest {
        val h = harness(linkedHubId = HUB_ID)
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        runCurrent()
        h.channel.connect(HUB_ID)
        advanceUntilIdle()
        // The hub-change branch never ran (hubId was seeded from
        // the link), but the stored state still loaded — the delta
        // resumes from the cursor, nothing wiped.
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.engine.ui.value.isDelta).isTrue()
        assertThat(h.store.syncStates[HUB_ID]).isNotNull()
        assertThat(h.channel.sent).hasSize(1)
        val request = h.channel.sent.single().asObject()
        assertThat(request["since_message_id"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    @Test fun `a relinked hub wipes the old hub's cache after a restart`() = runTest {
        val h = harness(linkedHubId = "hub-2")
        // The previous hub's state survives the restart (revocation
        // never wiped it); the link record already says the new hub.
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        runCurrent()
        h.channel.connect("hub-2", "Other phone")
        advanceUntilIdle()
        // The branch never ran (hubId was seeded from the link), so
        // only the stored-state check can see the hub changed.
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        assertThat(h.store.syncStates).isEmpty()
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `a same-hub reconnect does not wipe the cache`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        runCurrent()
        h.channel.connect(HUB_ID)
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        h.channel.disconnect()
        advanceUntilIdle()
        h.channel.connect(HUB_ID)
        advanceUntilIdle()
        assertThat(h.store.syncStates[HUB_ID]).isNotNull()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
    }

    // -- Full sync + the cursor rule --------------------------------

    @Test fun `full sync stores the stream then goes live`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 2,
                    totalMessages = 3,
                    cursorMessageId = "21",
                    hubIsDefaultSms = true,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.engine.ui.value.total).isEqualTo(3L)
        assertThat(h.engine.ui.value.hubIsDefaultSms).isTrue()
        h.channel.emit(
            ServerFrame.Conversations(
                SyncProtocol.Conversations(
                    conversations = listOf(sampleConversation(1), sampleConversation(2)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.mirror.conversations.map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(h.mirror.prunedWith).isEqualTo(setOf(1L, 2L))
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "1",
                    messages = listOf(sampleMessage(13, 1), sampleMessage(12, 1)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "2",
                    messages = listOf(sampleMessage(21, 2)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.engine.ui.value.imported).isEqualTo(3L)
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("21")))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        assertThat(h.engine.ui.value.imported).isEqualTo(0L)
        assertThat(h.engine.ui.value.total).isNull()
        assertThat(h.mirror.messages.map { it.id }).containsExactly("13", "12", "21").inOrder()
        assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("21")
        assertThat(h.store.syncStates[HUB_ID]?.initialSyncDone).isTrue()
        assertThat(h.store.syncStates[HUB_ID]?.lastSyncAt).isNotNull()
        // A fresh full sync walks the frontiers to the oldest stored
        // id and marks the history complete.
        assertThat(h.store.convStates[1L]?.minSyncedId).isEqualTo("12")
        assertThat(h.store.convStates[1L]?.historyComplete).isTrue()
        assertThat(h.store.convStates[2L]?.minSyncedId).isEqualTo("21")
        assertThat(h.store.convStates[2L]?.historyComplete).isTrue()
    }

    @Test fun `the cursor is the max id durably stored, never the server cursor`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 1,
                    totalMessages = 3,
                    cursorMessageId = "9999",
                    hubIsDefaultSms = true,
                ),
            ),
        )
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "1",
                    messages = listOf(sampleMessage(13, 1), sampleMessage(12, 1), sampleMessage(11, 1)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        // The server claims 9999 (a live message in flight past the
        // stream); only 13 was durably stored. Persisting 9999
        // would skip that live message forever.
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("9999")))
        advanceUntilIdle()
        assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("13")
    }

    @Test fun `a delta sync carries the stored cursor`() = runTest {
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
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.engine.ui.value.isDelta).isTrue()
        assertThat(h.channel.sent).hasSize(1)
        val request = h.channel.sent.single().asObject()
        assertThat(request.frameType()).isEqualTo("sync_request")
        assertThat(request["mode"]?.jsonPrimitive?.content).isEqualTo("full")
        assertThat(request["since_message_id"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    @Test fun `a delta batch never marks history complete`() = runTest {
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
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 1,
                    totalMessages = 2,
                    cursorMessageId = "44",
                    hubIsDefaultSms = true,
                ),
            ),
        )
        advanceUntilIdle()
        // A delta batch with `has_more: false` only says "no more
        // NEW messages" — the unfinished walk below the frontier
        // is untouched, or an incomplete history would read complete.
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "5",
                    messages = listOf(sampleMessage(44, 5), sampleMessage(43, 5)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.store.convStates[5L]).isNull()
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("44")))
        advanceUntilIdle()
        assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("44")
        assertThat(h.store.convStates[5L]).isNull()
    }

    // -- new_only ----------------------------------------------------

    @Test fun `new_only saves the now baseline at sync_started, counts conversations, and runs no gap-fill`() =
        runTest {
            val h = harness()
            runCurrent()
            h.channel.connect()
            advanceUntilIdle()
            h.engine.chooseMode(SyncProtocol.SyncRequest.NEW_ONLY)
            advanceUntilIdle()
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
            advanceUntilIdle()
            // The baseline is saved immediately — there are no
            // batches to advance it, and everything before "now" is
            // history we chose not to pull.
            assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("77")
            // `new_only` streams no messages, so the progress counts
            // conversations, not the 500 messages.
            assertThat(h.engine.ui.value.total).isEqualTo(2L)
            assertThat(h.engine.ui.value.hubIsDefaultSms).isFalse()
            h.channel.emit(
                ServerFrame.Conversations(
                    SyncProtocol.Conversations(
                        conversations = listOf(sampleConversation(1), sampleConversation(2)),
                        hasMore = false,
                    ),
                ),
            )
            advanceUntilIdle()
            assertThat(h.engine.ui.value.imported).isEqualTo(2L)
            h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("77")))
            advanceUntilIdle()
            assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
            assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("77")
            assertThat(h.channel.sent.map { it.frameType() }).containsExactly("sync_request").inOrder()
        }

    // -- Live frames -------------------------------------------------

    @Test fun `a live message lands in the cache and advances the cursor at once`() = runTest {
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
        advanceUntilIdle()
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("42")))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        h.channel.emit(ServerFrame.MessageNew(SyncProtocol.MessageNew(sampleMessage(50, 1))))
        advanceUntilIdle()
        assertThat(h.mirror.messages.map { it.id }).containsExactly("50")
        assertThat(h.store.syncStates[HUB_ID]?.globalLastMessageId).isEqualTo("50")
    }

    @Test fun `message_updated reaches the mirror`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        val update = SyncProtocol.MessageUpdated(
            messageId = "50",
            conversationId = "1",
            status = "delivered",
            read = true,
        )
        h.channel.emit(ServerFrame.MessageUpdated(update))
        advanceUntilIdle()
        assertThat(h.mirror.updates).containsExactly(update).inOrder()
    }

    @Test fun `conversation_deleted removes the conversation from the mirror`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.ConversationDeleted(SyncProtocol.ConversationDeleted("7")),
        )
        advanceUntilIdle()
        assertThat(h.mirror.deleted).containsExactly(7L).inOrder()
    }

    @Test fun `hub_status flips the default-sms flag both ways`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.channel.emit(ServerFrame.HubStatus(SyncProtocol.HubStatus(isDefaultSms = false)))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.hubIsDefaultSms).isFalse()
        h.channel.emit(ServerFrame.HubStatus(SyncProtocol.HubStatus(isDefaultSms = true)))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.hubIsDefaultSms).isTrue()
    }

    // -- VPN dial: wire freshness (D7) ------------------------------

    @Test fun `sync_started records the hub tunnel address`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 0,
                    totalMessages = 0,
                    cursorMessageId = "0",
                    hubIsDefaultSms = true,
                    hubTunnelIp = "10.6.0.2",
                ),
            ),
        )
        advanceUntilIdle()
        coVerify { h.linkStore.noteTunnelIp("10.6.0.2") }
    }

    @Test fun `hub_status records the tunnel address`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.HubStatus(
                SyncProtocol.HubStatus(isDefaultSms = true, tunnelIp = "10.6.0.2"),
            ),
        )
        advanceUntilIdle()
        coVerify { h.linkStore.noteTunnelIp("10.6.0.2") }
    }

    @Test fun `a pending pin lands when its message arrives`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findByHubConversationId(7L) } returns ConversationEntity(
            id = 11,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
            hubConversationId = 7,
        )
        h.mirror.conversationResult = {
            SyncedConversationResult(
                localId = 11,
                created = true,
                unresolvedPinnedHubMessageId = 50,
                pinnedMessageAt = 12345L,
            )
        }
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        // A run must be active: `conversations` frames outside a
        // run are dropped (no snapshot to prune against).
        h.engine.chooseMode(SyncProtocol.SyncRequest.FULL)
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.Conversations(
                SyncProtocol.Conversations(
                    conversations = listOf(
                        sampleConversation(7, pinnedMessageId = "50", pinnedMessageAt = 12345L),
                    ),
                    hasMore = true,
                ),
            ),
        )
        advanceUntilIdle()
        h.channel.emit(ServerFrame.MessageNew(SyncProtocol.MessageNew(sampleMessage(50, 7))))
        advanceUntilIdle()
        // The message landed with local id 1 (the fake's first id);
        // the pin is applied to conversation 11 with the pin time.
        coVerify { h.conversationDao.setPinnedMessage(11, 1, 12345L) }
    }

    @Test fun `a pending quote resolves when its target arrives`() = runTest {
        val h = harness()
        h.mirror.messageResult = { wire ->
            SyncedMessageResult(
                localId = h.mirror.nextLocalId++,
                inserted = true,
                unresolvedReplyToHubMessageId = wire.replyToMessageId?.toLongOrNull(),
            )
        }
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        // The reply arrives before the message it quotes (the
        // stream is newest-first): local id 1, target 60 unseen.
        h.channel.emit(
            ServerFrame.MessageNew(
                SyncProtocol.MessageNew(sampleMessage(70, 1, replyToMessageId = "60")),
            ),
        )
        advanceUntilIdle()
        assertThat(h.mirror.resolvedReplies).isEmpty()
        h.channel.emit(ServerFrame.MessageNew(SyncProtocol.MessageNew(sampleMessage(60, 1))))
        advanceUntilIdle()
        assertThat(h.mirror.resolvedReplies).containsExactly(1L to 2L).inOrder()
    }

    // -- Errors + retry ----------------------------------------------

    @Test fun `sync_error surfaces its code and message, and retry re-requests`() = runTest {
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
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.SyncError(
                SyncProtocol.SyncError(
                    code = SyncProtocol.SyncError.HUB_NO_SMS_ACCESS,
                    message = "Relay can't read messages",
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Error)
        assertThat(h.engine.ui.value.errorCode).isEqualTo("hub_no_sms_access")
        assertThat(h.engine.ui.value.errorMessage).isEqualTo("Relay can't read messages")
        h.engine.retry()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.channel.sent).hasSize(2)
        val retry = h.channel.sent[1].asObject()
        assertThat(retry["since_message_id"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    @Test fun `a send failure surfaces not_connected`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        h.channel.sendResult = false
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Error)
        assertThat(h.engine.ui.value.errorCode).isEqualTo(ClientSyncEngine.ERROR_NOT_CONNECTED)
        h.channel.sendResult = true
        h.engine.retry()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
    }

    // -- Gap-fill + fetch-on-open ------------------------------------

    @Test fun `gap-fill walks incomplete conversations from their frontier`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "10",
            historyComplete = false,
        )
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 0,
                    totalMessages = 0,
                    cursorMessageId = "42",
                    hubIsDefaultSms = true,
                ),
            ),
        )
        advanceUntilIdle()
        h.channel.emit(ServerFrame.SyncComplete(SyncProtocol.SyncComplete("42")))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Live)
        val requests = h.channel.sent.filter { it.frameType() == "messages_request" }
        assertThat(requests).hasSize(1)
        val first = requests[0].asObject()
        assertThat(first["conversation_id"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(first["before_id"]?.jsonPrimitive?.content).isEqualTo("10")
        assertThat(first["limit"]?.jsonPrimitive?.content).isEqualTo("200")
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "5",
                    messages = listOf(sampleMessage(9, 5), sampleMessage(8, 5)),
                    hasMore = true,
                    nextBeforeId = "8",
                ),
            ),
        )
        advanceUntilIdle()
        val second = h.channel.sent.filter { it.frameType() == "messages_request" }
        assertThat(second).hasSize(2)
        assertThat(second[1].asObject()["before_id"]?.jsonPrimitive?.content).isEqualTo("8")
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "5",
                    messages = listOf(sampleMessage(7, 5), sampleMessage(6, 5)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.channel.sent.filter { it.frameType() == "messages_request" }).hasSize(2)
        assertThat(h.store.convStates[5L]?.minSyncedId).isEqualTo("6")
        assertThat(h.store.convStates[5L]?.historyComplete).isTrue()
    }

    @Test fun `fetch-on-open requests the thread from its frontier`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(11L) } returns ConversationEntity(
            id = 11,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
            hubConversationId = 5,
        )
        coEvery { h.messageDao.countForConversation(11L) } returns 0
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "20",
            historyComplete = false,
        )
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.fetchThread(11)
        // A second fetch while the first page is in flight is a no-op.
        h.engine.fetchThread(11)
        val requests = h.channel.sent.filter { it.frameType() == "messages_request" }
        assertThat(requests).hasSize(1)
        val first = requests[0].asObject()
        assertThat(first["conversation_id"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(first["before_id"]?.jsonPrimitive?.content).isEqualTo("20")
        assertThat(first["limit"]?.jsonPrimitive?.content).isEqualTo("200")
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "5",
                    messages = listOf(sampleMessage(19, 5), sampleMessage(18, 5)),
                    hasMore = true,
                    nextBeforeId = "18",
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.channel.sent.filter { it.frameType() == "messages_request" }).hasSize(2)
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "5",
                    messages = listOf(sampleMessage(17, 5)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(h.store.convStates[5L]?.minSyncedId).isEqualTo("17")
        assertThat(h.store.convStates[5L]?.historyComplete).isTrue()
        // Fetched this session: a later open does not re-request.
        h.engine.fetchThread(11)
        assertThat(h.channel.sent.filter { it.frameType() == "messages_request" }).hasSize(2)
    }

    @Test fun `fetch-on-open skips a complete thread with cached messages`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(11L) } returns ConversationEntity(
            id = 11,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
            hubConversationId = 5,
        )
        coEvery { h.messageDao.countForConversation(11L) } returns 3
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "17",
            historyComplete = true,
        )
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.fetchThread(11)
        assertThat(h.channel.sent.filter { it.frameType() == "messages_request" }).isEmpty()
    }

    @Test fun `fetch-on-open fetches a complete-but-empty thread once per session`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(11L) } returns ConversationEntity(
            id = 11,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
            hubConversationId = 5,
        )
        coEvery { h.messageDao.countForConversation(11L) } returns 0
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "17",
            historyComplete = true,
        )
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.engine.fetchThread(11)
        assertThat(h.channel.sent.filter { it.frameType() == "messages_request" }).hasSize(1)
        // The hub had nothing before the frontier: an empty page is
        // the end of the walk, and the thread stays fetched.
        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "5",
                    messages = emptyList(),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()
        h.engine.fetchThread(11)
        assertThat(h.channel.sent.filter { it.frameType() == "messages_request" }).hasSize(1)
    }

    // -- Connection drops + resync ------------------------------------

    @Test fun `a drop mid-sync goes idle and the reconnect resumes the delta`() = runTest {
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
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        h.channel.disconnect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Idle)
        h.channel.connect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        assertThat(h.channel.sent).hasSize(2)
        val resume = h.channel.sent[1].asObject()
        assertThat(resume["since_message_id"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    @Test fun `removal resets the engine to idle`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        h.channel.remove()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Idle)
        assertThat(h.engine.ui.value.hubId).isNull()
    }

    @Test fun `resync wipes the cache and re-prompts`() = runTest {
        val h = harness()
        h.store.syncStates[HUB_ID] = ClientSyncStateEntity(
            hubId = HUB_ID,
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        h.store.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "10",
            historyComplete = false,
        )
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        h.engine.resync()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        assertThat(h.store.syncStates).isEmpty()
        assertThat(h.store.convStates).isEmpty()
        coVerify { h.conversationDao.deleteSynced() }
        coVerify { h.messageDao.deleteSynced() }
        // The next connect prompts again — the mode is forgotten too.
        h.channel.disconnect()
        advanceUntilIdle()
        h.channel.connect()
        advanceUntilIdle()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Prompting)
        assertThat(h.channel.sent.filter { it.frameType() == "sync_request" }).hasSize(1)
    }

    // -- The delta progress rule (pure) -------------------------------

    @Test fun `first-sync progress shows at once`() {
        val ui = ClientSyncUiState(
            phase = ClientSyncPhase.Syncing,
            total = 5,
            isDelta = false,
            deltaElapsed = false,
        )
        assertThat(shouldShowSyncProgress(ui)).isTrue()
    }

    @Test fun `an empty delta stays hidden until the delay`() {
        val ui = ClientSyncUiState(
            phase = ClientSyncPhase.Syncing,
            total = 0,
            isDelta = true,
            deltaElapsed = false,
        )
        assertThat(shouldShowSyncProgress(ui)).isFalse()
    }

    @Test fun `a delta shows once the delay elapses`() {
        val ui = ClientSyncUiState(
            phase = ClientSyncPhase.Syncing,
            total = 0,
            isDelta = true,
            deltaElapsed = true,
        )
        assertThat(shouldShowSyncProgress(ui)).isTrue()
    }

    @Test fun `a delta with announced work shows at once`() {
        val ui = ClientSyncUiState(
            phase = ClientSyncPhase.Syncing,
            total = 3,
            isDelta = true,
            deltaElapsed = false,
        )
        assertThat(shouldShowSyncProgress(ui)).isTrue()
    }

    @Test fun `progress never shows outside syncing or before sync_started`() {
        assertThat(
            shouldShowSyncProgress(
                ClientSyncUiState(phase = ClientSyncPhase.Live, total = 5, isDelta = false),
            ),
        ).isFalse()
        assertThat(
            shouldShowSyncProgress(
                ClientSyncUiState(phase = ClientSyncPhase.Syncing, total = null, isDelta = false),
            ),
        ).isFalse()
    }

    @Test fun `a delta run flips deltaElapsed after the delay`() = runTest {
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
        // `runCurrent` only: the 1 s delay has not elapsed yet.
        runCurrent()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Syncing)
        // The phone announces an empty delta (total 0): nothing to
        // show until the delay elapses.
        h.channel.emit(
            ServerFrame.SyncStarted(
                SyncProtocol.SyncStarted(
                    mode = "full",
                    totalConversations = 0,
                    totalMessages = 0,
                    cursorMessageId = "42",
                    hubIsDefaultSms = true,
                ),
            ),
        )
        runCurrent()
        assertThat(h.engine.ui.value.deltaElapsed).isFalse()
        assertThat(shouldShowSyncProgress(h.engine.ui.value)).isFalse()
        advanceTimeBy(ClientSyncEngine.DELTA_SYNC_DIALOG_DELAY_MS)
        runCurrent()
        assertThat(h.engine.ui.value.deltaElapsed).isTrue()
        assertThat(shouldShowSyncProgress(h.engine.ui.value)).isTrue()
    }

    // -- Client mode C3: send / schedule / write-back routing ------

    @Test fun `message_sent routes to the send manager`() = runTest {
        val h = harness()
        runCurrent()
        val sent = SyncProtocol.MessageSent("key-1", "50", "7", "pending")

        h.channel.emit(ServerFrame.MessageSent(sent))
        advanceUntilIdle()

        coVerify { h.sendManager.onMessageSent(sent) }
    }

    @Test fun `message_failed routes to the send manager`() = runTest {
        val h = harness()
        runCurrent()
        val failed = SyncProtocol.MessageFailed("key-1", "7", "send_failed", "radio refused")

        h.channel.emit(ServerFrame.MessageFailed(failed))
        advanceUntilIdle()

        coVerify { h.sendManager.onMessageFailed(failed) }
    }

    @Test fun `action_result routes to the action sender`() = runTest {
        val h = harness()
        runCurrent()
        val result = SyncProtocol.ActionResult("set_pin", "7", null, null, false, "internal", "boom")

        h.channel.emit(ServerFrame.ActionResult(result))
        advanceUntilIdle()

        coVerify { h.actionSender.onActionResult(result) }
    }

    @Test fun `message_scheduled and schedule_cancelled route to the send manager`() = runTest {
        val h = harness()
        runCurrent()
        val scheduled = SyncProtocol.MessageScheduled("key-1", "9", "7", 1700000000000)
        val cancelled = SyncProtocol.ScheduleCancelled("key-1")

        h.channel.emit(ServerFrame.MessageScheduled(scheduled))
        h.channel.emit(ServerFrame.ScheduleCancelled(cancelled))
        advanceUntilIdle()

        coVerify { h.sendManager.onMessageScheduled(scheduled) }
        coVerify { h.sendManager.onScheduleCancelled(cancelled) }
    }

    @Test fun `conversation_created upserts first, then notifies the action sender`() = runTest {
        val h = harness()
        runCurrent()
        val created = SyncProtocol.ConversationCreated(sampleConversation(7))

        h.channel.emit(ServerFrame.ConversationCreated(created))
        advanceUntilIdle()

        assertThat(h.mirror.conversations.map { it.id }).containsExactly("7")
        coVerify { h.actionSender.onConversationCreated(created) }
    }

    @Test fun `a live message carrying a client key notifies the send manager`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(
            ServerFrame.MessageNew(
                SyncProtocol.MessageNew(sampleMessage(50, 7, clientMessageId = "sched-key")),
            ),
        )
        advanceUntilIdle()

        coVerify { h.sendManager.onLiveMessage("sched-key") }
    }

    @Test fun `a live message without a client key does not notify the send manager`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(ServerFrame.MessageNew(SyncProtocol.MessageNew(sampleMessage(50, 7))))
        advanceUntilIdle()

        coVerify(exactly = 0) { h.sendManager.onLiveMessage(any()) }
    }

    @Test fun `a disconnect ends a pending create`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        // The initial Disconnected state already ended a (non-existent)
        // create at startup — clear it so the disconnect below is the
        // only call under test.
        clearMocks(h.actionSender)

        h.channel.disconnect()
        advanceUntilIdle()

        coVerify(exactly = 1) { h.actionSender.onSessionEnded() }
    }

    @Test fun `a removal ends a pending create`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        clearMocks(h.actionSender)

        h.channel.remove()
        advanceUntilIdle()

        coVerify(exactly = 1) { h.actionSender.onSessionEnded() }
    }

    // -- Client mode: tablet calls ----------------------------------

    @Test fun `incoming_call shows the ringing call`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(ServerFrame.IncomingCall(SyncProtocol.IncomingCall("+33612345678", 1L)))
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isEqualTo(
            ClientCallState("+33612345678", incoming = true, phase = ClientCallPhase.RINGING),
        )
    }

    @Test fun `call_state ringing shows the popup even without incoming_call`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(
            ServerFrame.CallState(
                SyncProtocol.CallState(SyncProtocol.CallState.RINGING, "+33612345678"),
            ),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isEqualTo(
            ClientCallState("+33612345678", incoming = true, phase = ClientCallPhase.RINGING),
        )
    }

    @Test fun `call_state active marks the ringing call active`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.emit(ServerFrame.IncomingCall(SyncProtocol.IncomingCall("+33612345678", 1L)))
        advanceUntilIdle()

        h.channel.emit(
            ServerFrame.CallState(SyncProtocol.CallState(SyncProtocol.CallState.ACTIVE, "")),
        )
        advanceUntilIdle()

        // The platform reports no number off-hook, so the tracked
        // number is kept.
        assertThat(h.engine.ui.value.call).isEqualTo(
            ClientCallState("+33612345678", incoming = true, phase = ClientCallPhase.ACTIVE),
        )
    }

    @Test fun `call_state dialing marks the dialed call active`() = runTest {
        // The tablet dialed; the hub's off-hook transition arrives
        // as `dialing` (no Bluetooth on a tablet, so this is the
        // only "the dial landed" signal it gets).
        val h = harness()
        runCurrent()
        h.engine.noteDialRequested("+33612345678")
        advanceUntilIdle()
        assertThat(h.engine.ui.value.call?.phase).isEqualTo(ClientCallPhase.DIALING)

        h.channel.emit(
            ServerFrame.CallState(SyncProtocol.CallState(SyncProtocol.CallState.DIALING, "")),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isEqualTo(
            ClientCallState("+33612345678", incoming = false, phase = ClientCallPhase.ACTIVE),
        )
    }

    @Test fun `call_state dialing without a call is ignored`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(
            ServerFrame.CallState(SyncProtocol.CallState(SyncProtocol.CallState.DIALING, "")),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isNull()
    }

    @Test fun `call_state active without a call is ignored`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(
            ServerFrame.CallState(SyncProtocol.CallState(SyncProtocol.CallState.ACTIVE, "")),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isNull()
    }

    @Test fun `call_state ended clears the call`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.emit(ServerFrame.IncomingCall(SyncProtocol.IncomingCall("+33612345678", 1L)))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.call).isNotNull()

        h.channel.emit(
            ServerFrame.CallState(SyncProtocol.CallState(SyncProtocol.CallState.ENDED, "")),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isNull()
    }

    @Test fun `an unknown call state is ignored`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(ServerFrame.CallState(SyncProtocol.CallState("holding", "+33612345678")))
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isNull()
    }

    @Test fun `dial_result routes to the action sender`() = runTest {
        val h = harness()
        runCurrent()
        val result = SyncProtocol.DialResult(ok = true)

        h.channel.emit(ServerFrame.DialResult(result))
        advanceUntilIdle()

        coVerify { h.actionSender.onDialResult(result) }
    }

    @Test fun `a refused dial_result clears the outgoing dialing state`() = runTest {
        val h = harness()
        runCurrent()
        h.engine.noteDialRequested("+33612345678")
        assertThat(h.engine.ui.value.call?.phase).isEqualTo(ClientCallPhase.DIALING)

        h.channel.emit(
            ServerFrame.DialResult(
                SyncProtocol.DialResult(ok = false, error = SyncProtocol.DialResult.FAILED),
            ),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isNull()
    }

    @Test fun `a disconnect clears the tracked call`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        h.channel.emit(ServerFrame.IncomingCall(SyncProtocol.IncomingCall("+33612345678", 1L)))
        advanceUntilIdle()
        assertThat(h.engine.ui.value.call).isNotNull()

        h.channel.disconnect()
        advanceUntilIdle()

        assertThat(h.engine.ui.value.call).isNull()
    }

    @Test fun `noteDialRequested shows the outgoing dialing state`() = runTest {
        val h = harness()
        runCurrent()

        h.engine.noteDialRequested("+33612345678")

        assertThat(h.engine.ui.value.call).isEqualTo(
            ClientCallState("+33612345678", incoming = false, phase = ClientCallPhase.DIALING),
        )
    }

    @Test fun `noteDialFailed clears only the matching dial`() = runTest {
        val h = harness()
        runCurrent()
        h.engine.noteDialRequested("+33612345678")

        h.engine.noteDialFailed("+33699999999")
        assertThat(h.engine.ui.value.call).isNotNull()

        h.engine.noteDialFailed("+33612345678")
        assertThat(h.engine.ui.value.call).isNull()
    }

    // -- Client mode C4: notifications + contacts routing -----------

    @Test fun `message_new calls the notification relay`() = runTest {
        val h = harness()
        runCurrent()
        val wire = sampleMessage(50, 7)
        val resultSlot = slot<SyncedMessageResult>()

        h.channel.emit(ServerFrame.MessageNew(SyncProtocol.MessageNew(wire)))
        advanceUntilIdle()

        coVerify { h.notificationRelay.onLiveMessage(wire, capture(resultSlot)) }
        assertThat(resultSlot.captured.inserted).isTrue()
    }

    @Test fun `messages_batch never calls the notification relay`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(
            ServerFrame.MessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = "7",
                    messages = listOf(sampleMessage(50, 7), sampleMessage(49, 7)),
                    hasMore = false,
                ),
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { h.notificationRelay.onLiveMessage(any(), any()) }
    }

    @Test fun `contacts frame routes to the contact sync`() = runTest {
        val h = harness()
        runCurrent()
        val contacts = SyncProtocol.Contacts(
            contacts = listOf(
                SyncedContact("Alice", listOf("+33611111111"), hasPhoto = false),
            ),
            hasMore = false,
        )

        h.channel.emit(ServerFrame.Contacts(contacts))
        advanceUntilIdle()

        coVerify { h.contactSync.onContacts(contacts) }
    }

    @Test fun `a completed contacts snapshot clears the contacts error`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.emit(
            ServerFrame.ContactsError(
                SyncProtocol.ContactsError("hub_no_contacts_access", "no permission"),
            ),
        )
        advanceUntilIdle()
        assertThat(h.engine.ui.value.contactsErrorCode).isEqualTo("hub_no_contacts_access")
        coEvery { h.contactSync.onContacts(any()) } returns true

        h.channel.emit(ServerFrame.Contacts(SyncProtocol.Contacts(emptyList(), hasMore = false)))
        advanceUntilIdle()

        assertThat(h.engine.ui.value.contactsErrorCode).isNull()
        assertThat(h.engine.ui.value.contactsErrorMessage).isNull()
    }

    @Test fun `contact_updated routes to the contact sync and clears the contacts error`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.emit(
            ServerFrame.ContactsError(SyncProtocol.ContactsError("internal", "boom")),
        )
        advanceUntilIdle()
        val updated = SyncProtocol.ContactUpdated(
            SyncedContact("Alice", listOf("+33611111111"), hasPhoto = false),
        )

        h.channel.emit(ServerFrame.ContactUpdated(updated))
        advanceUntilIdle()

        coVerify { h.contactSync.onContactUpdated(updated) }
        assertThat(h.engine.ui.value.contactsErrorCode).isNull()
    }

    @Test fun `contact_deleted routes to the contact sync`() = runTest {
        val h = harness()
        runCurrent()
        val deleted = SyncProtocol.ContactDeleted(listOf("+33611111111"))

        h.channel.emit(ServerFrame.ContactDeleted(deleted))
        advanceUntilIdle()

        coVerify { h.contactSync.onContactDeleted(deleted) }
    }

    @Test fun `contacts_error sets the contacts error without touching the sync error`() = runTest {
        val h = harness()
        runCurrent()

        h.channel.emit(
            ServerFrame.ContactsError(
                SyncProtocol.ContactsError("hub_no_contacts_access", "no permission"),
            ),
        )
        advanceUntilIdle()

        assertThat(h.engine.ui.value.contactsErrorCode).isEqualTo("hub_no_contacts_access")
        assertThat(h.engine.ui.value.contactsErrorMessage).isEqualTo("no permission")
        assertThat(h.engine.ui.value.errorCode).isNull()
        assertThat(h.engine.ui.value.phase).isEqualTo(ClientSyncPhase.Idle)
    }

    @Test fun `retryContacts sends a contacts_request frame`() = runTest {
        val h = harness()
        runCurrent()

        h.engine.retryContacts()
        advanceUntilIdle()

        assertThat(h.channel.sent).hasSize(1)
        assertThat(h.channel.sent.single().frameType()).isEqualTo("contacts_request")
        coVerify { h.contactSync.resetSnapshot() }
    }

    @Test fun `avatar chunks route to the contact sync, other chunks route to the downloader`() = runTest {
        val h = harness()
        runCurrent()
        val avatar = SyncProtocol.AttachmentChunk("avatar:+33611111111", 0, "AA", true, null, null)
        val other = SyncProtocol.AttachmentChunk("42", 0, "AA", true, null, null)

        h.channel.emit(ServerFrame.AttachmentChunk(avatar))
        h.channel.emit(ServerFrame.AttachmentChunk(other))
        advanceUntilIdle()

        coVerify(exactly = 1) { h.contactSync.onAvatarChunk(avatar) }
        // Client mode C5 — the non-avatar chunk is a download, not an ignore.
        coVerify(exactly = 1) { h.downloader.onChunk(other) }
        coVerify(exactly = 0) { h.contactSync.onAvatarChunk(other) }
    }

    @Test fun `avatar errors route to the contact sync, other errors route to the downloader`() = runTest {
        val h = harness()
        runCurrent()
        val avatar = SyncProtocol.AttachmentError(
            "avatar:+33611111111",
            SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
            "no photo",
        )
        val other = SyncProtocol.AttachmentError(
            "42",
            SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
            "nope",
        )

        h.channel.emit(ServerFrame.AttachmentError(avatar))
        h.channel.emit(ServerFrame.AttachmentError(other))
        advanceUntilIdle()

        coVerify(exactly = 1) { h.contactSync.onAvatarError(avatar) }
        // Client mode C5 — the non-avatar error is a download failure.
        coVerify(exactly = 1) { h.downloader.onError(other) }
        coVerify(exactly = 0) { h.contactSync.onAvatarError(other) }
    }

    @Test fun `a disconnect ends avatar downloads`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        clearMocks(h.contactSync)

        h.channel.disconnect()
        advanceUntilIdle()

        coVerify(exactly = 1) { h.contactSync.onSessionEnded() }
    }

    @Test fun `connecting resumes avatar downloads`() = runTest {
        val h = harness()
        runCurrent()
        clearMocks(h.contactSync)

        h.channel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { h.contactSync.onSessionResumed() }
    }

    @Test fun `a different hub drops avatar downloads`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        clearMocks(h.contactSync)

        h.channel.connect("hub-2", "Other phone")
        advanceUntilIdle()

        coVerify(exactly = 1) { h.contactSync.onHubChanged() }
    }

    // -- Attachment downloads/uploads (C5) --------------------------

    @Test fun `upload ready routes to the uploader`() = runTest {
        val h = harness()
        runCurrent()
        val ready = SyncProtocol.AttachmentUploadReady("key-1", 0)

        h.channel.emit(ServerFrame.AttachmentUploadReady(ready))
        advanceUntilIdle()

        coVerify(exactly = 1) { h.uploader.onReady(ready) }
    }

    @Test fun `upload done routes to the uploader`() = runTest {
        val h = harness()
        runCurrent()
        val done = SyncProtocol.AttachmentUploadDone("key-1", 0)

        h.channel.emit(ServerFrame.AttachmentUploadDone(done))
        advanceUntilIdle()

        coVerify(exactly = 1) { h.uploader.onDone(done) }
    }

    @Test fun `upload error routes to the uploader`() = runTest {
        val h = harness()
        runCurrent()
        val error = SyncProtocol.AttachmentUploadError("key-1", 0, "upload_too_large", "over the cap")

        h.channel.emit(ServerFrame.AttachmentUploadError(error))
        advanceUntilIdle()

        coVerify(exactly = 1) { h.uploader.onError(error) }
    }

    @Test fun `a disconnect ends attachment downloads and fails uploads`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        clearMocks(h.downloader, h.uploader)

        h.channel.disconnect()
        advanceUntilIdle()

        coVerify(exactly = 1) { h.downloader.onSessionEnded() }
        coVerify(exactly = 1) { h.uploader.onSessionEnded() }
    }

    @Test fun `connecting resumes attachment downloads`() = runTest {
        val h = harness()
        runCurrent()
        clearMocks(h.downloader)

        h.channel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { h.downloader.onSessionResumed() }
    }

    @Test fun `a different hub drops attachment downloads`() = runTest {
        val h = harness()
        runCurrent()
        h.channel.connect()
        advanceUntilIdle()
        clearMocks(h.downloader)

        h.channel.connect("hub-2", "Other phone")
        advanceUntilIdle()

        coVerify(exactly = 1) { h.downloader.onHubChanged() }
    }

    private companion object {
        const val HUB_ID = "hub-1"
        const val HUB_NAME = "Phone"
    }
}

private fun sampleConversation(
    id: Long,
    title: String = "Alice",
    pinnedMessageId: String? = null,
    pinnedMessageAt: Long? = null,
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
    pinnedMessageId = pinnedMessageId,
    pinnedMessageAt = pinnedMessageAt,
)

private fun sampleMessage(
    id: Long,
    conversationId: Long,
    body: String = "hello",
    replyToMessageId: String? = null,
    clientMessageId: String? = null,
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
    replyToMessageId = replyToMessageId,
    reaction = null,
    attachments = emptyList(),
    clientMessageId = clientMessageId,
)

private fun String.frameType(): String? =
    Json.parseToJsonElement(this).jsonObject["type"]?.jsonPrimitive?.content

private fun String.asObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.frameType(): String? = this["type"]?.jsonPrimitive?.content
