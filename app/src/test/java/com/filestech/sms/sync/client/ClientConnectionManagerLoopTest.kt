package com.filestech.sms.sync.client

import com.filestech.sms.sync.DeviceKind
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Client mode — the reconnect loop's network wakeup (tablet,
 * 2026-11-17): after Wi-Fi returned, messages caught up but only
 * after a long wait — the backoff had climbed to 30 s while Wi-Fi
 * was off, and nothing woke the loop when it came back.
 *
 * The loop runs in a separate TestScope on the same scheduler (the
 * [ClientSyncEngineTest] pattern — the loop never completes, so
 * `runTest` must not wait for it; NOT `backgroundScope`, whose
 * tasks `advanceUntilIdle` does not drive in this coroutines-test
 * version). Timing is asserted on the virtual clock: each attempt
 * records `testScheduler.currentTime`, so a retry that lands early
 * or late fails the test. Never `advanceUntilIdle` here: the loop
 * always has another backoff scheduled, so the scheduler is never
 * idle — every test advances bounded steps with [advanceTimeBy].
 */
class ClientConnectionManagerLoopTest {

    private class FakeNetworkAvailabilityMonitor : NetworkAvailabilityMonitor {
        private val channel = Channel<Unit>(Channel.CONFLATED)
        override val wakeups: ReceiveChannel<Unit> = channel

        var startCount = 0
            private set
        var stopCount = 0
            private set

        fun signalAvailable() {
            channel.trySend(Unit)
        }

        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }
    }

    private class FakeSession(
        private val end: CompletableDeferred<SessionEnd> = CompletableDeferred(),
    ) : ClientSession {
        override val hubId: String = HUB_ID
        override val hubName: String = HUB_NAME
        override val frames = emptyFlow<ServerFrame>()
        override suspend fun send(text: String): Boolean = true
        override suspend fun close() = Unit
        override suspend fun awaitEnd(): SessionEnd = end.await()
    }

    private class Harness(
        val manager: ClientConnectionManager,
        val network: FakeNetworkAvailabilityMonitor,
        val discovery: NsdDiscovery,
        val attemptTimes: List<Long>,
    )

    private fun TestScope.harness(
        linkedHubId: String? = null,
        outcomes: (attempt: Int) -> ConnectOutcome = {
            ConnectOutcome.Failed("connection failed")
        },
    ): Harness {
        val attemptTimes = mutableListOf<Long>()
        var attempts = 0
        val transport = mockk<ClientTransport>()
        coEvery { transport.connect(any(), any()) } answers {
            attempts++
            attemptTimes.add(testScheduler.currentTime)
            outcomes(attempts)
        }
        val linkStore = mockk<ClientLinkStore>(relaxed = true)
        // No link by default. A linked hub id seeds re-discovery:
        // without a known hub id `rediscover()` returns before it
        // ever scans (there is nothing to match the scan against).
        coEvery { linkStore.snapshot() } returns linkedHubId?.let {
            ClientLink(
                deviceId = "device-1",
                displayName = "Tablet",
                hubId = it,
                hubName = HUB_NAME,
            )
        }
        val discovery = mockk<NsdDiscovery>()
        coEvery { discovery.scan(any()) } returns emptyList()
        val network = FakeNetworkAvailabilityMonitor()
        // The loop never completes (it reconnects forever), so it
        // runs in a separate TestScope on the same scheduler:
        // advanceTimeBy still drives it, but runTest does not wait
        // for it at teardown. Cancelled when the test's Job
        // completes.
        val loopScope = TestScope(testScheduler)
        coroutineContext[Job]?.invokeOnCompletion { loopScope.cancel() }
        val manager = ClientConnectionManager(
            transport,
            linkStore,
            discovery,
            network,
            loopScope,
        )
        return Harness(manager, network, discovery, attemptTimes)
    }

    @Test fun `network return during backoff retries immediately`() = runTest {
        val h = harness()
        h.manager.connect(TARGET, DeviceKind.TABLET)
        // Attempt 1 fails at t=0 (backoff 1 s), attempt 2 at t=1000
        // (backoff 2 s — the re-discovery scan at 2 failures finds
        // nothing), attempt 3 at t=3000 (backoff 5 s).
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(h.attemptTimes).containsExactly(0L, 1_000L, 3_000L).inOrder()
        // Mid-way through the 5 s sleep, the network returns: the
        // retry lands at once, not at t=8000.
        advanceTimeBy(2_000)
        runCurrent()
        h.network.signalAvailable()
        runCurrent()
        assertThat(h.attemptTimes).containsExactly(0L, 1_000L, 3_000L, 5_000L).inOrder()
    }

    @Test fun `backoff resets to 1s after a wakeup`() = runTest {
        val h = harness()
        h.manager.connect(TARGET, DeviceKind.TABLET)
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        h.network.signalAvailable()
        runCurrent()
        assertThat(h.attemptTimes).containsExactly(0L, 1_000L, 3_000L, 5_000L).inOrder()
        // The wakeup retry failed too — but the counters reset, so
        // the next backoff is 1 s again, not the climbed 10 s.
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(h.attemptTimes).containsExactly(0L, 1_000L, 3_000L, 5_000L, 6_000L).inOrder()
    }

    @Test fun `backoff table unchanged without a signal`() = runTest {
        val h = harness()
        h.manager.connect(TARGET, DeviceKind.TABLET)
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        // 1, 2, 5, 10, 30, 30 (capped) — the wakeup changes nothing
        // for a hub that is genuinely unreachable.
        assertThat(h.attemptTimes).containsExactly(
            0L,
            1_000L,
            3_000L,
            8_000L,
            18_000L,
            48_000L,
            78_000L,
        ).inOrder()
    }

    @Test fun `network signal is a no-op while connected`() = runTest {
        val session = FakeSession()
        val h = harness { ConnectOutcome.Connected(session, LAN_CANDIDATE) }
        val state = h.manager.connect(TARGET, DeviceKind.TABLET)
        assertThat(state).isEqualTo(ClientConnectionState.Connected(HUB_ID, HUB_NAME))
        assertThat(h.attemptTimes).containsExactly(0L).inOrder()
        h.network.signalAvailable()
        advanceTimeBy(60_000)
        runCurrent()
        // No spurious reconnect: still one attempt, still connected.
        assertThat(h.attemptTimes).containsExactly(0L).inOrder()
        assertThat(h.manager.state.value).isEqualTo(
            ClientConnectionState.Connected(HUB_ID, HUB_NAME),
        )
        h.manager.disconnect()
        runCurrent()
    }

    @Test fun `monitor stops when the loop stops`() = runTest {
        val h = harness()
        h.manager.connect(TARGET, DeviceKind.TABLET)
        assertThat(h.network.startCount).isEqualTo(1)
        val attemptsBeforeStop = h.attemptTimes.size
        h.manager.disconnect()
        runCurrent()
        assertThat(h.network.stopCount).isEqualTo(1)
        // The loop is dead: no more attempts, even after a signal.
        h.network.signalAvailable()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(h.attemptTimes.size).isEqualTo(attemptsBeforeStop)
    }

    @Test fun `a stale signal before any failure is harmless`() = runTest {
        val h = harness()
        // The registration echo for a network that was already up:
        // buffered before the loop starts, drained at the first
        // cycle — the first backoff is still the full 1 s.
        h.network.signalAvailable()
        h.manager.connect(TARGET, DeviceKind.TABLET)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(h.attemptTimes).containsExactly(0L, 1_000L).inOrder()
    }

    @Test fun `wakeup resets the re-discovery schedule`() = runTest {
        // Linked: re-discovery only scans when a hub id is known.
        val h = harness(linkedHubId = HUB_ID)
        h.manager.connect(TARGET, DeviceKind.TABLET)
        advanceTimeBy(1_000)
        runCurrent()
        // Attempt 2 fails: 2 consecutive failures, first scan.
        coVerify(exactly = 1) { h.discovery.scan(any()) }
        advanceTimeBy(2_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        h.network.signalAvailable()
        runCurrent()
        // Wakeup retry (attempt 4) fails, then one more: 2 failures
        // since the wakeup, so the second scan runs — without the
        // reset it would wait for 5.
        advanceTimeBy(1_000)
        runCurrent()
        coVerify(exactly = 2) { h.discovery.scan(any()) }
    }

    private companion object {
        val TARGET = HubTarget.fromDiscovery("192.168.1.2", 47831, "", null)
        val LAN_CANDIDATE = HubCandidate("192.168.1.2", AddressKind.LAN)
        const val HUB_ID = "hub-1"
        const val HUB_NAME = "Relay"
    }
}
