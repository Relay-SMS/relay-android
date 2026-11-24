package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-thread E2E notice storage (design §6.4).
 *
 * Notices are centered system rows, not bubbles: "Messages are now end-to-end
 * encrypted.", "NAME's security code changed. …". The row stores the KIND, not its
 * wording — the wording lives in string resources, so a notice renders in the
 * user's current language. Notices are never sent to the peer
 * and never backed up — but they DO sync to the user's own
 * linked devices over the encrypted device link (round 42), so a
 * desktop thread shows the same system rows as the phone thread.
 *
 * The ACTIVATED notice shows whenever the thread's latest E2E status is
 * not already "activated": on first establishment, and on
 * re-establishment after an "Encryption ended" (DEACTIVATED) notice —
 * but not on a re-establishment with the same keys while the thread
 * already shows the encryption as active (a key change gets its own
 * KEY_CHANGED notice instead). [recordActivated] owns that rule;
 * [recordOnce] stays the generic once-per-conversation guard for kinds
 * that never repeat.
 */
@Singleton
class E2eNoticeStore @Inject constructor(
    private val noticeDao: E2eNoticeDao,
) {

    /**
     * Records a notice, returning its row id. [subject] is the
     * canonical address of the member the notice is about (round
     * 49 — the group key-change / deactivation notices name their
     * member); null for notices about the thread itself.
     */
    suspend fun record(
        conversationId: Long,
        kind: E2eNoticeKind,
        now: Long = System.currentTimeMillis(),
        subject: String? = null,
    ): Long =
        noticeDao.insert(
            E2eNoticeEntity(conversationId = conversationId, kind = kind, date = now, subject = subject),
        )

    /**
     * Records the ACTIVATED notice when the thread's latest E2E status is
     * not already "activated" (round 47): on first establishment, and on
     * re-establishment after a DEACTIVATED notice — but not on a
     * re-establishment while the thread already shows the encryption as
     * active. Returns true when the notice was recorded.
     *
     * The old rule ([recordOnce]) suppressed every ACTIVATED after the
     * first, so after a teardown + re-establishment the thread's latest
     * pill still said "Encryption ended" while encryption was working
     * (device-proven 2026-11-23). The duplicate-bundle guard
     * ([E2eHandshakeHandler] first-reply-wins) already keeps a
     * re-processed bundle from reaching this, so no second guard is
     * needed here. Positions in the date-ordered list decide, not the
     * dates themselves — the render order is the thread's story.
     */
    suspend fun recordActivated(
        conversationId: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val notices = noticesForConversation(conversationId)
        val lastActivated = notices.indexOfLast { it.kind == E2eNoticeKind.ACTIVATED }
        val lastDeactivated = notices.indexOfLast { it.kind == E2eNoticeKind.DEACTIVATED }
        if (lastActivated >= 0 && lastActivated > lastDeactivated) return false
        record(conversationId, E2eNoticeKind.ACTIVATED, now)
        return true
    }

    /**
     * Thread-open backfill (round 48): repairs the notice story for a
     * conversation that re-established encryption under round 46, when
     * [recordActivated] was still [recordOnce] and refused a second
     * ACTIVATED. Round 47's [recordActivated] only runs on a NEW
     * bundle / encrypted-message receipt, so such a conversation kept
     * "Encryption ended" as its latest pill while encryption was
     * working — nothing ever recorded the second activation.
     *
     * Inserts one ACTIVATED notice — dated at the peer's [E2ePeerEntity.updatedAt]
     * (the latest ENCRYPTED flip, which is after the DEACTIVATED notice,
     * so the new pill sorts below it) — when and only when the peer is
     * ENCRYPTED and the conversation's latest notice is DEACTIVATED.
     * Returns true when the notice was recorded.
     *
     * Self-limiting: after the insert the latest notice is ACTIVATED,
     * so a later thread open finds nothing to repair. A peer that is
     * not ENCRYPTED never gets an ACTIVATED pill from here, and a
     * conversation with no notices at all is left alone — a fresh
     * ENCRYPTED conversation gets its ACTIVATED from the normal bundle
     * path ([recordActivated]); this only repairs the
     * DEACTIVATED-then-ENCRYPTED sequence. A missing or zero
     * [E2ePeerEntity.updatedAt] (should not happen for an ENCRYPTED
     * peer) falls back to [now].
     *
     * The caller passes the peer row it already read
     * ([E2ePeerRepository.getPeer]) — this store owns notices, not
     * peers — and calls this once per thread open (see
     * ThreadViewModel's `init`): one latest-notice query plus the
     * conditional insert, never on every message or recomposition.
     */
    suspend fun backfillActivatedIfNeeded(
        conversationId: Long,
        peer: E2ePeerEntity?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (peer == null || peer.state != E2ePeerState.ENCRYPTED) return false
        val latest = noticeDao.latestNotice(conversationId) ?: return false
        if (latest.kind != E2eNoticeKind.DEACTIVATED) return false
        val date = peer.updatedAt.takeIf { it > 0 } ?: now
        record(conversationId, E2eNoticeKind.ACTIVATED, date)
        return true
    }

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

    /**
     * E2E sync (round 42) — every notice, oldest first: the
     * device-link snapshot source (the in-thread notice set).
     * One-shot, for the snapshot paths.
     */
    suspend fun allNotices(): List<E2eNoticeEntity> = noticeDao.allNotices()

    /**
     * E2E sync (round 42) — the live version of [allNotices]:
     * re-emits whenever any `e2e_notices` row changes, so a
     * notice recorded while a desktop is linked re-sends the full
     * set to every linked device.
     */
    fun observeAllNotices(): Flow<List<E2eNoticeEntity>> = noticeDao.observeAll()

    /** Deletes a conversation's notices (conversation deleted). */
    suspend fun deleteForConversation(conversationId: Long): Int =
        noticeDao.deleteForConversation(conversationId)
}
