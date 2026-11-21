package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-thread E2E notice storage (design §6.4).
 *
 * Notices are centered system rows, not bubbles: "Messages are now end-to-end
 * encrypted.", "NAME's security code changed. …". The row stores the KIND, not its
 * wording — the wording lives in string resources, so a notice renders in the
 * user's current language. Notices are local-only: never sent, never synced, never
 * backed up.
 *
 * The ACTIVATED notice shows once per conversation per identity — not on every
 * re-establishment with the same keys. [recordOnce] is the once-per-conversation
 * guard; the per-identity part is the state machine's decision (it knows whether
 * the keys changed), which calls [record] directly when a new identity deserves a
 * fresh notice.
 */
@Singleton
class E2eNoticeStore @Inject constructor(
    private val noticeDao: E2eNoticeDao,
) {

    /** Records a notice, returning its row id. */
    suspend fun record(
        conversationId: Long,
        kind: E2eNoticeKind,
        now: Long = System.currentTimeMillis(),
    ): Long =
        noticeDao.insert(
            E2eNoticeEntity(conversationId = conversationId, kind = kind, date = now),
        )

    /**
     * Records a notice only when the conversation has none of this kind yet.
     * Returns true when the notice was recorded, false when one already existed.
     */
    suspend fun recordOnce(
        conversationId: Long,
        kind: E2eNoticeKind,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (hasNotice(conversationId, kind)) return false
        record(conversationId, kind, now)
        return true
    }

    /** Whether the conversation already has a notice of this kind. */
    suspend fun hasNotice(conversationId: Long, kind: E2eNoticeKind): Boolean =
        noticeDao.countByKind(conversationId, kind) > 0

    /** Every notice in a conversation, oldest first (the thread merge order). */
    suspend fun noticesForConversation(conversationId: Long): List<E2eNoticeEntity> =
        noticeDao.noticesForConversation(conversationId)

    /**
     * Live view of a conversation's notices, oldest first (the thread
     * merge order). The thread UI collects it: a notice recorded while
     * the thread is open appears without reopening the thread.
     */
    fun observeNotices(conversationId: Long): Flow<List<E2eNoticeEntity>> =
        noticeDao.observeForConversation(conversationId)

    /** Deletes a conversation's notices (conversation deleted). */
    suspend fun deleteForConversation(conversationId: Long): Int =
        noticeDao.deleteForConversation(conversationId)
}
