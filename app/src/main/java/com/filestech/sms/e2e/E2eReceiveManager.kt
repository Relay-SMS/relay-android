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
 *   message also flips the peer to ENCRYPTED, records the ACTIVATED
 *   notice when the thread's latest status is not already "activated",
 *   and retries kept ciphertext. A REACTION payload
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
 * Round 48 — a pre-key message that establishes a NEW inbound
 * session also sends our media-capability declaration
 * ([E2eMediaCapabilityManager.announceNow]): this side answered
 * the probe with a bundle, so its session only exists once the
 * peer's first encrypted message arrives — without this hook the
 * bundle-replier side would never declare. A MEDIA_KEY payload
 * ([E2eMediaKeyMessage]) is the receiver half of media
 * encryption: the key is parsed and stored ([E2eMediaKeyStore])
 * and nothing else happens — no message, no notification, no
 * notice, no re-probe. The ciphertext MMS part (which may
 * already have arrived, or may arrive later) decrypts against
 * the stored key on view; a malformed key JSON is dropped
 * silently (a malformed key is not a message).
 *
 * Round 49 — group messages (message type GROUP, payload
 * GROUP_TEXT / GROUP_REACTION) branch BEFORE the Olm decryptor:
 * they are sender-key encrypted, not Olm-encrypted, and the
 * pairing rule ([E2eWireFormat.isValidPairing]) guarantees a
 * GROUP message never reaches the decryptor. The group session
 * manager ([E2eGroupSessionManager]) decrypts (the sender's
 * sender key), resolves the group conversation from the key
 * row's member list, and records the group activation; a group
 * reaction folds onto its target like a 1:1 reaction (the fold
 * already searches group conversations). An undecryptable group
 * message becomes a placeholder in the group conversation when
 * it resolves (the base64 is kept, like a 1:1 placeholder — the
 * kept-envelope retry does not cover groups in v1, see design
 * §13.9) plus a key request to the sender. SENDER_KEY and
 * GROUP_KEY_REQUEST payloads are the control-message halves of
 * the group protocol: they travel over the pairwise session, so
 * they decrypt here like any 1:1 payload, then hand off to the
 * group session manager — silently, like media keys.
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
    private val mediaCapability: E2eMediaCapabilityManager,
    // Round 48 — the received-media-key store (the MEDIA_KEY
    // payload branch stores here; the MMS part decrypts against
    // it on view).
    private val mediaKeys: E2eMediaKeyStore,
    // Round 49 — the group session manager (group decrypt, key
    // distribution / requests, group notices, conversation
    // resolution).
    private val groupSessions: E2eGroupSessionManager,
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
        // Round 49 — a group message is sender-key encrypted, not
        // Olm-encrypted: it branches before the decryptor (which
        // rejects GROUP outright). The pairing rule guarantees a
        // GROUP message only ever carries a group payload.
        if (payload.message.type == OlmMessageType.GROUP) {
            handleGroupMessage(address, payload, base64, telephonyUri, dateMs, subId, now)
            return
        }
        when (val result = decryptor.decrypt(address, payload)) {
            is E2eSessionDecryptor.Outcome.Decrypted -> {
                val plaintext = result.plaintext.toString(Charsets.UTF_8)
                if (result.newSession) {
                    peers.setState(address, StoredPeerState.ENCRYPTED, now)
                    // Round 48 — this side's session exists now
                    // (it answered the probe with a bundle, so its
                    // session only starts here): declare our media
                    // capability on the channel the exchange
                    // proved. Best-effort (never throws).
                    mediaCapability.announceNow(address, subId, now)
                }
                when (payload.payloadType) {
                    E2eWireFormat.PayloadType.TEXT ->
                        insertDecryptedText(address, plaintext, telephonyUri, dateMs, subId, result.newSession, now)
                    E2eWireFormat.PayloadType.REACTION ->
                        handleDecryptedReaction(address, plaintext, telephonyUri, dateMs, subId, result.newSession, now)
                    // Round 48 — a media key message: store the
                    // key, nothing else. This branch is
                    // functionally required: without it a key
                    // message would decrypt and fall through
                    // silently, and its part would stay pending.
                    E2eWireFormat.PayloadType.MEDIA_KEY ->
                        handleDecryptedMediaKey(plaintext, now)
                    // Round 49 — a sender-key distribution or a
                    // key request: hand off to the group session
                    // manager, silently, like a media key. A
                    // malformed distribution / request is dropped
                    // there — it is not a message.
                    E2eWireFormat.PayloadType.SENDER_KEY ->
                        groupSessions.onSenderKeyReceived(address, plaintext, subId, now)
                    E2eWireFormat.PayloadType.GROUP_KEY_REQUEST ->
                        groupSessions.onKeyRequestReceived(address, plaintext, subId, now)
                    // Round 49 — GROUP payloads never reach the
                    // 1:1 decryptor: GROUP messages branch off
                    // above (handleGroupMessage). Listed so the
                    // `when` stays exhaustive.
                    E2eWireFormat.PayloadType.GROUP_TEXT,
                    E2eWireFormat.PayloadType.GROUP_REACTION,
                    -> Unit
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
     *
     * Round 46 — grace guard, re-anchored in round 47: a plain text does
     * NOT fire the fallback while the peer may not have flipped to
     * encrypted sending yet. The peer flips only when OUR first
     * encrypted (pre-key) message reaches them — and that send can land
     * arbitrarily long after OUR flip (we may not text them for hours),
     * so the round-46 window, measured from our establishment
     * ([E2ePeerEntity.updatedAt][com.filestech.sms.data.local.db.entity.E2ePeerEntity.updatedAt]),
     * fired on a plain reply sent minutes after our first encrypted
     * message (seen on device 2026-11-23: a plain reply minutes after
     * our first encrypted message tore down a session established
     * about an hour earlier). The guard now
     * measures from our first OUTGOING encrypted message on this
     * session (read from the message table — no new column): a plain
     * text within [DEACTIVATED_GRACE_MS] of that send is expected, not
     * proof the peer lost Relay. When we never sent encrypted on this
     * session, the peer cannot have flipped yet, so the fallback waits
     * [DEACTIVATED_NEVER_SENT_MS] — past that the exchange is stale,
     * and downgrading restarts it via the re-probe. The `updatedAt`
     * filter is still an approximation (other writes bump it too), and
     * it fails safe: a bumped `updatedAt` only ever DELAYS a genuine
     * DEACTIVATED, never fires a false one.
     *
     * Round 48 — consecutive-plain override: the grace guard cannot
     * tell a race from a genuine peer switch on timing alone (seen on
     * device 2026-11-23: the peer switched default SMS apps, but the
     * first encrypted send was minutes earlier, so every plain reply
     * fell inside the window and the conversation stayed ENCRYPTED —
     * outgoing stayed encrypted and unreadable to the peer). So a
     * streak of [DEACTIVATED_CONSECUTIVE_PLAIN_THRESHOLD] plain SMS
     * from the peer, with no encrypted message from them in between,
     * fires the fallback even inside the grace window: a race
     * produces at most 1-2 in-flight plain replies (messages the
     * peer sent before processing our encrypted message), while a
     * genuine switch produces an ongoing plain stream — 3 is the
     * smallest count that separates the two. The streak is derived
     * from the message table (plain incoming SMS since the last
     * encrypted incoming message on this session), so it survives
     * restarts and needs no new column. The override applies only
     * to the grace window: when we never sent encrypted, the peer
     * cannot have flipped at all, so the 24-hour backstop governs
     * regardless of the count.
     */
    suspend fun onPlainSmsReceived(
        address: String,
        conversationId: Long,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        val peer = peers.getPeer(address)
        if (peer?.state != StoredPeerState.ENCRYPTED) return
        // Round 47 — the peer could only have flipped after our first
        // encrypted (pre-key) message reached them, so the grace
        // window runs from that send, not from our establishment.
        val firstEncryptedSentAt =
            messageDao.firstOutgoingEncryptedSince(conversationId, peer.updatedAt)
        if (firstEncryptedSentAt != null) {
            if (now - firstEncryptedSentAt < DEACTIVATED_GRACE_MS) {
                // Round 48 — consecutive-plain override: inside the
                // grace window, 3 plain SMS in a row still fire the
                // fallback. A race produces at most 1-2 in-flight
                // plain replies; a peer who switched default SMS apps
                // sends an ongoing plain stream. The count is derived
                // from the message table and includes the plain text
                // that just arrived — it was stored before this call
                // — and any encrypted message from the peer resets
                // it. The query only runs inside the window: past it
                // the fallback fires regardless.
                val consecutivePlain =
                    messageDao.countPlainIncomingSinceLastEncrypted(conversationId, peer.updatedAt)
                if (consecutivePlain < DEACTIVATED_CONSECUTIVE_PLAIN_THRESHOLD) return
            }
        } else if (now - peer.updatedAt < DEACTIVATED_NEVER_SENT_MS) {
            // We never sent encrypted on this session: the peer cannot
            // have flipped yet, so their plain messages are expected.
            // The backstop bounds the wait — past it the exchange is
            // stale and downgrading restarts it via the re-probe.
            return
        }
        // Round 49 — BEFORE the peer flips: the group
        // deactivation notices need the groups as they were (the
        // state check reads "was this group encrypted?"), and
        // after the flip every group with this member reads plain.
        groupSessions.onPeerLeftRelay(address, now)
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
            notices.recordActivated(conversationId, now)
        }
        notifier.notifyIncoming(
            address = address,
            body = plaintext,
            messageId = msgId,
            conversationId = conversationId,
        )
    }

    /**
     * Round 49 — a group message (GROUP_TEXT / GROUP_REACTION):
     * decrypt with the sender's sender key, then insert or fold.
     * An undecryptable message becomes a placeholder + a key
     * request (see [handleUndecryptableGroupMessage]).
     */
    private suspend fun handleGroupMessage(
        address: String,
        payload: E2eWireFormat.DecodedPayload,
        base64: String,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        now: Long,
    ) {
        val decrypted = groupSessions.decryptGroupMessage(address, payload.message.bytes)
        if (decrypted == null) {
            handleUndecryptableGroupMessage(address, payload, base64, telephonyUri, dateMs, subId, now)
            return
        }
        when (payload.payloadType) {
            E2eWireFormat.PayloadType.GROUP_TEXT ->
                insertDecryptedGroupText(
                    address,
                    decrypted.plaintext,
                    decrypted.groupId,
                    decrypted.members,
                    telephonyUri,
                    dateMs,
                    subId,
                    now,
                )
            E2eWireFormat.PayloadType.GROUP_REACTION ->
                handleDecryptedReaction(
                    address,
                    decrypted.plaintext,
                    telephonyUri,
                    dateMs,
                    subId,
                    newSession = false,
                    now = now,
                    group = decrypted,
                )
            // 1:1 payloads never arrive with a GROUP message
            // type — wire pairing (E2eWireFormat.isValidPairing)
            // rejects them at parse time. Listed so the `when`
            // stays exhaustive.
            E2eWireFormat.PayloadType.TEXT,
            E2eWireFormat.PayloadType.REACTION,
            E2eWireFormat.PayloadType.MEDIA_KEY,
            E2eWireFormat.PayloadType.SENDER_KEY,
            E2eWireFormat.PayloadType.GROUP_KEY_REQUEST,
            -> Unit
        }
    }

    /**
     * Round 49 — a decrypted group text: insert into the group
     * conversation (resolved from the key row's member list),
     * record the group activation (once per conversation), and
     * notify like a 1:1 message. When the conversation cannot
     * resolve (our own number is unknown and no stored group
     * matches), the message is dropped — a group message has no
     * 1:1 conversation to fall back to, and showing it in the
     * sender's 1:1 thread would misattribute it.
     */
    private suspend fun insertDecryptedGroupText(
        address: String,
        plaintext: String,
        groupId: String,
        members: List<String>,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        now: Long,
    ) {
        val conversationId = groupSessions.conversationIdForGroup(groupId, members) ?: return
        val msgId = mirror.upsertIncomingGroupSms(
            conversationId = conversationId,
            senderAddress = address,
            body = plaintext,
            date = dateMs,
            telephonyUri = telephonyUri,
            subId = subId,
            encrypted = true,
        )
        groupSessions.recordGroupActivated(conversationId, now)
        notifier.notifyIncoming(
            address = address,
            body = plaintext,
            messageId = msgId,
            conversationId = conversationId,
        )
    }

    /**
     * Round 49 — an undecryptable group message (unknown sender
     * key, or an AEAD open failure): a placeholder in the group
     * conversation when it resolves from the group id alone, plus
     * a key request to the sender (rate-limited). When the
     * conversation cannot resolve, no placeholder is written —
     * there is no thread to show it in — but the key request
     * still goes out, so the NEXT message from that sender
     * decrypts. The base64 is kept on the placeholder like a 1:1
     * placeholder, but the kept-envelope retry does not cover
     * groups in v1 (design §13.9): this message stays a
     * placeholder even after the key arrives.
     */
    private suspend fun handleUndecryptableGroupMessage(
        address: String,
        payload: E2eWireFormat.DecodedPayload,
        base64: String,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        now: Long,
    ) {
        val envelope = E2eGroupWireFormat.parseEnvelope(payload.message.bytes)
        if (envelope == null) {
            Timber.i("E2E: group message with a malformed envelope dropped")
            return
        }
        val conversationId = groupSessions.conversationIdForGroup(envelope.groupIdHex, members = null)
        if (conversationId != null) {
            val msgId = mirror.upsertIncomingGroupSms(
                conversationId = conversationId,
                senderAddress = address,
                body = "",
                date = dateMs,
                telephonyUri = telephonyUri,
                subId = subId,
                encrypted = true,
            )
            messageDao.markDecryptFailed(msgId, base64)
            notifier.notifyIncoming(
                address = address,
                body = context.getString(R.string.e2e_decrypt_failed_notif),
                messageId = msgId,
                conversationId = conversationId,
            )
        }
        groupSessions.requestSenderKey(envelope.groupIdHex, address, subId, now)
    }

    /**
     * An encrypted reaction: the plaintext is the tapback string (design
     * §3.2 — "the existing reaction payload, encrypted"). Fold it onto
     * its target like a plain tapback; when no target matches, store the
     * decrypted body verbatim (the plain path's fall-through) so nothing
     * is silently lost. Round 49 — [group] is the decrypted group
     * message for a GROUP_REACTION: the fold already searches group
     * conversations, the fall-through inserts into the GROUP
     * conversation (never the 1:1), and a folded group reaction
     * records the group activation instead of the 1:1 one.
     */
    private suspend fun handleDecryptedReaction(
        address: String,
        plaintext: String,
        telephonyUri: String?,
        dateMs: Long,
        subId: Int?,
        newSession: Boolean,
        now: Long,
        group: GroupDecrypted? = null,
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
            // plain path when a tapback matches nothing — in the group
            // conversation for a group reaction, never the 1:1.
            if (group != null) {
                insertDecryptedGroupText(
                    address,
                    plaintext,
                    group.groupId,
                    group.members,
                    telephonyUri,
                    dateMs,
                    subId,
                    now,
                )
            } else {
                insertDecryptedText(address, plaintext, telephonyUri, dateMs, subId, newSession, now)
            }
            return
        }
        if (group != null) {
            groupSessions.recordGroupActivated(applied.conversationId, now)
        } else if (newSession) {
            notices.recordActivated(applied.conversationId, now)
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
     * Round 48 — an encrypted media key message: the plaintext is
     * the key JSON ([E2eMediaKeyMessage.decode]). On success the
     * key is stored; the ciphertext MMS part — which may already
     * have arrived (pending, waiting for this key) or may arrive
     * later (the key waits) — decrypts against it on view.
     * Pending is derived (encrypted + not failed + no key row),
     * so nothing is written to the message table here: the
     * thread's key Flow re-emits and the pending row flips to
     * the decrypted bubble, and the sync engine re-pushes the
     * conversation on the store's arrival signal.
     *
     * A malformed key is dropped SILENTLY: no placeholder, no
     * notification, no re-probe — a malformed key is not a
     * message, and the part stays pending until its key arrives
     * (or the 24-hour prune fails it). Only the error class is
     * logged — never key bytes. Note the asymmetry with the text
     * path: a key message whose SESSION decrypt fails never
     * reaches this method (it becomes a placeholder + re-probe
     * like any undecryptable payload — we cannot even tell it
     * was a key message). Only a successfully-decrypted but
     * malformed key skips the re-probe.
     */
    private suspend fun handleDecryptedMediaKey(plaintext: String, now: Long) {
        val key = E2eMediaKeyMessage.decode(plaintext)
        if (key == null) {
            Timber.i("E2E: malformed media key message dropped")
            return
        }
        mediaKeys.store(key, now)
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

        /**
         * Round 46 — DEACTIVATED grace window (1 hour), re-anchored in
         * round 47: a plain SMS arriving within this window of OUR
         * FIRST encrypted send to the peer does not fire the peer-left
         * fallback — the peer may simply not have flipped to ENCRYPTED
         * yet (it needs our pre-key message first).
         */
        const val DEACTIVATED_GRACE_MS = 60 * 60 * 1000L

        /**
         * Round 47 — DEACTIVATED backstop (24 hours): when no
         * encrypted message ever went out on this session, the peer
         * cannot have flipped to encrypted sending yet, so the
         * peer-left fallback waits this long after the establishment
         * before firing. Past it the exchange is stale — downgrading
         * restarts it via the re-probe, and our next messages go out
         * plain (readable) instead of as fragments the peer cannot
         * read.
         */
        const val DEACTIVATED_NEVER_SENT_MS = 24 * DEACTIVATED_GRACE_MS

        /**
         * Round 48 — consecutive-plain override threshold: this many
         * plain SMS in a row from an ENCRYPTED peer (no encrypted
         * message from them in between) fires the peer-left fallback
         * even inside the grace window. A race produces at most 1-2
         * in-flight plain replies; a genuine switch produces an
         * ongoing plain stream — 3 is the smallest count that
         * separates the two. Derived from the message table, so it
         * survives restarts.
         */
        const val DEACTIVATED_CONSECUTIVE_PLAIN_THRESHOLD = 3
    }
}
