package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eFragmentDao
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity
import com.filestech.sms.domain.purge.MS_PER_DAY
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-flight ciphertext fragment storage (design §3.5).
 *
 * Fragments arrive as single-part SMS and are stored here instead of as messages.
 * When the final fragment of a set arrives, [reassemble] concatenates the chunks in
 * index order into the base64 of the whole payload — the receive path then decodes
 * and decrypts it — and [deleteSet] drops the rows. A re-sent fragment set carries
 * the same msgId, so retries dedupe on the composite primary key.
 *
 * A set is COMPLETE only when every index `0..total-1` is present: the count check
 * alone would accept a set whose middle fragment was lost and whose last fragment
 * claims a smaller total. Incomplete sets expire after [FRAGMENT_TTL_MS], pruned by
 * the periodic worker ([pruneExpired]).
 */
@Singleton
class E2eFragmentStore @Inject constructor(
    private val fragmentDao: E2eFragmentDao,
) {

    /**
     * Stores one fragment. Returns true when the fragment was stored, false when
     * this (sender, msgId, index) was already stored (a re-sent fragment).
     */
    suspend fun insert(
        sender: String,
        msgId: String,
        idx: Int,
        total: Int,
        chunk: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val rowId = fragmentDao.insert(
            E2eFragmentEntity(
                sender = sender,
                msgId = msgId,
                idx = idx,
                total = total,
                chunk = chunk,
                receivedAt = now,
            ),
        )
        return rowId != -1L
    }

    /**
     * The set's fragments in index order, or null when the set is incomplete
     * (still awaiting fragments) or unknown.
     */
    suspend fun completeSet(sender: String, msgId: String): List<E2eFragmentEntity>? {
        val fragments = fragmentDao.fragmentsFor(sender, msgId)
        if (fragments.isEmpty()) return null
        val total = fragments.first().total
        if (fragments.size != total) return null
        if (fragments.map { it.idx } != (0 until total).toList()) return null
        return fragments
    }

    /**
     * The reassembled payload (base64 of the whole encrypted payload), or null when
     * the set is incomplete. The caller decodes the base64 and decrypts; on success
     * it calls [deleteSet].
     */
    suspend fun reassemble(sender: String, msgId: String): String? =
        completeSet(sender, msgId)?.joinToString(separator = "") { it.chunk }

    /** Deletes a whole fragment set — after reassembly, or when the set is abandoned. */
    suspend fun deleteSet(sender: String, msgId: String): Int =
        fragmentDao.deleteSet(sender, msgId)

    /**
     * Deletes fragments older than [FRAGMENT_TTL_MS]. Wired into the periodic worker
     * ([com.filestech.sms.system.scheduler.TelephonySyncWorker]); returns the number
     * of rows pruned.
     */
    suspend fun pruneExpired(now: Long = System.currentTimeMillis()): Int =
        fragmentDao.deleteOlderThan(now - FRAGMENT_TTL_MS)

    companion object {
        /** A fragment set that never completes is dropped after 24 hours (design §3.5). */
        const val FRAGMENT_TTL_MS: Long = MS_PER_DAY
    }
}
