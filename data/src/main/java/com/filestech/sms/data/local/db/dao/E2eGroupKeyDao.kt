package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.filestech.sms.data.local.db.entity.E2eGroupKeyEntity

/**
 * DAO for [E2eGroupKeyEntity] — the sender keys for encrypted
 * groups (round 49, design §13).
 *
 * All access goes through
 * [com.filestech.sms.e2e.E2eGroupSenderKeyStore], which owns the
 * current-key / generation rules (a distribution only becomes
 * current when its generation beats the stored one, or the
 * sender's identity key changed). Never call this DAO from the UI
 * layer directly.
 */
@Dao
interface E2eGroupKeyDao {

    /**
     * Stores a sender key. IGNORE on conflict — first key wins:
     * a re-sent distribution for the same (group, sender, key
     * id) must not overwrite the first key, which is the one the
     * sender's messages are encrypted under. Returns the row id,
     * or -1 on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(key: E2eGroupKeyEntity): Long

    /** One key by its full triple, or null when no such key was ever stored. */
    @Query(
        """
        SELECT * FROM e2e_group_keys
         WHERE group_id = :groupId AND sender_address = :senderAddress AND key_id = :keyId
         LIMIT 1
        """,
    )
    suspend fun findKey(groupId: String, senderAddress: String, keyId: String): E2eGroupKeyEntity?

    /** Every generation of one sender's key for a group, newest generation first. */
    @Query(
        """
        SELECT * FROM e2e_group_keys
         WHERE group_id = :groupId AND sender_address = :senderAddress
         ORDER BY key_generation DESC, created_at DESC
        """,
    )
    suspend fun keysForSender(groupId: String, senderAddress: String): List<E2eGroupKeyEntity>

    /** The sender's current key for a group, or null when none is marked current. */
    @Query(
        """
        SELECT * FROM e2e_group_keys
         WHERE group_id = :groupId AND sender_address = :senderAddress AND is_current = 1
         LIMIT 1
        """,
    )
    suspend fun currentKeyForSender(groupId: String, senderAddress: String): E2eGroupKeyEntity?

    /**
     * Every key row for a group (all senders, all generations).
     * The member-list lookup reads `members_csv` from any row —
     * every row for one group carries the same list.
     */
    @Query("SELECT * FROM e2e_group_keys WHERE group_id = :groupId")
    suspend fun keysForGroup(groupId: String): List<E2eGroupKeyEntity>

    /**
     * Every distinct member list ever stored, for the
     * own-number fallback: when our own number is unknown, a
     * group send finds its member list here (the stored list
     * that holds the thread's addresses plus exactly one more
     * address — ourselves).
     */
    @Query("SELECT DISTINCT members_csv FROM e2e_group_keys")
    suspend fun allMembersLists(): List<String>

    /**
     * Every group id this sender has a key for: the notice paths
     * (key-change / left-Relay) find the affected groups here —
     * only groups where the member actually held a sender key
     * get a group notice.
     */
    @Query("SELECT DISTINCT group_id FROM e2e_group_keys WHERE sender_address = :senderAddress")
    suspend fun groupIdsForSender(senderAddress: String): List<String>

    /**
     * Marks one key current for its (group, sender): clears the
     * flag on the sender's other keys for the group, then sets
     * it on this one. One transaction, so a crash cannot leave
     * two current keys (or none).
     */
    @Transaction
    suspend fun setCurrentKey(groupId: String, senderAddress: String, keyId: String) {
        clearCurrentForSender(groupId, senderAddress)
        markCurrent(groupId, senderAddress, keyId)
    }

    @Query(
        """
        UPDATE e2e_group_keys SET is_current = 0
         WHERE group_id = :groupId AND sender_address = :senderAddress
        """,
    )
    suspend fun clearCurrentForSender(groupId: String, senderAddress: String)

    @Query(
        """
        UPDATE e2e_group_keys SET is_current = 1
         WHERE group_id = :groupId AND sender_address = :senderAddress AND key_id = :keyId
        """,
    )
    suspend fun markCurrent(groupId: String, senderAddress: String, keyId: String)

    /**
     * Prunes old generations for one sender in one group, keeping
     * the [keep] highest generations (by generation, then
     * recency). The CURRENT key is never pruned — after an
     * identity change the current key can carry a LOWER
     * generation than the pre-change key, and pruning it would
     * strand the sender's new messages. Returns the rows deleted.
     */
    @Query(
        """
        DELETE FROM e2e_group_keys
         WHERE group_id = :groupId AND sender_address = :senderAddress AND is_current = 0
           AND key_id NOT IN (
               SELECT key_id FROM e2e_group_keys
                WHERE group_id = :groupId AND sender_address = :senderAddress
                ORDER BY key_generation DESC, created_at DESC
                LIMIT :keep
           )
        """,
    )
    suspend fun pruneOldGenerations(groupId: String, senderAddress: String, keep: Int): Int

    /** Deletes every key row for a group (conversation deleted). */
    @Query("DELETE FROM e2e_group_keys WHERE group_id = :groupId")
    suspend fun deleteByGroup(groupId: String): Int

    /** Deletes every key row from one sender (peer deleted / panic wipe). */
    @Query("DELETE FROM e2e_group_keys WHERE sender_address = :senderAddress")
    suspend fun deleteBySender(senderAddress: String): Int
}
