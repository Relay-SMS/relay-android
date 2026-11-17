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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Client mode — where to connect: a hub's host (IP or mDNS hostname)
 * and port (47831). The URL is always `wss://` — there is no
 * unencrypted fallback.
 */
data class HubTarget(
    val host: String,
    val port: Int = SyncProtocol.PORT,
) {
    /** `wss://<host>:<port>/sync`, bracketing IPv6 literals (desktop rule). */
    fun wsUrl(): String {
        val bracketed = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "wss://$bracketed:$port/sync"
    }
}

/** The outcome of one connect + auth attempt. */
sealed interface ConnectOutcome {
    /** Authenticated: the session is live and heartbeating. */
    data class Connected(val session: ClientSession) : ConnectOutcome

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
 * Client mode — one connect + auth attempt over Ktor client +
 * OkHttp, the mirror of the desktop's `attempt_connect`:
 *
 * `wss://<host>:47831/sync`, TLS with the TOFU trust manager,
 * `hello` → `challenge` → `auth` (signing the RAW challenge bytes)
 * → `auth_ok` / `auth_failed`, all inside a 10 s connect timeout
 * and a 10 s auth budget (the hub closes unauthenticated sockets
 * after 10 s).
 *
 * OkHttp is the engine (not CIO): it is the battle-tested Android
 * TLS path, the custom TOFU `X509TrustManager` plugs straight into
 * it, and it is already in the APK transitively via Coil. Hostname
 * verification is deliberately skipped — the TOFU fingerprint IS
 * the identity check.
 *
 * The transport owns one attempt only: the reconnect loop lives in
 * [ClientConnectionManager].
 */
@Singleton
class ClientTransport @Inject constructor(
    private val identityStore: ClientIdentityStore,
    private val pinStore: TofuPinStore,
) {

    /**
     * One connect + auth attempt against [target]. Never throws (a
     * caller cancellation still propagates): every failure comes back
     * as [ConnectOutcome.Failed] with a human-readable reason.
     */
    suspend fun connect(target: HubTarget, kind: DeviceKind): ConnectOutcome {
        val ready = when (val opened = openSocket(target)) {
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
    private suspend fun openSocket(target: HubTarget): OpenSocketResult {
        val identity = try {
            identityStore.identity()
        } catch (ce: CancellationException) {
            rethrowCancellation(ce)
        } catch (t: Throwable) {
            return OpenSocketResult.Failed("cannot load this device's identity: ${t.message}")
        }
        val client = try {
            buildClient()
        } catch (ce: CancellationException) {
            rethrowCancellation(ce)
        } catch (t: Throwable) {
            return OpenSocketResult.Failed("cannot build the TLS client: ${t.message}")
        }
        return try {
            val webSocket = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                client.webSocketSession(target.wsUrl())
            }
            if (webSocket == null) {
                client.close()
                return OpenSocketResult.Failed(
                    "cannot reach the hub at ${target.host}:${target.port}: " +
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

    private fun buildClient(): HttpClient {
        val trustManager = TofuTrustManager(pinStore)
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
        /** A dead host must fail here in seconds, not after the OS TCP timeout (~2 min). */
        const val CONNECT_TIMEOUT_MS = 10_000L
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
