package com.filestech.sms.ui.screens.conversations

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.settings.SortMode

/**
 * v1.28.8 (issue #17) — ordre de la liste : les conversations ÉPINGLÉES d'abord, dans tous les tris.
 *
 * En v1.6.1 (audit QUAL-14), `DATE` avait cessé de remonter les épinglées pour se distinguer de
 * `PINNED_FIRST`. Conséquence signalée par un utilisateur : épingler ne faisait RIEN de visible
 * dans le tri par défaut. L'épinglage redevient ce qu'il promet, et `PINNED_FIRST`, devenu
 * identique à `DATE`, est retiré. La requête de la liste triait déjà ainsi
 * (`ORDER BY pinned DESC, last_message_at DESC`).
 *
 * `sortedWith` est stable : à égalité, l'ordre reçu est conservé.
 */
internal fun trierConversations(rows: List<Conversation>, mode: SortMode): List<Conversation> {
    val epingleesDabord = compareByDescending<Conversation> { it.pinned }
    val ordre = when (mode) {
        SortMode.DATE -> epingleesDabord.thenByDescending { it.lastMessageAt }
        SortMode.UNREAD_FIRST ->
            epingleesDabord
                .thenByDescending { it.unreadCount > 0 }
                .thenByDescending { it.lastMessageAt }
    }
    return rows.sortedWith(ordre)
}

/**
 * Notes — the merged list sort: the same order as
 * [trierConversations], over the merged list ([ConversationListItem]).
 * Pinned first (notes and conversations together), then — under
 * `UNREAD_FIRST` — unread first (notes are never unread, so they
 * sit with the read rows), then by [ConversationListItem.sortKey]
 * descending. `sortedWith` is stable: on a tie the order received
 * (conversations first, notes after) is kept.
 */
internal fun trierListItems(
    rows: List<ConversationListItem>,
    mode: SortMode,
): List<ConversationListItem> {
    val epingleesDabord = compareByDescending<ConversationListItem> { it.pinned }
    val ordre = when (mode) {
        SortMode.DATE -> epingleesDabord.thenByDescending { it.sortKey }
        SortMode.UNREAD_FIRST ->
            epingleesDabord
                .thenByDescending { it.isUnread }
                .thenByDescending { it.sortKey }
    }
    return rows.sortedWith(ordre)
}
