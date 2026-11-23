package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Round 48 — one candidate for the media-key prune: an encrypted
 * MMS message carrying a relay-media ciphertext part
 * ([com.filestech.sms.data.mms.RelayMediaFilename]). The prune
 * ([com.filestech.sms.e2e.E2eMediaPrune]) reads the file id back
 * out of [fileName] and checks the key table — a candidate with
 * no key row is a message whose media can never decrypt, and is
 * marked failed. The key lookup stays in Kotlin (not a SQL join):
 * the file id is embedded in the filename, and the join would
 * couple this DAO to the key table's shape.
 */
data class PendingMediaAttachment(
    /** The carrying message's Room id. */
    @ColumnInfo(name = "message_id") val messageId: Long,
    /** The part filename (`relay-media-<id>.bin`). */
    @ColumnInfo(name = "file_name") val fileName: String?,
)
