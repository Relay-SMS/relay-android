package com.filestech.sms.e2e

import com.filestech.sms.core.ext.canonicalPhone
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live group encryption state (round 49, design §13): the
 * per-conversation [GroupEncryptionState] the thread UI, the
 * composer, and the send path collect.
 *
 * The state is derived from the members' peer rows (via
 * [E2eGroupState.derive]) — never stored — so it recomputes the
 * moment any member's exchange completes, capability declaration
 * lands, or block changes. The inputs are the whole peer table
 * ([E2ePeerRepository.observeAllPeers]) and the blocklist: Room
 * re-emits on any peer write, and the derivation re-runs for the
 * requested members only. A group with no members in the table
 * yet reads [GroupEncryptionState.SettingUp] (nothing has
 * happened — the exchanges start on thread-open / send, like the
 * 1:1 triggers).
 *
 * The caller passes the conversation's member addresses (the
 * thread's addresses, excluding ourselves — exactly what
 * `Conversation.addresses` holds). Our own number is NOT an
 * input: the state is about the OTHER members' reachability, and
 * the own number only matters to the send path (forming the
 * group id), where its absence fails the send loudly instead of
 * changing the state.
 */
@Singleton
class E2eGroupStateProvider @Inject constructor(
    private val peers: E2ePeerRepository,
    private val blockedRepo: BlockedNumberRepository,
) {

    /**
     * Live view of one group's encryption state. Re-emits
     * whenever any peer row or the blocklist changes (the
     * derivation re-runs for these members — a flip elsewhere in
     * the table re-emits the same state, and the collector's
     * `distinctUntilChanged` drops it).
     */
    fun observeGroupState(memberAddresses: List<String>): Flow<GroupEncryptionState> {
        val members = memberAddresses.map { it.canonicalPhone() }.distinct()
        return combine(
            peers.observeAllPeers(),
            blockedRepo.observe(),
        ) { allPeers, blocked ->
            val peerByAddress = allPeers.associateBy { it.address.canonicalPhone() }
            val blockedCanonical = blocked.map { it.normalizedNumber.canonicalPhone() }.toSet()
            E2eGroupState.derive(members, peerByAddress, blockedCanonical)
        }
    }

    /**
     * One-shot read of one group's encryption state, for the send
     * path (the decision must be fresh at send time, not a stale
     * collected value). May throw on a dead database — the caller
     * fails OPEN to plain (the mirror of the 1:1 gate's failure
     * mode: a broken database cannot encrypt anyway, and the send
     * must not be refused for a read error). See
     * [E2eGroupSessionManager.decideGroupSend].
     */
    suspend fun groupState(memberAddresses: List<String>): GroupEncryptionState {
        val members = memberAddresses.map { it.canonicalPhone() }.distinct()
        val allPeers: List<E2ePeerEntity> = peers.allPeers()
        val peerByAddress = allPeers.associateBy { it.address.canonicalPhone() }
        val blockedCanonical = blockedRepo.blockedNormalizedSnapshot()
            .map { it.canonicalPhone() }.toSet()
        return E2eGroupState.derive(members, peerByAddress, blockedCanonical)
    }
}
