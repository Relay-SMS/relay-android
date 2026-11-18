package com.filestech.sms.sync.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — the device-idle signal behind sync pause/resume.
 *
 * A first full sync stalls when the device falls asleep: Android
 * Doze defers the app's network, the counter freezes, and today an
 * interrupted run restarts from 0. The engine pauses the run when
 * the device enters idle mode *and* the app is not exempt from
 * battery optimizations — that conjunction is the "know" condition:
 * idle alone (an exempt app keeps its network) never pauses, and
 * screen-off alone never pauses either (only idle mode defers the
 * network). The screen-on signal is the auto-resume trigger: the
 * user is back, so a parked run restarts.
 *
 * All APIs are API 23+ (`PowerManager.isDeviceIdleMode`,
 * `isIgnoringBatteryOptimizations`, `ACTION_DEVICE_IDLE_MODE_CHANGED`),
 * so they are safe at min SDK 26. The narrow interface keeps the
 * engine testable on plain JVM: tests use a fake that drives the
 * flows on demand, never the Android framework.
 */
interface DeviceIdleMonitor {
    /** True while the device is in Doze idle mode. */
    fun isDeviceIdleMode(): Boolean

    /**
     * True when the user exempted Relay from battery optimizations
     * (Settings → battery): an exempt app keeps its network in
     * Doze, so its syncs never pause for sleep.
     */
    fun isIgnoringBatteryOptimizations(): Boolean

    /**
     * The idle state on every change (replays the latest, so a
     * collector that starts while the device is idle sees `true`
     * at once). Emitted from the `ACTION_DEVICE_IDLE_MODE_CHANGED`
     * broadcast.
     */
    val idleChanges: Flow<Boolean>

    /** One emission per screen-on (`ACTION_SCREEN_ON`), for auto-resume. */
    val screenOn: Flow<Unit>

    /** Starts listening for idle changes + screen-on. Idempotent. */
    fun start()

    /** Stops listening. Idempotent. */
    fun stop()
}

/**
 * The Android implementation: a broadcast receiver for
 * `ACTION_DEVICE_IDLE_MODE_CHANGED` + `ACTION_SCREEN_ON`,
 * registered non-exported (the broadcasts are system-sent; no
 * other app may spoof them into this receiver). A failed
 * registration only loses the idle signals — the engine then
 * never pauses for sleep (the stall watchdog still guards a
 * frozen stream), never a crash.
 *
 * The monitor lives as long as the app process: the engine starts
 * it once and never stops it (there is no engine stop — the
 * collectors run for the app's lifetime, like the engine's
 * channel collectors). [stop] exists for tests and symmetry.
 */
@Singleton
class AndroidDeviceIdleMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceIdleMonitor {

    private val appContext = context.applicationContext

    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private val idleFlow = MutableSharedFlow<Boolean>(replay = 1)
    private val screenOnFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val idleChanges: Flow<Boolean> = idleFlow.asSharedFlow()
    override val screenOn: Flow<Unit> = screenOnFlow.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                    idleFlow.tryEmit(isDeviceIdleMode())

                Intent.ACTION_SCREEN_ON -> screenOnFlow.tryEmit(Unit)
            }
        }
    }

    @Volatile
    private var observing = false

    override fun isDeviceIdleMode(): Boolean = powerManager?.isDeviceIdleMode ?: false

    override fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false

    override fun start() {
        if (observing) return
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            observing = true
            idleFlow.tryEmit(isDeviceIdleMode())
        }.onFailure {
            Timber.w(it, "DeviceIdleMonitor: register failed — no idle signals")
        }
    }

    override fun stop() {
        if (!observing) return
        observing = false
        runCatching { appContext.unregisterReceiver(receiver) }
            .onFailure {
                Timber.w(it, "DeviceIdleMonitor: unregister failed")
            }
    }
}
