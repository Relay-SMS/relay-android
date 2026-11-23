package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eMediaKeyDao
import com.filestech.sms.data.local.db.entity.E2eMediaKeyEntity
import com.filestech.sms.domain.purge.MS_PER_DAY
import com.filestech.sms.e2e.E2eMediaKeyMessage.MediaKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 48 — the received-media-key store: the thin repository seam
 * over [E2eMediaKeyDao], mirroring [E2ePeerRepository] /
 * [E2eFragmentStore]. A key message can arrive BEFORE its MMS part
 * (SMS and MMS deliver independently), so the receiver stores the
 * key here and the part decrypts when it lands — or the part waits
 * pending until the key arrives.
 *
 * The store maps between the wire/domain [MediaKey] and the Room
 * entity at this boundary, so no caller touches entity field
 * names. Key bytes are base64 in the table (SQLCipher encrypts
 * the whole database at rest — no extra pickle wrap, per the
 * entity's KDoc).
 *
 * Key lifecycle (design §3, round-48 plan §4.5):
 * - Insert is IGNORE — first key wins: a re-sent key message for
 *   the same file id must not overwrite the first key, which is
 *   the one the ciphertext part was encrypted under.
 * - Keys are NOT deleted after a decrypt: decrypt-on-view needs
 *   the key for every view (thread, gallery, desktop sync), so
 *   the row lives as long as the message does. (The DAO KDoc's
 *   "deleted once decrypted" describes a decrypt-on-arrival flow
 *   the receiver does not implement — ciphertext stays at rest.)
 * - Keys DO expire when orphaned: the prune drops rows older than
 *   [MEDIA_KEY_TTL_MS] whose MMS part never arrived (no attachment
 *   carries the relay-media filename), and then fails the pending
 *   messages left without a key. A key for an existing message is
 *   kept indefinitely. A key that arrives after its message was
 *   failed does not resurrect it.
 *
 * [keyArrivals] is the live signal for the sync engine: the
 * desktop withholds a pending message until its key lands, and
 * the engine re-pushes the conversation on each arrival (the
 * message table itself never changes on a key arrival, so the
 * conversation watch would not fire on its own). `replay = 1`
 * closes the race between the snapshot read and the collector
 * start — a redundant re-push is a no-op at the source (the
 * message is no longer withheld). The thread UI does NOT use
 * this flow: it collects [observe] per file (a Room Flow, the
 * source of truth for the pending → decryptable flip).
 */
@Singleton
class E2eMediaKeyStore @Inject constructor(
    private val dao: E2eMediaKeyDao,
) {

    private val _keyArrivals = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)

    /** The file ids of newly stored keys, in arrival order. */
    val keyArrivals: Flow<String> = _keyArrivals.asSharedFlow()

    /**
     * Stores a received key. Returns true when the row was
     * inserted, false when a key for this file id already
     * existed (first key wins — the duplicate is dropped, not
     * an error). A fresh insert emits on [keyArrivals].
     */
    suspend fun store(key: MediaKey, now: Long = System.currentTimeMillis()): Boolean {
        val inserted = dao.insert(
            E2eMediaKeyEntity(
                fileId = key.id,
                keyB64 = Base64.getEncoder().encodeToString(key.key),
                mime = key.mime,
                size = key.sizeBytes,
                sha256 = key.sha256Hex,
                name = key.fileName,
                receivedAt = now,
            ),
        ) != -1L
        if (inserted) _keyArrivals.tryEmit(key.id)
        return inserted
    }

    /** The stored key for one file id, or null when no key arrived yet. */
    suspend fun get(fileId: String): MediaKey? = dao.findByFileId(fileId)?.toMediaKey()

    /**
     * Live view of one file's key row (null = no key yet). The
     * thread UI collects it: a pending part flips to decryptable
     * the moment its key arrives, without reopening the thread.
     */
    fun observe(fileId: String): Flow<MediaKey?> =
        dao.observeByFileId(fileId).map { it?.toMediaKey() }

    /**
     * File ids of keys older than [cutoff] (epoch ms) — the prune
     * checks each for an orphaned part before deleting it.
     */
    suspend fun listFileIdsOlderThan(cutoff: Long): List<String> =
        dao.findFileIdsOlderThan(cutoff)

    /** Deletes one key row (its message was deleted — the key has served). */
    suspend fun delete(fileId: String) {
        dao.deleteByFileId(fileId)
    }

    companion object {
        /** A media key for a message that never arrived (orphaned) expires after 24 hours. */
        const val MEDIA_KEY_TTL_MS: Long = MS_PER_DAY
    }
}

/**
 * Entity → domain key. Defensive: a row whose base64 does not
 * decode to exactly 32 bytes reads as no key — stored rows are
 * written from validated [MediaKey]s, so this only fires on a
 * corrupt database, never on a live key.
 */
private fun E2eMediaKeyEntity.toMediaKey(): MediaKey? {
    val keyBytes = runCatching { Base64.getDecoder().decode(keyB64) }.getOrNull()
        ?.takeIf { it.size == E2eMediaKeyMessage.KEY_BYTES }
        ?: return null
    return runCatching {
        MediaKey(
            id = fileId,
            key = keyBytes,
            mime = mime,
            sizeBytes = size,
            sha256Hex = sha256,
            fileName = name,
        )
    }.getOrNull()
}
