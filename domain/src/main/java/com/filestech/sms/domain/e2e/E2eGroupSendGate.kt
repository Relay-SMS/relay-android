package com.filestech.sms.domain.e2e

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome

/**
 * Domain port for the encrypted-group send path (round 49, design
 * §13): decide + encrypt for one group text or reaction.
 *
 * `SendSmsUseCase` lives in `:domain`, which cannot see the
 * encryption code (`:app`, `com.filestech.sms.e2e`), so the
 * group encrypt-or-plain decision and the sender-key encryption
 * go through this port. The real implementation
 * ([com.filestech.sms.e2e.E2eGroupSessionManager]) is bound by
 * Hilt; [NoOp] keeps `:domain` tests compiling untouched and is
 * the plain-only behavior when encryption is unavailable.
 *
 * The contract the use cases rely on:
 *
 * - [decideGroupSend] answers "does this group text go out
 *   encrypted?" — every member ENCRYPTED + group-capable, plus
 *   the 8,000-character cap (the same cap as 1:1). A group with
 *   any other member state sends plain (the caller probes the
 *   members in the background, like the 1:1 path). A state-read
 *   failure fails OPEN to [Decision.Plain].
 *   [Decision.RefuseOverCap] is a REFUSAL, never a silent fall
 *   back to plain: the caller returns a failure without writing
 *   a row or touching the radio.
 * - [encryptGroupMessage] ensures our sender key for the group,
 *   distributes it to every member missing it, encrypts the body
 *   with our sender key, and fragments it. The fragment set is
 *   THE SAME for every member (one ciphertext, fanned out) — the
 *   caller sends the same fragments to each member as individual
 *   SMS and writes one encrypted group echo. A failure anywhere
 *   returns [Outcome.Failure] — the caller marks the row FAILED
 *   and the user can retry. It NEVER sends the plaintext instead.
 *   [AppError.OwnNumberMissing] means our own number is unknown
 *   (and no stored distribution could supply the member list):
 *   the group id cannot be formed, so the send fails fast like a
 *   group MMS without a number.
 * - The gate never decides the TRANSPORT: whether the group
 *   sends as SMS fan-out or group MMS is the router's decision
 *   ([com.filestech.sms.domain.usecase.EnvoyerMessageUseCase]).
 *   An encrypted group text always travels as per-member SMS
 *   fragments (the group MMS path has no encrypted-text channel
 *   in v1) — the router routes encrypted groups to the SMS
 *   fan-out regardless of the group-MMS setting.
 */
interface E2eGroupSendGate {

    /** The encryption decision for one group text send. */
    sealed interface Decision {
        /** Send plain (the group is not fully encrypted); the caller probes the members. */
        data object Plain : Decision

        /** Send encrypted (every member is ENCRYPTED + group-capable, body fits the cap). */
        data object Encrypted : Decision

        /**
         * Refuse the send: the group IS encrypted but the body exceeds the
         * 8,000-character encrypted cap. Never fall back to plain.
         */
        data object RefuseOverCap : Decision
    }

    /**
     * One encrypted group message, ready to fan out: the fragment
     * set is identical for every member (one ciphertext, one
     * fragment id space) — the caller sends the same fragments
     * to each member. [groupId] is the group the fragments belong
     * to (32 hex chars), for the echo row / diagnostics.
     */
    data class EncryptedGroupMessage(
        /** The `RLY1:` fragments (GROUP message type), same set for every member. */
        val fragments: List<String>,
        /** The group id (32 hex chars). */
        val groupId: String,
    )

    /**
     * Whether this group text to [memberAddresses] (the thread's
     * addresses, excluding ourselves) goes out encrypted, plain,
     * or is refused (over the cap while encrypted). [body] is the
     * final wire body (signature included). Pure decision — no
     * sending, no key distribution.
     */
    suspend fun decideGroupSend(memberAddresses: List<String>, body: String): Decision

    /**
     * Encrypts [body] for the group: ensures our sender key,
     * distributes it to the members missing it, encrypts with
     * our sender key, and fragments. [memberAddresses] are the
     * thread's addresses (excluding ourselves); the full member
     * list (including ourselves) is resolved inside, from our own
     * number or a stored distribution. [date] is the message
     * timestamp, written on the key-distribution fragment rows in
     * the system sent box. [payloadType] is the content kind:
     * [E2ePayloadType.TEXT] for an ordinary message,
     * [E2ePayloadType.REACTION] for a group reaction (the
     * formatted tapback body, encrypted — the receiver folds it
     * onto its target in the group thread). MEDIA_KEY is refused:
     * encrypted media in groups is a follow-up (design §13.7).
     */
    suspend fun encryptGroupMessage(
        memberAddresses: List<String>,
        body: String,
        subId: Int?,
        date: Long,
        payloadType: E2ePayloadType = E2ePayloadType.TEXT,
    ): Outcome<EncryptedGroupMessage>

    /**
     * Records the GROUP_ACTIVATED notice for the group with these
     * members — the send-side half of the first-encrypted-message
     * event (design §6.4): the receive path records it on the
     * first decrypted group message, the send path on the first
     * encrypted group send. Once per conversation (the store
     * dedupes). [memberAddresses] are the thread's addresses
     * (excluding ourselves); [now] is the message timestamp.
     * Best-effort: the implementation swallows failures — the
     * notice is cosmetic and must never fail a send that already
     * dispatched.
     */
    suspend fun recordGroupActivated(memberAddresses: List<String>, now: Long)

    companion object {
        /**
         * Plain-only gate: every send is [Decision.Plain],
         * encryption is refused as unavailable. The default for
         * the use-case constructors, so `:domain` tests that never
         * touch encryption keep compiling — and the plain path is
         * byte-identical to the pre-encryption behavior.
         */
        val NoOp: E2eGroupSendGate = object : E2eGroupSendGate {
            override suspend fun decideGroupSend(
                memberAddresses: List<String>,
                body: String,
            ): Decision = Decision.Plain

            override suspend fun encryptGroupMessage(
                memberAddresses: List<String>,
                body: String,
                subId: Int?,
                date: Long,
                payloadType: E2ePayloadType,
            ): Outcome<EncryptedGroupMessage> =
                Outcome.Failure(AppError.Validation("encryption unavailable"))

            override suspend fun recordGroupActivated(
                memberAddresses: List<String>,
                now: Long,
            ) = Unit
        }
    }
}
