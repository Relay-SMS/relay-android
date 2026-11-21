package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * Handles the handshake data SMS (design §2.3): probes and bundle
 * replies on port [E2eHandshake.E2E_DATA_SMS_PORT].
 *
 * - **Probe received** → record the peer's identity keys, reply with a
 *   bundle (our identity keys + one one-time key), rate-limited to one
 *   bundle per sender per day. A probe from a known peer with
 *   DIFFERENT identity keys is a key-change event (design §4.4): the
 *   verified flag clears and a KEY_CHANGED notice is recorded.
 * - **Bundle received** → create the outbound session, flip the peer
 *   to ENCRYPTED, record the one-time ACTIVATED notice. First reply
 *   wins (§2.4 dual-install): a bundle identical to one already used
 *   (same keys + a live current session) is ignored. A bundle with
 *   different keys from a known peer is a key change: the new session
 *   becomes current, the old one stays as a fallback, and kept
 *   ciphertext is retried.
 *
 * Anything that is not one of our packets (bad magic, version, type,
 * length) is dropped silently — a random data SMS to this port is not
 * our business. Blocked senders are dropped too, like on the SMS path.
 *
 * Error-log rules (§8.3): no keys, no addresses — state transitions
 * and conversation ids only.
 */
@Singleton
class E2eHandshakeHandler @Inject constructor(
    private val peers: E2ePeerRepository,
    private val sessions: dagger.Lazy<E2eSessionStore>,
    private val accountManager: dagger.Lazy<E2eAccountManager>,
    private val crypto: dagger.Lazy<E2eCrypto>,
    private val decryptor: E2eSessionDecryptor,
    private val notices: E2eNoticeStore,
    private val dataSmsSender: E2eDataSmsSender,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val phoneIdentity: PhoneIdentity,
    private val blockedRepo: BlockedNumberRepository,
) {

    /**
     * Handles one received data SMS. Returns `true` when the packet was
     * one of ours (probe or bundle) and was processed; `false` when it
     * was dropped (not our packet, blocked sender, or a failed bundle
     * build). Never throws.
     */
    suspend fun handleDataSms(
        address: String,
        data: ByteArray,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val packet = E2eHandshake.decode(data) ?: return false
        if (runCatching { blockedRepo.isBlocked(address) }.getOrDefault(false)) return false
        return when (packet) {
            is E2eHandshake.HandshakePacket.Probe -> handleProbe(address, packet, subId, now)
            is E2eHandshake.HandshakePacket.Bundle -> handleBundle(address, packet, now)
        }
    }

    /**
     * Retries every undecryptable placeholder from a peer against the
     * current sessions (design §4.4 step 3): after a re-establishment,
     * kept ciphertext may decrypt after all. Returns the number of
     * placeholders that became readable messages. Called after a bundle
     * establishes a new session, and after a pre-key message does.
     */
    suspend fun retryDecryptFailed(address: String): Int {
        var retried = 0
        for (row in messageDao.findDecryptFailed(address)) {
            val plaintext = decryptKept(row.ciphertext, address) ?: continue
            messageDao.update(
                row.copy(body = plaintext, decryptFailed = false, ciphertext = null),
            )
            messageDao.refreshConversationPreview(row.conversationId)
            retried++
        }
        if (retried > 0) Timber.i("E2E: %d kept ciphertext(s) decrypted on retry", retried)
        return retried
    }

    private suspend fun handleProbe(
        address: String,
        probe: E2eHandshake.HandshakePacket.Probe,
        subId: Int?,
        now: Long,
    ): Boolean {
        val peer = peers.getPeer(address)
        val curveB64 = Base64.getEncoder().encodeToString(probe.curveKey)
        val edB64 = Base64.getEncoder().encodeToString(probe.edKey)
        if (keysChanged(peer, curveB64, edB64)) {
            onKeyChanged(address, peer?.verified == true, now)
        }
        peers.recordKeys(address, curveB64, edB64, now)
        // The exchange is in flight — unless we are already ENCRYPTED
        // (a key change keeps the state, §4.4 step 3).
        if (peer?.state != StoredPeerState.ENCRYPTED) {
            peers.setState(address, StoredPeerState.PROBING, now)
        }
        // Rate limit: at most one bundle per sender per day (§2.3).
        // The keys above are recorded regardless — only the reply is
        // gated, so a re-probe tomorrow still finds fresh keys.
        if (!E2eProbePolicy.canReplyWithBundle(peer?.lastBundleReplyAt, now)) {
            Timber.i("E2E: bundle reply rate-limited (one per sender per day)")
            return true
        }
        val account = accountManager.get()
        val oneTimeKey = account.nextOneTimeKey() ?: run {
            // The pool is empty (a previous reply published the batch):
            // top it up and take a key from the fresh batch.
            account.generateOneTimeKeys()
            account.nextOneTimeKey()
        } ?: return false
        val keys = account.identityKeys()
        val bundle = E2eHandshake.encodeBundle(keys.curve25519, keys.ed25519, oneTimeKey)
        val sent = dataSmsSender.sendDataMessage(
            address = address,
            subId = subId,
            port = E2eHandshake.E2E_DATA_SMS_PORT,
            data = bundle,
        )
        // Record the reply ONLY after the bundle SMS went out — a
        // failed send must not consume the day's reply.
        if (!sent) return false
        peers.recordBundleReply(address, now)
        // Handing a key out published the whole batch: top the pool
        // back up (and re-save the Account pickle).
        account.generateOneTimeKeys()
        Timber.i("E2E: bundle reply sent")
        return true
    }

    private suspend fun handleBundle(
        address: String,
        bundle: E2eHandshake.HandshakePacket.Bundle,
        now: Long,
    ): Boolean {
        val peer = peers.getPeer(address)
        val curveB64 = Base64.getEncoder().encodeToString(bundle.curveKey)
        val edB64 = Base64.getEncoder().encodeToString(bundle.edKey)
        // First reply wins (§2.4 dual-install): a bundle identical to
        // the one that already built our current session is the second
        // install answering the same probe — ignore it.
        if (!keysChanged(peer, curveB64, edB64) &&
            sessions.get().currentSessionId(address) != null
        ) {
            Timber.i("E2E: duplicate bundle ignored (first reply wins)")
            return true
        }
        if (keysChanged(peer, curveB64, edB64)) {
            onKeyChanged(address, peer?.verified == true, now)
        }
        peers.recordKeys(address, curveB64, edB64, now)
        // The Account must be loaded before creating a session (and a
        // first E2E use creates it here).
        accountManager.get().ensureAccount()
        val sessionId = runCatching {
            crypto.get().createOutboundSession(bundle.curveKey, bundle.oneTimeKey)
        }.getOrElse {
            Timber.w(it, "E2E: outbound session creation failed")
            return false
        }
        // The new session becomes current; the old one, if any, stays
        // as a fallback for late messages (§4.2).
        sessions.get().saveNewSession(address, sessionId, now)
        peers.setState(address, StoredPeerState.ENCRYPTED, now)
        conversationIdForAddress(address)?.let { conversationId ->
            notices.recordOnce(conversationId, E2eNoticeKind.ACTIVATED, now)
        }
        Timber.i("E2E: peer state -> ENCRYPTED (bundle received)")
        retryDecryptFailed(address)
        return true
    }

    /**
     * A key-change event (§4.4): the peer's identity keys differ from
     * the ones on file (reinstall / new phone). Messages keep flowing
     * (TOFU + notice): the verified flag clears and an in-thread notice
     * records the change. The caller re-establishes the session.
     */
    private suspend fun onKeyChanged(address: String, wasVerified: Boolean, now: Long) {
        peers.setVerified(address, false, now)
        val kind = if (wasVerified) {
            E2eNoticeKind.KEY_CHANGED_VERIFIED
        } else {
            E2eNoticeKind.KEY_CHANGED
        }
        conversationIdForAddress(address)?.let { conversationId ->
            notices.record(conversationId, kind, now)
            Timber.i("E2E: peer key changed (conversation=%d, wasVerified=%b)", conversationId, wasVerified)
        }
    }

    /** True when the peer has keys on file and either differs from these. */
    private fun keysChanged(peer: E2ePeerEntity?, curveB64: String, edB64: String): Boolean {
        val storedCurve = peer?.theirCurveKey ?: return false
        val storedEd = peer.theirEdKey ?: return false
        return storedCurve != curveB64 || storedEd != edB64
    }

    /** Decrypts one kept ciphertext, or null when it still cannot be read. */
    private suspend fun decryptKept(ciphertext: String?, address: String): String? {
        val payload = if (ciphertext.isNullOrBlank()) {
            null
        } else {
            runCatching {
                E2eWireFormat.parsePayload(Base64.getDecoder().decode(ciphertext))
            }.getOrNull()
        } ?: return null
        // Only text payloads become messages on retry: a kept reaction
        // payload has no placeholder text to restore (reactions fold
        // onto their target on the live path, never as bubbles).
        if (payload.payloadType != E2eWireFormat.PayloadType.TEXT) return null
        return when (val result = decryptor.decrypt(address, payload)) {
            is E2eSessionDecryptor.Outcome.Decrypted ->
                result.plaintext.toString(Charsets.UTF_8)
            E2eSessionDecryptor.Outcome.Failed -> null
        }
    }

    /**
     * The 1:1 conversation for an address, for in-thread notices. Exact
     * CSV match first, then the identity fallback for representation
     * drift (`+33` vs `06`) — the same two-step lookup the reaction
     * fold uses. Null when no conversation exists (yet): a notice has
     * nowhere to render, so it is skipped.
     */
    private suspend fun conversationIdForAddress(address: String): Long? {
        conversationDao.findByAddressesCsv(address)?.let { return it.id }
        val ident = phoneIdentity.snapshot()
        return conversationDao.snapshotOneToOneConversations().firstOrNull { conv ->
            val raw = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw
            raw != null && ident.matches(raw, address)
        }?.id
    }
}
