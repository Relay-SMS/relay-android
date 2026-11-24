package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.E2eGroupDistributionDao
import com.filestech.sms.data.local.db.dao.E2eGroupKeyDao
import com.filestech.sms.data.local.db.entity.E2eGroupDistributionEntity
import com.filestech.sms.data.local.db.entity.E2eGroupKeyEntity
import com.filestech.sms.e2e.E2eGroupSenderKeyMessage.SenderKeyDistribution
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A sender key, as the group session manager sees it: the wire /
 * storage [SenderKeyDistribution] plus the storage-side facts
 * (own vs peer, current vs old generation, the sender's identity
 * key at receipt). A plain class, not a data class: data-class
 * equality on [key] ([ByteArray]) is referential, which would
 * silently break key comparisons. [toString] never prints the key.
 */
class SenderKey(
    /** The group id (32 hex chars). */
    val groupId: String,
    /** The key owner's canonical address (ours for our own key). */
    val senderAddress: String,
    /** The key id (16 hex chars) — selects this generation. */
    val keyId: String,
    /** The raw 32-byte AES-256-GCM sender key. */
    val key: ByteArray,
    /** The full member list (canonical, sorted, including ourselves). */
    val members: List<String>,
    /** The generation: 1 for a group's first key, +1 per re-key. */
    val generation: Int,
    /** The sender's Curve25519 identity key (base64) at receipt; null when unknown. */
    val senderCurveKeyB64: String?,
    /** True when this is OUR key for the group. */
    val isOwn: Boolean,
    /** True when this is the sender's current key for the group. */
    val isCurrent: Boolean,
    /** Epoch ms the key was created (ours) or received (theirs). */
    val createdAt: Long,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SenderKey) return false
        return groupId == other.groupId &&
            senderAddress == other.senderAddress &&
            keyId == other.keyId &&
            key.contentEquals(other.key) &&
            members == other.members &&
            generation == other.generation &&
            senderCurveKeyB64 == other.senderCurveKeyB64 &&
            isOwn == other.isOwn &&
            isCurrent == other.isCurrent &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + senderAddress.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + generation
        result = 31 * result + (senderCurveKeyB64?.hashCode() ?: 0)
        result = 31 * result + isOwn.hashCode()
        result = 31 * result + isCurrent.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "SenderKey(groupId=$groupId, sender=$senderAddress, keyId=$keyId, key=<32-byte key>, " +
            "members=<${members.size} members>, generation=$generation, isOwn=$isOwn, " +
            "isCurrent=$isCurrent)"
}

/**
 * Round 49 — the sender-key store: the thin repository seam over
 * [E2eGroupKeyDao] + [E2eGroupDistributionDao], mirroring
 * [E2eMediaKeyStore] / [E2ePeerRepository]. The store maps between
 * the domain [SenderKey] and the Room entities at this boundary,
 * so no caller touches entity field names. Key bytes are base64
 * in the table (SQLCipher encrypts the whole database at rest —
 * no extra pickle wrap, per the entity's KDoc).
 *
 * Key lifecycle (design §13):
 * - Insert is IGNORE — first key wins: a re-sent distribution
 *   for the same (group, sender, key id) must not overwrite the
 *   first key, which is the one the sender's messages are
 *   encrypted under.
 * - A distribution only becomes CURRENT when its generation beats
 *   the stored current one — except after an identity change
 *   (the sender's curve key differs from the stored current
 *   key's), when a fresh generation-1 key is accepted: a
 *   reinstalled sender distributes from generation 1 again, and
 *   the generation check alone would reject it. Old generations
 *   are KEPT (up to [MAX_GENERATIONS] per sender per group) so
 *   in-flight messages under an old key still decrypt.
 * - Distribution records are REPLACE: a re-send refreshes the
 *   timestamp, which is what the key-request rate limit reads.
 */
@Singleton
class E2eGroupSenderKeyStore @Inject constructor(
    private val keyDao: E2eGroupKeyDao,
    private val distributionDao: E2eGroupDistributionDao,
) {

    /** What storing a received distribution found. */
    sealed interface StoreResult {
        /** Stored (and current when [isCurrent] — see the class KDoc). */
        data class Stored(val key: SenderKey, val isCurrent: Boolean) : StoreResult

        /** Already stored (a re-sent distribution — first key wins). */
        data object AlreadyStored : StoreResult
    }

    /**
     * Stores OUR new key for a group (fresh generation): it
     * becomes current immediately, the previous current key (if
     * any) drops to an old generation, and stale distribution
     * records for the old key are pruned (the new key
     * distributes to everyone fresh). Old generations are kept
     * up to [MAX_GENERATIONS].
     */
    suspend fun storeOwnKey(
        groupId: String,
        ownAddress: String,
        keyId: String,
        key: ByteArray,
        members: List<String>,
        generation: Int,
        ownCurveKeyB64: String?,
        now: Long = System.currentTimeMillis(),
    ): SenderKey {
        val entity = E2eGroupKeyEntity(
            groupId = groupId,
            senderAddress = ownAddress,
            keyId = keyId,
            keyB64 = Base64.getEncoder().encodeToString(key),
            membersCsv = members.joinToString(";"),
            generation = generation,
            senderCurveKeyB64 = ownCurveKeyB64,
            isOwn = true,
            isCurrent = false,
            createdAt = now,
        )
        keyDao.insert(entity)
        keyDao.setCurrentKey(groupId, ownAddress, keyId)
        keyDao.pruneOldGenerations(groupId, ownAddress, MAX_GENERATIONS)
        distributionDao.deleteForGroupExceptKey(groupId, keyId)
        return checkNotNull(keyDao.findKey(groupId, ownAddress, keyId)?.toSenderKey()) {
            "own sender key missing right after insert"
        }
    }

    /**
     * Stores a received distribution. Returns [StoreResult.Stored]
     * (with whether it became current) or
     * [StoreResult.AlreadyStored] for a re-sent distribution.
     * [senderCurveKeyB64] is the sender's CURRENT identity key
     * from their peer row — the identity-change check compares it
     * against the stored current key's identity.
     */
    suspend fun storePeerKey(
        distribution: SenderKeyDistribution,
        senderAddress: String,
        senderCurveKeyB64: String?,
        now: Long = System.currentTimeMillis(),
    ): StoreResult {
        val entity = E2eGroupKeyEntity(
            groupId = distribution.groupId,
            senderAddress = senderAddress,
            keyId = distribution.keyId,
            keyB64 = Base64.getEncoder().encodeToString(distribution.key),
            membersCsv = distribution.members.joinToString(";"),
            generation = distribution.generation,
            senderCurveKeyB64 = senderCurveKeyB64,
            isOwn = false,
            isCurrent = false,
            createdAt = now,
        )
        if (keyDao.insert(entity) == -1L) return StoreResult.AlreadyStored
        val current = keyDao.currentKeyForSender(distribution.groupId, senderAddress)
        val becomesCurrent = shouldBecomeCurrent(distribution, current, senderCurveKeyB64)
        if (becomesCurrent) {
            keyDao.setCurrentKey(distribution.groupId, senderAddress, distribution.keyId)
        }
        keyDao.pruneOldGenerations(
            distribution.groupId,
            senderAddress,
            MAX_GENERATIONS,
        )
        val stored = checkNotNull(
            keyDao.findKey(distribution.groupId, senderAddress, distribution.keyId)
                ?.toSenderKey(),
        ) { "peer sender key missing right after insert" }
        return StoreResult.Stored(stored, becomesCurrent)
    }

    /**
     * Whether a newly stored distribution becomes the sender's
     * current key: no current key yet, a higher generation than
     * the current one, or an identity change (the sender's
     * CURRENT curve key differs from the stored current key's —
     * a reinstalled sender distributes from generation 1 again,
     * and the generation check alone would reject it). A null
     * identity on either side falls back to the generation check
     * alone — never assume a change without evidence.
     */
    private fun shouldBecomeCurrent(
        distribution: SenderKeyDistribution,
        current: E2eGroupKeyEntity?,
        senderCurveKeyB64: String?,
    ): Boolean {
        if (current == null) return true
        if (distribution.generation > current.generation) return true
        val currentCurve = current.senderCurveKeyB64
        if (senderCurveKeyB64 != null && currentCurve != null) {
            return senderCurveKeyB64 != currentCurve
        }
        return false
    }

    /** Our current key for a group, or null when we never created one. */
    suspend fun ownCurrentKey(groupId: String, ownAddress: String): SenderKey? =
        keyDao.currentKeyForSender(groupId, ownAddress)?.toSenderKey()
            ?.takeIf { it.isOwn }

    /** One member's current key for a group, or null when none was ever stored. */
    suspend fun peerCurrentKey(groupId: String, senderAddress: String): SenderKey? =
        keyDao.currentKeyForSender(groupId, senderAddress)?.toSenderKey()

    /** One key by its full triple, or null when no such key was ever stored. */
    suspend fun keyFor(groupId: String, senderAddress: String, keyId: String): SenderKey? =
        keyDao.findKey(groupId, senderAddress, keyId)?.toSenderKey()

    /** The member list of any key row for a group (all rows carry the same list). */
    suspend fun membersForGroup(groupId: String): List<String>? =
        keyDao.keysForGroup(groupId).firstOrNull()?.toSenderKey()?.members

    /** Every group id this sender has a key for (the notice paths' group lookup). */
    suspend fun groupIdsForSender(senderAddress: String): Set<String> =
        keyDao.groupIdsForSender(senderAddress).toSet()

    /**
     * The own-number fallback for a group send: the stored member
     * list that holds [threadAddresses] plus exactly one more
     * address (ourselves) — the minus-one rule, like the
     * group-MMS path. Null when no stored list matches: the send
     * then needs the own number from settings / the SIM.
     */
    suspend fun findMemberListFor(threadAddresses: List<String>): List<String>? {
        val thread = threadAddresses.map { it.trim() }.toSet()
        return keyDao.allMembersLists()
            .map { it.split(";").map(String::trim).filter(String::isNotEmpty) }
            .firstOrNull { members ->
                members.size == thread.size + 1 && members.containsAll(thread)
            }
    }

    /** Records that our key went out to one member (radio accepted). */
    suspend fun recordDistribution(
        groupId: String,
        memberAddress: String,
        keyId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        distributionDao.insert(
            E2eGroupDistributionEntity(
                groupId = groupId,
                memberAddress = memberAddress,
                keyId = keyId,
                distributedAt = now,
            ),
        )
    }

    /** When we last sent this key to this member, or null when never sent. */
    suspend fun distributionAt(groupId: String, memberAddress: String, keyId: String): Long? =
        distributionDao.find(groupId, memberAddress, keyId)?.distributedAt

    /** The members we already sent this key to (the send-path ensure skips them). */
    suspend fun distributedMembers(groupId: String, keyId: String): Set<String> =
        distributionDao.distributedMembers(groupId, keyId).toSet()

    /** Deletes every key row + distribution record for a group (conversation deleted). */
    suspend fun deleteForGroup(groupId: String) {
        keyDao.deleteByGroup(groupId)
        distributionDao.deleteByGroup(groupId)
    }

    /** Deletes every key row from one sender + distributions to them (peer deleted / wipe). */
    suspend fun deleteBySender(address: String) {
        keyDao.deleteBySender(address)
        distributionDao.deleteByMember(address)
    }

    companion object {
        /**
         * Generations kept per sender per group (current + old):
         * the Olm session rule (current + 2 fallbacks) applied to
         * sender keys — in-flight messages under an old key must
         * still decrypt, and a re-key loop must not grow the
         * table unboundedly.
         */
        const val MAX_GENERATIONS: Int = 3
    }
}

/**
 * Entity → domain key. Defensive: a row whose base64 does not
 * decode to exactly 32 bytes, or whose ids are malformed, reads
 * as no key — stored rows are written from validated keys, so
 * this only fires on a corrupt database, never on a live key.
 */
private fun E2eGroupKeyEntity.toSenderKey(): SenderKey? {
    val keyBytes = runCatching { Base64.getDecoder().decode(keyB64) }.getOrNull()
        ?.takeIf { it.size == E2eGroupWireFormat.SENDER_KEY_BYTES }
        ?: return null
    val idsOk = E2eGroupWireFormat.isValidGroupIdHex(groupId) &&
        E2eGroupWireFormat.isValidKeyIdHex(keyId)
    val members = membersCsv.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    if (!idsOk || members.size < E2eGroupSenderKeyMessage.MIN_MEMBERS) return null
    return SenderKey(
        groupId = groupId,
        senderAddress = senderAddress,
        keyId = keyId,
        key = keyBytes,
        members = members,
        generation = generation,
        senderCurveKeyB64 = senderCurveKeyB64,
        isOwn = isOwn,
        isCurrent = isCurrent,
        createdAt = createdAt,
    )
}
