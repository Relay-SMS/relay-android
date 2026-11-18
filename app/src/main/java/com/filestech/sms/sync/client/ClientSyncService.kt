package com.filestech.sms.sync.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import com.filestech.sms.crashlog.SessionMarkerTracker
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/**
 * Client mode — the Android wrapper around [ClientConnectionManager],
 * the mirror of the hub's [com.filestech.sms.sync.SyncService].
 *
 * A foreground service, because the link to the hub phone must stay
 * alive while the app is in the background — a tablet that only
 * syncs while its screen is on is not a linked device. Started when
 * the device links ([ClientLinkViewModel] on first connect, and
 * [com.filestech.sms.system.receiver.BootReceiver] restarts it after
 * a reboot); stopped on unlink.
 *
 * `specialUse`, not `dataSync`, for the same reason as the hub
 * service: under targetSdk 35 Android 15 caps a `dataSync` service
 * at 6 h cumulative per 24 h and forbids starting it from
 * `BOOT_COMPLETED`. A link that dies after six hours is not a link.
 *
 * The notification shows the link status — "Connected to <hub>",
 * "Reconnecting…", or "Offline" — and updates live as the
 * connection state changes. It never names the hub's messages, only
 * the hub itself.
 */
@AndroidEntryPoint
class ClientSyncService : Service() {

    @Inject
    lateinit var connectionManager: ClientConnectionManager

    @Inject
    lateinit var linkStore: ClientLinkStore

    /**
     * Client mode C2 — the sync engine, lazy: resolving it builds
     * the mirror graph (Room + the sync-state tables), which stays
     * off the service's start path until the link is confirmed.
     * Resolved on the IO scope below, never on the main thread.
     */
    @Inject
    lateinit var syncEngine: Lazy<ClientSyncEngine>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The job watching the connection state to refresh the notification. */
    private var observeJob: Job? = null

    /** The job watching the engine state to disconnect on pause / reconnect on resume. */
    private var engineObserveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(connectionManager.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // Same three failure modes as SyncService
            // (POST_NOTIFICATIONS revoked, background-start
            // restriction, missing service type): log and give up
            // instead of crashing the caller.
            Timber.w(t, "ClientSyncService.startForeground failed — link will not start")
            stopSelf()
            return START_NOT_STICKY
        }
        observeJob?.cancel()
        engineObserveJob?.cancel()
        engineObserveJob = null
        observeJob = scope.launch {
            connectionManager.state.collect { state ->
                val manager = getSystemService<NotificationManager>()
                manager?.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        scope.launch {
            val link = linkStore.snapshot()
            if (link?.isLinked != true) {
                // Nothing to connect to — a service with no hub
                // behind it would be a lie (and a notification
                // promising a link that is not there), so it stops.
                Timber.i("ClientSyncService: no linked hub, stopping service")
                stopSelf()
                return@launch
            }
            val host = link.hubHost
            val port = link.hubPort
            if (host == null || port == null) {
                Timber.w("ClientSyncService: linked hub has no address, stopping service")
                stopSelf()
                return@launch
            }
            val kind = deviceKindFor(resources.configuration.smallestScreenWidthDp)
            // Client mode C2 — the engine starts BEFORE the first
            // connect, so it sees the connection state from the
            // beginning (prompt on first connect, delta resume on
            // reconnect) and no frame arrives unobserved.
            val engine = syncEngine.get()
            engine.start()
            // Pause/resume — the engine owns the sync state, the
            // service owns the transport: when the engine pauses a
            // run (device asleep / stalled stream), the service
            // disconnects so the reconnect loop does not fight the
            // pause; when a resume flips the phase back to Syncing
            // with no session, the service reconnects and the
            // engine completes the resume on connect. The link is
            // re-read fresh at reconnect time: the hub may have
            // moved since this service started, and the manager
            // keeps the link record current.
            engineObserveJob = scope.launch {
                var paused = false
                engine.ui.collect { ui ->
                    // Session marker — record the sync phase so a
                    // death during a sync still says the sync was
                    // running. Only phase transitions write; the
                    // tracker skips unchanged values, so the
                    // per-batch progress emissions cost nothing.
                    SessionMarkerTracker.current?.updateSyncState(ui.phase.name.lowercase(Locale.ROOT))
                    if (ui.phase == ClientSyncPhase.Paused) {
                        if (!paused) {
                            paused = true
                            connectionManager.disconnect()
                        }
                    } else {
                        paused = false
                        if (ui.phase == ClientSyncPhase.Syncing &&
                            ui.connection is ClientConnectionState.Disconnected
                        ) {
                            val fresh = linkStore.snapshot()
                            val freshHost = fresh?.hubHost
                            val freshPort = fresh?.hubPort
                            if (freshHost != null && freshPort != null) {
                                connectionManager.connect(
                                    HubTarget(freshHost, freshPort, fresh.hubId.orEmpty()),
                                    kind,
                                )
                            }
                        }
                    }
                }
            }
            connectionManager.connect(HubTarget(host, port, link.hubId.orEmpty()), kind)
        }
        // START_STICKY: if the system kills the service, Android
        // restarts it and the link comes back. A user-initiated stop
        // (unlink) does not trigger a sticky restart.
        return START_STICKY
    }

    /**
     * Safety net, as in SyncService: `specialUse` carries no
     * minuteur, so this should never run — but if a future policy
     * applies one, stopping promptly beats a platform crash
     * (`ForegroundServiceDidNotStopInTimeException`).
     */
    override fun onTimeout(startId: Int) {
        Timber.w("ClientSyncService: foreground-service timeout (startId=%d) — stopping", startId)
        stopSelf(startId)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        observeJob = null
        engineObserveJob?.cancel()
        engineObserveJob = null
        connectionManager.disconnect()
        scope.cancel()
    }

    /**
     * The service owns its channel (it does not go through the app's
     * channel initializer). IMPORTANCE_LOW, silent: a persistent
     * notification must not buzz.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.client_sync_channel_label),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.client_sync_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ClientConnectionState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val statusText = when (state) {
            is ClientConnectionState.Connected ->
                getString(R.string.client_notification_connected, state.hubName)
            is ClientConnectionState.Connecting,
            is ClientConnectionState.Failed,
            -> getString(R.string.client_notification_reconnecting)
            is ClientConnectionState.Disconnected,
            is ClientConnectionState.Removed,
            -> getString(R.string.client_notification_offline)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(getString(R.string.client_notification_title))
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x434C // 'CL'
        private const val CHANNEL_ID = "client_sync"

        /**
         * Starts the service (and with it, the link). Idempotent —
         * Android dedupes via `onStartCommand` on the same instance.
         * Defensive try/catch, as in SyncService: on Android 12+ a
         * background start can throw before the service exists.
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, ClientSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Timber.w(t, "ClientSyncService.start failed — likely an Android 12+ background restriction")
            }
        }

        /** Stops the service (and with it, the link). Idempotent. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ClientSyncService::class.java))
            } catch (t: Throwable) {
                Timber.w(t, "ClientSyncService.stop failed — service likely not running")
            }
        }
    }
}
