package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity

/**
 * DAO for [E2eFragmentEntity] — in-flight ciphertext fragments awaiting reassembly.
 *
 * All access goes through [com.filestech.sms.e2e.E2eFragmentStore], which owns the
 * complete-set check (count == total, indices contiguous), the delete-on-reassembly,
 * and the 24-hour expiry prune.
 */
@Dao
interface E2eFragmentDao {

    /**
     * Stores a fragment, ignoring the insert if this (sender, msgId, index) is already
     * stored — a re-sent fragment set carries the same msgId, so retries dedupe here.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(fragment: E2eFragmentEntity): Long

    /** Every stored fragment of one set, in index order (the reassembly order). */
    @Query(
        "SELECT * FROM e2e_fragments WHERE sender = :sender AND msg_id = :msgId ORDER BY idx ASC",
    )
    suspend fun fragmentsFor(sender: String, msgId: String): List<E2eFragmentEntity>

    @Query("SELECT COUNT(*) FROM e2e_fragments WHERE sender = :sender AND msg_id = :msgId")
    suspend fun countFor(sender: String, msgId: String): Int

    /** Deletes a whole fragment set — after reassembly, or when the set is abandoned. */
    @Query("DELETE FROM e2e_fragments WHERE sender = :sender AND msg_id = :msgId")
    suspend fun deleteSet(sender: String, msgId: String): Int

    /** Deletes fragments older than [cutoff] (epoch ms) — the 24-hour expiry prune. */
    @Query("DELETE FROM e2e_fragments WHERE received_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
