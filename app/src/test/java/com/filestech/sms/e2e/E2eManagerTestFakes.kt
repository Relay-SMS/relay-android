package com.filestech.sms.e2e

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.E2eAccountDao
import com.filestech.sms.data.local.db.dao.E2eFragmentDao
import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.dao.E2ePeerDao
import com.filestech.sms.data.local.db.dao.E2eSessionDao
import com.filestech.sms.data.local.db.entity.E2eAccountEntity
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity
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

/** Records every data SMS sent; [result] decides whether the radio accepts it. */
internal class RecordingDataSmsSender(var result: Boolean = true) : E2eDataSmsSender {
    data class Sent(
        val address: String,
        val subId: Int?,
        val port: Int,
        val data: ByteArray,
        val kind: DataSmsKind = DataSmsKind.PROBE,
    )

    val sent = mutableListOf<Sent>()

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
}
