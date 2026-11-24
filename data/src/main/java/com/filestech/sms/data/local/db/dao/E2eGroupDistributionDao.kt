package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.E2eGroupDistributionEntity

/**
 * DAO for [E2eGroupDistributionEntity] — the record of sender-key
 * distributions we sent (round 49, design §13).
 *
 * All access goes through
 * [com.filestech.sms.e2e.E2eGroupSenderKeyStore]. The store reads
 * these rows for the send-path ensure (which members still need
 * the current key) and for the key-request rate limit (a member
 * that asked again within 24 h of a send gets no re-send).
 */
@Dao
interface E2eGroupDistributionDao {

    /**
     * Records a distribution. REPLACE on conflict — a re-send
     * refreshes the timestamp, which is what the rate limit
     * reads (the re-send IS the latest send).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(distribution: E2eGroupDistributionEntity)

    /** One distribution record, or null when we never sent this key to this member. */
    @Query(
        """
        SELECT * FROM e2e_group_distributions
         WHERE group_id = :groupId AND member_address = :memberAddress AND key_id = :keyId
         LIMIT 1
        """,
    )
    suspend fun find(
        groupId: String,
        memberAddress: String,
        keyId: String,
    ): E2eGroupDistributionEntity?

    /** The members we already sent this key to (the send-path ensure skips them). */
    @Query(
        """
        SELECT member_address FROM e2e_group_distributions
         WHERE group_id = :groupId AND key_id = :keyId
        """,
    )
    suspend fun distributedMembers(groupId: String, keyId: String): List<String>

    /**
     * Prunes a group's stale distribution records when a new key
     * is created: the old key's records would otherwise claim
     * members "have" a key nobody uses anymore. The current
     * key's records survive (the re-key distributes it fresh,
     * and its records accumulate from there).
     */
    @Query(
        """
        DELETE FROM e2e_group_distributions
         WHERE group_id = :groupId AND key_id != :currentKeyId
        """,
    )
    suspend fun deleteForGroupExceptKey(groupId: String, currentKeyId: String): Int

    /** Deletes every distribution record for a group (conversation deleted). */
    @Query("DELETE FROM e2e_group_distributions WHERE group_id = :groupId")
    suspend fun deleteByGroup(groupId: String): Int

    /** Deletes every distribution record to one member (peer deleted / panic wipe). */
    @Query("DELETE FROM e2e_group_distributions WHERE member_address = :memberAddress")
    suspend fun deleteByMember(memberAddress: String): Int
}
