package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.domain.model.Message

/**
 * One in-thread E2E notice, as the thread UI sees it (design §6.4).
 *
 * The wording lives in string resources (keyed by [kind]), so a notice
 * renders in the user's current language; this model carries only the
 * identity, the kind and the date — the date is its position in the
 * thread. Mapped from [com.filestech.sms.data.local.db.entity
 * .E2eNoticeEntity] by the thread ViewModel.
 */
data class E2eNoticeUi(
    /** The notice row id (the LazyColumn item key). */
    val id: Long,
    /** Which notice this is (wording comes from string resources). */
    val kind: E2eNoticeKind,
    /** Epoch ms the notice was recorded — its position in the thread. */
    val date: Long,
)

/**
 * Where a conversation's notices land among its messages.
 *
 * [before] is parallel to the message list: `before[i]` renders above
 * message `i`, inside that message's LazyColumn item (the same slot the
 * day separator uses). [trailing] holds the notices newer than the last
 * message — they render after it, in a single item at the end of the
 * list. Notices are never LazyColumn items of their own: the thread's
 * scroll anchors, search jumps and sticky date all assume the global
 * LazyColumn index IS the message index, and interleaved items would
 * shift every index after the first notice.
 */
data class E2eNoticePlacement(
    /** Notices rendering above each message, parallel to the message list. */
    val before: List<List<E2eNoticeUi>>,
    /** Notices newer than the last message: they render after it. */
    val trailing: List<E2eNoticeUi>,
)

/**
 * The E2E notices rendering above one message item, with the contact
 * name their wording needs. One parameter for the thread item (which
 * is near the Detekt parameter cap): the per-message list plus the
 * shared name travel together.
 */
data class E2eNoticesForItem(
    /** The notices dated at or before this message. */
    val notices: List<E2eNoticeUi>,
    /** The contact name the notice wording uses (title or number). */
    val contactName: String,
)

/**
 * Places a conversation's E2E notices among its loaded messages (design
 * §6.4): a notice renders above the first message dated at or after it,
 * or after the last message when it is newer than everything loaded.
 *
 * The merge is deliberately window-friendly, so it never disturbs the
 * bounded-window paging: a notice older than the first loaded message
 * renders above it (at the top of the window) instead of vanishing, and
 * settles into its true position as older pages load; a notice newer
 * than the window's last message renders after it. [messages] must be
 * in display order (ascending by date, as the window query returns);
 * [notices] are sorted by date here, so the caller need not sort them.
 *
 * Pure Kotlin, no Android APIs — fully unit testable.
 */
object E2eThreadNotices {

    fun place(
        messages: List<Message>,
        notices: List<E2eNoticeUi>,
    ): E2eNoticePlacement {
        val before = Array(messages.size) { mutableListOf<E2eNoticeUi>() }
        val trailing = mutableListOf<E2eNoticeUi>()
        var messageIndex = 0
        for (notice in notices.sortedBy { it.date }) {
            while (messageIndex < messages.size && messages[messageIndex].date < notice.date) {
                messageIndex++
            }
            if (messageIndex < messages.size) {
                before[messageIndex].add(notice)
            } else {
                trailing.add(notice)
            }
        }
        return E2eNoticePlacement(
            before = before.map { it.toList() },
            trailing = trailing,
        )
    }
}
