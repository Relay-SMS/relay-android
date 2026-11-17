package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("message_id"),
        // Client mode (schema v18) — the hub attachment id is the
        // upsert key for synced attachment metadata, and the
        // download/upload key in C5. UNIQUE: one local row per hub
        // attachment, so a re-sync never duplicates one (SQLite
        // admits several NULLs, so hub-mode rows are unaffected).
        Index("hub_attachment_id", unique = true),
    ],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_name") val fileName: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "local_uri") val localUri: String,
    @ColumnInfo(name = "width") val width: Int? = null,
    @ColumnInfo(name = "height") val height: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    /**
     * Client mode (schema v18) — **the hub's Room id for this
     * attachment** (wire `id`): the upsert key for synced attachment
     * metadata, and the download key in C5. `null` on hub-mode rows.
     */
    @ColumnInfo(name = "hub_attachment_id") val hubAttachmentId: Long? = null,
)
