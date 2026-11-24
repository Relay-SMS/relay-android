package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * E2E encryption (schema v22) — one in-thread encryption notice (design §6.4),
 * rendered as a centered system row interleaved by date in the thread. The row
 * stores the notice KIND, not its wording — the wording lives in string resources,
 * so a notice renders in the user's current language. Round 49 — the row also
 * stores the SUBJECT (the canonical address of the member the notice is about):
 * a group notice must name its member ("NAME's security code changed"), and the
 * thread alone cannot say who. Null for notices about the thread itself
 * (ACTIVATED, DEACTIVATED, GROUP_ACTIVATED).
 *
 * Deliberately no foreign key to `conversations`: a notice is local derived state,
 * and the conversation eraser deletes notices explicitly
 * ([com.filestech.sms.data.local.db.dao.E2eNoticeDao.deleteForConversation]).
 * Notices are never sent to the peer and never backed up (a restore re-derives them
 * from the peer state, or they simply don't reappear — acceptable per the design).
 * They DO sync to the user's own linked devices over the encrypted device link
 * (round 42), so a desktop thread shows the same system rows as the phone thread.
 */
@Entity(tableName = "e2e_notices")
data class E2eNoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The conversation this notice belongs to. */
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    /** Which notice this is (wording comes from string resources). */
    @ColumnInfo(name = "kind") val kind: E2eNoticeKind,
    /** Epoch ms the notice was recorded — its position in the thread. */
    @ColumnInfo(name = "date") val date: Long,
    /**
     * Round 49 (schema v24) — the canonical address of the member
     * this notice is about, or null when the notice is about the
     * thread itself. The UI resolves the name from the address
     * (contacts first, number as fallback) for the wording.
     */
    @ColumnInfo(name = "subject") val subject: String? = null,
)
