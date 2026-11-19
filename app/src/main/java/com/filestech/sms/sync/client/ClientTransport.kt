package com.filestech.sms.sync.client

import com.filestech.sms.sync.DeviceKind
import com.filestech.sms.sync.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Client mode — which kind of address a dial candidate is: the
 * phone's tunnel address (its VPN address, learned from the mDNS
 * TXT `tip` record or the wire) or its LAN address. The kind is
 * internal only (logs + the last-working record) — the UI never
 * shows it.
 */
enum class AddressKind(val label: String) {
    TUNNEL("tunnel"),
    LAN("LAN");

    companion object {
        /** Parses the stored last-working value (`"tunnel"`/`"lan"`); anything else is unknown. */
        fun fromStored(value: String?): AddressKind? = when (value?.lowercase(Locale.ROOT)) {
            "tunnel" -> TUNNEL
            "lan" -> LAN
            else -> null
        }
    }
}

/** Client mode — one dial candidate: an address and its kind. */
data class HubCandidate(val host: String, val kind: AddressKind) {
    /** `wss://<host>:<port>/sync`, bracketing IPv6 literals (desktop rule). */
    fun wsUrl(port: Int): String {
        val bracketed = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "wss://$bracketed:$port/sync"
    }
}

/**
 * Client mode — where to connect: the hub's port (47831), the hub id
 * expected there (from the mDNS TXT record or the stored link —
 * empty when unknown, an old phone whose record carries none), and
 * the ordered dial candidates. The id is part of the target's
 * identity: a DHCP reassignment to a different phone is a different
 * target, and the TLS pin is keyed by it (one pin per hub). The URL
 * is always `wss://` — there is no unencrypted fallback.
 *
 * The candidate order is the dial order (the desktop's §5.2 rule):
 * [fromDiscovery] builds it from a fresh mDNS sighting (tunnel
 * first iff the TXT record carries a `tip`, else LAN only);
 * [fromStored] builds it from the last-known store with no fresh
 * info (last-working first, then the other known address). An
 * address that is both the tunnel and the LAN host is dialed once,
 * as the tunnel.
 */
data class HubTarget(
    val port: Int = SyncProtocol.PORT,
    val hubId: String = "",
    val candidates: List<HubCandidate> = emptyList(),
) {
    /** The LAN candidate's address, or null for a tunnel-only target. */
    val lanHost: String?
        get() = candidates.firstOrNull { it.kind == AddressKind.LAN }?.host

    companion object {
        /** Fresh mDNS truth: tunnel first iff `tip` is present, else LAN only. */
        fun fromDiscovery(host: String, port: Int, hubId: String, tunnelIp: String?): HubTarget {
            val tip = tunnelIp?.trim().orEmpty()
            val candidates = buildList {
                if (tip.isNotEmpty()) add(HubCandidate(tip, AddressKind.TUNNEL))
                if (host.isNotBlank() && host != tip) add(HubCandidate(host, AddressKind.LAN))
            }
            return HubTarget(port, hubId, candidates)
        }

        /**
         * No fresh info: last-working first, then the other known
         * address. With no history yet the tunnel goes first — it is
         * the address that works when the phone's VPN is on, and a
         * LAN-first attempt would burn the 8 s timeout on exactly
         * that case.
         */
        fun fromStored(
            host: String?,
            port: Int,
            hubId: String,
            tunnelIp: String?,
            lastWorking: AddressKind?,
        ): HubTarget {
            val lan = host?.trim().orEmpty().takeIf { it.isNotEmpty() }
                ?.let { HubCandidate(it, AddressKind.LAN) }
            val tunnel = tunnelIp?.trim().orEmpty().takeIf { it.isNotEmpty() }
                ?.let { HubCandidate(it, AddressKind.TUNNEL) }
            if (lan != null && tunnel != null && lan.host == tunnel.host) {
                return HubTarget(port, hubId, listOf(tunnel))
            }
            val ordered = when {
                lan == null -> listOfNotNull(tunnel)
                tunnel == null -> listOf(lan)
                lastWorking == AddressKind.LAN -> listOf(lan, tunnel)
                else -> listOf(tunnel, lan)
            }
            return HubTarget(port, hubId, ordered)
        }
    }
}

/** The outcome of one connect + auth attempt. */
sealed interface ConnectOutcome {
    /**
     * Authenticated: the session is live and heartbeating.
     * [candidate] is the address that won, so the caller can record
     * it as the last-working address.
     */
    data class Connected(val session: ClientSession, val candidate: HubCandidate) : ConnectOutcome

    /** The attempt failed; [error] is human-readable and, for auth
     * failures, leads with the reason code verbatim (so the auto-link
     * loop can match `unknown_device`). */
    data class Failed(val error: String) : ConnectOutcome
}

/** Why an authenticated session ended. */
sealed interface SessionEnd {
    /** Socket dropped or pong missed: reconnect with backoff. */
    data class Dropped(val error: String) : SessionEnd

    /** Hub sent `revoked`: stop reconnecting entirely. */
    data object Revoked : SessionEnd

    /** We closed it (stop / unlink). */
    data object Closed : SessionEnd
}

/**
 * Client mode — one authenticated WebSocket session to a hub. The
 * heartbeat runs inside (ping every 30 s, pong within 10 s or dead);
 * inbound pings are answered; `revoked` ends the session. Sync
 * traffic arrives on [frames] for the engine (C2); the session never
 * parses sync payloads itself.
 */
interface ClientSession {
    val hubId: String
    val hubName: String

    /** Frames arriving from the hub (sync traffic + hub `error` notes). */
    val frames: Flow<ServerFrame>

    /** Sends one JSON text frame. False when the session is already over. */
    suspend fun send(text: String): Boolean

    /** Closes the socket and ends the session. */
    suspend fun close()

    /** Suspends until the session ends, with the reason. */
    suspend fun awaitEnd(): SessionEnd
}

/**
 * Client mode — the `auth_failed` reason as a human-readable error,
 * leading with the reason code verbatim (the desktop's
 * `auth_failed_message`, same words): the auto-link classifier
 * matches `unknown_device` in it.
 */
fun authFailedMessage(reason: String): String = when (reason) {
    SyncProtocol.AuthFailed.UNKNOWN_DEVICE ->
        "unknown_device: this device isn't paired on the hub phone yet — scan the pairing QR first"
    SyncProtocol.AuthFailed.BAD_SIGNATURE ->
        "bad_signature: the hub rejected this device's signature — re-pair from the hub phone"
    SyncProtocol.AuthFailed.REVOKED ->
        "revoked: this device was removed from the hub phone — re-pair to link it again"
    SyncProtocol.AuthFailed.PROTOCOL ->
        "protocol: the hub rejected the connection for a protocol error"
    else -> "$reason: the hub refused authentication"
}

/**
 * Client mode — a failure that ends the candidate walk (the
 * desktop's `is_terminal_refusal`): the phone answered and refused,
 * so another address would refuse identically. Auth timeouts and
 * dropped sockets are NOT terminal — they mean the path failed, and
 * the next candidate may work.
 */
fun isTerminalRefusal(error: String): Boolean = TERMINAL_REFUSAL_PREFIXES.any { error.startsWith(it) }

private val TERMINAL_REFUSAL_PREFIXES = listOf(
    "unknown_device:",
    "bad_signature:",
    "revoked:",
    "protocol:",
    "hub error during auth:",
    "hub TLS certificate changed:",
)

/**
 * Client mode — one failed attempt's outcome in a word (the
 * desktop's `attempt_outcome`): "timed out", "connection refused",
 * or the inner reason with the "cannot reach the hub" prefix
 * stripped.
 */
fun attemptOutcome(error: String): String {
    if ("timed out" in error) return "timed out"
    if ("Connection refused" in error) return "connection refused"
    if (error.startsWith("cannot reach the hub")) {
        val inner = error.substringAfterLast(": ", "")
        if (inner.isNotEmpty()) return inner
    }
    return error
}

/**
 * Client mode — the all-candidates-failed error (the desktop's
 * §5.5 shape): every attempted address with its kind and outcome,
 * plus — only when a tunnel address was attempted — the router hint
 * and the per-app exclusion workaround. The ping hint names a
 * computer on the same network: a tablet has no terminal to ping
 * from.
 */
fun allCandidatesFailedError(failures: List<Pair<HubCandidate, String>>): String {
    val parts = failures.joinToString(" or ") { (candidate, error) ->
        "${candidate.host} (${candidate.kind.label}, ${attemptOutcome(error)})"
    }
    var message = "couldn't reach the phone at $parts"
    val tunnel = failures.firstOrNull { it.first.kind == AddressKind.TUNNEL }
    if (tunnel != null) {
        message += ". If the phone's VPN is on, check the router forwards between the LAN and " +
            "the tunnel (from a computer on the same network: ping ${tunnel.first.host}) — " +
            "or exclude Relay from the tunnel in the VPN app as a workaround"
    }
    return message
}

/**
 * Client mode — one connect + auth attempt over Ktor client +
 * OkHttp, the mirror of the desktop's `attempt_connect`:
 *
 * `wss://<host>:47831/sync`, TLS with the TOFU trust manager,
 * `hello` → `challenge` → `auth` (signing the RAW challenge bytes)
 * → `auth_ok` / `auth_failed`, all inside an 8 s per-attempt
 * connect timeout and a 10 s auth budget (the hub closes
 * unauthenticated sockets after 10 s).
 *
 * OkHttp is the engine (not CIO): it is the battle-tested Android
 * TLS path, the custom TOFU `X509TrustManager` plugs straight into
 * it, and it is already in the APK transitively via Coil. Hostname
 * verification is deliberately skipped — the TOFU fingerprint IS
 * the identity check.
 *
 * The transport owns the candidate walk: [HubTarget.candidates] are
 * dialed in order, one at a time (never a race — one device, one
 * session), until one authenticates; a terminal refusal stops the
 * walk, and when every candidate fails the error names each
 * attempted address. The reconnect loop (backoff, re-discovery)
 * lives in [ClientConnectionManager].
 */
@Singleton
class ClientTransport @Inject constructor(
    private val identityStore: ClientIdentityStore,
    private val pinStore: TofuPinStore,
) {

    /**
     * Connects to [target]: walks its candidates in order,
     * sequentially, until one authenticates. Never throws (a caller
     * cancellation still propagates): every failure comes back as
     * [ConnectOutcome.Failed] with a human-readable reason. A
     * terminal refusal (the phone answered and refused) stops the
     * walk and surfaces verbatim; when every candidate fails, the
     * error names each attempted address.
     */
    suspend fun connect(target: HubTarget, kind: DeviceKind): ConnectOutcome {
        if (target.candidates.isEmpty()) {
            return ConnectOutcome.Failed("no address to dial for this hub")
        }
        val failures = mutableListOf<Pair<HubCandidate, String>>()
        for (candidate in target.candidates) {
            // The kind only, never the address: the tunnel address
            // never reaches the log (SYNC_PROTOCOL.md, "VPN /
            // tunnel") — the all-failed error names the addresses to
            // the user instead, where the ping hint needs them.
            Timber.i("ClientTransport: dialing the %s address", candidate.kind.label)
            when (val outcome = attemptCandidate(candidate, target, kind)) {
                is ConnectOutcome.Connected -> {
                    Timber.i(
                        "ClientTransport: connected via %s",
                        outcome.candidate.kind.label,
                    )
                    return outcome
                }
                is ConnectOutcome.Failed -> {
                    if (isTerminalRefusal(outcome.error)) return outcome
                    failures.add(candidate to outcome.error)
                }
            }
        }
        return ConnectOutcome.Failed(allCandidatesFailedError(failures))
    }

    /**
     * One connect + auth attempt against one candidate of [target].
     * Never throws (a caller cancellation still propagates).
     */
    private suspend fun attemptCandidate(
        candidate: HubCandidate,
        target: HubTarget,
        kind: DeviceKind,
    ): ConnectOutcome {
        val ready = when (val opened = openSocket(candidate, target)) {
            is OpenSocketResult.Failed -> return ConnectOutcome.Failed(opened.error)
            is OpenSocketResult.Ready -> opened
        }
        val webSocket = ready.webSocket
        val client = ready.client
        val identity = ready.identity
        return try {
            val auth = withTimeoutOrNull(SyncProtocol.AUTH_TIMEOUT_MS) {
                authenticate(webSocket, identity, kind)
            }
            when (auth) {
                null -> {
                    runCatching { webSocket.close() }
                    failAndClose(client, "auth timed out: the hub did not answer within 10s")
                }
                is AuthResult.Failed -> {
                    runCatching { webSocket.close() }
                    failAndClose(client, auth.error)
                }
                is AuthResult.Ok -> ConnectOutcome.Connected(
                    ClientSessionImpl(webSocket, client, auth.hubId, auth.hubName),
                    candidate,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching { webSocket.close() }
            failAndClose(client, describeConnectError(t))
        }
    }

    /**
     * Client mode — opens the socket for one attempt: loads the
     * identity, builds the TLS client, and handshakes the WebSocket
     * inside the connect timeout. A failure closes the client (when
     * one was built) and comes back as [OpenSocketResult.Failed];
     * a caller cancellation propagates untouched.
     */
    private suspend fun openSocket(candidate: HubCandidate, target: HubTarget): OpenSocketResult {
        val identity = try {
            identityStore.identity()
        } catch (ce: CancellationException) {
            rethrowCancellation(ce)
        } catch (t: Throwable) {
            return OpenSocketResult.Failed("cannot load this device's identity: ${t.message}")
        }
        val client = try {
            buildClient(target.hubId)
        } catch (ce: CancellationException) {
            rethrowCancellation(ce)
        } catch (t: Throwable) {
            return OpenSocketResult.Failed("cannot build the TLS client: ${t.message}")
        }
        return try {
            val webSocket = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                client.webSocketSession(candidate.wsUrl(target.port))
            }
            if (webSocket == null) {
                client.close()
                return OpenSocketResult.Failed(
                    "cannot reach the hub at ${candidate.host}:${target.port}: " +
                        "connection timed out after ${CONNECT_TIMEOUT_MS / 1000}s",
                )
            }
            OpenSocketResult.Ready(webSocket, client, identity)
        } catch (ce: CancellationException) {
            rethrowCancellation(ce)
        } catch (t: Throwable) {
            client.close()
            OpenSocketResult.Failed(describeConnectError(t))
        }
    }

    private fun buildClient(hubId: String): HttpClient {
        val trustManager = TofuTrustManager(pinStore, hubId)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        return HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    // The TOFU fingerprint IS the identity check —
                    // hostname verification is deliberately skipped.
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    }

    private fun failAndClose(client: HttpClient, error: String): ConnectOutcome.Failed {
        client.close()
        return ConnectOutcome.Failed(error)
    }

    /**
     * Client mode — throws an [AuthFailure]: a helper so each auth
     * function keeps at most two literal `throw` statements (the
     * Detekt cap). The message is the one the caller shows.
     */
    private fun authFail(message: String): Nothing = throw AuthFailure(message)

    /**
     * Client mode — rethrows a caller cancellation: a helper so
     * [openSocket] keeps at most two literal `throw` statements
     * (the Detekt cap). The cancellation itself is never swallowed
     * or wrapped.
     */
    private fun rethrowCancellation(ce: CancellationException): Nothing = throw ce

    /**
     * Runs `hello` → `challenge` → `auth` → `auth_ok` over an open
     * socket. A stray `ping` during auth is answered, not fatal (the
     * desktop rule). Throws [AuthFailure] for every auth-level
     * failure; a caller cancellation propagates untouched.
     */
    private suspend fun authenticate(
        webSocket: DefaultClientWebSocketSession,
        identity: ClientIdentity,
        kind: DeviceKind,
    ): AuthResult {
        try {
            val hello = SyncProtocol.Hello(
                protocol = SyncProtocol.PROTOCOL_VERSION,
                deviceId = identity.deviceId,
                kind = kind.wire,
                name = identity.displayName,
            )
            if (!sendText(webSocket, SyncProtocol.encodeHello(hello))) {
                authFail("connection to hub lost during auth")
            }
            val challengeB64 = readChallenge(webSocket)
            val challenge = Base64Url.decode(challengeB64)
                ?: authFail("protocol: challenge is not valid base64url")
            if (challenge.size != SyncProtocol.CHALLENGE_BYTES) {
                authFail("protocol: challenge is not 32 bytes")
            }
            // The signature covers the RAW challenge bytes, not the base64 text.
            val signature = Base64Url.encode(identityStore.sign(challenge))
            val auth = SyncProtocol.Auth(deviceId = identity.deviceId, signature = signature)
            if (!sendText(webSocket, ClientFrames.encodeAuth(auth))) {
                authFail("connection to hub lost during auth")
            }
            return readAuthResult(webSocket)
        } catch (ce: CancellationException) {
            throw ce
        } catch (af: AuthFailure) {
            return AuthResult.Failed(af.message ?: "auth failed")
        } catch (t: Throwable) {
            return AuthResult.Failed("connection to hub lost during auth: ${t.message}")
        }
    }

    private suspend fun readChallenge(webSocket: DefaultClientWebSocketSession): String {
        while (true) {
            when (val frame = nextAuthFrame(webSocket)) {
                null -> authFail("hub closed the connection during auth")
                is ServerFrame.Challenge -> {
                    val challenge = frame.value.challenge
                    if (challenge.isBlank()) {
                        authFail("protocol: challenge message has no challenge")
                    }
                    return challenge
                }
                is ServerFrame.Ping -> answerPing(webSocket, frame.value.t)
                is ServerFrame.Error ->
                    authFail("hub error during auth: ${frame.value.message}")
                else -> authFail(
                    "protocol: expected a challenge, got ${frameTypeName(frame)}",
                )
            }
        }
    }

    private suspend fun readAuthResult(webSocket: DefaultClientWebSocketSession): AuthResult {
        while (true) {
            when (val frame = nextAuthFrame(webSocket)) {
                null -> authFail("hub closed the connection during auth")
                is ServerFrame.AuthOk -> return AuthResult.Ok(frame.value.hubId, frame.value.hubName)
                is ServerFrame.AuthFailed -> authFail(authFailedMessage(frame.value.reason))
                is ServerFrame.Ping -> answerPing(webSocket, frame.value.t)
                is ServerFrame.Error ->
                    authFail("hub error during auth: ${frame.value.message}")
                else -> authFail(
                    "protocol: expected auth_ok, got ${frameTypeName(frame)}",
                )
            }
        }
    }

    /**
     * Reads the next frame that matters during auth. WS-level pings
     * are answered here (OkHttp usually answers them first, but the
     * defense costs nothing); anything else that is not a JSON text
     * message ends the read — a close or a dropped socket is a dead
     * channel.
     */
    private suspend fun nextAuthFrame(
        webSocket: DefaultClientWebSocketSession,
    ): ServerFrame? {
        while (true) {
            val frame = try {
                webSocket.incoming.receive()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w("ClientTransport: auth frame read failed: %s", t.javaClass.simpleName)
                return null
            }
            when (frame) {
                is Frame.Text -> return ServerFrameParser.parse(frame.readText())
                is Frame.Ping -> runCatching { webSocket.send(Frame.Pong(frame.data)) }
                else -> return null
            }
        }
    }

    private suspend fun answerPing(webSocket: DefaultClientWebSocketSession, t: Long) {
        sendText(webSocket, SyncProtocol.encodePong(SyncProtocol.Ping(t)))
    }

    private suspend fun sendText(webSocket: DefaultClientWebSocketSession, text: String): Boolean {
        return try {
            webSocket.send(Frame.Text(text))
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Client mode — a failed send means the channel is dead,
            // not a crash: log the failure type (never the payload)
            // and report false.
            Timber.w("ClientTransport: send failed: %s", t.javaClass.simpleName)
            false
        }
    }

    /**
     * A connect failure as a human-readable error. A TOFU mismatch
     * anywhere in the cause chain gets the re-pair wording (the
     * desktop's `describe_ws_error`); anything else is a reachability
     * failure.
     */
    private fun describeConnectError(t: Throwable): String {
        var cause: Throwable? = t
        while (cause != null) {
            if ("fingerprint does not match" in (cause.message.orEmpty())) {
                return "hub TLS certificate changed: its fingerprint does not match the pinned " +
                    "one — re-pair from the hub phone to trust the new certificate"
            }
            cause = cause.cause
        }
        return "cannot reach the hub: ${t.message ?: t.javaClass.simpleName}"
    }

    /** The wire type of a parsed frame, for "expected X, got Y" errors. */
    private fun frameTypeName(frame: ServerFrame): String = when (frame) {
        is ServerFrame.Challenge -> "challenge"
        is ServerFrame.AuthOk -> "auth_ok"
        is ServerFrame.AuthFailed -> "auth_failed"
        is ServerFrame.Ping -> "ping"
        is ServerFrame.Pong -> "pong"
        ServerFrame.Revoked -> "revoked"
        is ServerFrame.Error -> "error"
        is ServerFrame.Unknown -> frame.type ?: "a message with no type"
        ServerFrame.Malformed -> "a malformed message"
        else -> "a sync message"
    }

    /** An auth-level failure: carries the message the caller shows. */
    private class AuthFailure(message: String) : Exception(message)

    /** Client mode — what [openSocket] produced: a live socket, or the failure reason. */
    private sealed interface OpenSocketResult {
        data class Ready(
            val webSocket: DefaultClientWebSocketSession,
            val client: HttpClient,
            val identity: ClientIdentity,
        ) : OpenSocketResult

        data class Failed(val error: String) : OpenSocketResult
    }

    private sealed interface AuthResult {
        data class Ok(val hubId: String, val hubName: String) : AuthResult
        data class Failed(val error: String) : AuthResult
    }

    companion object {
        /**
         * Per-attempt connect timeout: a dead host must fail here in
         * seconds, not after the OS TCP timeout (~2 min). 8 s per
         * candidate, so a two-candidate walk costs at most 16 s —
         * inside the manager's 20 s first-attempt wait.
         */
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}

/**
 * Client mode — the live session: heartbeat + inbound frames until
 * the socket drops, the pong times out, or the hub revokes us (the
 * desktop's `run_session`).
 *
 * The heartbeat is protocol-level JSON (`ping`/`pong`), not
 * WS-level: a `ping` goes out every 30 s, and a `pong` must arrive
 * within 10 s of it. The pong check runs every second so a dead hub
 * is noticed promptly, not at the next ping.
 */
private class ClientSessionImpl(
    private val webSocket: DefaultClientWebSocketSession,
    private val client: HttpClient,
    override val hubId: String,
    override val hubName: String,
) : ClientSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val endDeferred = CompletableDeferred<SessionEnd>()
    private val frameFlow = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 64)

    override val frames: Flow<ServerFrame> = frameFlow.asSharedFlow()

    /** When the last ping went out, or null while none is outstanding. */
    @Volatile
    private var lastPingSentAtMs: Long? = null

    init {
        scope.launch { pingLoop() }
        scope.launch { pongCheckLoop() }
        scope.launch { readLoop() }
    }

    override suspend fun send(text: String): Boolean = sendFrame(Frame.Text(text))

    override suspend fun close() {
        end(SessionEnd.Closed)
        endDeferred.await()
    }

    override suspend fun awaitEnd(): SessionEnd = endDeferred.await()

    private suspend fun sendFrame(frame: Frame): Boolean {
        if (endDeferred.isCompleted) return false
        return sendMutex.withLock {
            try {
                webSocket.send(frame)
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Client mode — a failed send means the channel is
                // dead, not a crash: log the failure type (never the
                // payload) and report false.
                Timber.w("ClientSession: send failed: %s", t.javaClass.simpleName)
                false
            }
        }
    }

    private suspend fun pingLoop() {
        // The first ping goes out after a full interval, not at once.
        delay(SyncProtocol.PING_INTERVAL_MS)
        while (true) {
            val t = System.currentTimeMillis()
            if (!send(SyncProtocol.encodePing(SyncProtocol.Ping(t)))) {
                end(SessionEnd.Dropped("connection to hub lost"))
                return
            }
            lastPingSentAtMs = t
            delay(SyncProtocol.PING_INTERVAL_MS)
        }
    }

    private suspend fun pongCheckLoop() {
        while (true) {
            delay(PONG_CHECK_INTERVAL_MS)
            val sentAt = lastPingSentAtMs
            if (sentAt != null && System.currentTimeMillis() - sentAt > PONG_TIMEOUT_MS) {
                end(SessionEnd.Dropped("hub stopped answering pings"))
                return
            }
        }
    }

    private suspend fun readLoop() {
        try {
            for (frame in webSocket.incoming) {
                when (frame) {
                    is Frame.Text -> handleText(frame.readText())
                    is Frame.Ping -> sendFrame(Frame.Pong(frame.data))
                    is Frame.Close -> {
                        end(SessionEnd.Dropped("hub closed the connection"))
                        return
                    }
                    else -> Unit
                }
                if (endDeferred.isCompleted) return
            }
            // The channel closed without a Close frame: the socket dropped.
            end(SessionEnd.Dropped("hub closed the connection"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            end(SessionEnd.Dropped("connection to hub lost: ${t.message}"))
        } finally {
            withContext(NonCancellable) {
                runCatching { webSocket.close() }
                client.close()
            }
        }
    }

    private suspend fun handleText(text: String) {
        when (val frame = ServerFrameParser.parse(text)) {
            is ServerFrame.Pong -> lastPingSentAtMs = null
            is ServerFrame.Ping -> {
                if (!send(SyncProtocol.encodePong(frame.value))) {
                    end(SessionEnd.Dropped("connection to hub lost"))
                }
            }
            ServerFrame.Revoked -> end(SessionEnd.Revoked)
            is ServerFrame.Error -> {
                Timber.w("ClientSession: hub error: %s", frame.value.message)
                frameFlow.emit(frame)
            }
            // Invalid JSON mid-session: ignore, channel stays up.
            ServerFrame.Malformed -> Unit
            // Unknown future message type: ignore.
            is ServerFrame.Unknown -> Unit
            // Sync traffic: forwarded to the engine verbatim, typed.
            else -> frameFlow.emit(frame)
        }
    }

    /** Ends the session once; the first caller wins, the rest are no-ops. */
    private fun end(end: SessionEnd) {
        if (endDeferred.complete(end)) {
            scope.cancel()
        }
    }

    companion object {
        /** A `pong` must arrive within this long of its `ping`. */
        private const val PONG_TIMEOUT_MS = 10_000L

        /** How often the pong deadline is checked. */
        private const val PONG_CHECK_INTERVAL_MS = 1_000L
    }
}
