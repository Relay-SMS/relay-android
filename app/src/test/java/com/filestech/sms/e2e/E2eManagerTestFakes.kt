package com.filestech.sms.e2e

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.E2eAccountDao
import com.filestech.sms.data.local.db.dao.E2eFragmentDao
import com.filestech.sms.data.local.db.dao.E2eGroupDistributionDao
import com.filestech.sms.data.local.db.dao.E2eGroupKeyDao
import com.filestech.sms.data.local.db.dao.E2eMediaKeyDao
import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.dao.E2ePeerDao
import com.filestech.sms.data.local.db.dao.E2eSessionDao
import com.filestech.sms.data.local.db.entity.E2eAccountEntity
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity
import com.filestech.sms.data.local.db.entity.E2eGroupDistributionEntity
import com.filestech.sms.data.local.db.entity.E2eGroupKeyEntity
import com.filestech.sms.data.local.db.entity.E2eMediaKeyEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.local.db.entity.E2eSessionEntity
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.repository.BlockedNumberRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fakes shared by the E2E manager tests ([E2eProbeManagerTest],
 * [E2eHandshakeHandlerTest], [E2eReceiveManagerTest], [E2eSendManagerTest]).
 *
 * Stateful fakes, not MockK stubs: the managers read-then-write the same
 * rows across a call (probe → record → re-read attempts), and a fake
 * that holds the rows keeps those sequences consistent without a forest
 * of `coEvery` answers. Each fake mirrors the Room semantics its DAO
 * declares (IGNORE on conflict, targeted UPDATEs, ordering).
 */

/** A fixed pickle key, copied fresh per call — the manager wipes the copy after use. */
internal val testPickleKey: ByteArray = ByteArray(32) { (it + 3).toByte() }

internal fun fakeKeyManager(): E2eKeyManager {
    val manager = mockk<E2eKeyManager>()
    every { manager.getOrCreatePickleKey() } answers { testPickleKey.copyOf() }
    return manager
}

/** `dagger.Lazy` over a fixed value (the managers take their crypto collaborators lazily). */
internal fun <T : Any> lazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }

internal class FakeE2eAccountDao : E2eAccountDao {
    var stored: E2eAccountEntity? = null

    override suspend fun get(): E2eAccountEntity? = stored
    override suspend fun upsert(account: E2eAccountEntity) {
        stored = account
    }

    override suspend fun delete() {
        stored = null
    }
}

internal class FakeE2ePeerDao : E2ePeerDao {
    val peers = mutableMapOf<String, E2ePeerEntity>()

    override suspend fun findByAddress(address: String): E2ePeerEntity? = peers[address]
    override fun observeByAddress(address: String): Flow<E2ePeerEntity?> =
        flowOf(peers[address])

    override fun observeAddressesInState(state: E2ePeerState): Flow<List<String>> =
        flowOf(peers.values.filter { it.state == state }.map { it.address })

    override suspend fun verifiedPeers(): List<E2ePeerEntity> =
        peers.values.filter { it.verified }.sortedByDescending { it.updatedAt }

    override fun observeVerifiedPeers(): Flow<List<E2ePeerEntity>> =
        flowOf(peers.values.filter { it.verified }.sortedByDescending { it.updatedAt })

    override suspend fun encryptedAddresses(): List<String> =
        peers.values.filter { it.state == E2ePeerState.ENCRYPTED }.map { it.address }

    override fun observeEncryptedAddresses(): Flow<List<String>> =
        flowOf(peers.values.filter { it.state == E2ePeerState.ENCRYPTED }.map { it.address })

    override suspend fun groupEncryptedAddresses(): List<String> =
        peers.values.filter { it.state == E2ePeerState.ENCRYPTED && it.groupCapable }.map { it.address }

    override fun observeGroupEncryptedAddresses(): Flow<List<String>> =
        flowOf(peers.values.filter { it.state == E2ePeerState.ENCRYPTED && it.groupCapable }.map { it.address })

    override suspend fun allPeers(): List<E2ePeerEntity> =
        peers.values.sortedBy { it.address }

    override fun observeAllPeers(): Flow<List<E2ePeerEntity>> =
        flowOf(peers.values.sortedBy { it.address })

    override suspend fun insert(peer: E2ePeerEntity): Long {
        if (peers.containsKey(peer.address)) return -1L
        peers[peer.address] = peer
        return 1L
    }

    override suspend fun updateState(address: String, state: E2ePeerState, updatedAt: Long) {
        peers[address]?.let { peers[address] = it.copy(state = state, updatedAt = updatedAt) }
    }

    override suspend fun updateKeys(address: String, curveKey: String, edKey: String, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(theirCurveKey = curveKey, theirEdKey = edKey, updatedAt = updatedAt)
        }
    }

    override suspend fun setVerified(address: String, verified: Boolean, updatedAt: Long) {
        peers[address]?.let { peers[address] = it.copy(verified = verified, updatedAt = updatedAt) }
    }

    override suspend fun recordProbe(
        address: String,
        attempts: Int,
        windowStartedAt: Long,
        lastProbeAt: Long,
        updatedAt: Long,
    ) {
        peers[address]?.let {
            peers[address] = it.copy(
                probeAttempts = attempts,
                windowStartedAt = windowStartedAt,
                lastProbeAt = lastProbeAt,
                updatedAt = updatedAt,
            )
        }
    }

    override suspend fun recordBundleReply(address: String, lastBundleReplyAt: Long, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(lastBundleReplyAt = lastBundleReplyAt, updatedAt = updatedAt)
        }
    }

    // Round 48 — media capability columns.
    override suspend fun setMediaCapable(address: String, mediaCapable: Boolean, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(mediaCapable = mediaCapable, updatedAt = updatedAt)
        }
    }

    override suspend fun setMediaAnnounced(address: String, mediaAnnounced: Boolean, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(mediaAnnounced = mediaAnnounced, updatedAt = updatedAt)
        }
    }

    // Round 49 — group capability columns.
    override suspend fun setGroupCapable(address: String, groupCapable: Boolean, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(groupCapable = groupCapable, updatedAt = updatedAt)
        }
    }

    override suspend fun setGroupAnnounced(address: String, groupAnnounced: Boolean, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(groupAnnounced = groupAnnounced, updatedAt = updatedAt)
        }
    }

    override suspend fun setHandshakeChannel(address: String, channel: String?, updatedAt: Long) {
        peers[address]?.let {
            peers[address] = it.copy(handshakeChannel = channel, updatedAt = updatedAt)
        }
    }

    override suspend fun resetTiming(updatedAt: Long) {
        // Round 43 — mirrors the DAO's UPDATE: timing clears for every
        // peer, non-ENCRYPTED states return to UNKNOWN, keys and the
        // verified flag survive.
        peers.replaceAll { _, peer ->
            peer.copy(
                probeAttempts = 0,
                windowStartedAt = null,
                lastProbeAt = null,
                lastBundleReplyAt = null,
                state = if (peer.state == E2ePeerState.ENCRYPTED) {
                    E2ePeerState.ENCRYPTED
                } else {
                    E2ePeerState.UNKNOWN
                },
                updatedAt = updatedAt,
            )
        }
    }

    override suspend fun deleteByAddress(address: String) {
        peers.remove(address)
    }
}

internal class FakeE2eFragmentDao : E2eFragmentDao {
    val fragments = mutableListOf<E2eFragmentEntity>()

    override suspend fun insert(fragment: E2eFragmentEntity): Long {
        val exists = fragments.any {
            it.sender == fragment.sender && it.msgId == fragment.msgId && it.idx == fragment.idx
        }
        if (exists) return -1L
        fragments += fragment
        return fragments.size.toLong()
    }

    override suspend fun fragmentsFor(sender: String, msgId: String): List<E2eFragmentEntity> =
        fragments.filter { it.sender == sender && it.msgId == msgId }.sortedBy { it.idx }

    override suspend fun countFor(sender: String, msgId: String): Int =
        fragments.count { it.sender == sender && it.msgId == msgId }

    override suspend fun deleteSet(sender: String, msgId: String): Int {
        val before = fragments.size
        fragments.removeAll { it.sender == sender && it.msgId == msgId }
        return before - fragments.size
    }

    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val before = fragments.size
        fragments.removeAll { it.receivedAt < cutoff }
        return before - fragments.size
    }
}

internal class FakeE2eSessionDao : E2eSessionDao {
    val sessions = mutableListOf<E2eSessionEntity>()
    private var nextId = 1L

    override suspend fun findByAddressAndSessionId(
        address: String,
        sessionId: String,
    ): E2eSessionEntity? =
        sessions.firstOrNull { it.address == address && it.sessionId == sessionId }

    override suspend fun currentSession(address: String): E2eSessionEntity? =
        sessions.firstOrNull { it.address == address && it.isCurrent }

    override suspend fun sessionsForPeer(address: String): List<E2eSessionEntity> =
        sessions.filter { it.address == address }
            .sortedWith(compareByDescending<E2eSessionEntity> { it.isCurrent }.thenByDescending { it.lastUsedAt })

    override suspend fun countForPeer(address: String): Int =
        sessions.count { it.address == address }

    override suspend fun insert(session: E2eSessionEntity): Long {
        val id = nextId++
        sessions += session.copy(id = id)
        return id
    }

    override suspend fun update(session: E2eSessionEntity) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session
    }

    override suspend fun clearCurrent(address: String) {
        sessions.replaceAll { if (it.address == address) it.copy(isCurrent = false) else it }
    }

    override suspend fun markCurrent(address: String, sessionId: String) {
        sessions.replaceAll {
            if (it.address == address && it.sessionId == sessionId) it.copy(isCurrent = true) else it
        }
    }

    override suspend fun updatePickle(
        address: String,
        sessionId: String,
        pickle: String,
        lastUsedAt: Long,
    ) {
        sessions.replaceAll {
            if (it.address == address && it.sessionId == sessionId) {
                it.copy(pickle = pickle, lastUsedAt = lastUsedAt)
            } else {
                it
            }
        }
    }

    override suspend fun pruneFallbacks(address: String, keepFallbacks: Int): Int {
        val fallbacks = sessions.filter { it.address == address && !it.isCurrent }
            .sortedByDescending { it.lastUsedAt }
        val dropped = fallbacks.drop(keepFallbacks)
        sessions.removeAll(dropped.toSet())
        return dropped.size
    }

    override suspend fun deleteByAddress(address: String): Int {
        val before = sessions.size
        sessions.removeAll { it.address == address }
        return before - sessions.size
    }
}

internal class FakeE2eNoticeDao : E2eNoticeDao {
    val notices = mutableListOf<E2eNoticeEntity>()
    private var nextId = 1L

    override suspend fun insert(notice: E2eNoticeEntity): Long {
        val id = nextId++
        notices += notice.copy(id = id)
        return id
    }

    override suspend fun noticesForConversation(conversationId: Long): List<E2eNoticeEntity> =
        notices.filter { it.conversationId == conversationId }.sortedBy { it.date }

    override suspend fun latestNotice(conversationId: Long): E2eNoticeEntity? =
        notices.filter { it.conversationId == conversationId }
            .maxWithOrNull(compareBy({ it.date }, { it.id }))

    override fun observeForConversation(conversationId: Long): Flow<List<E2eNoticeEntity>> =
        flowOf(notices.filter { it.conversationId == conversationId }.sortedBy { it.date })

    override suspend fun countByKind(conversationId: Long, kind: E2eNoticeKind): Int =
        notices.count { it.conversationId == conversationId && it.kind == kind }

    override suspend fun allNotices(): List<E2eNoticeEntity> =
        notices.sortedBy { it.date }

    override fun observeAll(): Flow<List<E2eNoticeEntity>> =
        flowOf(notices.sortedBy { it.date })

    override suspend fun deleteForConversation(conversationId: Long): Int {
        val before = notices.size
        notices.removeAll { it.conversationId == conversationId }
        return before - notices.size
    }
}

/** A [BlockedNumberRepository] that blocks exactly [blocked]. */
internal class FakeBlockedRepo(private val blocked: Set<String> = emptySet()) :
    BlockedNumberRepository {
    override fun observe(): Flow<List<BlockedNumber>> = flowOf(emptyList())
    override suspend fun isBlocked(rawNumber: String): Boolean = rawNumber in blocked
    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> =
        Outcome.Success(Unit)

    override suspend fun unblock(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> =
        Outcome.Success(Unit)

    override suspend fun blockedNormalizedSnapshot(): Set<String> = blocked

    override suspend fun blockedRawSnapshot(): List<String> = blocked.toList()
}

/**
 * Records every handshake SMS sent, data and text; [result] decides
 * whether the radio accepts it (both transports share the verdict).
 */
internal class RecordingDataSmsSender(var result: Boolean = true) : E2eDataSmsSender {
    data class Sent(
        val address: String,
        val subId: Int?,
        val port: Int,
        val data: ByteArray,
        val kind: DataSmsKind = DataSmsKind.PROBE,
    )

    /** One regular-SMS handshake send (round 45): the `RLYH:` text. */
    data class SentText(
        val address: String,
        val subId: Int?,
        val text: String,
        val kind: DataSmsKind = DataSmsKind.PROBE,
    )

    val sent = mutableListOf<Sent>()
    val sentTexts = mutableListOf<SentText>()

    override fun sendDataMessage(
        address: String,
        subId: Int?,
        port: Int,
        data: ByteArray,
        kind: DataSmsKind,
    ): Boolean {
        sent += Sent(address, subId, port, data.copyOf(), kind)
        return result
    }

    override fun sendTextMessage(
        address: String,
        subId: Int?,
        text: String,
        kind: DataSmsKind,
    ): Boolean {
        sentTexts += SentText(address, subId, text, kind)
        return result
    }
}

/**
 * Round 48 — in-memory `e2e_media_keys` stand-in for
 * [E2eMediaKeyStore] tests. `insert` is IGNORE (a duplicate
 * file id returns -1 and keeps the first row, like the real
 * DAO). Observations are live StateFlows, so a write after
 * subscribing emits the new row.
 */
internal class FakeE2eMediaKeyDao : E2eMediaKeyDao {
    val keys = mutableMapOf<String, E2eMediaKeyEntity>()
    private val flows = mutableMapOf<String, MutableStateFlow<E2eMediaKeyEntity?>>()

    override suspend fun insert(key: E2eMediaKeyEntity): Long {
        if (keys.containsKey(key.fileId)) return -1L
        keys[key.fileId] = key
        flows[key.fileId]?.value = key
        return 1L
    }

    override suspend fun findByFileId(fileId: String): E2eMediaKeyEntity? = keys[fileId]

    override fun observeByFileId(fileId: String): Flow<E2eMediaKeyEntity?> =
        flows.getOrPut(fileId) { MutableStateFlow(keys[fileId]) }

    override suspend fun deleteByFileId(fileId: String) {
        keys.remove(fileId)
        flows[fileId]?.value = null
    }

    override suspend fun findFileIdsOlderThan(cutoff: Long): List<String> =
        keys.filterValues { it.receivedAt < cutoff }.keys.toList()
}

/**
 * Round 49 — in-memory sender-key table for the
 * [E2eGroupSenderKeyStore] / [E2eGroupSessionManager] tests.
 * Mirrors the Room semantics the DAO declares: IGNORE insert
 * (first key wins), current-key marking, generation-ordered
 * prune that never prunes the current key.
 */
internal class FakeE2eGroupKeyDao : E2eGroupKeyDao {
    val keys = mutableMapOf<Triple<String, String, String>, E2eGroupKeyEntity>()

    override suspend fun insert(key: E2eGroupKeyEntity): Long {
        val triple = Triple(key.groupId, key.senderAddress, key.keyId)
        if (keys.containsKey(triple)) return -1L
        keys[triple] = key
        return 1L
    }

    override suspend fun findKey(
        groupId: String,
        senderAddress: String,
        keyId: String,
    ): E2eGroupKeyEntity? = keys[Triple(groupId, senderAddress, keyId)]

    override suspend fun keysForSender(
        groupId: String,
        senderAddress: String,
    ): List<E2eGroupKeyEntity> = keys.values
        .filter { it.groupId == groupId && it.senderAddress == senderAddress }
        .sortedWith(compareByDescending<E2eGroupKeyEntity> { it.generation }.thenByDescending { it.createdAt })

    override suspend fun currentKeyForSender(
        groupId: String,
        senderAddress: String,
    ): E2eGroupKeyEntity? = keys.values.firstOrNull {
        it.groupId == groupId && it.senderAddress == senderAddress && it.isCurrent
    }

    override suspend fun keysForGroup(groupId: String): List<E2eGroupKeyEntity> =
        keys.values.filter { it.groupId == groupId }

    override suspend fun allMembersLists(): List<String> =
        keys.values.map { it.membersCsv }.distinct()

    override suspend fun groupIdsForSender(senderAddress: String): List<String> =
        keys.values.filter { it.senderAddress == senderAddress }.map { it.groupId }.distinct()

    override suspend fun setCurrentKey(groupId: String, senderAddress: String, keyId: String) {
        clearCurrentForSender(groupId, senderAddress)
        markCurrent(groupId, senderAddress, keyId)
    }

    override suspend fun clearCurrentForSender(groupId: String, senderAddress: String) {
        keys.replaceAll { _, key ->
            if (key.groupId == groupId && key.senderAddress == senderAddress) {
                key.copy(isCurrent = false)
            } else {
                key
            }
        }
    }

    override suspend fun markCurrent(groupId: String, senderAddress: String, keyId: String) {
        val triple = Triple(groupId, senderAddress, keyId)
        keys[triple]?.let { keys[triple] = it.copy(isCurrent = true) }
    }

    override suspend fun pruneOldGenerations(
        groupId: String,
        senderAddress: String,
        keep: Int,
    ): Int {
        // Mirrors the SQL: the keep-set is the top `keep` keys by
        // (generation DESC, recency) INCLUDING the current key,
        // and only non-current keys outside it are deleted.
        val senderKeys = keysForSender(groupId, senderAddress)
        val keepIds = senderKeys.take(keep).map { it.keyId }.toSet()
        val doomed = senderKeys.filter { !it.isCurrent && it.keyId !in keepIds }
        doomed.forEach { keys.remove(Triple(it.groupId, it.senderAddress, it.keyId)) }
        return doomed.size
    }

    override suspend fun deleteByGroup(groupId: String): Int {
        val doomed = keys.filterValues { it.groupId == groupId }.keys.toList()
        doomed.forEach { keys.remove(it) }
        return doomed.size
    }

    override suspend fun deleteBySender(senderAddress: String): Int {
        val doomed = keys.filterValues { it.senderAddress == senderAddress }.keys.toList()
        doomed.forEach { keys.remove(it) }
        return doomed.size
    }
}

/**
 * Round 49 — in-memory distribution table for the
 * [E2eGroupSenderKeyStore] / [E2eGroupSessionManager] tests.
 * `insert` is REPLACE (a re-send refreshes the timestamp, like
 * the real DAO).
 */
internal class FakeE2eGroupDistributionDao : E2eGroupDistributionDao {
    val distributions = mutableMapOf<Triple<String, String, String>, E2eGroupDistributionEntity>()

    override suspend fun insert(distribution: E2eGroupDistributionEntity) {
        distributions[Triple(distribution.groupId, distribution.memberAddress, distribution.keyId)] =
            distribution
    }

    override suspend fun find(
        groupId: String,
        memberAddress: String,
        keyId: String,
    ): E2eGroupDistributionEntity? =
        distributions[Triple(groupId, memberAddress, keyId)]

    override suspend fun distributedMembers(groupId: String, keyId: String): List<String> =
        distributions.values
            .filter { it.groupId == groupId && it.keyId == keyId }
            .map { it.memberAddress }

    override suspend fun deleteForGroupExceptKey(groupId: String, currentKeyId: String): Int {
        val doomed = distributions.filterValues {
            it.groupId == groupId && it.keyId != currentKeyId
        }.keys.toList()
        doomed.forEach { distributions.remove(it) }
        return doomed.size
    }

    override suspend fun deleteByGroup(groupId: String): Int {
        val doomed = distributions.filterValues { it.groupId == groupId }.keys.toList()
        doomed.forEach { distributions.remove(it) }
        return doomed.size
    }

    override suspend fun deleteByMember(memberAddress: String): Int {
        val doomed = distributions.filterValues { it.memberAddress == memberAddress }.keys.toList()
        doomed.forEach { distributions.remove(it) }
        return doomed.size
    }
}
