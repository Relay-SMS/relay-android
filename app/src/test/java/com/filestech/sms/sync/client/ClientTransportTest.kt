package com.filestech.sms.sync.client

import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — the transport's pure surface: the WebSocket URL
 * (IPv6 literals get bracketed, the desktop rule) and the
 * auth-failure messages, which must lead with the reason code
 * verbatim — the auto-link loop classifies on it, and the screen
 * shows it raw.
 */
class ClientTransportTest {

    @Test fun `ws url uses the default sync port`() {
        assertThat(HubTarget("192.168.1.2").wsUrl())
            .isEqualTo("wss://192.168.1.2:47831/sync")
    }

    @Test fun `ws url honors an explicit port`() {
        assertThat(HubTarget("192.168.1.2", 50000).wsUrl())
            .isEqualTo("wss://192.168.1.2:50000/sync")
    }

    @Test fun `ws url brackets an ipv6 literal`() {
        assertThat(HubTarget("fe80::1").wsUrl())
            .isEqualTo("wss://[fe80::1]:47831/sync")
    }

    @Test fun `ws url keeps an already bracketed ipv6 literal`() {
        assertThat(HubTarget("[fe80::1]").wsUrl())
            .isEqualTo("wss://[fe80::1]:47831/sync")
    }

    @Test fun `ws url keeps a hostname as is`() {
        assertThat(HubTarget("kristins-phone.local").wsUrl())
            .isEqualTo("wss://kristins-phone.local:47831/sync")
    }

    @Test fun `ws url ignores the hub id`() {
        assertThat(HubTarget("192.168.1.2", hubId = "hub-1").wsUrl())
            .isEqualTo("wss://192.168.1.2:47831/sync")
    }

    @Test fun `the hub id is part of the target`() {
        // A DHCP reassignment to a different phone is a different
        // target (and a different TLS pin), even at the same address.
        assertThat(HubTarget("192.168.1.2", 47831, "hub-1"))
            .isNotEqualTo(HubTarget("192.168.1.2", 47831, "hub-2"))
        assertThat(HubTarget("192.168.1.2", 47831, "hub-1"))
            .isEqualTo(HubTarget("192.168.1.2", 47831, "hub-1"))
    }

    @Test fun `unknown_device message leads with the reason code`() {
        assertThat(authFailedMessage(SyncProtocol.AuthFailed.UNKNOWN_DEVICE)).isEqualTo(
            "unknown_device: this device isn't paired on the hub phone yet — scan the pairing QR first",
        )
    }

    @Test fun `bad_signature message leads with the reason code`() {
        assertThat(authFailedMessage(SyncProtocol.AuthFailed.BAD_SIGNATURE)).isEqualTo(
            "bad_signature: the hub rejected this device's signature — re-pair from the hub phone",
        )
    }

    @Test fun `revoked message leads with the reason code`() {
        assertThat(authFailedMessage(SyncProtocol.AuthFailed.REVOKED)).isEqualTo(
            "revoked: this device was removed from the hub phone — re-pair to link it again",
        )
    }

    @Test fun `protocol message leads with the reason code`() {
        assertThat(authFailedMessage(SyncProtocol.AuthFailed.PROTOCOL)).isEqualTo(
            "protocol: the hub rejected the connection for a protocol error",
        )
    }

    @Test fun `unknown reason falls back to a generic refusal`() {
        assertThat(authFailedMessage("something_else")).isEqualTo(
            "something_else: the hub refused authentication",
        )
    }
}
