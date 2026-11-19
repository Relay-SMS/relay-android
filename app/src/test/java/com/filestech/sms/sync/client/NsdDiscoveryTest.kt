package com.filestech.sms.sync.client

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Client mode — the address preference for a discovered hub
 * ([preferAddress]): routable IPv4 first, link-local 169.254/16
 * last, else the mDNS hostname. Loopback and multicast addresses
 * are never usable for a LAN peer and are skipped.
 */
class NsdDiscoveryTest {

    @Test fun `a routable address wins over a link-local one`() {
        val addresses = listOf(
            InetAddress.getByName("169.254.10.20"),
            InetAddress.getByName("192.168.1.2"),
        )
        assertThat(preferAddress(addresses, "phone.local.")).isEqualTo("192.168.1.2")
    }

    @Test fun `a link-local address is used when it is the only one`() {
        val addresses = listOf(InetAddress.getByName("169.254.10.20"))
        assertThat(preferAddress(addresses, "phone.local.")).isEqualTo("169.254.10.20")
    }

    @Test fun `the hostname is the fallback with its trailing dot trimmed`() {
        assertThat(preferAddress(emptyList(), "phone.local.")).isEqualTo("phone.local")
    }

    @Test fun `nothing usable returns null`() {
        assertThat(preferAddress(emptyList(), null)).isNull()
        assertThat(preferAddress(emptyList(), "   ")).isNull()
        assertThat(preferAddress(emptyList(), ".")).isNull()
    }

    @Test fun `loopback and multicast addresses are skipped`() {
        val addresses = listOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("224.0.0.1"),
        )
        assertThat(preferAddress(addresses, "phone.local.")).isEqualTo("phone.local")
    }

    @Test fun `ipv6 addresses are skipped`() {
        val addresses = listOf(InetAddress.getByName("fe80::1"))
        assertThat(preferAddress(addresses, "phone.local.")).isEqualTo("phone.local")
    }

    @Test fun `the tip record yields the tunnel address`() {
        val attributes = mapOf(
            "hub" to "hub-1".toByteArray(Charsets.UTF_8),
            "tip" to "10.6.0.2".toByteArray(Charsets.UTF_8),
        )
        assertThat(tunnelIpFromAttributes(attributes)).isEqualTo("10.6.0.2")
    }

    @Test fun `no tip record means no tunnel address`() {
        val attributes = mapOf("hub" to "hub-1".toByteArray(Charsets.UTF_8))
        assertThat(tunnelIpFromAttributes(attributes)).isNull()
    }

    @Test fun `a blank tip record means no tunnel address`() {
        val attributes = mapOf("tip" to "   ".toByteArray(Charsets.UTF_8))
        assertThat(tunnelIpFromAttributes(attributes)).isNull()
    }

    @Test fun `a tip record with whitespace is trimmed`() {
        val attributes = mapOf("tip" to " 10.6.0.2 ".toByteArray(Charsets.UTF_8))
        assertThat(tunnelIpFromAttributes(attributes)).isEqualTo("10.6.0.2")
    }
}
