package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * E2E media encryption (schema v23) — one row per received media key
 * (design §3, round 48). A key message can arrive BEFORE its MMS part
 * (the key travels as regular SMS, the ciphertext as MMS — the two
 * channels have independent delivery timing), so the receiver stores
 * the key here and decrypts the part when it lands. The file id is
 * the only link between the key and the part, so it is the primary
 * key — and it is random per file (16 hex chars), so a collision
 * across peers is negligible even without a sender column.
 *
 * The key is stored base64'd in SQLCipher: the whole database is
 * encrypted at rest, so no extra pickle wrap is needed (unlike the
 * Olm Account/session pickles, which are Keystore-wrapped because
 * they are the long-lived identity — a per-file key is single-use
 * and worthless once its file is decrypted).
 *
 * Written by the media-key receive path, read when an encrypted MMS
 * part arrives (Worker 1b). Rows are KEPT for decrypt-on-view — the
 * key is needed every time the user opens the media. Only orphan
 * rows (a key whose MMS never arrives) are pruned by age (the prune
 * checks each old key for a matching attachment first).
 */
@Entity(tableName = "e2e_media_keys")
data class E2eMediaKeyEntity(
    /** Random per file (16 hex chars) — also the part filename stem. */
    @PrimaryKey
    @ColumnInfo(name = "file_id")
    val fileId: String,
    /** The 32-byte AES-256-GCM key, base64 (as received on the wire). */
    @ColumnInfo(name = "key_b64") val keyB64: String,
    /** The file's original MIME type (e.g. `image/jpeg`). */
    @ColumnInfo(name = "mime") val mime: String,
    /** The plaintext size in bytes (before the 29-byte envelope). */
    @ColumnInfo(name = "size") val size: Long,
    /** Lowercase hex SHA-256 of the plaintext. */
    @ColumnInfo(name = "sha256") val sha256: String,
    /** The original filename, or null when the sender sent none. */
    @ColumnInfo(name = "name") val name: String?,
    /** Epoch ms the key message arrived (pruning + ordering). */
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)
