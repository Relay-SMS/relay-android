package com.filestech.sms.domain.e2e

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome

/**
 * Domain port for the end-to-end encryption send path (design §3.4).
 *
 * `SendSmsUseCase` and `RetrySendUseCase` live in `:domain`, which cannot
 * see the encryption code (`:app`, `com.filestech.sms.e2e`), so the
 * encrypt-or-plain decision and the fragment dispatch go through this
 * port. The real implementation ([com.filestech.sms.e2e.E2eSendManager])
 * is bound by Hilt; [NoOp] keeps `:domain` tests compiling untouched and
 * is the plain-only behavior when encryption is unavailable.
 *
 * The contract the use cases rely on:
 *
 * - [decideSend] answers the question "does this 1:1 text go out
 *   encrypted?" — ENCRYPTED peer state, plus the 8,000-character cap.
 *   [Decision.RefuseOverCap] is a REFUSAL, never a silent fall back to
 *   plain: the caller returns a failure without writing a row or
 *   touching the radio.
 * - [sendEncrypted] encrypts with the peer's current session, fragments,
 *   writes each fragment to the system sent box, and dispatches each
 *   fragment as its own single-part send. A failure anywhere returns
 *   [Outcome.Failure] — the caller marks the row FAILED and the user can
 *   retry. It NEVER sends the plaintext instead.
 * - [probeForSend] is fire-and-forget: it kicks off a probe for a peer
 *   that is not ENCRYPTED yet (the send-path probe trigger, design
 *   §2.4) and returns immediately, so the plain send is never delayed.
 */
interface E2eSendGate {

    /** The encryption decision for one 1:1 text send. */
    sealed interface Decision {
        /** Send plain (peer is not ENCRYPTED); the caller triggers a probe. */
        data object Plain : Decision

        /** Send encrypted (peer is ENCRYPTED and the body fits the cap). */
        data object Encrypted : Decision

        /**
         * Refuse the send: the peer IS encrypted but the body exceeds the
         * 8,000-character encrypted cap. Never fall back to plain.
         */
        data object RefuseOverCap : Decision
    }

    /**
     * Whether this 1:1 text to [address] goes out encrypted, plain, or
     * is refused (over the cap while encrypted). [body] is the final
     * wire body (signature included). Pure decision — no sending.
     */
    suspend fun decideSend(address: String, body: String): Decision

    /**
     * Encrypts [body] with the peer's current session and sends it as
     * fragments. [localMessageId] is the Room row the fragments are
     * tracked against (their sent/delivery reports update it); the
     * fragment set id derives from it, so a retry re-sends the same
     * bytes under the same id. [date] is the message timestamp, written
     * on the fragment rows in the system sent box. [attempt] is the
     * send-attempt number (0 for a first send), carried into the
     * tracking intents so a stale attempt's reports are ignored.
     * [payloadType] is the content kind carried on the wire:
     * [E2ePayloadType.TEXT] for an ordinary message,
     * [E2ePayloadType.REACTION] for a 1:1 reaction (the formatted
     * tapback body, encrypted — the receiver folds it onto its target
     * instead of showing a bubble).
     */
    suspend fun sendEncrypted(
        localMessageId: Long,
        address: String,
        body: String,
        date: Long,
        subId: Int?,
        requestDeliveryReport: Boolean,
        attempt: Int = 0,
        payloadType: E2ePayloadType = E2ePayloadType.TEXT,
    ): Outcome<Unit>

    /**
     * Fire-and-forget probe trigger for the send path: a 1:1 send to a
     * peer that is not ENCRYPTED yet starts the key exchange in the
     * background. Returns immediately; the probe itself is
     * eligibility- and budget-gated inside the manager.
     */
    fun probeForSend(address: String, subId: Int?)

    companion object {
        /**
         * Plain-only gate: every send is [Decision.Plain], encryption is
         * refused as unavailable, probes are no-ops. The default for the
         * use-case constructors, so `:domain` tests that never touch
         * encryption keep compiling — and the plain path is byte-identical
         * to the pre-encryption behavior.
         */
        val NoOp: E2eSendGate = object : E2eSendGate {
            override suspend fun decideSend(address: String, body: String): Decision =
                Decision.Plain

            override suspend fun sendEncrypted(
                localMessageId: Long,
                address: String,
                body: String,
                date: Long,
                subId: Int?,
                requestDeliveryReport: Boolean,
                attempt: Int,
                payloadType: E2ePayloadType,
            ): Outcome<Unit> =
                Outcome.Failure(AppError.Validation("encryption unavailable"))

            override fun probeForSend(address: String, subId: Int?) = Unit
        }
    }
}
