package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * E2E encryption (schema v22) — one received ciphertext fragment, awaiting reassembly
 * (design §3.5). Fragments arrive as single-part SMS (`RLY1:` prefix) and are stored
 * here instead of as messages; on arrival of the final fragment they concatenate in
 * index order, base64-decode, and decrypt — then the rows are deleted.
 *
 * The composite primary key (sender, msgId, index) dedupes retries: a re-sent
 * fragment set carries the same msgId, so re-inserting a fragment is a no-op
 * (`OnConflictStrategy.IGNORE`). Incomplete sets expire after 24 hours, pruned by
 * the periodic worker ([com.filestech.sms.e2e.E2eFragmentStore.pruneExpired]).
 */
@Entity(
    tableName = "e2e_fragments",
    primaryKeys = ["sender", "msg_id", "idx"],
)
data class E2eFragmentEntity(
    /** The sender's normalized phone number. */
    @ColumnInfo(name = "sender") val sender: String,
    /** The 6-hex fragment-set id, random per message. */
    @ColumnInfo(name = "msg_id") val msgId: String,
    /** This fragment's index within its set (0-based). */
    @ColumnInfo(name = "idx") val idx: Int,
    /** The set's fragment count (same on every fragment of the set). */
    @ColumnInfo(name = "total") val total: Int,
    /** One substring of the base64 of the whole payload. */
    @ColumnInfo(name = "chunk") val chunk: String,
    /** Epoch ms this fragment arrived. */
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)
