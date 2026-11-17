package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.SyncedConversationResult
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — the narrow surface the sync engine needs from the
 * message store: upsert synced rows, patch a live-mutable message,
 * delete a synced conversation, prune the snapshot. The engine talks
 * wire types in and gets local ids out; the mapping to the data
 * module's parsed DTOs happens here ([WireMappers]), so the engine
 * never sees a Long id it did not ask for.
 *
 * The implementation delegates the upserts to
 * [ConversationMirror] (the single write path — seam S1); prune /
 * delete / patch go straight to the DAOs, since they need no
 * conversation touch.
 */
interface ClientMirror {

    /**
     * Upserts one synced conversation. Null when the wire id does
     * not parse — the row is dropped, never stored under a wrong key.
     */
    suspend fun upsertSyncedConversation(wire: SyncedConversation): SyncedConversationResult?

    /**
     * Upserts one synced message. Null when the wire id does not
     * parse.
     */
    suspend fun upsertSyncedMessage(wire: SyncedMessage): SyncedMessageResult?

    /**
     * Applies a partial `message_updated`: only the fields present
     * in the frame change. A removed reaction rides as
     * `reaction_removed` (a null `reaction` is omitted on encode, so
     * it cannot mean "removed"). No-op when the message is unknown
     * (not synced yet) or the id does not parse.
     */
    suspend fun applyMessageUpdated(update: SyncProtocol.MessageUpdated)

    /**
     * Points one reply at its target after the target landed late
     * (the stream is newest-first, so a reply routinely arrives
     * before the message it quotes). No-op when either row is
     * unknown, or the reply already points at the target.
     */
    suspend fun resolveReplyTarget(localMessageId: Long, localTargetId: Long)

    /**
     * Deletes one synced conversation by hub id, with its messages
     * (FK cascade) and its walk frontier. No-op when unknown.
     */
    suspend fun deleteSyncedConversation(hubConversationId: Long)

    /**
     * Snapshot prune: deletes every locally mirrored conversation
     * whose hub id is absent from [keepHubIds] (the snapshot just
     * received — deletions included, there is no tombstone feed).
     * Returns the number of conversations pruned.
     */
    suspend fun pruneSyncedConversations(keepHubIds: Set<Long>): Int
}

/**
 * Client mode — the [ClientMirror] backed by the real store:
 * [ConversationMirror] for the upserts, the DAOs for prune /
 * delete / patch.
 */
@Singleton
class ConversationMirrorAdapter @Inject constructor(
    private val mirror: ConversationMirror,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val syncStateStore: SyncStateStore,
) : ClientMirror {

    override suspend fun upsertSyncedConversation(
        wire: SyncedConversation,
    ): SyncedConversationResult? {
        val data = wire.toData() ?: return null
        val result = mirror.upsertSyncedConversation(data)
        // Every synced conversation gets a frontier row (incomplete
        // by default) so gap-fill can find it — including ones whose
        // stream batches never arrived.
        syncStateStore.ensureConversationState(data.hubConversationId)
        return result
    }

    override suspend fun upsertSyncedMessage(wire: SyncedMessage): SyncedMessageResult? {
        val data = wire.toData() ?: return null
        return mirror.upsertSyncedMessage(data)
    }

    override suspend fun applyMessageUpdated(update: SyncProtocol.MessageUpdated) {
        val hubId = parseHubId(update.messageId) ?: return
        val existing = messageDao.findByHubMessageId(hubId) ?: return
        val direction = existing.direction
        messageDao.update(
            existing.copy(
                status = update.status?.let { parseMessageStatus(it, direction) }
                    ?: existing.status,
                reactionEmoji = when {
                    update.reactionRemoved -> null
                    update.reaction != null -> update.reaction
                    else -> existing.reactionEmoji
                },
                read = update.read ?: existing.read,
                starred = update.starred ?: existing.starred,
            ),
        )
    }

    override suspend fun resolveReplyTarget(localMessageId: Long, localTargetId: Long) {
        val existing = messageDao.findById(localMessageId) ?: return
        if (existing.replyToMessageId == localTargetId) return
        messageDao.update(existing.copy(replyToMessageId = localTargetId))
    }

    override suspend fun deleteSyncedConversation(hubConversationId: Long) {
        val row = conversationDao.findByHubConversationId(hubConversationId) ?: return
        // Messages + attachments follow by FK cascade; the pin lives
        // on the row itself, so it goes with it.
        conversationDao.delete(row.id)
        syncStateStore.deleteConversationState(hubConversationId)
    }

    override suspend fun pruneSyncedConversations(keepHubIds: Set<Long>): Int {
        val local = conversationDao.hubConversationIds()
        var pruned = 0
        for (hubId in local) {
            if (hubId !in keepHubIds) {
                deleteSyncedConversation(hubId)
                pruned++
            }
        }
        return pruned
    }
}
