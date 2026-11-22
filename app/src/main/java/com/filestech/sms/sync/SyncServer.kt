package com.filestech.sms.sync

import com.filestech.sms.sync.calls.CallDialer
import com.filestech.sms.sync.calls.CallEvent
import com.filestech.sms.sync.calls.CallStateObserver
import com.filestech.sms.sync.calls.encodeCallEventFrames
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
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
 * HTTP/2 is off (`enableHttp2 = false` in [configureEngine]): Ktor
 * enables it by default, which turns on ALPN negotiation ("h2",
 * "http/1.1") — and Netty's ALPN engine kills the handshake at the
 * finish line when the client offers no ALPN extension, which our
 * desktop client doesn't (AssertionError in JdkAlpnSslEngine, seen
 * on device 2026-11-14: the crypto all succeeded, then the channel
 * died). The sync protocol is one WebSocket over HTTP/1.1, so H2
 * buys nothing.
 *
 * Lifecycle is owned by [SyncService] (start/stop follow the server
 * toggle); this class only runs the engine. The auth state machine
 * lives in [SyncSessionHandler] — this file is the Ktor wiring around
 * it, nothing more. Each session gets its own [SyncEngine], fed by
 * the injected [SyncMessageSource]; the engine is cancelled when the
 * session ends. Every session's engine shares the one injected
 * [SyncWakelock], held only while a sync stream runs.
 *
 * Slice 5 — the server also owns the live contact delta: it holds
 * the last-broadcast contact snapshot (keyed by CONTACT_ID, internal
 * only), re-checks it on a debounced provider-change notification
 * ([notifyContactsChanged], called from the ContentObserver in
 * `MainApplication`) and on the watchdog tick, and broadcasts
 * `contact_updated` / `contact_deleted` to every authenticated
 * session. A session that has not synced contacts yet harmlessly
 * upserts the delta rows; its `sync_request` snapshot + prune
 * reconciles everything anyway.
 *
 * Own [CoroutineScope] (SupervisorJob + IO) instead of the app's
 * application scope: the sync package imports no app code, so it
 * cannot use the app's DI qualifiers (see the glue-points section at
 * the end of SYNC_PROTOCOL.md).
 *
 * Live calls — the server also owns the call-state observer: ONE
 * observer for the whole server (a single system listener), started
 * in [start] and stopped in [stop], whose events broadcast
 * `incoming_call` / `call_state` to every authenticated session
 * ([broadcastCallEvent]). The observer lives here, not in the
 * per-session engines: a call is a phone-wide event, not a
 * session event, and one listener must not be registered per
 * socket. The dial side is per-session instead — each engine gets
 * the shared [CallDialer] and answers its own session's
 * `dial_request` with `dial_result`.
 *
 * Tunnel — the server also owns the tunnel advertisement: when a
 * VPN tunnel is the phone's active network, the phone's tunnel
 * IPv4 address rides the mDNS TXT `tip` record and the
 * `hub_status` / `sync_started` tunnel fields, so a linked device
 * can dial through the tunnel (SYNC_PROTOCOL.md, "VPN / tunnel").
 * [refreshTunnelAdvertisement] re-reads the address and, on
 * change, re-advertises mDNS and pushes the new value; the
 * instant trigger is the VPN network callback in [SyncService]
 * and the 30 s watchdog tick is the safety net, mirroring the
 * default-SMS re-check.
 *
 * @param vpnAddressProvider the tunnel-address reader, or null
 * when the server has none (tests): the tunnel advertisement is
 * then simply never refreshed.
 * @param callStateObserver the phone's call-state observer, or
 * null when the server has none (tests): call events are then
 * simply never broadcast.
 * @param callDialer the dial executor handed to each session's
 * engine, or null when the server has none (tests): a dial then
 * answers `dial_result` `failed`.
 * @param noteSource the note store seam handed to each session's
 * engine, or null when the server has none (tests): the notes
 * phase is then skipped and note frames are ignored.
 * @param e2eSource the E2E store seam handed to each session's
 * engine, or null when the server has none (tests): no E2E frame
 * is then sent and the E2E commands answer `ok: false`.
 */
@Singleton
class SyncServer @Inject constructor(
    private val store: LinkedDeviceStore,
    private val tlsProvider: TlsCertificateProvider,
    private val mdnsAdvertiser: MdnsAdvertiser,
    private val messageSource: SyncMessageSource,
    private val syncWakelock: SyncWakelock,
    private val vpnAddressProvider: VpnAddressProvider? = null,
    private val callStateObserver: CallStateObserver? = null,
    private val callDialer: CallDialer? = null,
    private val noteSource: NoteSyncSource? = null,
    private val e2eSource: E2eSyncSource? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startStopMutex = Mutex()

    private var engine: EmbeddedServer<*, *>? = null
    private var watchdogJob: Job? = null

    /** Live calls — the collector that broadcasts the observer's events. */
    private var callEventsJob: Job? = null

    /** Live sessions, keyed by their state machine (which owns the socket's close). */
    private val sessions = ConcurrentHashMap<SyncSessionHandler, Unit>()

    private val _running = MutableStateFlow(false)

    /** True once the engine is listening (and mDNS is advertising). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _connectedDeviceIds = MutableStateFlow<Set<String>>(emptySet())

    /** Device ids with a live authenticated socket — the "Connected now" state. */
    val connectedDeviceIds: StateFlow<Set<String>> = _connectedDeviceIds.asStateFlow()

    /** Last default-SMS status broadcast (null = never checked). */
    private var lastHubStatus: Boolean? = null

    /** The hub id, cached at [start] for the mDNS re-advertisement in [refreshTunnelAdvertisement]. */
    private var hubId: String? = null

    /**
     * Last-advertised tunnel IPv4 address (null = no tunnel path
     * when last checked — or never checked). The comparison in
     * [refreshTunnelAdvertisement] is against this, so a first
     * check with no tunnel up is correctly "no change".
     */
    private var lastTunnelIp: String? = null

    /**
     * Slice 5 — last-broadcast contact snapshot, keyed by CONTACT_ID
     * (internal only — ids never cross the wire). Null until the
     * first re-check seeds it. Guarded by [contactsMutex]: the
     * debounced notify and the watchdog tick can both re-check.
     */
    private var lastContacts: Map<Long, SyncedContact>? = null

    /** Slice 5 — serializes the contact re-checks (debounce + watchdog). */
    private val contactsMutex = Mutex()

    /** Slice 5 — the pending debounced re-check, replaced on every new notification. */
    private var contactsDebounceJob: Job? = null

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
            // The start-failure diagnostic (§6 of the release
            // investigation): the release build logs nothing, so a
            // start failure would otherwise be silent. The step is
            // recorded with the exception and shown on the Linked
            // devices screen; a successful start clears it.
            var step = STEP_IDENTITY
            try {
                // Netty logs through its own factory, which on Android goes
                // nowhere — route it to Timber first, so a TLS handshake
                // failure is visible in logcat instead of vanishing silently.
                installNettyTimberLogging()
                val hubId = store.hubId()
                this.hubId = hubId
                val hubName = store.hubName()
                val identity = tlsProvider.ensureIdentity(hubId)
                step = STEP_ENGINE
                val server = embeddedServer(
                    Netty,
                    applicationEnvironment { },
                    configure = { configureEngine(this, identity) },
                ) {
                    install(WebSockets)
                    routing {
                        webSocket("/sync") {
                            handleSession(this, hubId, hubName)
                        }
                    }
                }
                server.start(wait = false)
                engine = server
                step = STEP_MDNS
                // The tunnel address rides the first advertisement
                // too — a desktop scanning right after the server
                // starts must see it, not wait for a change. The
                // read never throws (the provider is fail-soft),
                // but a throw here would fail the whole start, so
                // it is guarded anyway.
                val tunnelIp = runCatching { vpnAddressProvider?.currentTunnelIpv4() }.getOrNull()
                lastTunnelIp = tunnelIp
                mdnsAdvertiser.start(hubId, hubName, tunnelIp)
                startWatchdog()
                _running.value = true
                startCallObserver()
                store.clearStartError()
                Timber.i("SyncServer: listening on port %d (TLS only)", SyncProtocol.PORT)
            } catch (t: Throwable) {
                // recordStartError is itself runCatching, so a
                // persistence failure never masks the original throw —
                // and the rethrow preserves the service's stopSelf()
                // behavior in SyncService.
                store.recordStartError(step, t)
                throw t
            }
        }
    }

    /** Stops the engine, the watchdog and mDNS. Idempotent. */
    suspend fun stop() {
        startStopMutex.withLock {
            watchdogJob?.cancel()
            watchdogJob = null
            callEventsJob?.cancel()
            callEventsJob = null
            runCatching { callStateObserver?.stop() }
            contactsDebounceJob?.cancel()
            contactsDebounceJob = null
            lastContacts = null
            hubId = null
            lastTunnelIp = null
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

    /**
     * Tells every authenticated session whether this app is the
     * phone's default SMS app — the desktop shows (or clears) its
     * live-sync warning from this. Called from [refreshHubStatus]
     * when the role changes; a dead socket just skips. [tunnelIp]
     * rides along when the tunnel address changed too (see
     * [refreshTunnelAdvertisement]); absent (null) means the push
     * carries no tunnel news, and the desktop keeps any tunnel
     * address it stored earlier.
     */
    suspend fun broadcastHubStatus(isDefaultSms: Boolean, tunnelIp: String? = null) {
        broadcast(SyncProtocol.encodeHubStatus(SyncProtocol.HubStatus(isDefaultSms, tunnelIp)))
    }

    /**
     * Live calls — starts the call-state observer and the
     * collector that broadcasts its events to every authenticated
     * session. The collector launches BEFORE the observer starts:
     * the event flow has no replay, so a call that begins
     * between the two would otherwise be missed — and the
     * listener's callbacks are delivered asynchronously on the
     * telephony thread, so the collector is subscribed long
     * before the first event can arrive. A no-op without an
     * observer (tests). Fail-soft: the observer never throws, so
     * a listening failure degrades to "no call events" instead
     * of failing the server start.
     */
    private fun startCallObserver() {
        val observer = callStateObserver ?: return
        callEventsJob = scope.launch {
            observer.events.collect { event ->
                runCatching { broadcastCallEvent(event) }
            }
        }
        runCatching { observer.start() }
    }

    /**
     * Live calls — broadcasts one call event to every
     * authenticated session: a ringing phone sends both
     * `incoming_call` and `call_state` `ringing`; every other
     * transition sends only its `call_state` (see
     * [encodeCallEventFrames]). A dead socket just skips.
     */
    private suspend fun broadcastCallEvent(event: CallEvent) {
        for (frame in encodeCallEventFrames(event)) {
            broadcast(frame)
        }
    }

    /**
     * Slice 5 — the contacts provider changed (called from the
     * ContentObserver in `MainApplication`, only when the server
     * toggle is on). A no-op when the server is not running.
     * Provider changes fire in bursts during an account sync/import,
     * so the re-check runs 2 s after the last tick, not per
     * notification. Not suspending: the observer calls it from the
     * main thread, and the re-check runs in the server scope.
     */
    fun notifyContactsChanged() {
        if (!_running.value) return
        contactsDebounceJob?.cancel()
        contactsDebounceJob = scope.launch {
            delay(CONTACTS_DEBOUNCE_MS)
            recheckContacts()
        }
    }

    /** Sends one frame to every authenticated session; a dead socket just skips. */
    private suspend fun broadcast(frame: String) {
        for (handler in sessions.keys) {
            if (handler.isAuthenticated) {
                runCatching { handler.sendText(frame) }
            }
        }
    }

    /**
     * Slice 5 — re-reads the contact snapshot, diffs it against the
     * last-broadcast one, and pushes `contact_updated` /
     * `contact_deleted` to every authenticated session. The first
     * re-check only seeds the snapshot (each session's own
     * `sync_request` snapshot covers it); a missing permission or a
     * failed read leaves the snapshot alone — the next re-check
     * retries, and the next connect re-syncs fully. Called from the
     * debounced [notifyContactsChanged] and from the watchdog tick
     * (the safety net for a missed observer notification). Logging
     * is counts only — contact names and numbers never reach the log.
     */
    private suspend fun recheckContacts() {
        contactsMutex.withLock {
            if (!_running.value) return
            val readiness = runCatching { messageSource.contactsReadiness() }.getOrNull()
            if (readiness != ContactReadiness.Ready) return
            val fresh = runCatching { messageSource.contacts() }.getOrNull() ?: return
            // Contacts without an id (never from the reader — it
            // always sets one) can't be diffed by id; they still
            // ride the snapshot stream, just not the live delta.
            val snapshot = fresh.filter { it.contactId != 0L }.associateBy { it.contactId }
            val previous = lastContacts
            lastContacts = snapshot
            if (previous == null) return
            broadcastContactDiff(previous, snapshot)
        }
    }

    /**
     * Slice 5 — pushes one diff: a deleted id becomes
     * `contact_deleted` carrying the contact's last-known phones; a
     * new or changed id becomes `contact_updated`, with
     * `removed_phones` listing any numbers the contact dropped, so
     * the desktop deletes those rows instead of letting them linger
     * until the next full snapshot.
     */
    private suspend fun broadcastContactDiff(
        previous: Map<Long, SyncedContact>,
        snapshot: Map<Long, SyncedContact>,
    ) {
        val deleted = previous.keys - snapshot.keys
        val updated = snapshot.filter { (id, contact) -> previous[id] != contact }
        if (deleted.isEmpty() && updated.isEmpty()) return
        Timber.d(
            "SyncServer: contacts changed (%d updated, %d deleted)",
            updated.size,
            deleted.size,
        )
        for (id in deleted) {
            broadcast(
                SyncProtocol.encodeContactDeleted(
                    SyncProtocol.ContactDeleted(phones = previous.getValue(id).phones),
                ),
            )
        }
        for ((id, contact) in updated) {
            val removed = previous[id]?.phones.orEmpty().filter { it !in contact.phones }
            broadcast(
                SyncProtocol.encodeContactUpdated(
                    SyncProtocol.ContactUpdated(
                        contact = contact,
                        removedPhones = removed.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
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
                refreshHubStatus()
                // The tunnel safety net: a missed network callback
                // converges within 30 s.
                refreshTunnelAdvertisement()
                // Slice 5 — the contacts safety net: a missed
                // observer notification converges within 30 s.
                recheckContacts()
            }
        }
    }

    /**
     * Re-checks the default-SMS role and pushes `hub_status` when it
     * changed, so the desktop's warning clears — or appears — as soon
     * as the user switches the default app in Settings. The instant
     * path is the system's `ACTION_DEFAULT_SMS_PACKAGE_CHANGED`
     * broadcast, which [SyncService] listens for while it runs and
     * answers by calling this; the watchdog's 30 s tick below calls
     * it too, as a safety net in case the broadcast is ever missed
     * on some device. A re-check, not a listener on the role itself:
     * `RoleManager`'s role-change listener is a system API, absent
     * from the public SDK (verified against the API 36 stubs), so
     * there is nothing to subscribe to — and the check itself is one
     * cheap synchronous call. The first check also broadcasts
     * (lastHubStatus starts null), which establishes the status for
     * sessions that connected before any sync ran.
     */
    suspend fun refreshHubStatus() {
        val current = runCatching { messageSource.isDefaultSmsApp() }.getOrNull() ?: return
        if (current != lastHubStatus) {
            lastHubStatus = current
            runCatching { broadcastHubStatus(current) }
        }
    }

    /**
     * Re-reads the phone's tunnel address and, only on change,
     * re-advertises mDNS with the new TXT `tip` and pushes
     * `hub_status` with the new value to every authenticated
     * session, so a linked device learns the tunnel address (or
     * its disappearance) without a re-scan. No change → no work
     * (the check itself is cheap: one network lookup). The
     * instant path is the VPN network callback in [SyncService];
     * the watchdog's 30 s tick calls this too, as a safety net in
     * case the callback is ever missed on some device. A no-op
     * when the server is not running, or has no tunnel provider
     * (tests). The address itself never reaches the log — only
     * whether one is present.
     */
    suspend fun refreshTunnelAdvertisement() {
        val provider = vpnAddressProvider ?: return
        if (!_running.value) return
        val hub = hubId ?: return
        val current = runCatching { provider.currentTunnelIpv4() }.getOrNull()
        if (current == lastTunnelIp) return
        lastTunnelIp = current
        Timber.i("SyncServer: tunnel address %s", if (current == null) "gone" else "present")
        runCatching { mdnsAdvertiser.restart(hub, current) }
        val isDefault = lastHubStatus
            ?: runCatching { messageSource.isDefaultSmsApp() }.getOrNull()
        if (isDefault != null) {
            runCatching { broadcastHubStatus(isDefault, current) }
        }
    }

    /**
     * One socket's lifetime: a fresh state machine, a fresh sync
     * engine, a 10 s auth watchdog, then the frame loop. Binary frames
     * are ignored — the protocol is JSON text. Any socket error just
     * ends the session; Ktor logs its own detail.
     */
    private suspend fun handleSession(
        session: WebSocketServerSession,
        hubId: String,
        hubName: String,
    ) {
        val engine = SyncEngine(
            source = messageSource,
            parentScope = session,
            send = { text -> session.send(Frame.Text(text)) },
            wakelock = syncWakelock,
            callDialer = callDialer,
            noteSource = noteSource,
            e2eSource = e2eSource,
        )
        val handler = SyncSessionHandler(
            lookup = store,
            hubId = hubId,
            hubName = hubName,
            send = { text -> session.send(Frame.Text(text)) },
            close = { reason -> session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) },
            engine = engine,
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
            engine.stop()
            sessions.remove(handler)
            refreshConnected()
        }
    }

    private fun refreshConnected() {
        _connectedDeviceIds.value = sessions.keys.mapNotNull { it.deviceId }.toSet()
    }

    companion object {
        /** Start-failure diagnostic steps (§6 of the release investigation). */
        private const val STEP_IDENTITY = "identity"
        private const val STEP_ENGINE = "engine"
        private const val STEP_MDNS = "mdns"

        /** Grace period / hard timeout for the engine's stop. */
        private const val GRACE_PERIOD_MS = 500L
        private const val TIMEOUT_MS = 2_000L

        /** Slice 5 — the contact-change debounce: the re-check runs this long after the last tick. */
        private const val CONTACTS_DEBOUNCE_MS = 2_000L

        /**
         * The engine's configuration, shared by [SyncServer.start] and
         * [SyncEngineTlsTest] so the test boots the exact wiring the
         * app runs. TLS only: there is deliberately no plain-text
         * connector anywhere.
         *
         * HTTP/2 is off: Ktor enables it by default, which turns on
         * ALPN negotiation ("h2", "http/1.1") — and Netty's ALPN
         * engine kills the handshake at the finish line when the
         * client offers no ALPN extension, which our desktop client
         * doesn't (AssertionError in JdkAlpnSslEngine, seen on device
         * 2026-11-14). The sync protocol is one WebSocket over
         * HTTP/1.1, so H2 buys nothing.
         */
        internal fun configureEngine(
            config: NettyApplicationEngine.Configuration,
            identity: TlsIdentity,
            host: String = "0.0.0.0",
            port: Int = SyncProtocol.PORT,
        ) {
            config.enableHttp2 = false
            with(config) {
                sslConnector(
                    identity.keyStore,
                    identity.keyAlias,
                    { identity.keyPassword },
                    { identity.storePassword },
                ) {
                    this.host = host
                    this.port = port
                    enabledProtocols = SyncProtocol.TLS_PROTOCOLS
                }
            }
        }
    }
}
