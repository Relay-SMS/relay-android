package com.filestech.sms.sync.client

import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — the transport's pure surface: the WebSocket URL
 * (IPv6 literals get bracketed, the desktop rule), the dial
 * candidate order (tunnel-first on fresh discovery, last-working
 * first from the store), the terminal-refusal classifier, and the
 * auth-failure messages, which must lead with the reason code
 * verbatim — the auto-link loop classifies on it, and the screen
 * shows it raw.
 */
class ClientTransportTest {

    @Test fun `ws url uses the default sync port`() {
        assertThat(HubCandidate("192.168.1.2", AddressKind.LAN).wsUrl(SyncProtocol.PORT))
            .isEqualTo("wss://192.168.1.2:47831/sync")
    }

    @Test fun `ws url honors an explicit port`() {
        assertThat(HubCandidate("192.168.1.2", AddressKind.LAN).wsUrl(50000))
            .isEqualTo("wss://192.168.1.2:50000/sync")
    }

    @Test fun `ws url brackets an ipv6 literal`() {
        assertThat(HubCandidate("fe80::1", AddressKind.LAN).wsUrl(SyncProtocol.PORT))
            .isEqualTo("wss://[fe80::1]:47831/sync")
    }

    @Test fun `ws url keeps an already bracketed ipv6 literal`() {
        assertThat(HubCandidate("[fe80::1]", AddressKind.LAN).wsUrl(SyncProtocol.PORT))
            .isEqualTo("wss://[fe80::1]:47831/sync")
    }

    @Test fun `ws url keeps a hostname as is`() {
        assertThat(HubCandidate("kristins-phone.local", AddressKind.LAN).wsUrl(SyncProtocol.PORT))
            .isEqualTo("wss://kristins-phone.local:47831/sync")
    }

    @Test fun `the hub id is part of the target`() {
        // A DHCP reassignment to a different phone is a different
        // target (and a different TLS pin), even at the same address.
        assertThat(HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null))
            .isNotEqualTo(HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-2", null))
        assertThat(HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null))
            .isEqualTo(HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null))
    }

    // -- Dial order: fresh discovery --------------------------------

    @Test fun `fresh discovery dials the tunnel first when a tip is present`() {
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", "10.6.0.2")
        assertThat(target.candidates).containsExactly(
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
            HubCandidate("192.168.1.2", AddressKind.LAN),
        ).inOrder()
    }

    @Test fun `fresh discovery dials lan only when no tip is present`() {
        val target = HubTarget.fromDiscovery("192.168.1.2", 47831, "hub-1", null)
        assertThat(target.candidates).containsExactly(
            HubCandidate("192.168.1.2", AddressKind.LAN),
        ).inOrder()
    }

    @Test fun `fresh discovery dials an address that is both tunnel and lan once as tunnel`() {
        val target = HubTarget.fromDiscovery("10.6.0.2", 47831, "hub-1", "10.6.0.2")
        assertThat(target.candidates).containsExactly(
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
        ).inOrder()
    }

    @Test fun `fresh discovery with no lan host dials the tunnel alone`() {
        val target = HubTarget.fromDiscovery("", 47831, "hub-1", "10.6.0.2")
        assertThat(target.candidates).containsExactly(
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
        ).inOrder()
    }

    // -- Dial order: the last-known store ---------------------------

    @Test fun `stored dial puts the last working tunnel first`() {
        val target = HubTarget.fromStored(
            "192.168.1.2",
            47831,
            "hub-1",
            "10.6.0.2",
            AddressKind.TUNNEL,
        )
        assertThat(target.candidates).containsExactly(
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
            HubCandidate("192.168.1.2", AddressKind.LAN),
        ).inOrder()
    }

    @Test fun `stored dial puts the last working lan first`() {
        val target = HubTarget.fromStored(
            "192.168.1.2",
            47831,
            "hub-1",
            "10.6.0.2",
            AddressKind.LAN,
        )
        assertThat(target.candidates).containsExactly(
            HubCandidate("192.168.1.2", AddressKind.LAN),
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
        ).inOrder()
    }

    @Test fun `stored dial with no history puts the tunnel first`() {
        val target = HubTarget.fromStored("192.168.1.2", 47831, "hub-1", "10.6.0.2", null)
        assertThat(target.candidates).containsExactly(
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
            HubCandidate("192.168.1.2", AddressKind.LAN),
        ).inOrder()
    }

    @Test fun `stored dial with no tunnel dials lan only`() {
        val target = HubTarget.fromStored("192.168.1.2", 47831, "hub-1", null, null)
        assertThat(target.candidates).containsExactly(
            HubCandidate("192.168.1.2", AddressKind.LAN),
        ).inOrder()
    }

    @Test fun `stored dial with no lan host dials the tunnel alone`() {
        val target = HubTarget.fromStored(null, 47831, "hub-1", "10.6.0.2", null)
        assertThat(target.candidates).containsExactly(
            HubCandidate("10.6.0.2", AddressKind.TUNNEL),
        ).inOrder()
    }

    @Test fun `stored dial with neither address has no candidates`() {
        val target = HubTarget.fromStored(null, 47831, "hub-1", null, null)
        assertThat(target.candidates).isEmpty()
    }

    @Test fun `the stored last-working value round-trips`() {
        assertThat(AddressKind.fromStored("tunnel")).isEqualTo(AddressKind.TUNNEL)
        assertThat(AddressKind.fromStored("lan")).isEqualTo(AddressKind.LAN)
        assertThat(AddressKind.fromStored("LAN")).isEqualTo(AddressKind.LAN)
        assertThat(AddressKind.fromStored(null)).isNull()
        assertThat(AddressKind.fromStored("other")).isNull()
    }

    // -- Terminal refusals + the all-failed error -------------------

    @Test fun `a hub refusal stops the candidate walk`() {
        assertThat(isTerminalRefusal("unknown_device: not paired")).isTrue()
        assertThat(isTerminalRefusal("bad_signature: rejected")).isTrue()
        assertThat(isTerminalRefusal("revoked: removed")).isTrue()
        assertThat(isTerminalRefusal("protocol: rejected")).isTrue()
        assertThat(isTerminalRefusal("hub error during auth: boom")).isTrue()
        assertThat(isTerminalRefusal("hub TLS certificate changed: fingerprint")).isTrue()
    }

    @Test fun `a path failure does not stop the candidate walk`() {
        assertThat(isTerminalRefusal("cannot reach the hub at 1.2.3.4:47831: timed out")).isFalse()
        assertThat(isTerminalRefusal("auth timed out: the hub did not answer within 10s")).isFalse()
        assertThat(isTerminalRefusal("cannot reach the hub: Connection refused")).isFalse()
    }

    @Test fun `attempt outcomes are one word`() {
        assertThat(attemptOutcome("cannot reach the hub at 1.2.3.4:47831: connection timed out after 8s"))
            .isEqualTo("timed out")
        assertThat(attemptOutcome("cannot reach the hub: Connection refused"))
            .isEqualTo("connection refused")
        assertThat(attemptOutcome("cannot reach the hub: No address associated with hostname"))
            .isEqualTo("No address associated with hostname")
        assertThat(attemptOutcome("auth timed out: the hub did not answer within 10s"))
            .isEqualTo("timed out")
    }

    @Test fun `the all-failed error names each attempt and hints at the router`() {
        val error = allCandidatesFailedError(
            listOf(
                HubCandidate("10.6.0.2", AddressKind.TUNNEL) to
                    "cannot reach the hub at 10.6.0.2:47831: connection timed out after 8s",
                HubCandidate("192.168.1.2", AddressKind.LAN) to
                    "cannot reach the hub: Connection refused",
            ),
        )
        assertThat(error).isEqualTo(
            "couldn't reach the phone at 10.6.0.2 (tunnel, timed out) or " +
                "192.168.1.2 (LAN, connection refused). If the phone's VPN is on, check the " +
                "router forwards between the LAN and the tunnel (from a computer on the same " +
                "network: ping 10.6.0.2) — or exclude Relay from the tunnel in the VPN app " +
                "as a workaround",
        )
    }

    @Test fun `the all-failed error without a tunnel attempt carries no router hint`() {
        val error = allCandidatesFailedError(
            listOf(
                HubCandidate("192.168.1.2", AddressKind.LAN) to
                    "cannot reach the hub: Connection refused",
            ),
        )
        assertThat(error).isEqualTo(
            "couldn't reach the phone at 192.168.1.2 (LAN, connection refused)",
        )
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
