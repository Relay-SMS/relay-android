package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file attached to a [NoteMessageEntity]. Unlike MMS attachments, the bytes at
 * [localUri] are ENCRYPTED (AES-256-GCM under the notes master key — see
 * [com.filestech.sms.data.notes.NoteAttachmentCrypto]): the file format is
 * `[version:1][iv:12][ciphertext+tag]`, one random IV per file.
 *
 * [sizeBytes] is the size of the PLAINTEXT bytes (the display size); the ciphertext on disk
 * is 29 bytes larger. Decryption happens at render/download time through
 * [com.filestech.sms.domain.notes.NoteAttachmentResolver], never by rewriting this URI.
 *
 * A note attachment file is owned SOLELY by its row — delete the file when the row is
 * deleted. There is no refcounting (unlike `FichiersDePiecesJointes`, which exists because
 * one MMS file can be cited by several rows; a note file cannot).
 */
@Entity(
    tableName = "note_attachments",
    foreignKeys = [
        ForeignKey(
            entity = NoteMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["note_message_id"]),
        // Backup dedup / merge key (D7).
        Index(value = ["uuid"], unique = true),
        // Phase 2 (D8) — the hub attachment id is the upsert/download key for mirrored files.
        Index(value = ["hub_attachment_id"], unique = true),
    ],
)
data class NoteAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UUID v4, assigned at creation; the backup dedup key (and the file name on disk). */
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "note_message_id") val noteMessageId: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_name") val fileName: String?,
    /** Size of the PLAINTEXT bytes; the ciphertext file is 29 bytes larger. */
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    /** Absolute path of the ENCRYPTED file on disk (under `filesDir/notes/attachments/`). */
    @ColumnInfo(name = "local_uri") val localUri: String,
    @ColumnInfo(name = "width") val width: Int? = null,
    @ColumnInfo(name = "height") val height: Int? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    /** Phase 2 (D8) — the hub's Room id for this attachment; null on hub-mode rows. */
    @ColumnInfo(name = "hub_attachment_id") val hubAttachmentId: Long? = null,
)
