package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * E2E encryption (schema v22) — one in-thread encryption notice (design §6.4),
 * rendered as a centered system row interleaved by date in the thread. The row
 * stores the notice KIND, not its wording — the wording lives in string resources,
 * so a notice renders in the user's current language.
 *
 * Deliberately no foreign key to `conversations`: a notice is local derived state,
 * and the conversation eraser deletes notices explicitly
 * ([com.filestech.sms.data.local.db.dao.E2eNoticeDao.deleteForConversation]).
 * Notices are never synced and never backed up (a restore re-derives them from the
 * peer state, or they simply don't reappear — acceptable per the design).
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
)
