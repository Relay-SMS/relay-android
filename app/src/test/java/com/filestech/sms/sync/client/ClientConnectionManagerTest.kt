package com.filestech.sms.sync.client

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — the reconnect loop's pure helpers: the re-discovery
 * predicate (switch target only when the *same* hub answers at a
 * *different* address) and the backoff table. The loop itself needs
 * a live transport and is device-verified.
 */
class ClientConnectionManagerTest {

    @Test fun `same hub at a new host switches`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.9", 47831, "Relay")
        val target = HubTarget("192.168.1.2", 47831)
        assertThat(isSameHubAtNewAddress(phone, "hub-1", target)).isTrue()
    }

    @Test fun `same hub at a new port switches`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 50000, "Relay")
        val target = HubTarget("192.168.1.2", 47831)
        assertThat(isSameHubAtNewAddress(phone, "hub-1", target)).isTrue()
    }

    @Test fun `same hub at the same address does not switch`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 47831, "Relay")
        val target = HubTarget("192.168.1.2", 47831)
        assertThat(isSameHubAtNewAddress(phone, "hub-1", target)).isFalse()
    }

    @Test fun `a different hub never switches`() {
        val phone = DiscoveredHub("hub-2", "192.168.1.9", 47831, "Relay")
        val target = HubTarget("192.168.1.2", 47831)
        assertThat(isSameHubAtNewAddress(phone, "hub-1", target)).isFalse()
    }

    @Test fun `an empty hub id never switches`() {
        val phone = DiscoveredHub("", "192.168.1.9", 47831, "Relay")
        val target = HubTarget("192.168.1.2", 47831)
        assertThat(isSameHubAtNewAddress(phone, "", target)).isFalse()
        assertThat(isSameHubAtNewAddress(phone, "hub-1", target)).isFalse()
    }

    @Test fun `reconnect delay backs off 1 2 5 10 30 and caps`() {
        assertThat(reconnectDelayMs(0)).isEqualTo(1_000L)
        assertThat(reconnectDelayMs(1)).isEqualTo(2_000L)
        assertThat(reconnectDelayMs(2)).isEqualTo(5_000L)
        assertThat(reconnectDelayMs(3)).isEqualTo(10_000L)
        assertThat(reconnectDelayMs(4)).isEqualTo(30_000L)
        assertThat(reconnectDelayMs(5)).isEqualTo(30_000L)
        assertThat(reconnectDelayMs(100)).isEqualTo(30_000L)
    }

    @Test fun `negative reconnect index clamps to the first delay`() {
        assertThat(reconnectDelayMs(-1)).isEqualTo(1_000L)
    }
}
