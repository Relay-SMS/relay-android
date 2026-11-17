package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity
import com.filestech.sms.data.local.db.entity.ClientSyncStateEntity
import com.filestech.sms.data.repository.FichiersDePiecesJointes
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Client mode C2 — the cache wiper. The Re-sync contract: the synced
 * message cache goes (mirrored conversations + messages + all sync
 * state), and nothing else is touched — in particular the schedules
 * this device created survive, because the wiper holds no
 * scheduled-messages DAO at all and its only calls are the two
 * `deleteSynced()`s and `clearAll()` (`confirmVerified` below pins
 * that: any other DAO call would fail it).
 */
class ClientCacheWiperTest {

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
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val linkStore = mockk<ClientLinkStore>(relaxed = true)
        val pinStore = mockk<TofuPinStore>(relaxed = true)
        val stateStore = FakeStateStore()
        val contactSync = mockk<ClientContactSync>(relaxed = true)

        // Client mode C5 — the downloaded files go with the cache.
        val attachmentDao = mockk<AttachmentDao>(relaxed = true)
        val fichiers = mockk<FichiersDePiecesJointes>(relaxed = true)
        val downloader = mockk<ClientAttachmentDownloader>(relaxed = true)
        val wiper = ClientCacheWiper(
            linkStore,
            pinStore,
            conversationDao,
            messageDao,
            stateStore,
            contactSync,
            attachmentDao,
            fichiers,
            downloader,
        )
    }

    @Test fun `wipeMessageCache deletes synced rows and clears sync state`() = runTest {
        val f = Fixture()
        f.stateStore.syncStates["hub-1"] = ClientSyncStateEntity(
            hubId = "hub-1",
            mode = "full",
            globalLastMessageId = "42",
            initialSyncDone = true,
            lastSyncAt = 1L,
        )
        f.stateStore.convStates[5L] = ClientConversationStateEntity(
            hubConversationId = 5,
            minSyncedId = "10",
            historyComplete = false,
        )
        f.wiper.wipeMessageCache()
        coVerify(exactly = 1) { f.attachmentDao.findLocalUrisForSynced() }
        coVerify(exactly = 1) { f.conversationDao.deleteSynced() }
        coVerify(exactly = 1) { f.messageDao.deleteSynced() }
        coVerify(exactly = 1) { f.contactSync.onCacheWiped() }
        coVerify(exactly = 1) { f.downloader.onCacheWiped() }
        assertThat(f.stateStore.syncStates).isEmpty()
        assertThat(f.stateStore.convStates).isEmpty()
        // Nothing else was touched: no other DAO call, no link/pin
        // change — the link survives a Re-sync. (No files were
        // collected — the relaxed DAO returns an empty list — so the
        // file deleter was never called.)
        confirmVerified(f.conversationDao)
        confirmVerified(f.messageDao)
        confirmVerified(f.linkStore)
        confirmVerified(f.pinStore)
        confirmVerified(f.contactSync)
        confirmVerified(f.attachmentDao)
        confirmVerified(f.fichiers)
        confirmVerified(f.downloader)
    }

    @Test fun `wipeMessageCache deletes the downloaded files it collected`() = runTest {
        val f = Fixture()
        coEvery { f.attachmentDao.findLocalUrisForSynced() } returns
            listOf("/files/mms_attachments/42.jpg", "/files/mms_attachments/43.mp4")

        f.wiper.wipeMessageCache()

        coVerify(exactly = 1) {
            f.fichiers.effacerSiPlusCites(
                listOf("/files/mms_attachments/42.jpg", "/files/mms_attachments/43.mp4"),
            )
        }
        coVerify(exactly = 1) { f.downloader.onCacheWiped() }
    }

    @Test fun `wipeHubState wipes the cache and clears the link and pin`() = runTest {
        val f = Fixture()
        f.wiper.wipeHubState()
        coVerify(exactly = 1) { f.attachmentDao.findLocalUrisForSynced() }
        coVerify(exactly = 1) { f.conversationDao.deleteSynced() }
        coVerify(exactly = 1) { f.messageDao.deleteSynced() }
        coVerify(exactly = 1) { f.contactSync.onCacheWiped() }
        coVerify(exactly = 1) { f.downloader.onCacheWiped() }
        coVerify(exactly = 1) { f.linkStore.clearHub() }
        verify(exactly = 1) { f.pinStore.clear() }
    }
}
