package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.ClientConversationStateDao
import com.filestech.sms.data.local.db.dao.ClientSyncStateDao
import com.filestech.sms.data.local.db.dao.SyncedContactDao
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity
import com.filestech.sms.data.local.db.entity.ClientSyncStateEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — the persisted sync state, per hub and per
 * conversation: the mode choice, the global cursor, the
 * history-walk frontiers. The engine reads it on connect and writes
 * it as the sync advances; the wiper clears it on Re-sync / Unlink /
 * mode switch.
 *
 * The cursor is a decimal string of a hub message id (`"0"` = none
 * yet). It advances only to the max hub id durably stored — never
 * to a server-advertised cursor — so a crash mid-batch re-delivers
 * by id (the mirror upserts idempotently) instead of skipping.
 */
interface SyncStateStore {

    /** The stored state for one hub, or null when never synced (or wiped). */
    suspend fun getSyncState(hubId: String): ClientSyncStateEntity?

    /** The hub ids with stored sync state (the hub-change check reads this). */
    suspend fun listHubIds(): List<String>

    /** Inserts or replaces one hub's state. */
    suspend fun saveSyncState(state: ClientSyncStateEntity)

    /** Drops one hub's state (unlink from that hub). */
    suspend fun deleteSyncState(hubId: String)

    /** The walk frontier of one conversation, or null when never walked. */
    suspend fun getConversationState(hubConversationId: Long): ClientConversationStateEntity?

    /** Inserts or replaces one conversation's frontier. */
    suspend fun saveConversationState(state: ClientConversationStateEntity)

    /**
     * Creates the frontier row for a conversation that has none yet
     * (incomplete, frontier `"0"`) — a no-op when one exists.
     */
    suspend fun ensureConversationState(hubConversationId: Long)

    /** Drops one conversation's frontier (its conversation was deleted). */
    suspend fun deleteConversationState(hubConversationId: Long)

    /** The hub ids of every conversation whose history walk is unfinished. */
    suspend fun listIncompleteConversationIds(): List<Long>

    /**
     * Wipes ALL sync state: every hub row, every frontier, and the
     * synced-contacts cache. The message rows themselves are wiped
     * by [ClientCacheWiper] alongside this.
     */
    suspend fun clearAll()
}

/** Client mode — the [SyncStateStore] backed by the Room tables. */
@Singleton
class RoomSyncStateStore @Inject constructor(
    private val syncStateDao: ClientSyncStateDao,
    private val conversationStateDao: ClientConversationStateDao,
    private val syncedContactDao: SyncedContactDao,
) : SyncStateStore {

    override suspend fun getSyncState(hubId: String): ClientSyncStateEntity? =
        syncStateDao.get(hubId)

    override suspend fun listHubIds(): List<String> = syncStateDao.listHubIds()

    override suspend fun saveSyncState(state: ClientSyncStateEntity) {
        syncStateDao.upsert(state)
    }

    override suspend fun deleteSyncState(hubId: String) {
        syncStateDao.delete(hubId)
    }

    override suspend fun getConversationState(
        hubConversationId: Long,
    ): ClientConversationStateEntity? = conversationStateDao.get(hubConversationId)

    override suspend fun saveConversationState(state: ClientConversationStateEntity) {
        conversationStateDao.upsert(state)
    }

    override suspend fun ensureConversationState(hubConversationId: Long) {
        conversationStateDao.ensureExists(hubConversationId)
    }

    override suspend fun deleteConversationState(hubConversationId: Long) {
        conversationStateDao.delete(hubConversationId)
    }

    override suspend fun listIncompleteConversationIds(): List<Long> =
        conversationStateDao.listIncomplete()

    override suspend fun clearAll() {
        syncStateDao.deleteAll()
        conversationStateDao.deleteAll()
        syncedContactDao.deleteAll()
    }
}
