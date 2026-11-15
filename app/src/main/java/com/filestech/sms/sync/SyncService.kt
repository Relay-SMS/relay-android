package com.filestech.sms.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.filestech.sms.MainActivity
import com.filestech.sms.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * GLUE — the Android wrapper around [SyncServer]. This is one of the
 * sync package's three glue points (see the glue-points section at the
 * end of SYNC_PROTOCOL.md): the service, the notification channel and
 * the string resources are Android-app concerns, while the server,
 * protocol, crypto, discovery and store underneath import no app code.
 *
 * A foreground service, because a LAN server must keep listening while
 * the app is in the background — a desktop that can only connect while
 * the phone's screen is on is not a link. Started by the app when the
 * server toggle is on ([com.filestech.sms.MainApplication] observes
 * the toggle, [com.filestech.sms.system.receiver.BootReceiver] restarts
 * it after a reboot); stopped when the toggle goes off.
 *
 * ## Why `specialUse` and not `dataSync`
 *
 * The manifest already declares `FOREGROUND_SERVICE_DATA_SYNC`, but a
 * `dataSync` service is the wrong type for a permanent server: under
 * targetSdk 35 Android 15 caps it at 6 h cumulative per 24 h (then
 * calls [onTimeout] and kills the app if the service does not stop),
 * and forbids starting it from `BOOT_COMPLETED` — both measured on
 * [com.filestech.sms.system.service.KeepAliveService] in v1.27.10,
 * which moved to `specialUse` for exactly these reasons. A server
 * that dies after six hours is not a hub. `specialUse` carries no
 * cap and no boot restriction, and it describes the service honestly:
 * it holds the process (and the port) open, nothing else.
 *
 * The notification is plain and neutral — no device names, no counts,
 * nothing a shoulder-surfer could use.
 *
 * While it runs, the service also listens for the system's
 * default-SMS-change broadcast and answers it with an immediate
 * role re-check, so the desktop's live-sync warning clears the
 * moment the user switches the default app in Settings instead of
 * waiting for the server's 30 s watchdog tick (which stays as the
 * safety net in case the broadcast is ever missed on some device).
 */
@AndroidEntryPoint
class SyncService : Service() {

    @Inject
    lateinit var syncServer: SyncServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Listens for the system broadcast sent to both the old and the
     * new default package when the phone's default SMS app changes,
     * so the desktop's warning clears the moment the user switches.
     * The broadcast is only a trigger: the re-check in
     * [SyncServer.refreshHubStatus] is the single source of truth —
     * the broadcast's extra is never trusted as the state itself.
     */
    private val defaultSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
            scope.launch {
                runCatching { syncServer.refreshHubStatus() }
            }
        }
    }

    /** Whether [defaultSmsReceiver] is currently registered. */
    private var defaultSmsReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        registerDefaultSmsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // Same three failure modes as KeepAliveService (POST_NOTIFICATIONS
            // revoked, background-start restriction, missing service type):
            // log and give up instead of crashing the caller. The toggle
            // observer in MainApplication will try again on the next flip.
            Timber.w(t, "SyncService.startForeground failed — server will not start")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            runCatching { syncServer.start() }
                .onFailure { t ->
                    // The port is taken, the identity cannot be built — a
                    // running service with no server behind it would be a
                    // lie (and a notification promising a link that is not
                    // there), so the service stops with it.
                    Timber.w(t, "SyncService: server failed to start, stopping service")
                    stopSelf()
                }
        }
        // START_STICKY: if the system kills the service, Android restarts
        // it and the server comes back. A user-initiated stop (toggle off)
        // does not trigger a sticky restart.
        return START_STICKY
    }

    /**
     * Safety net, as in KeepAliveService: `specialUse` carries no
     * minuteur, so this should never run — but if a future policy
     * applies one, stopping promptly beats a platform crash
     * (`ForegroundServiceDidNotStopInTimeException`).
     */
    override fun onTimeout(startId: Int) {
        Timber.w("SyncService: foreground-service timeout (startId=%d) — stopping", startId)
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (defaultSmsReceiverRegistered) {
            defaultSmsReceiverRegistered = false
            runCatching { unregisterReceiver(defaultSmsReceiver) }
        }
        scope.launch {
            runCatching { syncServer.stop() }
            scope.cancel()
        }
    }

    /**
     * Registers [defaultSmsReceiver] for the default-SMS-change
     * broadcast. RECEIVER_EXPORTED is required on API 33+: the
     * broadcast comes from the system (another UID), and an
     * unexported receiver never sees it. `ContextCompat` keeps the
     * call compiling against minSdk 26. If registration fails, the
     * server's 30 s watchdog re-check still covers the role change.
     */
    private fun registerDefaultSmsReceiver() {
        runCatching {
            ContextCompat.registerReceiver(
                this,
                defaultSmsReceiver,
                IntentFilter(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            defaultSmsReceiverRegistered = true
        }.onFailure { t ->
            Timber.w(t, "SyncService: default-SMS receiver not registered — 30 s re-check still applies")
        }
    }

    /**
     * The service owns its channel (it does not go through the app's
     * channel initializer — one less import of app code from the sync
     * package). IMPORTANCE_LOW, silent: a persistent notification must
     * not buzz.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_channel_label),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sync_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(getString(R.string.sync_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x5359 // 'SY'
        private const val CHANNEL_ID = "sync_server"

        /**
         * Starts the service (and with it, the server). Idempotent —
         * Android dedupes via `onStartCommand` on the same instance.
         * Defensive try/catch, as in KeepAliveService: on Android 12+
         * a background start can throw before the service exists.
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, SyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Timber.w(t, "SyncService.start failed — likely an Android 12+ background restriction")
            }
        }

        /** Stops the service (and with it, the server). Idempotent. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SyncService::class.java))
            } catch (t: Throwable) {
                Timber.w(t, "SyncService.stop failed — service likely not running")
            }
        }
    }
}
