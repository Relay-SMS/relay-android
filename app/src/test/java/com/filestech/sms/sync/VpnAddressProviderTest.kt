package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * The tunnel-address selection logic ([selectTunnelIpv4]): pure,
 * no Android classes, so it runs on the JVM. The
 * `ConnectivityManager` calls around it are device-verified.
 */
class VpnAddressProviderTest {

    @Test fun `picks the first usable IPv4 address`() {
        val picked = selectTunnelIpv4(listOf(addr("10.6.0.2"), addr("10.6.0.3")))
        assertThat(picked).isEqualTo("10.6.0.2")
    }

    @Test fun `skips loopback and link-local addresses`() {
        val picked = selectTunnelIpv4(
            listOf(addr("127.0.0.1"), addr("169.254.1.2"), addr("10.6.0.2")),
        )
        assertThat(picked).isEqualTo("10.6.0.2")
    }

    @Test fun `skips the unspecified address`() {
        val picked = selectTunnelIpv4(listOf(addr("0.0.0.0"), addr("10.6.0.2")))
        assertThat(picked).isEqualTo("10.6.0.2")
    }

    @Test fun `skips IPv6 addresses`() {
        val picked = selectTunnelIpv4(listOf(addr("fd00::1"), addr("10.6.0.2")))
        assertThat(picked).isEqualTo("10.6.0.2")
    }

    @Test fun `returns null when no usable IPv4 exists`() {
        val picked = selectTunnelIpv4(
            listOf(addr("127.0.0.1"), addr("169.254.1.2"), addr("fd00::1")),
        )
        assertThat(picked).isNull()
    }

    @Test fun `returns null for an empty list`() {
        val picked = selectTunnelIpv4(emptyList())
        assertThat(picked).isNull()
    }

    private fun addr(text: String): InetAddress = InetAddress.getByName(text)
}
