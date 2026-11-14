package com.filestech.sms.sync

import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's LAN server (SYNC_PROTOCOL.md): Ktor on the Netty engine,
 * TLS only, one WebSocket route (`/sync`). There is deliberately no
 * plain-text connector — `embeddedServer` here takes an environment
 * and a `configure` block, so the only connector is the `sslConnector`
 * below. No unencrypted fallback exists anywhere in this file.
 *
 * Netty, not CIO: Ktor 3.x's CIO server engine has no HTTPS support —
 * it throws UnsupportedOperationException on any `sslConnector`, in
 * the engine's own start job, so the throw escapes every `runCatching`
 * around `start()` and crashes the app (observed on device 2026-11-13,
 * the moment the server started). Netty is the engine with TLS +
 * WebSocket support. `SyncEngineTlsTest` pins this: it boots the same
 * wiring and probes the port, so a future engine swap that drops TLS
 * fails the test instead of the app.
 *
 * Lifecycle is owned by [SyncService] (start/stop follow the server
 * toggle); this class only runs the engine. The auth state machine
 * lives in [SyncSessionHandler] — this file is the Ktor wiring around
 * it, nothing more.
 *
 * Own [CoroutineScope] (SupervisorJob + IO) instead of the app's
 * application scope: the sync package imports no app code, so it
 * cannot use the app's DI qualifiers (see the glue-points section at
 * the end of SYNC_PROTOCOL.md).
 */
@Singleton
class SyncServer @Inject constructor(
    private val store: LinkedDeviceStore,
    private val tlsProvider: TlsCertificateProvider,
    private val mdnsAdvertiser: MdnsAdvertiser,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startStopMutex = Mutex()

    private var engine: EmbeddedServer<*, *>? = null
    private var watchdogJob: Job? = null

    /** Live sessions, keyed by their state machine (which owns the socket's close). */
    private val sessions = ConcurrentHashMap<SyncSessionHandler, Unit>()

    private val _running = MutableStateFlow(false)

    /** True once the engine is listening (and mDNS is advertising). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _connectedDeviceIds = MutableStateFlow<Set<String>>(emptySet())

    /** Device ids with a live authenticated socket — the "Connected now" state. */
    val connectedDeviceIds: StateFlow<Set<String>> = _connectedDeviceIds.asStateFlow()

    /**
     * Starts the server: TLS identity, engine, mDNS, silence watchdog.
     * Idempotent — a second call while running is a no-op. Throws if
     * the identity cannot be built or the port cannot be bound; the
     * caller treats that as "the server cannot start" and stops the
     * service rather than pretending to listen.
     */
    suspend fun start() {
        startStopMutex.withLock {
            if (engine != null) return
            val hubId = store.hubId()
            val identity = tlsProvider.ensureIdentity(hubId)
            val server = embeddedServer(
                Netty,
                applicationEnvironment { },
                configure = {
                    sslConnector(
                        identity.keyStore,
                        identity.keyAlias,
                        { identity.keyPassword },
                        { identity.storePassword },
                    ) {
                        host = "0.0.0.0"
                        port = SyncProtocol.PORT
                        enabledProtocols = SyncProtocol.TLS_PROTOCOLS
                    }
                },
            ) {
                install(WebSockets)
                routing {
                    webSocket("/sync") {
                        handleSession(this, hubId)
                    }
                }
            }
            server.start(wait = false)
            engine = server
            mdnsAdvertiser.start(hubId)
            startWatchdog()
            _running.value = true
            Timber.i("SyncServer: listening on port %d (TLS only)", SyncProtocol.PORT)
        }
    }

    /** Stops the engine, the watchdog and mDNS. Idempotent. */
    suspend fun stop() {
        startStopMutex.withLock {
            watchdogJob?.cancel()
            watchdogJob = null
            mdnsAdvertiser.stop()
            engine?.stopSuspend(GRACE_PERIOD_MS, TIMEOUT_MS)
            engine = null
            sessions.clear()
            _connectedDeviceIds.value = emptySet()
            _running.value = false
            Timber.i("SyncServer: stopped")
        }
    }

    /**
     * Revokes a device's live socket, if it has one: the handler sends
     * `revoked` first, then closes. The stored key is deleted by the
     * caller ([LinkedDeviceStore.removeDevice]) — this only handles the
     * live connection.
     */
    suspend fun revoke(deviceId: String) {
        for (handler in sessions.keys) {
            if (handler.deviceId == deviceId) {
                runCatching { handler.revoke() }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(SyncProtocol.SILENCE_CHECK_MS)
                val now = System.currentTimeMillis()
                for (handler in sessions.keys) {
                    if (handler.isAuthenticated &&
                        now - handler.lastReceivedAtMs > SyncProtocol.SILENCE_LIMIT_MS
                    ) {
                        Timber.i(
                            "SyncServer: dropping silent client %s",
                            handler.deviceId,
                        )
                        runCatching { handler.drop("silent too long") }
                    }
                }
            }
        }
    }

    /**
     * One socket's lifetime: a fresh state machine, a 10 s auth
     * watchdog, then the frame loop. Binary frames are ignored — the
     * protocol is JSON text. Any socket error just ends the session;
     * Ktor logs its own detail.
     */
    private suspend fun handleSession(
        session: WebSocketServerSession,
        hubId: String,
    ) {
        val handler = SyncSessionHandler(
            lookup = store,
            hubId = hubId,
            send = { text -> session.send(Frame.Text(text)) },
            close = { reason -> session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) },
        )
        sessions[handler] = Unit
        val authTimeout = session.launch {
            delay(SyncProtocol.AUTH_TIMEOUT_MS)
            runCatching { handler.onAuthTimeout() }
        }
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val wasAuthenticated = handler.isAuthenticated
                    runCatching { handler.onText(frame.readText()) }
                        .onFailure { t ->
                            Timber.w(t, "SyncServer: session handler failed, closing")
                            runCatching { handler.drop("handler error") }
                        }
                    if (!wasAuthenticated && handler.isAuthenticated) {
                        authTimeout.cancel()
                        refreshConnected()
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.d(t, "SyncServer: session ended")
        } finally {
            authTimeout.cancel()
            sessions.remove(handler)
            refreshConnected()
        }
    }

    private fun refreshConnected() {
        _connectedDeviceIds.value = sessions.keys.mapNotNull { it.deviceId }.toSet()
    }

    companion object {
        /** Grace period / hard timeout for the engine's stop. */
        private const val GRACE_PERIOD_MS = 500L
        private const val TIMEOUT_MS = 2_000L
    }
}
