package com.filestech.sms.sync.client

import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.sync.DeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — the connection state the UI shows: Linked devices
 * renders Connected / Reconnecting… / Offline from this, and
 * [Removed] surfaces "This device was removed from your phone".
 */
sealed interface ClientConnectionState {
    /** No hub target (never linked, or disconnected). */
    data object Disconnected : ClientConnectionState

    /** A connect attempt is in flight. */
    data object Connecting : ClientConnectionState

    /** Authenticated and heartbeating. */
    data class Connected(val hubId: String, val hubName: String) : ClientConnectionState

    /** The last attempt (or session) failed; the loop keeps retrying. */
    data class Failed(val error: String) : ClientConnectionState

    /** The hub removed this device — terminal until a fresh pairing. */
    data object Removed : ClientConnectionState
}

/**
 * Client mode — the re-discovery match: the same hub id, with a
 * fresh target that DIFFERS from the current one. Matching on the
 * id (stable, carried in the mDNS TXT record) rather than the
 * instance name or IP is what makes the switch safe when another
 * hub is on the same network. An empty id never matches.
 *
 * "Differs" covers a move (new host/port) AND a tunnel change at
 * the same address: when the phone's VPN comes up, the fresh
 * sighting carries a `tip` the current target lacks, and the loop
 * must switch to it — the LAN dial is the one hanging.
 */
fun isRediscoveryTarget(phone: DiscoveredHub, hubId: String, target: HubTarget): Boolean {
    if (hubId.isEmpty() || phone.hubId != hubId) return false
    val fresh = HubTarget.fromDiscovery(phone.host, phone.port, phone.hubId, phone.tunnelIp)
    return fresh != target
}

/** Reconnect backoff, in ms: 1 s, 2 s, 5 s, 10 s, then 30 s (capped). */
private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

/**
 * Client mode — the delay before the reconnect attempt after
 * [backoffIndex] consecutive failures (capped at 30 s). Pure, so the
 * backoff table is unit-tested without a transport.
 */
fun reconnectDelayMs(backoffIndex: Int): Long =
    BACKOFF_MS[backoffIndex.coerceIn(0, BACKOFF_MS.lastIndex)]

/**
 * Client mode — owns the connection to the hub phone: the background
 * connect/session task, the reconnect loop, and the connection state
 * (the desktop's `ChannelManager`, ported).
 *
 * [connect] starts the loop for a target and returns the status the
 * first attempt resolves to (connected, or failed with the reason);
 * the loop keeps retrying after that, with backoff `[1, 2, 5, 10,
 * 30]` s (capped at 30 s, reset on success). After 2 consecutive
 * failures, then every 3 more, the loop re-runs mDNS discovery and
 * switches to the same hub — matched by its stable id — at a fresh
 * target (a new address, or a tunnel address that appeared at the
 * same address); a switch connects at once (no backoff sleep,
 * counters reset).
 *
 * A network return during a backoff sleep (Wi-Fi back on) wakes the
 * loop at once instead of waiting out the remaining delay, and resets
 * the counters — see [NetworkAvailabilityMonitor]. Without a wakeup
 * the table is unchanged, so a hub that is genuinely unreachable
 * still costs only the capped backoff: no polling, no spin.
 *
 * A `revoked` frame ends the session and stops reconnecting
 * entirely. A removed device that re-authenticates gets
 * `unknown_device` — the `revoked` auth reason is a dead constant
 * on the hub (never sent) — so `unknown_device` *after having been
 * paired* (a stored hub, or an auth in this run) is treated as
 * "you were removed" and surfaces [ClientConnectionState.Removed],
 * not a generic failure. Before pairing, `unknown_device` is just
 * "not scanned yet" and the loop keeps retrying (the auto-link wait
 * state).
 *
 * Frames from the live session are forwarded on [frames] for the
 * sync engine (C2); the manager never parses sync payloads itself.
 */
@Singleton
class ClientConnectionManager @Inject constructor(
    private val transport: ClientTransport,
    private val linkStore: ClientLinkStore,
    private val discovery: NsdDiscovery,
    private val networkAvailability: NetworkAvailabilityMonitor,
    @ApplicationScope private val appScope: CoroutineScope,
) : ClientSyncChannel {

    private val _state =
        MutableStateFlow<ClientConnectionState>(ClientConnectionState.Disconnected)
    override val state: StateFlow<ClientConnectionState> = _state.asStateFlow()

    private val frameFlow = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<ServerFrame> = frameFlow.asSharedFlow()

    private val connectMutex = Mutex()

    @Volatile
    private var currentTarget: HubTarget? = null

    /** The hub id from the last successful auth in this run. */
    @Volatile
    private var knownHubId: String? = null

    @Volatile
    private var loopJob: Job? = null

    @Volatile
    private var liveSession: ClientSession? = null

    /**
     * Connects to a hub and keeps the session alive with reconnects
     * until [disconnect] or revocation. Returns the status the first
     * attempt resolves to (connected, or failed with the reason) —
     * the background task keeps retrying after that, and later
     * outcomes arrive via [state].
     */
    suspend fun connect(target: HubTarget, kind: DeviceKind): ClientConnectionState {
        connectMutex.withLock {
            val alreadyLive = state.value is ClientConnectionState.Connected ||
                state.value is ClientConnectionState.Connecting
            if (currentTarget == target && alreadyLive) {
                return state.value
            }
            // Switching hubs (or reconnecting after a failure): stop
            // the old task first.
            stopTask()
            currentTarget = target
            // A new target is a new hub until auth says otherwise —
            // the old hub's id must never steer re-discovery for
            // this target.
            knownHubId = null
            _state.value = ClientConnectionState.Connecting
            loopJob = appScope.launch { runLoop(target, kind) }
        }
        // Wait for the first attempt to resolve (connected / failed)
        // so the caller gets the real outcome instead of "connecting".
        withTimeoutOrNull(FIRST_ATTEMPT_WAIT_MS) {
            state.first { it !is ClientConnectionState.Connecting }
        }
        return state.value
    }

    /** Closes the channel and stops reconnecting. */
    fun disconnect() {
        stopTask()
        currentTarget = null
        knownHubId = null
        _state.value = ClientConnectionState.Disconnected
    }

    /**
     * Sends one JSON message on the live session. False when no
     * session is live — the caller retries once reconnected.
     */
    override suspend fun send(text: String): Boolean {
        val session = liveSession ?: return false
        return session.send(text)
    }

    /** Aborts the background task, if any, without touching state/hub. */
    private fun stopTask() {
        loopJob?.cancel()
        loopJob = null
        liveSession?.let { session ->
            appScope.launch { session.close() }
        }
        liveSession = null
    }

    /**
     * Mutable state of one [runLoop] task, threaded through [runOneCycle].
     * Task-local: only one loop runs at a time (see [connect]).
     */
    private class LoopState(
        var current: HubTarget,
        var nextRediscoveryAt: Int,
        var backoffIndex: Int = 0,
        var consecutiveFailures: Int = 0,
    )

    /**
     * Background task: connect, run the session, reconnect with
     * backoff. Runs until the hub is forgotten ([disconnect]), the
     * device is revoked, or the task is cancelled. The network
     * monitor lives exactly as long as the loop: started here,
     * unregistered in the `finally` whatever ends the task, so no
     * callback ever leaks past a disconnect or mode switch.
     */
    private suspend fun runLoop(target: HubTarget, kind: DeviceKind) {
        networkAvailability.start()
        try {
            val loop = LoopState(current = target, nextRediscoveryAt = REDISCOVERY_AFTER_FAILURES)
            while (true) {
                if (!runOneCycle(loop, kind)) break
            }
        } finally {
            networkAvailability.stop()
        }
    }

    /**
     * One connect + run + backoff cycle. True keeps the task going;
     * false stops it (hub forgotten, revoked, closed, or removed).
     * The loop itself holds the only jump — the cycle answers with a
     * value so the reconnect logic stays flat.
     */
    private suspend fun runOneCycle(loop: LoopState, kind: DeviceKind): Boolean {
        // Drop any wakeup that arrived before this cycle began (the
        // registration echo for a network that was already up, or a
        // return while the last session was still alive): only a
        // network return DURING this cycle cuts the backoff sleep
        // short. A return during the connect or the re-discovery
        // scan below is kept — the drain already ran.
        networkAvailability.wakeups.tryReceive()
        // Stop if the hub was forgotten while we were working.
        if (currentTarget != loop.current) return false
        if (state.value !is ClientConnectionState.Connecting) {
            _state.value = ClientConnectionState.Connecting
        }
        val keepGoing = when (val outcome = transport.connect(loop.current, kind)) {
            is ConnectOutcome.Connected -> runConnectedSession(loop, outcome)
            is ConnectOutcome.Failed -> handleConnectFailed(loop, outcome)
        }
        // Reconnect with backoff — but only while a hub is still known.
        if (!keepGoing || currentTarget != loop.current) return false
        // The hub may have moved: once failures pile up, re-run
        // discovery and switch to the same hub at its new address. A
        // switch connects at once (no backoff sleep on a fresh
        // target); a scan that finds nothing just falls through to
        // the backoff below, and the next scan waits for more
        // failures — the loop never spins.
        if (rediscoverIfDue(loop)) return true
        // Reconnect with backoff — interruptible: a network return
        // during the sleep (Wi-Fi back on) retries at once instead
        // of waiting out the remaining delay, and resets the
        // counters, so the next failure starts from 1 s again and a
        // hub at a new address is re-found after 2 failures, like a
        // fresh loop. Without a wakeup the table is unchanged.
        val woken = withTimeoutOrNull(reconnectDelayMs(loop.backoffIndex)) {
            networkAvailability.wakeups.receive()
        } != null
        if (woken) {
            loop.backoffIndex = 0
            loop.consecutiveFailures = 0
            loop.nextRediscoveryAt = REDISCOVERY_AFTER_FAILURES
        } else {
            loop.backoffIndex++
        }
        return true
    }

    /**
     * Runs one live session until it ends: forwards frames, waits for
     * the end, and records the outcome. False stops the task — the
     * hub revoked us, or the session closed for good. A dropped
     * session counts as a failure and the task reconnects.
     */
    private suspend fun runConnectedSession(
        loop: LoopState,
        outcome: ConnectOutcome.Connected,
    ): Boolean {
        loop.backoffIndex = 0
        loop.consecutiveFailures = 0
        loop.nextRediscoveryAt = REDISCOVERY_AFTER_FAILURES
        val session = outcome.session
        // Remember the hub id: re-discovery matches a phone found at
        // a new address against it (the id is stable — the address
        // is not).
        knownHubId = session.hubId
        // Record the win kind-keyed: a tunnel win never overwrites
        // the LAN host, and the next reconnect without fresh info
        // dials the winner first.
        linkStore.noteConnected(
            session.hubId,
            session.hubName,
            outcome.candidate.kind,
            outcome.candidate.host,
            loop.current.port,
            System.currentTimeMillis(),
        )
        liveSession = session
        _state.value = ClientConnectionState.Connected(session.hubId, session.hubName)
        val forwardJob = appScope.launch {
            session.frames.collect { frame ->
                if (!frameFlow.tryEmit(frame)) {
                    Timber.w("ClientConnectionManager: dropping a frame — no consumer")
                }
            }
        }
        val end = session.awaitEnd()
        forwardJob.cancel()
        liveSession = null
        return when (end) {
            SessionEnd.Revoked -> {
                // Revoked: drop to removed and STOP reconnecting.
                // The hub deleted our key; only a fresh pairing can
                // link us again.
                currentTarget = null
                knownHubId = null
                _state.value = ClientConnectionState.Removed
                false
            }
            is SessionEnd.Dropped -> {
                _state.value = ClientConnectionState.Failed(end.error)
                loop.consecutiveFailures++
                true
            }
            SessionEnd.Closed -> false
        }
    }

    /**
     * Records a failed connect attempt. False stops the task:
     * `unknown_device` after pairing means the hub removed us —
     * terminal until a fresh pairing.
     */
    private suspend fun handleConnectFailed(loop: LoopState, outcome: ConnectOutcome.Failed): Boolean {
        if (wasPaired() &&
            classifyConnectFailure(outcome.error) == AutoLinkFailureKind.WAIT
        ) {
            currentTarget = null
            knownHubId = null
            _state.value = ClientConnectionState.Removed
            return false
        }
        _state.value = ClientConnectionState.Failed(outcome.error)
        loop.consecutiveFailures++
        return true
    }

    /**
     * Re-runs discovery when failures pile up. True when re-discovery
     * found a fresh target: the cycle returns at once so the next
     * attempt connects without a backoff sleep.
     */
    private suspend fun rediscoverIfDue(loop: LoopState): Boolean {
        if (loop.consecutiveFailures < loop.nextRediscoveryAt) return false
        loop.nextRediscoveryAt = loop.consecutiveFailures + REDISCOVERY_EVERY_FAILURES
        val moved = rediscover(loop.current) ?: return false
        loop.current = moved
        currentTarget = moved
        loop.backoffIndex = 0
        loop.consecutiveFailures = 0
        loop.nextRediscoveryAt = REDISCOVERY_AFTER_FAILURES
        return true
    }

    /**
     * Whether this device has ever paired (a stored hub, or an auth
     * in this run). Decides whether `unknown_device` means "you were
     * removed" (paired) or "not scanned yet" (pairing in progress).
     */
    private suspend fun wasPaired(): Boolean =
        knownHubId != null || linkStore.snapshot()?.hubId != null

    /**
     * Re-runs mDNS discovery looking for the known hub. Returns a
     * fresh target when the SAME hub id answers with a target that
     * differs from the current one — a move to a new address, or a
     * tunnel address that appeared at the same address; null when
     * the hub is not found, its target is unchanged, it has never
     * authenticated, or the scan itself fails — the retry loop then
     * just continues on the remembered address until the next scan.
     * A failed scan is logged, never fatal. Any sighting of the hub
     * refreshes the store (the LAN host, and the tunnel address when
     * the TXT record carries one), even when the target does not
     * change.
     */
    private suspend fun rediscover(target: HubTarget): HubTarget? {
        val hubId = knownHubId ?: linkStore.snapshot()?.hubId ?: return null
        val found = findHubSighting(hubId) ?: return null
        linkStore.updateLastKnownAddress(found.host, found.port)
        linkStore.noteTunnelIp(found.tunnelIp)
        if (!isRediscoveryTarget(found, hubId, target)) return null
        val fresh = HubTarget.fromDiscovery(found.host, found.port, found.hubId, found.tunnelIp)
        val moved = fresh.lanHost != target.lanHost || fresh.port != target.port
        if (moved) {
            Timber.i(
                "ClientConnectionManager: hub moved to %s:%d — reconnecting",
                found.host,
                found.port,
            )
            _state.value = ClientConnectionState.Failed(
                "found your phone at a new address (${found.host}:${found.port}) — reconnecting",
            )
        } else {
            Timber.i("ClientConnectionManager: hub tunnel address changed — reconnecting")
            _state.value = ClientConnectionState.Failed("found your phone — reconnecting")
        }
        return fresh
    }

    /**
     * One scan for the known hub: the sighting, or null when the
     * scan fails or the hub is not on it. A failed scan is logged,
     * never fatal.
     */
    private suspend fun findHubSighting(hubId: String): DiscoveredHub? {
        val phones = runCatching { discovery.scan(REDISCOVERY_SCAN_MS) }.getOrNull() ?: return null
        return phones.firstOrNull { it.hubId == hubId }
    }

    companion object {
        /** Longest `connect` waits for the first attempt to resolve. */
        const val FIRST_ATTEMPT_WAIT_MS = 20_000L

        /** Re-discovery runs after this many consecutive failures… */
        private const val REDISCOVERY_AFTER_FAILURES = 2

        /** …then every this many more. */
        private const val REDISCOVERY_EVERY_FAILURES = 3

        /** How long a re-discovery scan listens. */
        private const val REDISCOVERY_SCAN_MS = 5_000L
    }
}
