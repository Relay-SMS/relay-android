package com.filestech.sms.sync.client

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — the reconnect loop's pure helpers: the re-discovery
 * predicate (switch target only when the *same* hub answers with a
 * *different* target — a new address, or a tunnel address that
 * appeared at the same address) and the backoff table. The loop
 * itself needs a live transport and is device-verified.
 */
class ClientConnectionManagerTest {

    @Test fun `same hub at a new host switches`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.9", 47831, "Relay")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isTrue()
    }

    @Test fun `same hub at a new port switches`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 50000, "Relay")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isTrue()
    }

    @Test fun `same hub at the same address does not switch`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 47831, "Relay")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isFalse()
    }

    @Test fun `a different hub never switches`() {
        val phone = DiscoveredHub("hub-2", "192.168.1.9", 47831, "Relay")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isFalse()
    }

    @Test fun `an empty hub id never switches`() {
        val phone = DiscoveredHub("", "192.168.1.9", 47831, "Relay")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(isRediscoveryTarget(phone, "", target)).isFalse()
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isFalse()
    }

    @Test fun `same hub at the same address with a new tip switches`() {
        // The phone's VPN came up: the fresh sighting carries a
        // tunnel address the current target lacks, and the LAN dial
        // is the one hanging.
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 47831, "Relay", "10.6.0.2")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isTrue()
    }

    @Test fun `same hub at the same address with the same tip does not switch`() {
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 47831, "Relay", "10.6.0.2")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", "10.6.0.2")
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isFalse()
    }

    @Test fun `same hub at the same address with the tip gone switches`() {
        // The phone's VPN went down: the fresh target drops the dead
        // tunnel candidate instead of dialing it first every round.
        val phone = DiscoveredHub("hub-1", "192.168.1.2", 47831, "Relay")
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", "10.6.0.2")
        assertThat(isRediscoveryTarget(phone, "hub-1", target)).isTrue()
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
