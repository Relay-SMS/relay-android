package com.filestech.sms.e2e

import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.ext.canonicalPhone
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.SimSelfNumber
import com.filestech.sms.domain.e2e.E2eGroupSendGate
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.e2e.E2eGroupSenderKeyMessage.SenderKeyDistribution
import com.filestech.sms.e2e.E2eGroupSenderKeyStore.StoreResult
import timber.log.Timber
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One decrypted group message: the plaintext plus the group it
 * belongs to. The receiver folds it into the group conversation
 * (resolved from [members] — or, when the key row is missing,
 * from [groupId] alone) and records the group activation.
 */
data class GroupDecrypted(
    /** The decrypted body (a group text, or a formatted tapback body for a reaction). */
    val plaintext: String,
    /** The group id (32 hex chars). */
    val groupId: String,
    /** The full member list from the sender's key row (canonical, sorted, including ourselves). */
    val members: List<String>,
    /** The key id the message was encrypted under (16 hex chars). */
    val keyId: String,
)

/** What one sender-key distribution run did, per member. */
data class DistributionReport(
    /** Members the key reached (the radio accepted the send). */
    val sent: List<String>,
    /** Members the send failed for (retried on the next group send). */
    val failed: List<String>,
    /** Members that already had this key (no re-send). */
    val skipped: List<String>,
)

/**
 * Round 49 — the encrypted-groups orchestrator (design §13):
 * sender-key lifecycle, group encrypt/decrypt, key requests,
 * group notices, and the [E2eGroupSendGate] the domain send path
 * calls. One class, one owner for everything group-E2E: the
 * receive manager routes group payloads here, the handshake
 * handler reports key changes here, and the send path decides
 * through here.
 *
 * The protocol in one paragraph: each member has a per-group
 * sender key (32-byte AES-256-GCM, generated with
 * `SecureRandom`), distributed to the other members over their
 * pairwise vodozemac sessions as an Olm-encrypted JSON message
 * (payload type `0x04` SENDER_KEY — see
 * [E2eGroupSenderKeyMessage]). A group message is the body
 * encrypted with the sender's sender key ([AeadCipher], the
 * round-48 media envelope — no new crypto), wrapped in the group
 * envelope ([E2eGroupWireFormat]: group id + key id in the
 * clear, ciphertext after), and fanned out as `RLY1:` fragments
 * (message type GROUP `0x03`, payload GROUP_TEXT `0x05` or
 * GROUP_REACTION `0x06`) — the SAME fragment set to every
 * member. A changed member list is a different group id, so
 * membership changes re-key by construction: the new group gets
 * fresh keys at generation 1, removed members never receive the
 * new key, and old keys stay stored so in-flight messages under
 * them still decrypt.
 *
 * Failure posture, like the 1:1 path: never send plaintext
 * instead of ciphertext, never refuse a send for a read error
 * ([decideGroupSend] fails open to Plain), and never lose a
 * message for a missing key without asking for it (an
 * undecryptable group message becomes a placeholder + a key
 * request — the receive manager owns the placeholder, this class
 * owns the request).
 */
@Singleton
class E2eGroupSessionManager @Inject constructor(
    private val peers: E2ePeerRepository,
    private val groupKeys: E2eGroupSenderKeyStore,
    private val sendManager: E2eSendManager,
    private val aead: AeadCipher,
    private val conversationDao: ConversationDao,
    private val notices: E2eNoticeStore,
    private val settings: AppSettingsSource,
    private val simSelfNumber: SimSelfNumber,
    private val stateProvider: E2eGroupStateProvider,
    private val mirror: ConversationMirror,
) : E2eGroupSendGate {

    // In-memory key-request clock: (group id, sender) → last
    // request time. A requester-side limit only (the responder's
    // limit is persisted — see [onKeyRequestReceived]). Guarded
    // by its own monitor; the manager is a singleton and the
    // receive path can run on several threads.
    private val keyRequests = mutableMapOf<Pair<String, String>, Long>()

    // ----- Own number + member lists -----

    /**
     * Our own number (canonical), from settings first, then the
     * SIM — the same order as the group-MMS path
     * ([com.filestech.sms.data.sms.OwnNumberEnsurerImpl]).
     * Null when neither knows it: the group id cannot be formed
     * from the thread alone, and the stored-distribution fallback
     * ([E2eGroupSenderKeyStore.findMemberListFor]) is the only
     * other source.
     */
    suspend fun ownNumber(): String? {
        val fromSettings = settings.hydratedOrNull()?.sending?.userMsisdn
            ?.takeIf { it.isNotBlank() }?.canonicalPhone()
        if (fromSettings != null) return fromSettings
        return simSelfNumber.ownNumber(null)?.takeIf { it.isNotBlank() }?.canonicalPhone()
    }

    /**
     * The full member list for a group send: the thread's
     * addresses plus ourselves (canonical, sorted). When our own
     * number is unknown, falls back to the stored distribution
     * that holds the thread's addresses plus exactly one more —
     * the minus-one rule, like the group-MMS path. Null when
     * neither source knows the list: the send fails fast with
     * [AppError.OwnNumberMissing].
     */
    suspend fun resolveMemberList(threadAddresses: List<String>): List<String>? {
        val thread = E2eGroupWireFormat.canonicalMembers(threadAddresses)
        val own = ownNumber()
        return if (own != null) {
            E2eGroupWireFormat.canonicalMembers(thread + own)
        } else {
            groupKeys.findMemberListFor(thread)
        }
    }

    // ----- Sender-key lifecycle -----

    /**
     * Our current sender key for a group, creating it (generation
     * 1) when we never had one. Null when our own number is
     * unknown — a sender key needs its owner's address, and
     * recipients cannot attribute a key from an unknown sender.
     * (A group's first key is always generation 1: membership
     * changes form a NEW group id, so the same group id never
     * re-keys in v1 — the generation machinery is forward
     * compatibility for rotation, and for peer keys after an
     * identity change.)
     */
    suspend fun ensureOwnKey(members: List<String>, now: Long): SenderKey? {
        val full = E2eGroupWireFormat.canonicalMembers(members)
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(full)
        val own = ownNumber() ?: return null
        groupKeys.ownCurrentKey(groupId, own)?.let { return it }
        return groupKeys.storeOwnKey(
            groupId = groupId,
            ownAddress = own,
            keyId = E2eGroupWireFormat.generateKeyId(),
            key = E2eGroupWireFormat.generateSenderKey(),
            members = full,
            generation = 1,
            // Our own identity key is never compared (the
            // identity-change check applies to peer keys), so
            // own rows record null.
            ownCurveKeyB64 = null,
            now = now,
        )
    }

    /**
     * Distributes our current sender key for a group to every
     * member missing it (the distribution records are the
     * ledger). Best-effort per member: a failed send is recorded
     * in the report and retried on the next group send — one
     * member's radio failure must not block the group's send.
     * Null when our own key cannot be ensured (no own number).
     */
    suspend fun distributeOwnKey(
        members: List<String>,
        subId: Int?,
        now: Long,
    ): DistributionReport? {
        val key = ensureOwnKey(members, now) ?: return null
        return distributeKeyToMembers(key, members, subId, now)
    }

    /** The per-member fan-out of one key: skip the ledgered, send to the rest. */
    private suspend fun distributeKeyToMembers(
        key: SenderKey,
        members: List<String>,
        subId: Int?,
        now: Long,
    ): DistributionReport {
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val already = groupKeys.distributedMembers(key.groupId, key.keyId)
        for (member in members) {
            if (member == key.senderAddress) continue
            if (member in already) {
                skipped += member
                continue
            }
            if (sendKeyToMember(key, member, subId, now)) sent += member else failed += member
        }
        return DistributionReport(sent, failed, skipped)
    }

    /**
     * Sends our key to one member (Olm-encrypted JSON, via
     * [E2eSendManager.sendSenderKey]) and records the
     * distribution on success. Returns whether the radio
     * accepted the send.
     */
    private suspend fun sendKeyToMember(
        key: SenderKey,
        member: String,
        subId: Int?,
        now: Long,
    ): Boolean {
        val distribution = SenderKeyDistribution(
            groupId = key.groupId,
            keyId = key.keyId,
            key = key.key,
            members = key.members,
            generation = key.generation,
        )
        val sent = sendManager.sendSenderKey(
            member,
            E2eGroupSenderKeyMessage.encodeDistribution(distribution),
            now,
            subId,
        ) is Outcome.Success
        if (sent) groupKeys.recordDistribution(key.groupId, member, key.keyId, now)
        return sent
    }

    // ----- The send gate (E2eGroupSendGate) -----

    /**
     * Whether this group text goes out encrypted, plain, or is
     * refused (over the cap while encrypted). A state-read
     * failure fails OPEN to [E2eGroupSendGate.Decision.Plain] —
     * the mirror of the 1:1 gate: a broken database cannot
     * encrypt anyway, and the send must not be refused for a read
     * error.
     */
    override suspend fun decideGroupSend(
        memberAddresses: List<String>,
        body: String,
    ): E2eGroupSendGate.Decision {
        val state = runCatching { stateProvider.groupState(memberAddresses) }.getOrNull()
        return when {
            state == null -> E2eGroupSendGate.Decision.Plain
            state != GroupEncryptionState.Encrypted -> E2eGroupSendGate.Decision.Plain
            E2eWireFormat.plaintextExceedsCap(body) -> E2eGroupSendGate.Decision.RefuseOverCap
            else -> E2eGroupSendGate.Decision.Encrypted
        }
    }

    /**
     * Encrypts a group text or reaction: resolves the member
     * list, ensures our sender key, distributes it to the members
     * missing it, encrypts with our sender key, and fragments.
     * The fragment set is THE SAME for every member — the caller
     * fans it out. A failure anywhere returns
     * [Outcome.Failure]: the caller marks the row FAILED, the
     * user can retry, and the plaintext NEVER goes out instead.
     * [AppError.OwnNumberMissing] means our own number is unknown
     * and no stored distribution could supply the member list.
     * MEDIA_KEY is refused: encrypted media in groups is a
     * follow-up (design §13.7).
     */
    override suspend fun encryptGroupMessage(
        memberAddresses: List<String>,
        body: String,
        subId: Int?,
        date: Long,
        payloadType: E2ePayloadType,
    ): Outcome<E2eGroupSendGate.EncryptedGroupMessage> {
        if (payloadType == E2ePayloadType.MEDIA_KEY) {
            return Outcome.Failure(
                AppError.Validation("encrypted media in groups is not supported yet"),
            )
        }
        val members = resolveMemberList(memberAddresses)
            ?: return Outcome.Failure(AppError.OwnNumberMissing)
        val key = ensureOwnKey(members, date)
            ?: return Outcome.Failure(AppError.OwnNumberMissing)
        distributeKeyToMembers(key, members, subId, date)
        return encryptWithKey(key, body, payloadType)
    }

    /** The encrypt + fragment half of [encryptGroupMessage]. */
    private fun encryptWithKey(
        key: SenderKey,
        body: String,
        payloadType: E2ePayloadType,
    ): Outcome<E2eGroupSendGate.EncryptedGroupMessage> {
        val groupPayloadType = when (payloadType) {
            E2ePayloadType.TEXT -> E2eWireFormat.PayloadType.GROUP_TEXT
            E2ePayloadType.REACTION -> E2eWireFormat.PayloadType.GROUP_REACTION
            E2ePayloadType.MEDIA_KEY,
            E2ePayloadType.SENDER_KEY,
            E2ePayloadType.GROUP_KEY_REQUEST,
            -> return Outcome.Failure(
                AppError.Validation("this payload type cannot be a group message"),
            )
        }
        val aad = E2eGroupWireFormat.aadFor(key.groupId, key.senderAddress, key.keyId)
        val ciphertext = when (
            val encrypted = aead.encryptRaw(key.key, body.toByteArray(UTF_8), aad)
        ) {
            is Outcome.Success -> encrypted.value
            is Outcome.Failure -> return Outcome.Failure(encrypted.error)
        }
        // The ids come from a validated stored key, so the
        // envelope always builds — but encodeEnvelope returns
        // null on malformed ids, and a null here must fail the
        // send, never crash it.
        val envelope = E2eGroupWireFormat.encodeEnvelope(key.groupId, key.keyId, ciphertext)
            ?: return Outcome.Failure(AppError.Validation("could not build the group envelope"))
        val fragments = E2eWireFormat.encode(
            E2eWireFormat.generateMessageId(),
            EncryptedMessage(OlmMessageType.GROUP, envelope),
            groupPayloadType,
        )
        return Outcome.Success(E2eGroupSendGate.EncryptedGroupMessage(fragments, key.groupId))
    }

    // ----- Receive path -----

    /**
     * Decrypts one group message envelope (the bytes after the
     * `RLY1:` header, from [E2eWireFormat.parsePayload]). Null
     * when the envelope is malformed, the sender's key for this
     * (group, key id) is unknown, or the AEAD open fails — the
     * caller then shows a placeholder and asks for the key (see
     * [requestSenderKey]). The member list comes from the key
     * row: the conversation resolves from it, not from the
     * envelope (which carries no addresses).
     */
    suspend fun decryptGroupMessage(
        senderAddress: String,
        envelopeBytes: ByteArray,
        now: Long,
    ): GroupDecrypted? {
        val envelope = E2eGroupWireFormat.parseEnvelope(envelopeBytes) ?: return null
        val sender = senderAddress.canonicalPhone()
        val key = groupKeys.keyFor(envelope.groupIdHex, sender, envelope.keyIdHex) ?: return null
        val aad = E2eGroupWireFormat.aadFor(envelope.groupIdHex, sender, envelope.keyIdHex)
        val plain = when (val decrypted = aead.decryptRaw(key.key, envelope.aeadBlob, aad)) {
            is Outcome.Success -> decrypted.value
            is Outcome.Failure -> return null
        }
        return GroupDecrypted(
            plaintext = String(plain, UTF_8),
            groupId = envelope.groupIdHex,
            members = key.members,
            keyId = envelope.keyIdHex,
        )
    }

    /**
     * Asks the sender to re-distribute their key for a group —
     * the recovery path when a group message arrives under an
     * unknown key (the distribution was lost, or we were added
     * after it went out). Rate-limited to one request per
     * (group, sender) per hour, in memory: a burst of
     * undecryptable messages must not spam the sender. The
     * responder's own limit (one re-send per member per day) is
     * persisted — see [onKeyRequestReceived].
     */
    suspend fun requestSenderKey(groupId: String, senderAddress: String, subId: Int?, now: Long) {
        val sender = senderAddress.canonicalPhone()
        if (isRequestRateLimited(groupId, sender, now)) return
        markRequested(groupId, sender, now)
        sendManager.sendGroupKeyRequest(
            sender,
            E2eGroupSenderKeyMessage.encodeKeyRequest(groupId),
            now,
            subId,
        )
    }

    private fun isRequestRateLimited(groupId: String, sender: String, now: Long): Boolean {
        synchronized(keyRequests) {
            val last = keyRequests[groupId to sender] ?: return false
            return now - last < KEY_REQUEST_INTERVAL_MS
        }
    }

    private fun markRequested(groupId: String, sender: String, now: Long) {
        synchronized(keyRequests) { keyRequests[groupId to sender] = now }
    }

    /**
     * Handles a received sender-key distribution (payload type
     * `0x04`, after Olm decryption). Validates the sender is a
     * member of the distributed list, and — when our own number
     * is known — that we are in it too; a distribution from a
     * non-member, or for a group we are not in, is dropped (a
     * stale or forged list must not route messages into the
     * wrong conversation). Returns whether the key was stored.
     * When the distribution becomes the sender's current key, we
     * announce our own key back to the sender when they lack a
     * record for it (they just joined, or just reinstalled — they
     * may not have our key yet).
     */
    suspend fun onSenderKeyReceived(
        senderAddress: String,
        plaintext: String,
        subId: Int?,
        now: Long,
    ): Boolean {
        val distribution = E2eGroupSenderKeyMessage.decodeDistribution(plaintext) ?: return false
        val sender = senderAddress.canonicalPhone()
        val own = ownNumber()
        val membershipOk = sender in distribution.members &&
            (own == null || own in distribution.members)
        if (!membershipOk) return false
        val peerCurveKey = runCatching { peers.getPeer(sender) }.getOrNull()?.theirCurveKey
        val stored = groupKeys.storePeerKey(distribution, sender, peerCurveKey, now)
        if (stored !is StoreResult.Stored) return false
        if (stored.isCurrent) announceBackIfNeeded(distribution.members, sender, subId, now)
        return true
    }

    /** Sends our current key to a member that lacks a distribution record for it. */
    private suspend fun announceBackIfNeeded(
        members: List<String>,
        sender: String,
        subId: Int?,
        now: Long,
    ) {
        val ownKey = ensureOwnKey(members, now) ?: return
        if (sender in groupKeys.distributedMembers(ownKey.groupId, ownKey.keyId)) return
        sendKeyToMember(ownKey, sender, subId, now)
    }

    /**
     * Handles a received key request (payload type `0x07`, after
     * Olm decryption): re-sends our CURRENT key for the group to
     * the requester. Silent unless every check passes — an
     * unknown group, a requester who is not a member, or a
     * re-send inside the 24-hour window is ignored. The window
     * is the distribution record: a re-send refreshes it, so a
     * requester cannot make us re-send more than once a day.
     */
    suspend fun onKeyRequestReceived(
        senderAddress: String,
        plaintext: String,
        subId: Int?,
        now: Long,
    ) {
        val groupId = E2eGroupSenderKeyMessage.decodeKeyRequest(plaintext) ?: return
        maybeResendKey(groupId, senderAddress.canonicalPhone(), subId, now)
    }

    private suspend fun maybeResendKey(groupId: String, requester: String, subId: Int?, now: Long) {
        val own = ownNumber() ?: return
        val key = groupKeys.ownCurrentKey(groupId, own) ?: return
        if (requester !in key.members) return
        val lastSent = groupKeys.distributionAt(groupId, requester, key.keyId)
        if (lastSent != null && now - lastSent < KEY_RESEND_INTERVAL_MS) return
        sendKeyToMember(key, requester, subId, now)
    }

    // ----- Notices -----

    /**
     * Records the "Messages are now end-to-end encrypted" notice
     * for a group — once per conversation, on the FIRST encrypted
     * message sent or received. Groups have no single
     * establishment event (members join at different times), so
     * the first encrypted message IS the event. Returns whether
     * this call recorded it (false when it was already there).
     */
    suspend fun recordGroupActivated(conversationId: Long, now: Long): Boolean =
        notices.recordOnce(conversationId, E2eNoticeKind.GROUP_ACTIVATED, now)

    /**
     * The send-side half of the group activation notice (the
     * gate's [E2eGroupSendGate.recordGroupActivated]): the send
     * path calls this after an encrypted group send dispatches,
     * with the thread's addresses — the conversation resolves
     * from the full member list (ourselves included, via the own
     * number). Best-effort: a failure to resolve or record is
     * logged and swallowed — the notice is cosmetic, and the
     * receive path records it on the first encrypted reply.
     */
    override suspend fun recordGroupActivated(memberAddresses: List<String>, now: Long) {
        runCatching {
            val own = ownNumber() ?: return@runCatching
            val full = E2eGroupWireFormat.canonicalMembers(memberAddresses + own)
            val conversationId = resolveConversationId(full) ?: return@runCatching
            recordGroupActivated(conversationId, now)
        }.onFailure { Timber.w(it, "E2E: could not record the group activation notice") }
    }

    /**
     * Fires on a member's identity-key change (the handshake
     * handler calls this after the 1:1 notice): a
     * GROUP_KEY_CHANGED notice in every group conversation where
     * the member held a sender key — GROUP_KEY_CHANGED_VERIFIED
     * when the member was verified (the user must re-verify them
     * before trusting the new key). Only groups with the member's
     * key get the notice: a group that was never encrypted has no
     * key to change.
     */
    suspend fun onPeerKeyChanged(address: String, wasVerified: Boolean, now: Long) {
        val canonical = address.canonicalPhone()
        val kind = if (wasVerified) {
            E2eNoticeKind.GROUP_KEY_CHANGED_VERIFIED
        } else {
            E2eNoticeKind.GROUP_KEY_CHANGED
        }
        for (groupId in groupKeys.groupIdsForSender(canonical)) {
            val conversationId = conversationIdForGroup(groupId, members = null) ?: continue
            notices.record(conversationId, kind, now, subject = canonical)
        }
    }

    /**
     * Fires when a member is about to flip to NOT_RELAY (a plain
     * SMS arrived from them) — the caller MUST call this BEFORE
     * the peer flips, so the state check below still sees the
     * group as it was. A GROUP_DEACTIVATED notice lands in every
     * group that WAS encrypted and contains the member: the group
     * drops to plain, and the thread must say why.
     */
    suspend fun onPeerLeftRelay(address: String, now: Long) {
        val canonical = address.canonicalPhone()
        for (groupId in groupKeys.groupIdsForSender(canonical)) {
            val conversationId = conversationIdForGroup(groupId, members = null) ?: continue
            if (!wasGroupEncrypted(conversationId)) continue
            notices.record(conversationId, E2eNoticeKind.GROUP_DEACTIVATED, now, subject = canonical)
        }
    }

    /** Whether this conversation's group is currently ENCRYPTED (read from its stored addresses). */
    private suspend fun wasGroupEncrypted(conversationId: Long): Boolean {
        val conversation = conversationDao.findById(conversationId) ?: return false
        val thread = PhoneAddress.list(conversation.addressesCsv)
            .map { it.normalized.canonicalPhone() }
        return runCatching { stateProvider.groupState(thread) }.getOrNull() ==
            GroupEncryptionState.Encrypted
    }

    // ----- Conversation resolution -----

    /**
     * The conversation for a group: from the member list when
     * known (a decrypted message's key row), from the stored key
     * rows when only the group id is known (a notice path), or
     * from a candidate-id scan of the group conversations when no
     * key row exists yet (an undecryptable message's placeholder).
     * Null when the group matches no conversation and cannot be
     * created (our own number is unknown — creation needs the
     * thread addresses, which need ourselves removed).
     */
    suspend fun conversationIdForGroup(groupId: String, members: List<String>?): Long? {
        val known = members ?: groupKeys.membersForGroup(groupId)
        return if (known != null) {
            resolveConversationId(known)
        } else {
            findConversationIdForGroupId(groupId)
        }
    }

    /**
     * Resolves a conversation from a full member list: the
     * minus-one match first (no creation — a stray distribution
     * must not create a thread), then find-or-create from the
     * thread addresses when our own number is known.
     */
    suspend fun resolveConversationId(members: List<String>): Long? {
        val found = mirror.findGroupConversationId(members.map { PhoneAddress.of(it) })
        if (found != null) return found
        val own = ownNumber() ?: return null
        val thread = members.filter { it != own }
        return mirror.ensureGroupConversation(thread.map { PhoneAddress.of(it) })
    }

    /**
     * Finds a group conversation by its group id alone: computes
     * the candidate id for every stored group conversation (thread
     * addresses + ourselves) and matches. The fallback for an
     * undecryptable message, where no key row (and no member
     * list) exists yet. Null when our own number is unknown —
     * without it, no candidate id can be formed.
     */
    suspend fun findConversationIdForGroupId(groupId: String): Long? {
        val own = ownNumber() ?: return null
        for (conversation in conversationDao.snapshotGroupConversations()) {
            val thread = PhoneAddress.list(conversation.addressesCsv)
                .map { it.normalized.canonicalPhone() }
            val full = E2eGroupWireFormat.canonicalMembers(thread + own)
            if (E2eGroupWireFormat.deriveGroupIdHex(full) == groupId) return conversation.id
        }
        return null
    }

    companion object {
        private val UTF_8 = StandardCharsets.UTF_8

        /** Requester-side key-request limit: one request per (group, sender) per hour. */
        const val KEY_REQUEST_INTERVAL_MS: Long = 3_600_000L

        /** Responder-side key re-send limit: one re-send per member per day (persisted). */
        const val KEY_RESEND_INTERVAL_MS: Long = 86_400_000L
    }
}
