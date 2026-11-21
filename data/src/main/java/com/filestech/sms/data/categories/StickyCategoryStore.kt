package com.filestech.sms.data.categories

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.categories.MessageClassifier
import com.filestech.sms.domain.model.PhoneAddress
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 35 — the sticky category store (schema v20, column
 * `conversations.sticky_category`).
 *
 * A stranger's conversation keeps the category its latest incoming
 * message earned at receive time until the user replies: classification
 * used to follow the latest message only, so a plain follow-up ("Touching
 * base again…") re-filed a spam thread as Personal, and the tab list
 * classified from the 240-character preview, cutting off opt-out footers
 * that only appear later in the body. The sticky value is computed from
 * the FULL body at receive time ([onIncomingMessage]) and cleared by any
 * outgoing message in the thread ([onOutgoingMessage], plus the
 * `resetUnread` arm of `ConversationMirror.touchConversation`).
 *
 * Contacts and groups never store: live classification handles them (a
 * contact's message is Personal by definition; a group's promo keywords
 * still apply at render). The render rule —
 * [MessageClassifier.effectiveCategory] — only honors the stored value
 * for a stranger 1:1 evaluated with the CURRENT contact status, so a
 * sender added to contacts later un-sticks.
 *
 * [backfill] is the one-shot upgrade pass: it files the conversations
 * the spammers already reached, from the full bodies of their recent
 * incoming messages, so they move to their tab without waiting for
 * another message.
 */
@Singleton
class StickyCategoryStore @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {

    /**
     * Receive-time update for one incoming message (full body [body],
     * sender [sender]). Callers invoke this BEFORE the conversation
     * touch, so `lastMessageAt` is still the pre-touch value the
     * newest-wins guard below compares against.
     */
    suspend fun onIncomingMessage(
        conversationId: Long,
        body: String,
        sender: PhoneAddress,
        date: Long,
    ) {
        val row = conversationDao.findById(conversationId) ?: return
        // A `;` in the CSV is what marks a group (the DAO's own
        // convention: `snapshotOneToOneConversations` filters on
        // `addresses_csv NOT LIKE '%;%'`).
        if (row.addressesCsv.contains(';')) return
        // A resolved display name is what marks a contact (the same
        // marker `classifyConversation` uses).
        if (row.displayName != null) return
        // Newest-wins, like the preview guard in `touchConversation`: a
        // late-arriving old message must not re-file a thread the user
        // already replied to.
        if (date < row.lastMessageAt) return
        val fresh = MessageClassifier.classify(
            body = body,
            sender = sender,
            isContact = false,
            isGroup = false,
        )
        val next = MessageClassifier.nextStickyCategory(row.stickyCategory, fresh)
        if (next != row.stickyCategory) {
            conversationDao.setStickyCategory(conversationId, next)
        }
    }

    /**
     * Any outgoing message in the thread un-sticks it: after the user
     * replies, live classification from the latest message applies (the
     * user's own text files Personal). The hub-mode send paths clear via
     * the `resetUnread` arm of `touchConversation` instead; this is for
     * the client mirror, where the touch must not reset the hub-owned
     * unread count.
     */
    suspend fun onOutgoingMessage(conversationId: Long) {
        conversationDao.setStickyCategory(conversationId, null)
    }

    /**
     * One-shot backfill, run once at upgrade from `StartupMigrations`.
     * For each 1:1 stranger conversation, looks at the incoming messages
     * since the last outgoing message (cap: the last
     * [BACKFILL_INCOMING_LIMIT]), classified on their full bodies. If
     * the latest incoming is OTP or Transactions, stores it; else if ANY
     * is Promotional, stores PROMOTIONAL. Returns how many conversations
     * were filed. Bounded (one capped query per conversation), idempotent
     * (a conversation that already has a sticky value is skipped), and
     * safe to re-run — the flag in `AdvancedSettings` is the once-guard.
     */
    suspend fun backfill(): Int {
        // Contacts never store, and a conversation that already has a
        // sticky value was filed by the receive-time hook (or a previous
        // backfill run) — filtering up front keeps the loop to one call
        // per candidate and the backfill idempotent.
        val conversations = conversationDao.snapshotOneToOneConversations()
            .filter { it.displayName == null && it.stickyCategory == null }
        var stored = 0
        for (conversation in conversations) {
            if (backfillOne(conversation)) stored++
        }
        Timber.i("StickyCategoryStore.backfill: %d conversation(s) filed", stored)
        return stored
    }

    /**
     * Files one conversation from its recent incoming messages (see
     * [backfill]); returns `true` when a sticky value was stored.
     */
    private suspend fun backfillOne(conversation: ConversationEntity): Boolean {
        val recent = messageDao.findRecentIncomingSinceLastOutgoing(
            conversation.id,
            BACKFILL_INCOMING_LIMIT,
        )
        if (recent.isEmpty()) return false
        val sender = PhoneAddress.list(conversation.addressesCsv).firstOrNull() ?: return false
        val categories = recent.map { message ->
            MessageClassifier.classify(
                body = message.body,
                sender = sender,
                isContact = false,
                isGroup = false,
            )
        }
        val latest = categories.first()
        val value = when {
            latest == MessageCategory.OTP || latest == MessageCategory.TRANSACTIONS -> latest
            categories.any { it == MessageCategory.PROMOTIONAL } -> MessageCategory.PROMOTIONAL
            else -> null
        } ?: return false
        conversationDao.setStickyCategory(conversation.id, value)
        return true
    }

    private companion object {
        /** Backfill window: the last 10 incoming messages since the last outgoing. */
        const val BACKFILL_INCOMING_LIMIT = 10
    }
}
