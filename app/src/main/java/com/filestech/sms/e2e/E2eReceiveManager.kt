package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * The ciphertext receive path (design §3.5).
 *
 * [SmsDeliverReceiver][com.filestech.sms.system.receiver.SmsDeliverReceiver]
 * hands every `RLY1:` fragment here instead of inserting it as a message.
 * Fragments are stored in [E2eFragmentStore] (the store is 0-based, the
 * wire format 1-based — the index is converted on the way in); when the
 * final fragment of a set arrives, the set is reassembled, base64-decoded
 * and decrypted:
 *
 * - **Success** → ONE message is inserted with the decrypted body and
 *   `encrypted = 1`, and the fragment rows are deleted. A pre-key
 *   message also flips the peer to ENCRYPTED, records the one-time
 *   ACTIVATED notice, and retries kept ciphertext. A REACTION payload
 *   folds onto its target like a plain tapback instead of becoming a
 *   bubble.
 * - **Failure** (no session, wrong session, corrupt payload) → a
 *   placeholder row (`encrypted = 1`, `decrypt_failed = 1`, ciphertext
 *   kept for a later retry), rendered as "This message couldn't be
 *   decrypted." — raw base64 is never shown — plus a rate-limited
 *   re-probe, since an undecryptable message usually means the peer
 *   reinstalled.
 *
 * A completed set is handled at most once per process lifetime
 * ([processedSets]): a sender retry re-sends the full set with the same
 * message id, and without the guard a re-sent set would insert a
 * duplicate message. Recovery after a failure does not need re-sent
 * fragments — the placeholder keeps the ciphertext and the retry path
 * re-decrypts it once a new session exists.
 *
 * Round 41 — the plain-text fallback ([onPlainSmsReceived]) lives
 * here too, though it is not ciphertext: a plain SMS text from a
 * peer marked ENCRYPTED means they lost Relay (an encrypted peer
 * never sends plain via Relay), so the peer drops to UNKNOWN, the
 * verified flag clears, a re-probe goes out, and the thread gets
 * an "Encryption ended" notice. Only the plain-SMS path calls it —
 * RLY1 fragments route to [handleFragment] and MMS never reaches
 * this receiver — so neither can trigger it.
 *
 * Error-log rules (§8.3): message ids (6 hex chars, meaningless alone)
 * and error classes only — never plaintext, ciphertext, or addresses.
 */
@Singleton
class E2eReceiveManager @Inject constructor(
    private val fragments: E2eFragmentStore,
    private val peers: E2ePeerRepository,
    private val decryptor: E2eSessionDecryptor,
    private val notices: E2eNoticeStore,
    private val mirror: ConversationMirror,
    private val messageDao: MessageDao,
    private val conversationRepo: ConversationRepository,
    private val notifier: IncomingMessageNotifier,
    private val probeManager: E2eProbeManager,
    private val handshakeHandler: E2eHandshakeHandler,
    @ApplicationContext private val context: Context,
) {
    private val processedLock = Any()
    private val processedSets = LinkedHashSet<Pair<String, String>>()

    /**
     * Handles one received fragment. [telephonyUri] is the system-inbox
     * row the receiver just wrote for this fragment SMS; the decrypted
     * message links to the completing fragment's row.
     */
    suspend fun handleFragment(
        address: String,
        fragment: E2eWireFormat.Fragment,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        // The fragment store is 0-based; the wire format is 1-based.
        fragments.insert(address, fragment.messageId, fragment.index - 1, fragment.total, fragment.chunk, now)
        val base64 = fragments.reassemble(address, fragment.messageId) ?: return
        // A re-sent set (same sender + id) is dropped: the message was
        // already handled. Marked BEFORE decrypting so a duplicate
        // arriving mid-handling cannot double-insert.
        if (!markProcessed(address to fragment.messageId)) {
            fragments.deleteSet(address, fragment.messageId)
            return
        }
        fragments.deleteSet(address, fragment.messageId)
        val payload = decodePayload(base64)
        if (payload == null) {
            Timber.i("E2E: fragment set %s is not a valid payload", fragment.messageId)
            insertPlaceholder(address, base64, telephonyUri, dateMs, subId)
            probeManager.reprobeAfterDecryptFailure(address, subId)
            return
        }
        when (val result = decryptor.decrypt(address, payload)) {
            is E2eSessionDecryptor.Outcome.Decrypted -> {
                val plaintext = result.plaintext.toString(Charsets.UTF_8)
                if (result.newSession) {
                    peers.setState(address, StoredPeerState.ENCRYPTED, now)
                }
                when (payload.payloadType) {
                    E2eWireFormat.PayloadType.TEXT ->
                        insertDecryptedText(address, plaintext, telephonyUri, dateMs, subId, result.newSession, now)
                    E2eWireFormat.PayloadType.REACTION ->
                        handleDecryptedReaction(address, plaintext, telephonyUri, dateMs, subId, result.newSession, now)
                }
                if (result.newSession) {
                    handshakeHandler.retryDecryptFailed(address)
                }
            }
            E2eSessionDecryptor.Outcome.Failed -> {
                Timber.i("E2E: fragment set %s could not be decrypted", fragment.messageId)
                insertPlaceholder(address, base64, telephonyUri, dateMs, subId)
                probeManager.reprobeAfterDecryptFailure(address, subId)
            }
        }
    }

    /**
     * Round 41 — peer-deleted-Relay fallback: called by the plain-SMS
     * path for every inbound SMS text (after the message is stored).
     * A plain text from a peer marked ENCRYPTED means they lost Relay,
     * so the peer drops to UNKNOWN — NOT NotRelay, which would block
     * the re-probe — the verified flag clears (as on a key change), a
     * re-probe goes out (E2E resumes if they reinstall), and the
     * thread gets a DEACTIVATED notice mirroring the ACTIVATED one.
     *
     * The state gate is the dedupe: the first plain text flips the
     * peer to UNKNOWN, so later texts from the same peer return
     * early — one notice, one re-probe per ENCRYPTED→UNKNOWN
     * transition, which is why the notice uses [E2eNoticeStore.record]
     * rather than `recordOnce` (a peer who cycles delete → reinstall
     * → delete deserves a fresh notice each time). MMS and encrypted
     * messages never reach this method (separate receivers / the
     * fragment path), so they can never trigger it.
     */
    suspend fun onPlainSmsReceived(
        address: String,
        conversationId: Long,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (peers.state(address) != StoredPeerState.ENCRYPTED) return
        peers.setState(address, StoredPeerState.UNKNOWN, now)
        peers.setVerified(address, false, now)
        notices.record(conversationId, E2eNoticeKind.DEACTIVATED, now)
        probeManager.reprobeAfterPeerLeft(address, subId)
    }

    private fun decodePayload(base64: String): E2eWireFormat.DecodedPayload? {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
            ?: return null
        return runCatching { E2eWireFormat.parsePayload(bytes) }.getOrNull()
    }

    private suspend fun insertDecryptedText(
        address: String,
        plaintext: String,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        newSession: Boolean,
        now: Long,
    ) {
        val msgId = mirror.upsertIncomingSms(
            address = address,
            body = plaintext,
            date = dateMs,
            telephonyUri = telephonyUri,
            subId = subId,
            encrypted = true,
        )
        val conversationId = conversationRepo.findMessageForResend(msgId)?.conversationId
            ?: return
        if (newSession) {
            notices.recordOnce(conversationId, E2eNoticeKind.ACTIVATED, now)
        }
        notifier.notifyIncoming(
            address = address,
            body = plaintext,
            messageId = msgId,
            conversationId = conversationId,
        )
    }

    /**
     * An encrypted reaction: the plaintext is the tapback string (design
     * §3.2 — "the existing reaction payload, encrypted"). Fold it onto
     * its target like a plain tapback; when no target matches, store the
     * decrypted body verbatim (the plain path's fall-through) so nothing
     * is silently lost.
     */
    private suspend fun handleDecryptedReaction(
        address: String,
        plaintext: String,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        newSession: Boolean,
        now: Long,
    ) {
        val decoded = IncomingReactionDecoder.decode(plaintext)
        val applied = decoded?.let {
            runCatching {
                mirror.applyIncomingReaction(
                    address = address,
                    emoji = it.emoji,
                    bodyPrefix = it.previewPrefix,
                    kind = it.kind,
                    wasTruncated = it.wasTruncated,
                    isRemoval = it.isRemoval,
                    reactionDateMs = dateMs,
                )
            }.getOrNull()
        }
        if (decoded == null || applied == null) {
            // No fold: the decrypted body becomes a message, as on the
            // plain path when a tapback matches nothing.
            insertDecryptedText(address, plaintext, telephonyUri, dateMs, subId, newSession, now)
            return
        }
        if (newSession) {
            notices.recordOnce(applied.conversationId, E2eNoticeKind.ACTIVATED, now)
        }
        if (decoded.isRemoval) return
        // A removal only clears the badge — nothing to notify about.
        // Otherwise notify like the plain reaction path does.
        val targetPreview = applied.targetBody.stripInvisibleChars().trim().take(80)
        val notifBody = if (targetPreview.isEmpty()) {
            context.getString(R.string.reaction_notif_body_no_preview, decoded.emoji)
        } else {
            context.getString(
                R.string.reaction_notif_body_with_preview,
                decoded.emoji,
                targetPreview,
            )
        }
        notifier.notifyIncoming(
            address = address,
            body = notifBody,
            messageId = applied.targetMessageId,
            conversationId = applied.conversationId,
        )
    }

    /**
     * The undecryptable placeholder (design §3.5, §6.5): an empty-bodied
     * row with `encrypted = 1` / `decrypt_failed = 1` and the raw base64
     * kept for a later retry. The thread renders it as "This message
     * couldn't be decrypted." — the ciphertext is never shown.
     */
    private suspend fun insertPlaceholder(
        address: String,
        base64Ciphertext: String,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
    ) {
        val msgId = mirror.upsertIncomingSms(
            address = address,
            body = "",
            date = dateMs,
            telephonyUri = telephonyUri,
            subId = subId,
            encrypted = true,
        )
        messageDao.markDecryptFailed(msgId, base64Ciphertext)
        val conversationId = conversationRepo.findMessageForResend(msgId)?.conversationId
            ?: return
        notifier.notifyIncoming(
            address = address,
            body = context.getString(R.string.e2e_decrypt_failed_notif),
            messageId = msgId,
            conversationId = conversationId,
        )
    }

    /** Records a set as handled; `false` when it was already handled. Bounded, oldest-first eviction. */
    private fun markProcessed(key: Pair<String, String>): Boolean = synchronized(processedLock) {
        val added = processedSets.add(key)
        if (processedSets.size > MAX_PROCESSED_SETS) {
            processedSets.remove(processedSets.first())
        }
        added
    }

    companion object {
        /** Completed sets remembered as handled (duplicate suppression for sender retries). */
        const val MAX_PROCESSED_SETS = 256
    }
}
