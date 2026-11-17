package com.filestech.sms.sync.client

import com.filestech.sms.sync.DeviceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a connect failure means for the auto-link loop:
 * - [AutoLinkFailureKind.WAIT]: the phone hasn't registered this
 *   device yet — the scan and confirm tap are still pending. A calm
 *   wait state, not an error; keep cycling.
 * - [AutoLinkFailureKind.SHOW]: anything else (bad signature,
 *   protocol refusal, TLS / fingerprint failure, unreachable host,
 *   scan or command failure). Shown verbatim and retried while the
 *   QR is open.
 *
 * There is deliberately no `revoked` case: the phone never sends
 * `revoked` as an auth-failure reason — revocation arrives as a
 * `revoked` frame on an already-authenticated session, and a
 * removed device that re-authenticates gets `unknown_device`
 * instead. If a `revoked` string ever did appear here it classifies
 * as [AutoLinkFailureKind.SHOW], which is the safe default
 * (visible, never silently a wait).
 */
enum class AutoLinkFailureKind { WAIT, SHOW }

private val UNKNOWN_DEVICE_REGEX = Regex("unknown[_\\s-]?device", RegexOption.IGNORE_CASE)

/** Client mode — `unknown_device` (any spelling) is a wait; anything else is shown. */
fun classifyConnectFailure(error: String?): AutoLinkFailureKind =
    if (UNKNOWN_DEVICE_REGEX.containsMatchIn(error.orEmpty())) {
        AutoLinkFailureKind.WAIT
    } else {
        AutoLinkFailureKind.SHOW
    }

/** How long each auto-link mDNS scan listens. */
const val AUTO_LINK_SCAN_TIMEOUT_MS = 5_000L

/**
 * Longest the loop parks while one phone's first connect attempt is
 * still in flight (the manager waits up to 20 s for it to resolve).
 * After this the loop resumes cycling instead of waiting forever.
 */
const val AUTO_LINK_MAX_WAIT_MS = 60_000L

/**
 * Delay between auto-link cycles, by consecutive failed cycle (a
 * cycle fails when no phone accepted the connection): 3 s, 5 s,
 * 10 s, 20 s, then 30 s. Each cycle already takes seconds on its own
 * (a 5 s scan plus a connect attempt per phone), so the loop never
 * spins.
 */
private val AUTO_LINK_CYCLE_DELAYS_MS = longArrayOf(3_000L, 5_000L, 10_000L, 20_000L, 30_000L)

/** Client mode — the delay before the next cycle after [failedCycles] failed cycles. */
fun nextCycleDelayMs(failedCycles: Int): Long {
    val index = failedCycles.coerceIn(1, AUTO_LINK_CYCLE_DELAYS_MS.size) - 1
    return AUTO_LINK_CYCLE_DELAYS_MS[index]
}

/** Cap on the failure lines shown at once (newest first). */
const val MAX_AUTO_LINK_ERROR_LINES = 3

/**
 * Client mode — prepends a failure line, dropping a consecutive
 * duplicate and capping the list: the display shows the latest
 * failures without growing.
 */
fun addErrorLine(lines: List<String>, line: String): List<String> {
    val next = if (lines.firstOrNull() == line) lines else listOf(line) + lines
    return next.take(MAX_AUTO_LINK_ERROR_LINES)
}

/**
 * Client mode — orders the phones for one cycle: rotates so the
 * cycle starts with the phone AFTER the one tried last, so with
 * several phones each gets its turn as the last-tried phone — the
 * one whose background retry task stays alive between cycles. When
 * the last-tried phone isn't in this scan (or there was none), keeps
 * discovery order. Never mutates.
 */
fun orderPhonesForCycle(
    phones: List<DiscoveredHub>,
    lastTriedHubId: String?,
): List<DiscoveredHub> {
    if (lastTriedHubId == null || phones.size < 2) return phones.toList()
    val lastIndex = phones.indexOfFirst { it.hubId == lastTriedHubId }
    if (lastIndex < 0) return phones.toList()
    val start = (lastIndex + 1) % phones.size
    return phones.subList(start, phones.size) + phones.subList(0, start)
}

/** What the auto-link status display is doing right now. */
enum class AutoLinkPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    WAITING,
    ERROR,
}

/**
 * Client mode — the auto-link display state (the desktop's
 * `AutoLinkViewState`). The status line itself is rendered from
 * string resources in the UI (all UI strings live in the five
 * locales) rather than a pure helper returning hardcoded text.
 */
data class AutoLinkViewState(
    val phase: AutoLinkPhase = AutoLinkPhase.IDLE,
    /** Name of the phone the loop is currently trying (or last tried). */
    val phoneName: String? = null,
    /**
     * True once a cycle has ended with ONLY unknown-device failures:
     * the screen shows the calm wait line instead of flickering
     * between "Connecting…" and the failure text while the manager
     * retries. Cleared by any show-kind failure or when the loop
     * stops.
     */
    val waitOnly: Boolean = false,
    /** True when the last scan completed with no phones found. */
    val noPhonesFound: Boolean = false,
    /** Show-kind failure lines from the latest cycle, newest first. */
    val errors: List<String> = emptyList(),
    /** Raw unknown-device error from the latest cycle, shown muted
     * under the wait line so the failure detail stays visible
     * (debugging). */
    val waitDetail: String? = null,
)

/** Client mode — the auto-link state before the loop starts. */
fun initialAutoLinkState(): AutoLinkViewState = AutoLinkViewState()

/**
 * Client mode — the auto-link loop (the desktop's auto-link, ported
 * from `autoLink.ts` + the settings loop): while the pairing QR is
 * shown and the device isn't connected, runs mDNS scan cycles,
 * trying each phone found in turn, instead of asking for a "Find
 * your phone" tap and a "Connect" tap.
 *
 * The manager retries each phone's auth in the background too (its
 * loop keeps attempting after ANY failure, unknown-device included),
 * so the loop only has to get one connect attempt started per phone;
 * the connection state flips to connected once the phone registers
 * this device.
 *
 * The wait state spans scan → confirm tap → next retry succeeds: the
 * phone shows a confirm dialog after scanning and only the confirm
 * tap registers the device, so "waiting" covers the whole gap, not
 * just the scan itself.
 *
 * Leaving the QR screen unlinked stops the loop and kills its retry
 * task ([stop]); a link in progress when the screen closes is left
 * alone — the connection stays.
 */
@Singleton
class AutoLinker @Inject constructor(
    private val discovery: NsdDiscovery,
    private val connectionManager: ClientConnectionManager,
) {

    private val _state = MutableStateFlow(initialAutoLinkState())
    val state: StateFlow<AutoLinkViewState> = _state.asStateFlow()

    private var loopJob: Job? = null

    /** True once the loop has started a connect attempt of its own. */
    private var started = false

    /** Starts the loop; no-op while it is already running. */
    fun start(kind: DeviceKind, scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop(kind) }
    }

    /**
     * Stops the loop. If the device connected, the connection is left
     * alone; otherwise the retry task the loop started is killed so
     * it doesn't hammer the phone forever. The display state resets.
     */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        if (connectionManager.state.value !is ClientConnectionState.Connected && started) {
            connectionManager.disconnect()
        }
        started = false
        _state.value = initialAutoLinkState()
    }

    private suspend fun runLoop(kind: DeviceKind) {
        // If a connect attempt is already in flight when the loop
        // starts, wait it out first, so the loop never claims (or
        // disconnects) a connection it didn't start.
        if (connectionManager.state.value is ClientConnectionState.Connecting) {
            _state.value = _state.value.copy(phase = AutoLinkPhase.CONNECTING)
            waitForNotConnecting()
        }
        var failedCycles = 0
        var lastTriedHubId: String? = null
        while (connectionManager.state.value !is ClientConnectionState.Connected) {
            _state.value = _state.value.copy(
                phase = AutoLinkPhase.SCANNING,
                noPhonesFound = false,
                waitDetail = null,
            )
            // A failed scan is null (the ERROR state is set in the
            // catch); an empty scan is an empty list. Either way the
            // cycle ends below with the usual backoff — the
            // phone-trying block is simply skipped.
            val phones: List<DiscoveredHub>? = try {
                discovery.scan(AUTO_LINK_SCAN_TIMEOUT_MS)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    phase = AutoLinkPhase.ERROR,
                    waitOnly = false,
                    errors = listOf("Scan failed — ${t.message ?: t.javaClass.simpleName}"),
                )
                null
            }
            if (phones.isNullOrEmpty()) {
                // An empty scan (not a scan failure — that already
                // set the ERROR state above): note that no phones
                // were found this cycle.
                if (phones != null) {
                    _state.value = _state.value.copy(
                        phase = AutoLinkPhase.SCANNING,
                        noPhonesFound = true,
                    )
                }
            } else {
                val ordered = orderPhonesForCycle(phones, lastTriedHubId)
                var sawWait = false
                var lastWaitError: String? = null
                var cycleErrors: List<String> = emptyList()
                var parked = false
                // One break only: a phone that connected — or whose
                // first attempt is still in flight (parked) — sets
                // `done`, and the next pass stops trying phones.
                var done = false
                for (phone in ordered) {
                    if (done ||
                        connectionManager.state.value is ClientConnectionState.Connected
                    ) {
                        break
                    }
                    _state.value = _state.value.copy(
                        phase = AutoLinkPhase.CONNECTING,
                        phoneName = phone.serviceName,
                    )
                    val status = connectionManager.connect(HubTarget(phone.host, phone.port), kind)
                    started = true
                    lastTriedHubId = phone.hubId
                    when (status) {
                        is ClientConnectionState.Connected -> done = true
                        is ClientConnectionState.Connecting -> {
                            // First attempt still in flight after the
                            // manager's wait: the background task owns
                            // this phone now — park until it resolves
                            // instead of trying another phone.
                            parked = true
                            waitForNotConnecting()
                            done = true
                        }
                        is ClientConnectionState.Failed -> when (
                            classifyConnectFailure(status.error)
                        ) {
                            AutoLinkFailureKind.WAIT -> {
                                sawWait = true
                                lastWaitError = status.error
                            }
                            AutoLinkFailureKind.SHOW ->
                                cycleErrors = addErrorLine(cycleErrors, status.error)
                        }
                        else -> Unit
                    }
                }
                if (connectionManager.state.value is ClientConnectionState.Connected) break
                if (parked) {
                    // The parked attempt resolved while we waited: fold
                    // its outcome into this cycle's result.
                    val live = connectionManager.state.value
                    if (live is ClientConnectionState.Failed) {
                        when (classifyConnectFailure(live.error)) {
                            AutoLinkFailureKind.WAIT -> {
                                sawWait = true
                                lastWaitError = live.error
                            }
                            AutoLinkFailureKind.SHOW ->
                                cycleErrors = addErrorLine(cycleErrors, live.error)
                        }
                    }
                }
                _state.value = when {
                    cycleErrors.isNotEmpty() -> _state.value.copy(
                        phase = AutoLinkPhase.ERROR,
                        waitOnly = false,
                        errors = cycleErrors,
                    )
                    sawWait -> _state.value.copy(
                        phase = AutoLinkPhase.WAITING,
                        waitOnly = true,
                        waitDetail = lastWaitError,
                    )
                    else -> _state.value.copy(phase = AutoLinkPhase.SCANNING)
                }
            }
            failedCycles++
            delay(nextCycleDelayMs(failedCycles))
        }
    }

    private suspend fun waitForNotConnecting() {
        withTimeoutOrNull(AUTO_LINK_MAX_WAIT_MS) {
            connectionManager.state.first { it !is ClientConnectionState.Connecting }
        }
    }
}
