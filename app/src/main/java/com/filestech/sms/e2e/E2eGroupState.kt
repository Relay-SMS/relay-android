package com.filestech.sms.e2e

import com.filestech.sms.core.ext.canonicalPhone
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState

/**
 * A group conversation's encryption state (round 49, design §13)
 * — the state the thread UI, the composer, and the send path
 * build against.
 *
 * **The safe default:** a group is encrypted ONLY when EVERY
 * member is reachable encrypted — an ENCRYPTED pairwise session
 * AND a declared group capability. One member who does not run
 * Relay, runs an old build, or is blocked keeps the whole group
 * plain, and the state names the blocking members so the UI can
 * say so plainly ("NAME doesn't use Relay yet" — never a silent
 * partial encryption, where some members would read the message
 * and others would see nothing at all).
 *
 * The state is DERIVED, never stored: it is a pure function of
 * the members' peer rows ([E2ePeerEntity]) and the blocklist, so
 * it recomputes live as exchanges complete — no migration, no
 * staleness, no flip event to miss. [E2eGroupStateProvider]
 * observes it; [derive] is the pure core, unit-testable without
 * Room.
 */
sealed interface GroupEncryptionState {

    /**
     * Every member is ENCRYPTED and group-capable: group
     * messages go out encrypted (sender keys), the thread shows
     * the lock, the composer shows "Relay message".
     */
    data object Encrypted : GroupEncryptionState

    /**
     * No member is definitively blocking, but at least one
     * member's exchange is still in flight (UNKNOWN / PROBING,
     * or no peer row yet): the group may flip to [Encrypted] on
     * its own as the exchanges complete. The UI shows "Setting
     * up encryption…" (like a 1:1 PROBING thread) and sends go
     * out plain for now. [pendingMembers] are the canonical
     * addresses still in flight.
     */
    data class SettingUp(val pendingMembers: List<String>) : GroupEncryptionState

    /**
     * The group is plain, with the reason the UI shows.
     * [blockingMembers] are the canonical addresses blocking
     * encryption (for [PlainReason.MEMBER_BLOCKED],
     * [PlainReason.MEMBER_NOT_RELAY] and
     * [PlainReason.MEMBER_NOT_CAPABLE]); empty for
     * [PlainReason.NOT_A_GROUP].
     */
    data class Plain(
        val reason: PlainReason,
        val blockingMembers: List<String>,
    ) : GroupEncryptionState
}

/**
 * Why a group is plain ([GroupEncryptionState.Plain]). The UI
 * words each reason for the details panel and the composer hint;
 * the reason is the contract, the wording is the UI's.
 */
enum class PlainReason {
    /** Fewer than two member addresses — group encryption does not apply (a 1:1). */
    NOT_A_GROUP,

    /** At least one member is blocked: we send them nothing, encrypted or plain. */
    MEMBER_BLOCKED,

    /** At least one member does not run Relay (NOT_RELAY, or a non-number). */
    MEMBER_NOT_RELAY,

    /**
     * At least one member runs Relay (ENCRYPTED) but has not
     * declared group capability — an old build, or a declaration
     * that has not arrived yet. The announce-back usually closes
     * the gap within seconds of opening the thread.
     */
    MEMBER_NOT_CAPABLE,
}

/**
 * The pure group-state derivation: member peer rows + the
 * blocklist in, the state out. No I/O, no Android APIs.
 *
 * Precedence when several reasons apply (the UI shows one):
 * blocked > not-Relay > not-capable. A blocked member is the
 * user's own choice and the first thing to say; a member without
 * Relay is the next fact; capability is last because it is the
 * most transient (a declaration may still be in flight).
 */
object E2eGroupState {

    /**
     * Derives the state for one group.
     *
     * @param memberAddresses the thread's member addresses
     * (excluding ourselves — the conversation's addresses).
     * @param peerByAddress the peer rows keyed by CANONICAL
     * address; a missing entry means no exchange has ever
     * started with that member (UNKNOWN).
     * @param blockedCanonical the blocklist as canonical
     * addresses (canonicalized by the caller — the stored
     * blocklist is normalized, not canonical, so the two forms
     * must meet on [canonicalPhone]).
     */
    fun derive(
        memberAddresses: List<String>,
        peerByAddress: Map<String, E2ePeerEntity?>,
        blockedCanonical: Set<String>,
    ): GroupEncryptionState {
        val members = memberAddresses.map { it.canonicalPhone() }.distinct()
        val blocked = members.filter { it in blockedCanonical }
        val notRelay = members.filter { !isRelayMember(it, peerByAddress[it]) }
        // Only an ENCRYPTED member can be "not capable": a member
        // with no session yet (UNKNOWN / PROBING / no row) has
        // simply not declared yet — that is SettingUp, not an old
        // build. (A no-row member reads groupCapable == false,
        // so the capability filter must be gated on ENCRYPTED.)
        val notCapable = members.filter { isEncryptedNotCapable(peerByAddress[it]) }
        val pending = members.filter { peerByAddress[it]?.state != E2ePeerState.ENCRYPTED }
        // Precedence, first match wins: blocked > not-Relay >
        // not-capable > pending (SettingUp) > encrypted.
        return when {
            members.size < 2 ->
                GroupEncryptionState.Plain(PlainReason.NOT_A_GROUP, emptyList())
            blocked.isNotEmpty() ->
                GroupEncryptionState.Plain(PlainReason.MEMBER_BLOCKED, blocked)
            notRelay.isNotEmpty() ->
                GroupEncryptionState.Plain(PlainReason.MEMBER_NOT_RELAY, notRelay)
            notCapable.isNotEmpty() ->
                GroupEncryptionState.Plain(PlainReason.MEMBER_NOT_CAPABLE, notCapable)
            pending.isNotEmpty() -> GroupEncryptionState.SettingUp(pending)
            else -> GroupEncryptionState.Encrypted
        }
    }

    /**
     * Whether this member could run Relay at all: a real phone
     * number ([E2eProbePolicy.isProbeableAddress] — a short code
     * or alphanumeric sender can never run Relay) whose probe
     * budget is not exhausted (NOT_RELAY). Anything else —
     * UNKNOWN, PROBING, ENCRYPTED — is still in play.
     */
    private fun isRelayMember(canonicalAddress: String, peer: E2ePeerEntity?): Boolean {
        if (!E2eProbePolicy.isProbeableAddress(canonicalAddress)) return false
        return peer?.state != E2ePeerState.NOT_RELAY
    }

    /**
     * Whether this member is ENCRYPTED but never declared group
     * capability — an old build, or a declaration still in
     * flight. Capability without a session is meaningless, so a
     * member that is not ENCRYPTED yet is pending, never
     * not-capable (see [derive]).
     */
    private fun isEncryptedNotCapable(peer: E2ePeerEntity?): Boolean =
        peer?.state == E2ePeerState.ENCRYPTED && peer.groupCapable != true
}
