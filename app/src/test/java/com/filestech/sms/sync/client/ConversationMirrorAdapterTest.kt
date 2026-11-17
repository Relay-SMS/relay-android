package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity
import com.filestech.sms.data.local.db.entity.ClientSyncStateEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.SyncedConversationData
import com.filestech.sms.data.repository.SyncedConversationResult
import com.filestech.sms.data.repository.SyncedMessageData
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Client mode C2 — the [ClientMirror] adapter: the wire → data
 * mapping, the delegation to [ConversationMirror], the frontier row
 * every conversation upsert ensures (so gap-fill finds threads
 * whose stream batches never arrived), and the partial
 * `message_updated` patch. The mirror's own dedup (upsert by hub
 * id, no duplicates on re-sync) needs a Room database and lives in
 * [com.filestech.sms.data.repository.ConversationMirrorSyncTest]
 * (Robolectric, CI-only — the local harness has no KSP-generated
 * Room implementations).
 */
class ConversationMirrorAdapterTest {

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

    private class Fixture {
        val mirror = mockk<ConversationMirror>(relaxed = true)
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val stateStore = FakeStateStore()
        val adapter = ConversationMirrorAdapter(
            mirror,
            conversationDao,
            messageDao,
            stateStore,
        )
    }

    @Test fun `upsertSyncedConversation maps the wire row and ensures the frontier`() = runTest {
        val f = Fixture()
        val expected = SyncedConversationResult(localId = 11, created = true)
        coEvery { f.mirror.upsertSyncedConversation(any()) } returns expected
        val slot = slot<SyncedConversationData>()
        val result = f.adapter.upsertSyncedConversation(sampleConversation())
        assertThat(result).isEqualTo(expected)
        coVerify { f.mirror.upsertSyncedConversation(capture(slot)) }
        assertThat(slot.captured.hubConversationId).isEqualTo(7L)
        assertThat(slot.captured.title).isEqualTo("Alice")
        assertThat(slot.captured.addresses).containsExactly("+33612345678").inOrder()
        assertThat(slot.captured.unreadCount).isEqualTo(2)
        assertThat(slot.captured.pinnedMessageHubId).isEqualTo(50L)
        // The frontier row exists now, incomplete — gap-fill can
        // find this thread even if its batches never arrived.
        assertThat(f.stateStore.convStates[7L]).isNotNull()
        assertThat(f.stateStore.convStates[7L]!!.historyComplete).isFalse()
        assertThat(f.stateStore.convStates[7L]!!.minSyncedId).isEqualTo("0")
    }

    @Test fun `upsertSyncedConversation drops a row whose id does not parse`() = runTest {
        val f = Fixture()
        val result = f.adapter.upsertSyncedConversation(sampleConversation(id = "abc"))
        assertThat(result).isNull()
        coVerify(exactly = 0) { f.mirror.upsertSyncedConversation(any()) }
        assertThat(f.stateStore.convStates).isEmpty()
    }

    @Test fun `upsertSyncedMessage maps the wire row and delegates`() = runTest {
        val f = Fixture()
        val expected = SyncedMessageResult(localId = 5, inserted = true)
        coEvery { f.mirror.upsertSyncedMessage(any()) } returns expected
        val slot = slot<SyncedMessageData>()
        val result = f.adapter.upsertSyncedMessage(sampleMessage())
        assertThat(result).isEqualTo(expected)
        coVerify { f.mirror.upsertSyncedMessage(capture(slot)) }
        assertThat(slot.captured.hubMessageId).isEqualTo(50L)
        assertThat(slot.captured.hubConversationId).isEqualTo(7L)
        assertThat(slot.captured.body).isEqualTo("hello")
        assertThat(slot.captured.type).isEqualTo(MessageType.MMS)
        assertThat(slot.captured.direction).isEqualTo(MessageDirection.OUTGOING)
        assertThat(slot.captured.status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(slot.captured.replyToHubMessageId).isEqualTo(40L)
        assertThat(slot.captured.attachments).hasSize(1)
    }

    @Test fun `upsertSyncedMessage drops a row whose id does not parse`() = runTest {
        val f = Fixture()
        val result = f.adapter.upsertSyncedMessage(sampleMessage(id = "abc"))
        assertThat(result).isNull()
        coVerify(exactly = 0) { f.mirror.upsertSyncedMessage(any()) }
    }

    @Test fun `applyMessageUpdated patches only the present fields`() = runTest {
        val f = Fixture()
        val existing = sampleMessageEntity().copy(
            id = 5,
            status = MessageStatus.SENT,
            reactionEmoji = "👍",
            read = false,
            starred = true,
            hubMessageId = 50,
        )
        coEvery { f.messageDao.findByHubMessageId(50L) } returns existing
        val slot = slot<MessageEntity>()
        f.adapter.applyMessageUpdated(
            SyncProtocol.MessageUpdated(
                messageId = "50",
                conversationId = "7",
                status = "delivered",
                reactionRemoved = true,
            ),
        )
        coVerify { f.messageDao.update(capture(slot)) }
        assertThat(slot.captured.id).isEqualTo(5L)
        assertThat(slot.captured.status).isEqualTo(MessageStatus.DELIVERED)
        // A removed reaction rides as its own flag (a null
        // `reaction` is omitted on encode, so it cannot mean
        // "removed") — the emoji is cleared.
        assertThat(slot.captured.reactionEmoji).isNull()
        // Absent fields stay as they were.
        assertThat(slot.captured.read).isFalse()
        assertThat(slot.captured.starred).isTrue()
        assertThat(slot.captured.body).isEqualTo("hello")
    }

    @Test fun `applyMessageUpdated is a no-op for an unknown message`() = runTest {
        val f = Fixture()
        coEvery { f.messageDao.findByHubMessageId(50L) } returns null
        f.adapter.applyMessageUpdated(
            SyncProtocol.MessageUpdated(
                messageId = "50",
                conversationId = "7",
                status = "delivered",
            ),
        )
        coVerify(exactly = 0) { f.messageDao.update(any()) }
    }

    @Test fun `deleteSyncedConversation deletes the row and its frontier`() = runTest {
        val f = Fixture()
        f.stateStore.convStates[7L] = ClientConversationStateEntity(
            hubConversationId = 7,
            minSyncedId = "10",
            historyComplete = false,
        )
        coEvery { f.conversationDao.findByHubConversationId(7L) } returns ConversationEntity(
            id = 11,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = "Alice",
            lastMessageAt = 0,
            lastMessagePreview = null,
            hubConversationId = 7,
        )
        f.adapter.deleteSyncedConversation(7)
        coVerify { f.conversationDao.delete(11) }
        assertThat(f.stateStore.convStates[7L]).isNull()
    }

    @Test fun `pruneSyncedConversations deletes only the absent ones`() = runTest {
        val f = Fixture()
        coEvery { f.conversationDao.hubConversationIds() } returns listOf(1L, 2L, 3L)
        coEvery { f.conversationDao.findByHubConversationId(any()) } answers {
            val hubId = firstArg<Long>()
            ConversationEntity(
                id = hubId + 10,
                threadId = null,
                addressesCsv = "+3360000000$hubId",
                displayName = null,
                lastMessageAt = 0,
                lastMessagePreview = null,
                hubConversationId = hubId,
            )
        }
        val pruned = f.adapter.pruneSyncedConversations(setOf(2L))
        assertThat(pruned).isEqualTo(2)
        coVerify { f.conversationDao.delete(11) }
        coVerify { f.conversationDao.delete(13) }
        coVerify(exactly = 0) { f.conversationDao.delete(12) }
    }

    @Test fun `resolveReplyTarget points the reply at its target`() = runTest {
        val f = Fixture()
        coEvery { f.messageDao.findById(5L) } returns sampleMessageEntity().copy(
            id = 5,
            replyToMessageId = null,
        )
        val slot = slot<MessageEntity>()
        f.adapter.resolveReplyTarget(5, 9)
        coVerify { f.messageDao.update(capture(slot)) }
        assertThat(slot.captured.replyToMessageId).isEqualTo(9L)
    }

    @Test fun `resolveReplyTarget is a no-op when already pointing`() = runTest {
        val f = Fixture()
        coEvery { f.messageDao.findById(5L) } returns sampleMessageEntity().copy(
            id = 5,
            replyToMessageId = 9,
        )
        f.adapter.resolveReplyTarget(5, 9)
        coVerify(exactly = 0) { f.messageDao.update(any()) }
    }

    private fun sampleMessageEntity() = MessageEntity(
        conversationId = 11,
        telephonyUri = null,
        address = "+33612345678",
        body = "hello",
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = 1700000000000,
        dateSent = null,
    )

    private fun sampleMessage(id: String = "50") = SyncedMessage(
        id = id,
        conversationId = "7",
        address = "+33612345678",
        body = "hello",
        type = "mms",
        direction = "outgoing",
        date = 1700000000000,
        dateSent = 1700000001000,
        read = false,
        starred = true,
        status = "delivered",
        errorCode = null,
        replyToMessageId = "40",
        reaction = "❤️",
        attachments = listOf(
            com.filestech.sms.sync.SyncedAttachment(
                id = "9",
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                sizeBytes = 1234,
                width = 800,
                height = 600,
                durationMs = null,
            ),
        ),
    )

    private fun sampleConversation(id: String = "7") = SyncedConversation(
        id = id,
        threadId = null,
        addresses = listOf("+33612345678"),
        displayName = "Alice A",
        customName = null,
        title = "Alice",
        lastMessageAt = 1700000000000,
        lastMessagePreview = "hi",
        unread = 2,
        pinned = false,
        archived = false,
        muted = false,
        blocked = false,
        messageCount = 9,
        pinnedMessageId = "50",
        pinnedMessageAt = 1700000002000,
    )
}
