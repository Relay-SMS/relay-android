package com.filestech.sms.sync.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — a network-availability wakeup for the reconnect loop.
 *
 * The loop backs off 1, 2, 5, 10, 30 s (capped) between attempts; while
 * the Wi-Fi is off the failures pile up and the backoff climbs to 30 s,
 * so without a wakeup the retry after Wi-Fi returns lands up to 30 s
 * late. This monitor reports the network's return so the loop can retry
 * at once instead of waiting out the sleep — no polling, so a hub that
 * is genuinely unreachable still costs only the capped backoff.
 *
 * The narrow interface (like [ClientSyncChannel] for the engine) keeps
 * the manager testable on plain JVM: tests use a fake that signals
 * availability on demand, never the Android framework.
 */
interface NetworkAvailabilityMonitor {
    /**
     * One wakeup per network-availability event while observing.
     * Conflated: at most one pending wakeup is kept — the loop only
     * cares that a network returned, not how many times. Never closed:
     * [stop] only unregisters the callback, so a receiver never sees
     * a closed channel.
     */
    val wakeups: ReceiveChannel<Unit>

    /** Starts listening for network availability. Idempotent. */
    fun start()

    /** Stops listening. Idempotent. */
    fun stop()
}

/**
 * The Android implementation: `ConnectivityManager
 * .registerDefaultNetworkCallback`. `onAvailable` fires when a default
 * network appears — including the one already up at registration, so
 * the first emission can be a stale echo; the loop drains it at the
 * start of each cycle (see [ClientConnectionManager]) and only a
 * return during a cycle cuts a backoff sleep short.
 *
 * No new permission: `registerDefaultNetworkCallback` needs
 * ACCESS_NETWORK_STATE, which the APK already holds via the
 * androidx.work manifest merge (see AndroidManifest.xml). A failed
 * registration (or a missing ConnectivityManager) only loses the
 * wakeups — the backoff loop still reconnects on its own schedule,
 * never a crash.
 */
@Singleton
class AndroidNetworkAvailabilityMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkAvailabilityMonitor {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val wakeupChannel = Channel<Unit>(Channel.CONFLATED)

    override val wakeups: ReceiveChannel<Unit> = wakeupChannel

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wakeupChannel.trySend(Unit)
        }
    }

    @Volatile
    private var observing = false

    override fun start() {
        if (observing) return
        val manager = connectivityManager ?: return
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { observing = true }
            .onFailure {
                Timber.w(it, "NetworkAvailabilityMonitor: register failed — no wakeups")
            }
    }

    override fun stop() {
        if (!observing) return
        observing = false
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            .onFailure {
                Timber.w(it, "NetworkAvailabilityMonitor: unregister failed")
            }
    }
}
