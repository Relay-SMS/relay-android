package com.filestech.sms.e2e

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * The [E2eSendGate] implementation (design §3.4): encrypt + fragment +
 * dispatch for 1:1 texts to ENCRYPTED peers.
 *
 * - [decideSend] reads the peer state: ENCRYPTED + within the
 *   8,000-character cap → Encrypted; ENCRYPTED + over the cap →
 *   RefuseOverCap (never a silent fall back to plain); anything else →
 *   Plain. A state-read failure fails OPEN to Plain — the mirror write
 *   that follows would fail on the same broken database anyway.
 * - [sendEncrypted] encrypts with the peer's current session, fragments
 *   via [E2eWireFormat], writes each fragment to the system sent box
 *   (ciphertext SMS are stored like any SMS), and dispatches each
 *   fragment as its own single-part send with per-fragment
 *   sent/delivery intents. The session is re-pickled right after
 *   encrypting: the ratchet advanced, and the stored pickle must not
 *   lag behind the sent message.
 *
 * Retry (design §3.4): a retry re-sends the FULL fragment set with the
 * SAME message id. Re-encrypting would produce different bytes under
 * the same id and mix two ciphertexts into one set at the receiver —
 * so the first send stores the payload base64 on the message row
 * (`messages.ciphertext`, the same column that holds kept ciphertext
 * for undecryptable incoming rows), and a retry re-fragments those
 * exact bytes. When the row holds no ciphertext (an older build's row,
 * or the store failed), the retry re-encrypts under a FRESH id
 * (derived from local id + attempt) so the new set can never mix with
 * the old partial one.
 *
 * Round 48 — media key messages ([sendMediaKey]): the per-file key +
 * metadata for one encrypted MMS attachment, sent as fragments with
 * payload type MEDIA_KEY. They deliberately do NOT reuse
 * [sendEncrypted]: that path is built around a message row (the
 * fragment-set id derives from the row id, the payload base64 is
 * stored on the row, and the fragment sent/delivery intents update
 * the row's status) — and a key message has no row. Wiring its
 * fragments to the MMS row's id would let a key fragment's async
 * sent-failure mark the MMS FAILED (and its delivery report mark it
 * DELIVERED) even when the MMS itself went out fine. Key fragments
 * therefore travel like handshake texts: a random fragment-set id,
 * no ciphertext stored anywhere, and the diagnostic-only sent
 * intent ([E2eDataSmsSender.sendTextMessage]) whose verdict lands
 * in [E2eDataSmsSentReceiver], never in the message-status
 * receivers. The only signal is the radio's synchronous accept —
 * a refusal fails the send, and the caller ([E2eMediaGateImpl])
 * falls back to plain MMS.
 *
 * The session store, account manager, and crypto are taken lazily (see
 * [E2eProbeManager]): resolving them provisions [E2eCrypto] (the
 * first native call loads the crypto library), and this manager is
 * injected into the send use cases — it must be cheap to construct.
 */
@Singleton
class E2eSendManager @Inject constructor(
    private val peers: E2ePeerRepository,
    private val sessions: dagger.Lazy<E2eSessionStore>,
    private val accountManager: dagger.Lazy<E2eAccountManager>,
    private val crypto: dagger.Lazy<E2eCrypto>,
    private val sender: SmsSender,
    private val recorder: SentSmsRecorder,
    private val messageDao: MessageDao,
    private val probeManager: E2eProbeManager,
    private val dataSmsSender: E2eDataSmsSender,
) : E2eSendGate {

    override suspend fun decideSend(address: String, body: String): E2eSendGate.Decision {
        val state = runCatching { peers.state(address) }.getOrNull()
            ?: return E2eSendGate.Decision.Plain
        if (state != StoredPeerState.ENCRYPTED) return E2eSendGate.Decision.Plain
        return if (E2eWireFormat.plaintextExceedsCap(body)) {
            E2eSendGate.Decision.RefuseOverCap
        } else {
            E2eSendGate.Decision.Encrypted
        }
    }

    override suspend fun sendEncrypted(
        localMessageId: Long,
        address: String,
        body: String,
        date: Long,
        subId: Int?,
        requestDeliveryReport: Boolean,
        attempt: Int,
        payloadType: E2ePayloadType,
    ): Outcome<Unit> {
        if (E2eWireFormat.plaintextExceedsCap(body)) {
            return Outcome.Failure(AppError.Validation("message too long to send encrypted"))
        }
        val plan = when (
            val resolution = resolvePlan(localMessageId, address, body, attempt, payloadType)
        ) {
            is Resolution.Ready -> resolution.plan
            is Resolution.Failed -> return Outcome.Failure(resolution.error)
        }
        if (plan is Plan.Fresh) {
            // Keep the payload bytes on the row so a retry re-sends the
            // exact same fragment set (see the class KDoc). A failed
            // store is not fatal: the retry then re-encrypts under a
            // fresh id instead.
            runCatching { messageDao.setCiphertext(localMessageId, plan.base64) }
                .onFailure { Timber.w(it, "E2E: could not store outgoing ciphertext") }
        }
        for ((index, fragment) in plan.fragments.withIndex()) {
            // The ciphertext SMS is stored like any SMS (design §3.1):
            // the system store holds what went out on the air.
            recorder.insertSentSms(address, fragment, date, subId = subId)
            val result = sender.sendFragment(
                localMessageId = localMessageId,
                destination = address,
                text = fragment,
                fragmentIndex = index,
                fragmentTotal = plan.fragments.size,
                subId = subId,
                requestDeliveryReport = requestDeliveryReport,
                attempt = attempt,
            )
            if (result is Outcome.Failure) return result
        }
        Timber.i("E2E: encrypted send dispatched (%d fragment(s))", plan.fragments.size)
        return Outcome.Success(Unit)
    }

    override fun probeForSend(address: String, subId: Int?) {
        probeManager.probeForSend(address, subId)
    }

    /**
     * Round 48 — sends one media key message ([E2eMediaKeyMessage]:
     * the per-file key + metadata JSON) to a media-capable peer as
     * encrypted fragments with payload type MEDIA_KEY. [keyJson] is
     * the already-encoded JSON plaintext; [date] is the MMS
     * message's timestamp, written on the fragment rows in the
     * system sent box.
     *
     * No message row is involved (see the class KDoc for why): the
     * fragment-set id is random, no ciphertext is stored, and the
     * fragments go out through [E2eDataSmsSender.sendTextMessage]
     * (diagnostic-only sent intent, no row-coupled status updates).
     * A failure anywhere — no session, encrypt failure, a fragment
     * the radio refuses — returns [Outcome.Failure] and the caller
     * falls back to plain MMS; a fragment accepted by the radio is
     * never retried (there is no MMS retry to attach one to, and
     * the key message is idempotent — a re-send would only
     * duplicate the key row on the receiver).
     */
    suspend fun sendMediaKey(
        address: String,
        keyJson: String,
        date: Long,
        subId: Int?,
    ): Outcome<Unit> {
        val messageId = E2eWireFormat.generateMessageId()
        val plan = when (
            val resolution = encryptToFragments(
                address = address,
                body = keyJson,
                messageId = messageId,
                payloadType = E2ePayloadType.MEDIA_KEY,
            )
        ) {
            is Resolution.Ready -> resolution.plan
            is Resolution.Failed -> return Outcome.Failure(resolution.error)
        }
        for (fragment in plan.fragments) {
            // The ciphertext SMS is stored like any SMS (design §3.1):
            // the system store holds what went out on the air.
            recorder.insertSentSms(address, fragment, date, subId = subId)
            val sent = dataSmsSender.sendTextMessage(
                address = address,
                subId = subId,
                text = fragment,
                kind = DataSmsKind.MEDIA_KEY,
            )
            if (!sent) {
                Timber.w("E2E: media key fragment refused by the radio")
                return Outcome.Failure(
                    AppError.Telephony("media key message could not be sent"),
                )
            }
        }
        Timber.i("E2E: media key sent (%d fragment(s))", plan.fragments.size)
        return Outcome.Success(Unit)
    }

    /**
     * The fragment set to send: either freshly encrypted ([Plan.Fresh])
     * or re-fragmented from the bytes the first attempt stored
     * ([Plan.Stored]).
     */
    private sealed interface Plan {
        val fragments: List<String>

        /** Freshly encrypted bytes (first attempt, or no stored bytes to reuse). */
        data class Fresh(val base64: String, override val fragments: List<String>) : Plan

        /** Re-fragmented from the stored payload bytes (retry, byte-identical). */
        data class Stored(override val fragments: List<String>) : Plan
    }

    /**
     * What [resolvePlan] found: a fragment set ready to send, or a
     * failure with the error to report. The too-large failure is
     * distinct from the character cap: 8,000 characters of multi-byte
     * text can exceed the fragment budget even inside the cap, and
     * the send must refuse (never fall back to plain) with the right
     * error.
     */
    private sealed interface Resolution {
        data class Ready(val plan: Plan) : Resolution
        data class Failed(val error: AppError) : Resolution
    }

    private suspend fun resolvePlan(
        localMessageId: Long,
        address: String,
        body: String,
        attempt: Int,
        payloadType: E2ePayloadType,
    ): Resolution {
        // A retry re-sends the stored bytes when the row still holds
        // them — same fragment set, same message id. The stored bytes
        // carry their own payload type (a reaction retry stays a
        // reaction); [payloadType] only applies to a fresh encrypt.
        if (attempt > 0) {
            val stored = runCatching { messageDao.findById(localMessageId)?.ciphertext }
                .getOrNull()
            if (!stored.isNullOrBlank()) {
                val fragments = fragmentStored(stored, messageIdForLocalId(localMessageId, 0))
                if (fragments != null) return Resolution.Ready(Plan.Stored(fragments))
                // Stored bytes that no longer fragment (corrupt row):
                // fall through to a fresh encrypt under a fresh id.
                Timber.w("E2E: stored ciphertext would not re-fragment; re-encrypting")
            }
        }
        return encryptFresh(localMessageId, address, body, attempt, payloadType)
    }

    /**
     * Loads the peer's current session into the crypto instance, or
     * null when there is no usable session. The Account must be loaded
     * BEFORE any session is loaded: the first `ensureAccount` unpickles
     * the stored Account, and the unpickle closes every live session —
     * a session loaded before it would be wiped before it is used.
     */
    private suspend fun loadCurrentSession(address: String): String? {
        val sessionId = sessions.get().currentSessionId(address) ?: return null
        // The Account load throws when crypto is unavailable (the
        // native library failed to load — see UnavailableE2eCrypto):
        // no session can be used then, so the encrypt fails cleanly
        // instead of throwing out of the send path.
        runCatching { accountManager.get().ensureAccount() }
            .onFailure { Timber.w(it, "E2E: cannot load account for encrypt") }
            .getOrNull() ?: return null
        return if (sessions.get().loadSession(address, sessionId)) sessionId else null
    }

    /** Encrypts [body] under the peer's current session and fragments it. */
    private suspend fun encryptFresh(
        localMessageId: Long,
        address: String,
        body: String,
        attempt: Int,
        payloadType: E2ePayloadType,
    ): Resolution {
        return encryptToFragments(
            address = address,
            body = body,
            messageId = messageIdForLocalId(localMessageId, attempt),
            payloadType = payloadType,
        )
    }

    /**
     * Encrypts [body] under the peer's current session and fragments
     * it under [messageId]. The shared core of [encryptFresh] (row
     * sends, whose id derives from the row) and [sendMediaKey]
     * (key messages, whose id is random — no row exists).
     */
    private suspend fun encryptToFragments(
        address: String,
        body: String,
        messageId: String,
        payloadType: E2ePayloadType,
    ): Resolution {
        val sessionId = loadCurrentSession(address)
            ?: return Resolution.Failed(
                AppError.Validation("no encryption session for this contact"),
            )
        val encrypted = runCatching {
            crypto.get().encrypt(sessionId, body.toByteArray(Charsets.UTF_8))
        }.onFailure { Timber.w(it, "E2E: encrypt failed") }.getOrNull()
            ?: return Resolution.Failed(AppError.Validation("couldn't encrypt this message"))
        // The ratchet advanced: re-pickle before the fragments go out,
        // so a crash cannot replay ratchet state.
        sessions.get().refreshSession(address, sessionId)
        val wireType = payloadType.toWire()
        val fragments = runCatching {
            E2eWireFormat.encode(messageId, encrypted, wireType)
        }.getOrNull() ?: return Resolution.Failed(
            AppError.Validation("message too long to send encrypted"),
        )
        val base64 = java.util.Base64.getEncoder().encodeToString(
            E2eWireFormat.encodePayload(encrypted, wireType),
        )
        return Resolution.Ready(Plan.Fresh(base64, fragments))
    }

    /** The domain payload type as a wire payload type (same kinds, same order). */
    private fun E2ePayloadType.toWire(): E2eWireFormat.PayloadType = when (this) {
        E2ePayloadType.TEXT -> E2eWireFormat.PayloadType.TEXT
        E2ePayloadType.REACTION -> E2eWireFormat.PayloadType.REACTION
        E2ePayloadType.MEDIA_KEY -> E2eWireFormat.PayloadType.MEDIA_KEY
    }

    /**
     * Re-fragments stored payload bytes: decode + parse + re-encode.
     * The round trip is byte-identical (the payload header is a fixed
     * 3-byte prefix), so the fragments match the first attempt's
     * exactly. Null when the stored value is corrupt or over-large.
     */
    private fun fragmentStored(base64: String, messageId: String): List<String>? {
        val bytes = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
            ?: return null
        val payload = runCatching { E2eWireFormat.parsePayload(bytes) }.getOrNull()
            ?: return null
        return runCatching {
            E2eWireFormat.encode(messageId, payload.message, payload.payloadType)
        }.getOrNull()
    }

    companion object {
        /**
         * The fragment-set id for a message: the first 6 hex chars of
         * SHA-256 over a domain tag + the Room id + the attempt number.
         * Deterministic, so a retry re-sends the same id (the receiver
         * dedupes by sender + id + index) — and a re-encrypt (no stored
         * bytes) uses a fresh id per attempt, so its set can never mix
         * with the previous attempt's partial set.
         */
        fun messageIdForLocalId(localMessageId: Long, attempt: Int = 0): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("relay-e2e-msgid-v1".toByteArray(Charsets.UTF_8))
            digest.update(ByteBuffer.allocate(8).putLong(localMessageId).array())
            digest.update(ByteBuffer.allocate(4).putInt(attempt).array())
            return digest.digest()
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                .substring(0, 6)
        }
    }
}
