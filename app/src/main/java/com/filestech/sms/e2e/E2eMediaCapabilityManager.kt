package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.repository.BlockedNumberRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media-capability signaling (round 48, design §2.7): the "I can do
 * encrypted media" declaration, and the record of who declared it.
 *
 * The probe/bundle packets have NO spare byte (both are validated
 * magic + version + type + exact length, and old builds drop
 * anything else), so the capability signal could not ride inside
 * the handshake — it is a NEW handshake packet type (`0x03`, see
 * [E2eHandshake]), sent AFTER the session is established, on the
 * SAME channel the handshake used ([HandshakeChannel]).
 *
 * Three send triggers, all best-effort and idempotent (a duplicate
 * declaration is a no-op on receipt — the flag just sets again):
 *
 * 1. **Session establishment** ([announceNow]): the bundle receiver
 *    announces when the bundle lands ([E2eHandshakeHandler]), and
 *    the bundle sender announces when the first pre-key message
 *    arrives ([E2eReceiveManager]) — both sides of a NEW exchange
 *    declare exactly once, on the channel the exchange proved.
 * 2. **Thread open** ([announceIfNeeded]): an already-ENCRYPTED peer
 *    whose session predates round 48 never fires an establishment
 *    event, so opening their thread sends the declaration once
 *    ([E2eProbeManager] — the same funnel as the probe trigger).
 *    The [E2ePeerRepository.isMediaAnnounced] flag makes it
 *    one-shot per peer; a failed send leaves the flag clear, so
 *    the next thread-open retries.
 * 3. **Announce-back** ([onDeclarationReceived]): receiving a
 *    declaration from a peer we have not announced to yet sends
 *    ours back, so capability becomes symmetric as soon as EITHER
 *    side announces — neither side waits for the other to open
 *    the thread first.
 *
 * Channel selection: the peer row records the channel our outbound
 * handshake packets provably used
 * ([E2ePeerRepository.handshakeChannel]) — a bundle arriving on a
 * channel proves our probe reached them on it, and a pre-key
 * message proves our bundle reply did. The declaration goes out
 * on THAT channel. When no channel is recorded (a handshake from
 * before round 48), the declaration goes out on BOTH channels —
 * and BOTH sends happen even if the first is accepted: radio
 * acceptance proves nothing about carrier delivery for data SMS
 * (the partner pair's line accepts the send and drops the segment
 * downstream — that is the whole reason the text channel exists).
 * The flag marks announced when at least one send is accepted.
 *
 * The discovery toggle does NOT gate declarations: the toggle
 * stops DISCOVERING new Relay contacts (probes to strangers), and
 * a declaration goes to an already-ENCRYPTED peer — no discovery
 * is involved, and no segment is spent on anyone we do not already
 * encrypt with. The blocked check DOES apply: a blocked peer gets
 * nothing, like on every other send path.
 *
 * All methods never throw: a diagnostics-grade failure (a dead
 * database, a failing sender) returns `false` instead of breaking
 * the handshake or the thread-open path that called it.
 */
@Singleton
class E2eMediaCapabilityManager @Inject constructor(
    private val peers: E2ePeerRepository,
    private val dataSmsSender: E2eDataSmsSender,
    private val blockedRepo: BlockedNumberRepository,
) {

    /**
     * Thread-open trigger: sends our declaration to an ENCRYPTED
     * peer we have not announced to yet. Returns `true` when a
     * declaration went out on this call; `false` when nothing was
     * sent (already announced, not ENCRYPTED, blocked, or the
     * radio refused both channels). A `false` from a refused send
     * is retried on the next thread-open — the announced flag is
     * only set after an accepted send.
     */
    suspend fun announceIfNeeded(
        address: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return runCatching {
            if (peers.state(address) != E2ePeerState.ENCRYPTED) return@runCatching false
            if (peers.isMediaAnnounced(address)) return@runCatching false
            announce(address, subId, now)
        }.getOrDefault(false)
    }

    /**
     * Establishment trigger: sends our declaration now, whether or
     * not we announced before — a re-establishment (key change,
     * reinstall) re-declares, so a peer that wiped its state
     * re-learns our capability with the new session. Still gated
     * on ENCRYPTED + not blocked: a declaration without a session
     * is meaningless. Returns `true` when a declaration went out.
     */
    suspend fun announceNow(
        address: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return runCatching {
            if (peers.state(address) != E2ePeerState.ENCRYPTED) return@runCatching false
            announce(address, subId, now)
        }.getOrDefault(false)
    }

    /**
     * A declaration arrived from this peer: record them as
     * media-capable, and announce back when we have not announced
     * to them yet (the symmetric close — see the class KDoc).
     * Called by [E2eHandshakeHandler] after its blocked check, so
     * no second blocked check happens here; the announce-back
     * re-checks anyway (cheap, and the handler's check is about
     * the inbound packet, this one about the outbound send).
     *
     * The announce-back is gated on ENCRYPTED, like every other
     * declaration: a declaration without a session is
     * meaningless, and the sender cannot be mid-exchange here
     * without one — the bundle side only gains a session when
     * the first encrypted message arrives, and that arrival
     * fires its own announce (see the class KDoc), so a peer
     * that is PROBING now still declares once it is ENCRYPTED.
     * Never throws.
     */
    suspend fun onDeclarationReceived(
        address: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            peers.setMediaCapable(address, true, now)
            Timber.i("E2E: peer declared media capability")
            if (peers.state(address) == E2ePeerState.ENCRYPTED && !peers.isMediaAnnounced(address)) {
                announce(address, subId, now)
            }
        }.onFailure { Timber.w(it, "E2E: media declaration handling failed") }
    }

    /**
     * Sends one declaration on the peer's proven channel (or both
     * channels when none is recorded — see the class KDoc) and
     * marks the peer announced when at least one send was
     * accepted. Returns `true` when at least one send went out.
     * Blocked peers get nothing.
     */
    private suspend fun announce(address: String, subId: Int?, now: Long): Boolean {
        if (runCatching { blockedRepo.isBlocked(address) }.getOrDefault(false)) return false
        val packet = E2eHandshake.encodeMediaCapable()
        val sent = when (peers.handshakeChannel(address)) {
            HandshakeChannel.DATA_SMS -> sendData(address, subId, packet)
            HandshakeChannel.TEXT_SMS -> sendText(address, subId, packet)
            // No proven channel (a pre-round-48 handshake): both
            // channels, unconditionally — a data send the radio
            // accepts may still be dropped downstream, so the
            // text send is not skipped when the data send is
            // accepted. Announced when either is accepted.
            // `or`, not `||`: the infix form evaluates BOTH
            // sides — short-circuiting here would drop the text
            // send exactly when the data send was accepted, which
            // is the case the both-channels rule exists for.
            null -> sendData(address, subId, packet) or sendText(address, subId, packet)
        }
        if (sent) {
            peers.setMediaAnnounced(address, true, now)
            Timber.i("E2E: media-capability declaration sent")
        }
        return sent
    }

    private fun sendData(address: String, subId: Int?, packet: ByteArray): Boolean =
        dataSmsSender.sendDataMessage(
            address = address,
            subId = subId,
            port = E2eHandshake.E2E_DATA_SMS_PORT,
            data = packet,
            kind = DataSmsKind.MEDIA_CAPABLE,
        )

    private fun sendText(address: String, subId: Int?, packet: ByteArray): Boolean =
        dataSmsSender.sendTextMessage(
            address = address,
            subId = subId,
            text = E2eHandshakeSms.encode(packet),
            kind = DataSmsKind.MEDIA_CAPABLE,
        )
}
