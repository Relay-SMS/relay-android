package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.E2eMediaKeyEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [E2eMediaKeyEntity] — the received media keys waiting for
 * their MMS part (round 48, design §3).
 *
 * The receiver (Worker 1b) owns the callers: a key message inserts
 * its row, an arriving encrypted MMS part reads its key by file id,
 * and the row is KEPT for decrypt-on-view — the key is needed every
 * time the user opens the media, so it is never deleted on decrypt.
 * Only ORPHANED keys expire: a key older than 24 h whose MMS part
 * never arrived (no attachment carries the relay-media filename)
 * is deleted by the prune. The sender never touches this table —
 * it keeps no copy of the per-file keys it sends (it holds the
 * plaintext file itself).
 */
@Dao
interface E2eMediaKeyDao {

    /**
     * Stores a received key. IGNORE on conflict — first key wins:
     * a duplicate key message for the same file id (a sender
     * retry, or a replayed fragment set) must not overwrite the
     * first key, which is the one the ciphertext part was
     * encrypted under. Returns the row id, or -1 on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(key: E2eMediaKeyEntity): Long

    /** The stored key for one file id, or null when no key arrived yet. */
    @Query("SELECT * FROM e2e_media_keys WHERE file_id = :fileId LIMIT 1")
    suspend fun findByFileId(fileId: String): E2eMediaKeyEntity?

    /**
     * Live view of one file's key row (null = no key yet). The
     * thread UI collects it: a pending ciphertext part flips to
     * decryptable the moment its key arrives, without reopening
     * the thread — and the desktop push re-reads on emission.
     */
    @Query("SELECT * FROM e2e_media_keys WHERE file_id = :fileId LIMIT 1")
    fun observeByFileId(fileId: String): Flow<E2eMediaKeyEntity?>

    /** Deletes a key row (its file was decrypted — the key has served). */
    @Query("DELETE FROM e2e_media_keys WHERE file_id = :fileId")
    suspend fun deleteByFileId(fileId: String)

    /**
     * Prunes orphan keys: rows whose MMS part never arrived (a
     * lost MMS, a sender that fell back to plain after the key
     * went out). The caller passes the age cutoff; returns the
     * number of rows deleted.
     */
    /**
     * File ids of keys older than [cutoff] (epoch ms). The prune
     * checks each for an orphaned part (no attachment carries the
     * relay-media filename) and deletes only the orphans — a key
     * for an existing message is kept for decrypt-on-view.
     */
    @Query("SELECT file_id FROM e2e_media_keys WHERE received_at < :cutoff")
    suspend fun findFileIdsOlderThan(cutoff: Long): List<String>
}
