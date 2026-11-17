package com.filestech.sms.sync.client

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — the auto-link loop's pure helpers (the desktop
 * `autoLink.ts` port): failure classification, cycle backoff, the
 * error-line stack, and the rotating phone order. The loop itself
 * needs mDNS + a live transport and is device-verified.
 */
class AutoLinkerTest {

    @Test fun `unknown_device in any spelling waits`() {
        assertThat(classifyConnectFailure("unknown_device")).isEqualTo(AutoLinkFailureKind.WAIT)
        assertThat(classifyConnectFailure("Unknown Device")).isEqualTo(AutoLinkFailureKind.WAIT)
        assertThat(classifyConnectFailure("unknown-device")).isEqualTo(AutoLinkFailureKind.WAIT)
        assertThat(classifyConnectFailure("unknowndevice")).isEqualTo(AutoLinkFailureKind.WAIT)
        assertThat(
            classifyConnectFailure(
                "unknown_device: this device isn't paired on the hub phone yet",
            ),
        ).isEqualTo(AutoLinkFailureKind.WAIT)
    }

    @Test fun `other failures show`() {
        assertThat(classifyConnectFailure("bad_signature")).isEqualTo(AutoLinkFailureKind.SHOW)
        assertThat(classifyConnectFailure("protocol: expected a challenge")).isEqualTo(
            AutoLinkFailureKind.SHOW,
        )
        assertThat(classifyConnectFailure("cannot reach the hub: timeout")).isEqualTo(
            AutoLinkFailureKind.SHOW,
        )
        assertThat(classifyConnectFailure("")).isEqualTo(AutoLinkFailureKind.SHOW)
        assertThat(classifyConnectFailure(null)).isEqualTo(AutoLinkFailureKind.SHOW)
    }

    @Test fun `cycle delay backs off 3 5 10 20 30 and caps`() {
        assertThat(nextCycleDelayMs(0)).isEqualTo(3_000L)
        assertThat(nextCycleDelayMs(1)).isEqualTo(3_000L)
        assertThat(nextCycleDelayMs(2)).isEqualTo(5_000L)
        assertThat(nextCycleDelayMs(3)).isEqualTo(10_000L)
        assertThat(nextCycleDelayMs(4)).isEqualTo(20_000L)
        assertThat(nextCycleDelayMs(5)).isEqualTo(30_000L)
        assertThat(nextCycleDelayMs(6)).isEqualTo(30_000L)
        assertThat(nextCycleDelayMs(100)).isEqualTo(30_000L)
    }

    @Test fun `error lines prepend newest first`() {
        val lines = addErrorLine(addErrorLine(emptyList(), "first"), "second")
        assertThat(lines).containsExactly("second", "first").inOrder()
    }

    @Test fun `error lines drop a consecutive duplicate`() {
        val lines = addErrorLine(addErrorLine(emptyList(), "same"), "same")
        assertThat(lines).containsExactly("same").inOrder()
    }

    @Test fun `error lines cap at three`() {
        var lines = emptyList<String>()
        lines = addErrorLine(lines, "one")
        lines = addErrorLine(lines, "two")
        lines = addErrorLine(lines, "three")
        lines = addErrorLine(lines, "four")
        assertThat(lines).containsExactly("four", "three", "two").inOrder()
    }

    @Test fun `phone order without a last tried keeps the scan order`() {
        val phones = listOf(phone("a"), phone("b"), phone("c"))
        assertThat(orderPhonesForCycle(phones, null)).containsExactly(
            phone("a"),
            phone("b"),
            phone("c"),
        ).inOrder()
    }

    @Test fun `phone order rotates to start after the last tried`() {
        val phones = listOf(phone("a"), phone("b"), phone("c"))
        assertThat(orderPhonesForCycle(phones, "a")).containsExactly(
            phone("b"),
            phone("c"),
            phone("a"),
        ).inOrder()
        assertThat(orderPhonesForCycle(phones, "b")).containsExactly(
            phone("c"),
            phone("a"),
            phone("b"),
        ).inOrder()
    }

    @Test fun `phone order with the last tried at the end keeps the order`() {
        val phones = listOf(phone("a"), phone("b"), phone("c"))
        assertThat(orderPhonesForCycle(phones, "c")).containsExactly(
            phone("a"),
            phone("b"),
            phone("c"),
        ).inOrder()
    }

    @Test fun `phone order with an unknown last tried keeps the order`() {
        val phones = listOf(phone("a"), phone("b"))
        assertThat(orderPhonesForCycle(phones, "gone")).containsExactly(
            phone("a"),
            phone("b"),
        ).inOrder()
    }

    @Test fun `phone order never mutates the input list`() {
        val phones = listOf(phone("a"), phone("b"), phone("c"))
        orderPhonesForCycle(phones, "a")
        assertThat(phones).containsExactly(phone("a"), phone("b"), phone("c")).inOrder()
    }

    @Test fun `initial auto-link state is idle and empty`() {
        val state = initialAutoLinkState()
        assertThat(state.phase).isEqualTo(AutoLinkPhase.IDLE)
        assertThat(state.phoneName).isNull()
        assertThat(state.waitOnly).isFalse()
        assertThat(state.noPhonesFound).isFalse()
        assertThat(state.errors).isEmpty()
        assertThat(state.waitDetail).isNull()
    }

    private fun phone(hubId: String) = DiscoveredHub(
        hubId = hubId,
        host = "192.168.1.2",
        port = 47831,
        serviceName = "Relay-$hubId",
    )
}
