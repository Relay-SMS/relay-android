package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.ClientConversationStateEntity

/** Client mode — per-conversation history-walk frontiers. */
@Dao
interface ClientConversationStateDao {

    @Query(
        "SELECT * FROM client_conversation_state " +
            "WHERE hub_conversation_id = :hubConversationId LIMIT 1",
    )
    suspend fun get(hubConversationId: Long): ClientConversationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ClientConversationStateEntity)

    /**
     * Creates the frontier row for a conversation that has none yet
     * (incomplete, frontier `"0"`) — a no-op when one exists. The
     * mirror adapter calls this on every conversation upsert, so
     * gap-fill's [listIncomplete] covers every synced conversation
     * that has never been fully walked, including ones whose stream
     * batches never arrived (a run that died before reaching them).
     */
    @Query(
        "INSERT OR IGNORE INTO client_conversation_state " +
            "(hub_conversation_id, min_synced_id, history_complete) " +
            "VALUES (:hubConversationId, '0', 0)",
    )
    suspend fun ensureExists(hubConversationId: Long)

    @Query("DELETE FROM client_conversation_state WHERE hub_conversation_id = :hubConversationId")
    suspend fun delete(hubConversationId: Long)

    /** The hub ids gap-fill still has to walk (oldest-first, by hub id). */
    @Query(
        "SELECT hub_conversation_id FROM client_conversation_state " +
            "WHERE history_complete = 0 ORDER BY hub_conversation_id ASC",
    )
    suspend fun listIncomplete(): List<Long>

    /** Wipes every conversation frontier (Re-sync / Unlink / mode switch). */
    @Query("DELETE FROM client_conversation_state")
    suspend fun deleteAll()
}
