package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [E2eNoticeEntity] — the in-thread encryption notices (system rows, not
 * bubbles).
 *
 * All access goes through [com.filestech.sms.e2e.E2eNoticeStore]. The thread
 * ViewModel merges [noticesForConversation] into the message window by date
 * (notices are rare, so the merge is cheap).
 */
@Dao
interface E2eNoticeDao {

    @Insert
    suspend fun insert(notice: E2eNoticeEntity): Long

    /** Every notice in a conversation, oldest first (the thread merge order). */
    @Query("SELECT * FROM e2e_notices WHERE conversation_id = :conversationId ORDER BY date ASC")
    suspend fun noticesForConversation(conversationId: Long): List<E2eNoticeEntity>

    /**
     * Live view of a conversation's notices, oldest first (the thread
     * merge order). The thread UI collects it: a notice recorded while
     * the thread is open (a bundle landing, a key change) appears
     * without reopening the thread.
     */
    @Query("SELECT * FROM e2e_notices WHERE conversation_id = :conversationId ORDER BY date ASC")
    fun observeForConversation(conversationId: Long): Flow<List<E2eNoticeEntity>>

    /** How many notices of this kind a conversation already has (the once-per-identity guard). */
    @Query(
        "SELECT COUNT(*) FROM e2e_notices WHERE conversation_id = :conversationId AND kind = :kind",
    )
    suspend fun countByKind(conversationId: Long, kind: E2eNoticeKind): Int

    /** Deletes a conversation's notices (conversation deleted). */
    @Query("DELETE FROM e2e_notices WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: Long): Int
}
