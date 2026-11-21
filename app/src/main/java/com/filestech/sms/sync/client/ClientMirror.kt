package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.SyncedConversationResult
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.e2e.E2ePeerRepository
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
 *
 * E2E (design §9.4): the client never probes, never holds keys,
 * never encrypts — it only stores and renders flags. The message
 * flags land on the Room mirror via [ConversationMirror]; the
 * conversation flag is DERIVED into the local peer state instead
 * (there is no `encrypted` column on the conversation row): an
 * `encrypted` 1:1 frame records its peer as ENCRYPTED, with no keys
 * attached, so the thread UI reads the same peer state on the
 * client as on the hub. A derived row is state only — it can never
 * encrypt (there is no session behind it) and the client never
 * probes from it.
 */
@Singleton
class ConversationMirrorAdapter @Inject constructor(
    private val mirror: ConversationMirror,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val syncStateStore: SyncStateStore,
    // The E2E peer state, or null in tests (which never need a
    // derived peer row — the conversation flag is then stored
    // nowhere, as before).
    private val e2ePeerRepository: E2ePeerRepository? = null,
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
        // E2E (§9.4): derive the peer state from the synced flag, in
        // BOTH directions — 1:1 only (groups are never encrypted). An
        // encrypted frame records ENCRYPTED; a plain frame drops a
        // derived ENCRYPTED row back to UNKNOWN, so the list badge
        // clears when the hub's session lapses. Either way the row is
        // only written when it disagrees — a re-sync must not rewrite
        // it on every snapshot. The peer key is the normalized number,
        // like the hub's own peer rows. The row carries state only, no
        // keys: the client never encrypts, it only renders the badge.
        if (data.addresses.size == 1) {
            val peerRepository = e2ePeerRepository
            if (peerRepository != null) {
                val address = PhoneAddress.of(data.addresses.first()).normalized
                val current = peerRepository.state(address)
                if (data.encrypted && current != E2ePeerState.ENCRYPTED) {
                    peerRepository.setState(address, E2ePeerState.ENCRYPTED)
                } else if (!data.encrypted && current == E2ePeerState.ENCRYPTED) {
                    peerRepository.setState(address, E2ePeerState.UNKNOWN)
                }
            }
        }
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
