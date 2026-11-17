package com.filestech.sms.sync.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.filestech.sms.sync.MulticastLockGuard
import com.filestech.sms.sync.SyncProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — one hub phone found on the LAN: its stable id (from
 * the mDNS TXT record), the address to connect to, and the service
 * name for display. [hubId] is empty when the TXT record carried no
 * `hub` entry — re-discovery never matches an empty id.
 */
data class DiscoveredHub(
    val hubId: String,
    val host: String,
    val port: Int,
    val serviceName: String,
)

/**
 * Client mode — picks the address to connect to, desktop order:
 * routable IPv4 first, link-local 169.254/16 last, else the mDNS
 * hostname (with its trailing dot trimmed). Loopback and multicast
 * addresses are never usable for a LAN peer and are skipped. Null
 * when nothing usable was found — the service is skipped, not
 * retried with a bad address.
 *
 * Pure function (no Android APIs beyond [InetAddress]): unit-tested
 * on the JVM.
 */
internal fun preferAddress(addresses: List<InetAddress>, hostname: String?): String? {
    val ipv4 = addresses.filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isMulticastAddress }
    val routable = ipv4.firstOrNull { !it.isLinkLocalAddress }?.hostAddress
    if (routable != null) return routable
    val linkLocal = ipv4.firstOrNull()?.hostAddress
    if (linkLocal != null) return linkLocal
    return hostname?.trimEnd('.')?.takeIf { it.isNotBlank() }
}

/**
 * Client mode — mDNS discovery of hub phones, wrapping the platform
 * `android.net.nsd.NsdManager` (`discoverServices` +
 * `resolveService`). No third-party library, no cloud.
 *
 * Match on service type + the `hub` TXT record, **never** on the
 * instance name: Android renames on clash (`Relay (2)`), so the name
 * is display-only. Scans are bounded (5 s, like the desktop): the
 * caller collects what resolved in the window, and discovery is
 * always stopped in `finally`.
 *
 * A Wi-Fi multicast lock is held during active scans only — the
 * client only needs multicast while *looking* (the hub holds its
 * lock continuously because it must be *found* at any time; the TCP
 * connection needs no lock). The lock is this scan's own instance
 * ([MulticastLockGuard.from]), never the hub's continuous guard.
 */
@Singleton
class NsdDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * One bounded scan: listens for [timeoutMs] (default 5 s) and
     * returns every hub that resolved in the window. Never throws —
     * a failed scan (no NsdManager, discovery refused) reads as an
     * empty result, and the caller retries on its own schedule.
     */
    suspend fun scan(timeoutMs: Long = SCAN_TIMEOUT_MS): List<DiscoveredHub> {
        val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = ConcurrentHashMap<String, DiscoveredHub>()
        val active = AtomicBoolean(true)
        val lock = MulticastLockGuard.from(context)
        val listener = ScanListener(manager, found, active)
        lock.acquire()
        try {
            val started = runCatching {
                manager.discoverServices(
                    SyncProtocol.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }.isSuccess
            if (!started) {
                Timber.w("NsdDiscovery: discoverServices refused to start")
                return emptyList()
            }
            delay(timeoutMs)
        } finally {
            active.set(false)
            runCatching { manager.stopServiceDiscovery(listener) }
                .onFailure { t -> Timber.w(t, "NsdDiscovery: stopServiceDiscovery failed") }
            lock.release()
        }
        return found.values.sortedBy { it.serviceName }
    }

    /**
     * Resolves one found service and records it in [found] when it
     * carries a usable address. Late resolutions (after the scan
     * stopped) are dropped via [active].
     */
    private class ScanListener(
        private val manager: NsdManager,
        private val found: ConcurrentHashMap<String, DiscoveredHub>,
        private val active: AtomicBoolean,
    ) : NsdManager.DiscoveryListener {

        override fun onDiscoveryStarted(serviceType: String) {
            Timber.d("NsdDiscovery: discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (!active.get()) return
            if (service.serviceType.trimEnd('.') != SyncProtocol.SERVICE_TYPE.trimEnd('.')) return
            runCatching { manager.resolveService(service, ResolveListener(found, active)) }
                .onFailure { t -> Timber.w(t, "NsdDiscovery: resolve failed to start") }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            found.remove(service.serviceName)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Timber.d("NsdDiscovery: discovery stopped")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.w("NsdDiscovery: start failed (code=%d)", errorCode)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.w("NsdDiscovery: stop failed (code=%d)", errorCode)
        }
    }

    private class ResolveListener(
        private val found: ConcurrentHashMap<String, DiscoveredHub>,
        private val active: AtomicBoolean,
    ) : NsdManager.ResolveListener {

        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
            Timber.w("NsdDiscovery: resolve failed for %s (code=%d)", service.serviceName, errorCode)
        }

        override fun onServiceResolved(service: NsdServiceInfo) {
            if (!active.get()) return
            val hubId = service.attributes["hub"]
                ?.let { String(it, Charsets.UTF_8) }
                .orEmpty()
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.hostAddresses
            } else {
                listOfNotNull(service.host)
            }
            // Client mode — NsdServiceInfo exposes no mDNS hostname
            // (only the instance name, which is not a hostname), so
            // the hostname fallback of preferAddress is unavailable
            // here: addresses only. A hub with no resolved address
            // cannot be connected to anyway.
            val host = preferAddress(addresses, null) ?: return
            found[service.serviceName] = DiscoveredHub(
                hubId = hubId,
                host = host,
                port = service.port,
                serviceName = service.serviceName,
            )
        }
    }

    companion object {
        /** How long one scan listens — the desktop's 5 s. */
        const val SCAN_TIMEOUT_MS = 5_000L
    }
}
