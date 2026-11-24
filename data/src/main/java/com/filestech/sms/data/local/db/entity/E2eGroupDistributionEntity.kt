package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * E2E encrypted groups (schema v24, round 49) — one row per
 * sender-key distribution we sent: our current key for [groupId]
 * went out to [memberAddress] (as [keyId]) at [distributedAt]
 * (design §13).
 *
 * Two jobs, one table:
 * - **Send-path ensure:** before a group send, the members
 *   WITHOUT a record for the current key id get the distribution
 *   (a member whose distribution was lost gets it again on the
 *   next send or thread-open — no per-message re-distribution,
 *   which would cost a segment per member per message).
 * - **Key-request rate limit:** a member that missed our key asks
 *   for it again (the GROUP_KEY_REQUEST payload); a record newer
 *   than 24 h answers "already sent, in flight or unprocessed" and
 *   the request is ignored, so a burst of requests earns at most
 *   one re-send per member per day.
 *
 * Rows are keyed by (group, member, key id): a re-key (new key id)
 * starts with no records, so the new key distributes to everyone;
 * the old key's records are pruned when the new key is created.
 * No key material lives here — only the fact and time of a send.
 */
@Entity(
    tableName = "e2e_group_distributions",
    primaryKeys = ["group_id", "member_address", "key_id"],
)
data class E2eGroupDistributionEntity(
    /** The group id the key belongs to (32 hex chars). */
    @ColumnInfo(name = "group_id")
    val groupId: String,
    /** The member the key was sent to (canonical address). */
    @ColumnInfo(name = "member_address")
    val memberAddress: String,
    /** The key id that was distributed (16 hex chars). */
    @ColumnInfo(name = "key_id")
    val keyId: String,
    /** Epoch ms the distribution went out (and the radio accepted it). */
    @ColumnInfo(name = "distributed_at")
    val distributedAt: Long,
)
