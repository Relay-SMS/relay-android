package com.filestech.sms.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learns the phone's own tunnel IPv4 address — the address a
 * linked device dials to reach this phone through the user's VPN
 * tunnel (SYNC_PROTOCOL.md, "VPN / tunnel"). Plain
 * `ConnectivityManager` — no third-party library, no new
 * permission class (`ACCESS_NETWORK_STATE` is normal-level,
 * granted at install).
 *
 * The rule is the active network only: the tunnel address is the
 * IPv4 address of the VPN network that IS the phone's active
 * (default) network. That is exactly when the tunnel breaks the
 * LAN dial — the phone's replies route via the tunnel with the
 * tunnel address as their source, so a desktop that dialed the
 * LAN address never completes the TCP handshake. If a VPN network
 * exists but is NOT the default route, the LAN dial works fine
 * and no tunnel address is reported (dialing it could even break:
 * replies would route via the non-tunnel default gateway with the
 * wrong source address).
 *
 * Absent (null) means "no tunnel path right now" — tunnel down,
 * tunnel not the default route, or IPv6-only tunnel — and the
 * feature degrades to today's LAN-only behavior. The value is
 * never an empty string.
 *
 * Fail-soft by construction: [currentTunnelIpv4] never throws —
 * a `ConnectivityManager` quirk degrades to the interface
 * fallback, and a failure there degrades to null. A tunnel read
 * must never take the server start down with it.
 */
@Singleton
class VpnAddressProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager: ConnectivityManager? = context.getSystemService<ConnectivityManager>()

    /**
     * The phone's tunnel IPv4 address, or null when no tunnel is
     * the active network. Cheap (one network lookup + one address
     * list) — safe to call on every watchdog tick.
     */
    fun currentTunnelIpv4(): String? {
        val manager = connectivityManager ?: return interfaceFallback()
        return try {
            val active = manager.activeNetwork ?: return interfaceFallback()
            val capabilities = manager.getNetworkCapabilities(active) ?: return interfaceFallback()
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // The default route is not a tunnel: the LAN dial
                // works, and no tunnel address is advertised.
                // Deliberately no interface fallback here — a VPN
                // that is not the default route must not be used.
                null
            } else {
                val addresses = manager.getLinkProperties(active)?.linkAddresses.orEmpty()
                    .map { it.address }
                selectTunnelIpv4(addresses) ?: interfaceFallback()
            }
        } catch (t: Throwable) {
            Timber.w(t, "VpnAddressProvider: network read failed, falling back to interfaces")
            interfaceFallback()
        }
    }

    /**
     * Defensive fallback: the first up, non-loopback interface
     * whose name starts with `tun` or `wg`, with a usable IPv4
     * address. A name heuristic, not a guarantee — it exists so a
     * `ConnectivityManager` quirk on some device does not silently
     * disable the feature. The `ConnectivityManager` path is
     * authoritative whenever it returns a value. Needs no
     * permission.
     */
    private fun interfaceFallback(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
            ?.let { Collections.list(it) }.orEmpty()
        interfaces
            .filter { it.isUp && !it.isLoopback && (it.name.startsWith("tun") || it.name.startsWith("wg")) }
            .flatMap { Collections.list(it.inetAddresses) }
            .let { selectTunnelIpv4(it) }
    }.getOrNull()
}

/**
 * Picks the tunnel address from a list of addresses: the first
 * non-loopback, non-link-local IPv4 address. Tunnel addresses are
 * RFC1918; link-local 169.254/16 is excluded defensively, as is
 * the unspecified address (0.0.0.0). IPv6 is out of v1 — a
 * list with IPv6 only yields null, same as tunnel-down.
 *
 * Pure — no Android classes — so it is unit-testable on the JVM
 * ([VpnAddressProviderTest]); the `ConnectivityManager` calls
 * themselves are device-verified.
 */
internal fun selectTunnelIpv4(addresses: List<InetAddress>): String? =
    addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
    }?.hostAddress
