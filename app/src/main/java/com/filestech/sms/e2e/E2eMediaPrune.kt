package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.mms.RelayMediaFilename
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 48 — the 24-hour media-key expiry, run by the periodic
 * worker ([com.filestech.sms.system.scheduler.TelephonySyncWorker],
 * every 12 h, hub only — keys live on the hub; client mode has
 * none). A media key that never arrived, or a ciphertext part
 * whose key never arrived, cannot stay pending forever: the
 * thread would show "Waiting to decrypt…" on a message that will
 * never decrypt.
 *
 * ORDER MATTERS — keys first, then messages:
 * 1. Delete ORPHANED key rows older than [E2eMediaKeyStore.MEDIA_KEY_TTL_MS]:
 *    a key whose MMS part never arrived (no attachment carries the
 *    relay-media filename). A key for an EXISTING message is kept
 *    indefinitely — decrypt-on-view needs it every time the user
 *    opens the media, so deleting it would break re-viewing past
 *    24 h. (Coordinator fix: the first version deleted every old
 *    key, which made encrypted media viewable for only 24 h after
 *    its key arrived.)
 * 2. Fail every pending media message older than 24 h with no key
 *    row left. A message WITH a surviving key is untouched, even
 *    when old (its key arrived late — the part waited, the key
 *    came, the user can still open it).
 *
 * A key that arrives AFTER its message was failed does not
 * resurrect it (no retry path for media — the text path's
 * `retryDecryptFailed` skips media rows, whose `ciphertext`
 * column is null). No re-probe in either step: a media key that
 * never arrived will not arrive on a re-probe.
 */
@Singleton
class E2eMediaPrune @Inject constructor(
    private val mediaKeys: E2eMediaKeyStore,
    private val attachmentDao: AttachmentDao,
    private val messageDao: MessageDao,
) {

    /** The prune outcome, for the worker's log line. */
    data class Result(
        /** Expired key rows deleted. */
        val keysDeleted: Int,
        /** Pending messages marked failed (no key, older than 24 h). */
        val messagesFailed: Int,
    )

    suspend fun prune(now: Long = System.currentTimeMillis()): Result {
        val cutoff = now - E2eMediaKeyStore.MEDIA_KEY_TTL_MS
        // Keys first, orphaned only: an old key is deleted only
        // when its MMS part never arrived (no attachment carries
        // the relay-media filename). A key for an existing
        // message is kept — decrypt-on-view needs it every time.
        var keysDeleted = 0
        for (fileId in mediaKeys.listFileIdsOlderThan(cutoff)) {
            val fileName = RelayMediaFilename.fileNameFor(fileId)
            if (attachmentDao.findByFileName(fileName) == null) {
                mediaKeys.delete(fileId)
                keysDeleted++
            }
        }
        var messagesFailed = 0
        val failedIds = mutableSetOf<Long>()
        val candidates = attachmentDao.findPendingMediaMessages(cutoff)
        for (candidate in candidates) {
            // A message with two relay-media parts yields two
            // rows — fail it once, and count it once. The id
            // joins the set only when the message is actually
            // failed: a message with one keyed part and one
            // keyless part must still fail (on the keyless row),
            // whichever row the join returns first.
            if (candidate.messageId !in failedIds) {
                val fileId = RelayMediaFilename.parse(candidate.fileName)
                if (fileId != null && mediaKeys.get(fileId) == null) {
                    failedIds.add(candidate.messageId)
                    runCatching { messageDao.markMediaDecryptFailed(candidate.messageId) }
                        .onSuccess { messagesFailed++ }
                        .onFailure {
                            Timber.w(it, "E2E media prune: could not fail message %d", candidate.messageId)
                        }
                }
            }
        }
        return Result(keysDeleted, messagesFailed)
    }
}
