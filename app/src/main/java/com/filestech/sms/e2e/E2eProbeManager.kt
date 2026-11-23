package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * Probe orchestration (design §2.3–§2.4): the "automatic" half of
 * "Relay to Relay auto-encrypts".
 *
 * A probe is a 70-byte data SMS carrying our identity keys. It goes out
 * on two triggers — opening a 1:1 thread ([onThreadOpened]) and sending
 * a 1:1 message ([probeForSend]) — for a peer in state UNKNOWN,
 * PROBING, or NOT_RELAY, gated by the probe budget (at most 3 attempts
 * per 30 days, at least 24 hours apart, owned by
 * [E2ePeerRepository]) and by eligibility ([E2eProbePolicy]: 1:1 only,
 * real phone number, not blocked).
 *
 * On the state gate: the design's trigger text names UNKNOWN /
 * NOT_RELAY, but the budget only makes sense if a PROBING peer with
 * attempts left is re-probed (the repository always records a probe as
 * PROBING, so a strict UNKNOWN/NOT_RELAY gate would make attempts 2–3
 * unreachable and the 3-attempt budget dead code). The state machine
 * ([E2ePeerStateMachine.canProbe]) is the authority here: every
 * non-ENCRYPTED state is probeable within the budget, and the 3rd
 * attempt flips the peer to NOT_RELAY until the window elapses —
 * for automatic probes. A manual retry (round 44) leaves the
 * peer PROBING even at 3 attempts: the probe is in flight, so
 * "Checking if…" is the honest state, and the panel must not
 * flop back to "Tap to retry" the moment the user taps Retry.
 *
 * The decrypt-failure re-probe ([reprobeAfterDecryptFailure]) is the
 * one path that probes an ENCRYPTED peer: an undecryptable message from
 * a known contact usually means they reinstalled, and the re-probe
 * re-establishes the session (design §3.5, §4.4). It respects the same
 * budget — the state exclusion is the only thing lifted.
 *
 * Round 40 — the discovery toggle (Encryption settings, default ON):
 * when OFF, this phone stops SENDING probes ([gateFor] refuses);
 * inbound handshakes stay fully active (answering probes, receiving
 * bundles, decrypting), so encryption still starts when the other
 * side initiates. The gate lives in [gateFor], so it covers all
 * three triggers, including the decrypt-failure re-probe.
 *
 * Round 41 — the manual retry (Details panel encryption-row tap):
 * an explicit user tap sends ONE probe now, bypassing the budget
 * and the discovery toggle (the tap is consent for this one SMS).
 * The other gates still apply (real number, not blocked, not
 * already ENCRYPTED), and a failed send still records
 * `e2e-probe-send` — the manual path goes through the same sender.
 *
 * Round 42 — the manual retry records its failures: a tap that did
 * nothing used to leave no trace anywhere (the Boolean was
 * discarded, the exception Timber-only, and Timber is a no-op in
 * release). [retryProbe] now runs the detailed core
 * ([probeConversationDetailed]) and records every non-[Sent]
 * outcome in the error log under `e2e-manual-retry`, gated or
 * failed, with the reason — fixed messages, no addresses. Success
 * records nothing (the state flip to PROBING is the signal), and
 * automatic probes never record (they use the Boolean cores and
 * gate constantly).
 *
 * Round 42 — the peer row is keyed by the NORMALIZED address (see
 * [E2ePeerRepository]): the probe used to write under the raw
 * conversation form while the thread UI observed the normalized
 * form, so a peer whose number was stored with formatting never
 * showed its state change in the panel.
 *
 * All entry points are fire-and-forget (non-suspend, own scope): a
 * probe must never delay opening a thread or sending a message. The
 * suspend cores ([probeConversation], [probeAddress]) are the testable
 * surface. Probes serialize on one mutex: a thread-open racing a send
 * must not spend two attempts on the same peer.
 *
 * The account manager is taken lazily: resolving it provisions
 * [E2eCrypto] (the first native call loads the crypto library, and
 * the Account load that follows does Keystore + database I/O), and
 * this manager is injected into the thread ViewModel — it must be
 * cheap to construct on the main thread. Resolution happens inside the
 * background probe coroutine.
 */

/**
 * The outcome of one probe attempt (round 42): the manual path
 * needs the REASON a probe did not go out, not just a Boolean, so
 * a tap that does nothing leaves a trace in the error log.
 */
sealed interface ProbeResult {
    /** The probe data SMS went out. */
    data object Sent : ProbeResult

    /** No probe went out: the attempt was refused before sending. */
    data class Gated(val reason: ProbeGate) : ProbeResult

    /** The send was attempted but the radio refused it. */
    data object SendFailed : ProbeResult
}

/**
 * Why a probe attempt was refused before sending. [label] is the
 * fixed, PII-free wording recorded in the error log.
 */
enum class ProbeGate(val label: String) {
    /** No conversation exists for the id. */
    NoConversation("no conversation"),

    /** The conversation is not 1:1 (group, or no address). */
    NotOneToOne("not a 1:1 conversation"),

    /** The address is not a phone number (short code, service, empty). */
    InvalidAddress("not a phone number"),

    /** The number is blocked. */
    Blocked("number is blocked"),

    /** The peer is already ENCRYPTED (and the re-probe did not lift it). */
    AlreadyEncrypted("already encrypted"),

    /** The probe budget is exhausted (automatic probes only — a manual retry lifts it). */
    BudgetExhausted("probe budget exhausted"),

    /** The discovery toggle is off (automatic probes only — a manual retry lifts it). */
    DiscoveryOff("discovery is off"),
}

@Singleton
class E2eProbeManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val blockedRepo: BlockedNumberRepository,
    private val peers: E2ePeerRepository,
    private val accountManager: dagger.Lazy<E2eAccountManager>,
    private val dataSmsSender: E2eDataSmsSender,
    private val settings: AppSettingsSource,
    @ApplicationScope private val scope: CoroutineScope,
    private val recorder: E2eFailureRecorder,
) {
    private val probeMutex = Mutex()

    private companion object {
        /** Error-log tag for manual-retry failures (round 42). */
        const val MANUAL_RETRY_TAG = "e2e-manual-retry"
    }

    /**
     * Thread-open trigger (design §2.4): opening a 1:1 thread is intent
     * to talk, so the session is usually live by the time the user hits
     * send. Called by the thread ViewModel; non-suspend by contract.
     */
    fun onThreadOpened(conversationId: Long) {
        scope.launch {
            runCatching { probeConversation(conversationId) }
                .onFailure { Timber.w(it, "E2E: probe on thread open failed") }
        }
    }

    /**
     * Send-path trigger (design §2.4): covers sends that never opened
     * the thread (notification reply, desktop, scheduled). Called by
     * the send hook when the peer is not ENCRYPTED; non-suspend.
     */
    fun probeForSend(address: String, subId: Int?) {
        scope.launch {
            runCatching { probeAddress(address, subId) }
                .onFailure { Timber.w(it, "E2E: probe on send failed") }
        }
    }

    /**
     * Decrypt-failure re-probe (design §3.5): an undecryptable message
     * from a known contact usually means they reinstalled. Probes even
     * an ENCRYPTED peer; the budget still applies.
     */
    fun reprobeAfterDecryptFailure(address: String, subId: Int?) {
        scope.launch {
            runCatching { probeAddress(address, subId, allowEncrypted = true) }
                .onFailure { Timber.w(it, "E2E: re-probe after decrypt failure failed") }
        }
    }

    /**
     * Peer-left re-probe (round 41): a plain SMS text arrived from an
     * ENCRYPTED peer, so they lost Relay — the peer is already UNKNOWN
     * by the time this runs, and the re-probe resumes E2E if they
     * reinstall. Automatic recovery, not a manual retry: the budget
     * and the discovery toggle still apply (no `manual` bypass).
     */
    fun reprobeAfterPeerLeft(address: String, subId: Int?) {
        scope.launch {
            runCatching { probeAddress(address, subId) }
                .onFailure { Timber.w(it, "E2E: re-probe after peer left failed") }
        }
    }

    /**
     * Manual retry (round 41, Details panel encryption-row tap): the
     * user asked to retry the key exchange now, so ONE probe goes out
     * immediately, bypassing the budget and the discovery toggle. The
     * other gates still apply (real number, not blocked, not already
     * ENCRYPTED). Fire-and-forget, like the other triggers.
     *
     * Round 42 — failures are recorded in the error log (see
     * [recordManualResult]): a tap that does nothing used to leave
     * no trace anywhere.
     */
    fun retryProbe(conversationId: Long) {
        scope.launch {
            runCatching { probeConversationDetailed(conversationId, manual = true) }
                .onSuccess { result -> recordManualResult(result) }
                .onFailure { error ->
                    recorder.record(MANUAL_RETRY_TAG, error)
                    Timber.w(error, "E2E: manual probe retry failed")
                }
        }
    }

    /**
     * Records a manual retry that did not send (round 42). Success
     * records nothing — the state flip to PROBING is the signal —
     * and automatic probes never reach this (they use the Boolean
     * cores). Fixed messages only, no addresses; the recorder's
     * per-process dedupe collapses repeated failed taps into one
     * entry, which is all the diagnosis needs.
     */
    private fun recordManualResult(result: ProbeResult) {
        when (result) {
            ProbeResult.Sent -> Unit
            is ProbeResult.Gated -> recorder.recordEvent(
                MANUAL_RETRY_TAG,
                "Manual retry refused: ${result.reason.label}.",
            )
            ProbeResult.SendFailed -> recorder.recordEvent(
                MANUAL_RETRY_TAG,
                "Manual retry failed: the probe could not be sent.",
            )
        }
    }

    /**
     * Probes the single address of a conversation, if probeable.
     * Returns `true` when a probe data SMS went out. [manual] lifts
     * the budget and discovery-toggle gates for an explicit user
     * retry (round 41).
     */
    suspend fun probeConversation(
        conversationId: Long,
        now: Long = System.currentTimeMillis(),
        manual: Boolean = false,
    ): Boolean =
        probeConversationDetailed(conversationId, now, manual) == ProbeResult.Sent

    /**
     * Detailed core of [probeConversation]: resolves the conversation
     * to its single address, then probes it. [ProbeResult.Gated] with
     * [ProbeGate.NoConversation] / [ProbeGate.NotOneToOne] when the
     * conversation is missing or not 1:1.
     */
    suspend fun probeConversationDetailed(
        conversationId: Long,
        now: Long = System.currentTimeMillis(),
        manual: Boolean = false,
    ): ProbeResult {
        val conversation = conversationDao.findById(conversationId)
            ?: return ProbeResult.Gated(ProbeGate.NoConversation)
        val addresses = PhoneAddress.list(conversation.addressesCsv).map { it.raw }
        if (addresses.size != 1) return ProbeResult.Gated(ProbeGate.NotOneToOne)
        val address = addresses.first()
        // The probe leaves on the same subscription the thread's sends
        // use (design §11.1): the default SMS subscription from
        // settings, `null` (system default) when unreadable.
        val subId = runCatching { settings.hydratedOrNull()?.sending?.defaultSubId }
            .getOrNull()
        return probeAddressDetailed(address, subId, now = now, manual = manual)
    }

    /**
     * Probes one address, if probeable. Returns `true` when a probe data
     * SMS went out. [allowEncrypted] lifts the ENCRYPTED exclusion for
     * the decrypt-failure re-probe only. [manual] lifts the budget and
     * discovery-toggle gates for an explicit user retry (round 41);
     * the address, blocked, and ENCRYPTED gates still apply.
     */
    suspend fun probeAddress(
        address: String,
        subId: Int?,
        allowEncrypted: Boolean = false,
        manual: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        probeAddressDetailed(address, subId, allowEncrypted, manual, now) == ProbeResult.Sent

    /**
     * Detailed core of [probeAddress]: the same probe, with the
     * outcome instead of a Boolean. The manual path ([retryProbe])
     * records the non-[ProbeResult.Sent] outcomes in the error log;
     * the automatic triggers use the Boolean [probeAddress] and
     * record nothing (automatic probes gate constantly).
     */
    suspend fun probeAddressDetailed(
        address: String,
        subId: Int?,
        allowEncrypted: Boolean = false,
        manual: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): ProbeResult = probeMutex.withLock {
        val state = peers.state(address)
        val gate = gateFor(address, state, allowEncrypted, manual, now)
        if (gate != null) return@withLock ProbeResult.Gated(gate)
        // Lazy account creation: the first probe sent (or received) is
        // the first E2E use, and the identity is created here — users
        // who never use E2E pay nothing (design §4.1).
        val keys = accountManager.get().ensureAccount()
        val probe = E2eHandshake.encodeProbe(keys.curve25519, keys.ed25519)
        val sent = dataSmsSender.sendDataMessage(
            address = address,
            subId = subId,
            port = E2eHandshake.E2E_DATA_SMS_PORT,
            data = probe,
        )
        // A failed send must not consume budget: record only after the
        // radio accepted the probe.
        if (!sent) return@withLock ProbeResult.SendFailed
        peers.recordProbeSent(address, now)
        // The 3rd unanswered attempt exhausts the budget: the peer
        // becomes NOT_RELAY until the window elapses (state machine
        // `onProbeSent`). Re-read: the repository owns the counting.
        // Round 44 — automatic probes only: a manual retry leaves
        // the peer PROBING, so the panel shows "Checking if…" while
        // the probe the user just asked for is in flight (flipping
        // to NOT_RELAY here made the Retry tap feel broken — the box
        // flashed PROBING then flopped back). The manual retry still
        // consumes budget (recordProbeSent bumped the attempts
        // above); only the immediate state flip is skipped, and the
        // NOT_RELAY conclusion still applies to automatic probes.
        val attempts = peers.getPeer(address)?.probeAttempts ?: 0
        if (!manual && attempts >= E2ePeerRepository.MAX_PROBE_ATTEMPTS) {
            peers.setState(address, StoredPeerState.NOT_RELAY, now)
        }
        Timber.i("E2E: probe sent (state=%s, attempts=%d)", state, attempts)
        ProbeResult.Sent
    }

    /**
     * Eligibility + state + budget, in one place (see the class KDoc):
     * the gate refusing this probe, or null when it may go out.
     * Round 41 — a manual retry is explicit consent for this one
     * probe: the budget and the discovery toggle do not apply.
     * Round 40 — the discovery toggle (Encryption settings, default
     * ON): OFF stops outbound probes. An unreadable store fails
     * OPEN: the default is ON, and a cold process must not silently
     * stop discovering.
     */
    private suspend fun gateFor(
        address: String,
        state: StoredPeerState,
        allowEncrypted: Boolean,
        manual: Boolean,
        now: Long,
    ): ProbeGate? {
        // The settings read happens only after the address, blocked
        // and ENCRYPTED gates pass (and never for a manual retry):
        // a settings read must not precede a cheap refusal.
        return when {
            !E2eProbePolicy.isProbeableAddress(address) -> ProbeGate.InvalidAddress
            blockedRepo.isBlocked(address) -> ProbeGate.Blocked
            state == StoredPeerState.ENCRYPTED && !allowEncrypted ->
                ProbeGate.AlreadyEncrypted
            manual -> null
            !(settings.hydratedOrNull()?.encryption?.autoDiscoverRelayContacts ?: true) ->
                ProbeGate.DiscoveryOff
            !peers.canProbe(address, now) -> ProbeGate.BudgetExhausted
            else -> null
        }
    }
}
