package com.filestech.sms.sync

import android.content.Context
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The sync wakelock, in two halves: the holder's acquire/release
 * lifecycle (idempotent, fail-soft, never throws — the real
 * [PowerManager.WakeLock] sits behind mockk mocks, never called
 * for real on the JVM), and the hub engine's wiring (the lock is
 * held for a sync stream and released on finish, error, cancel,
 * and supersede — never for any other operation).
 */
class SyncWakelockTest {

    // -- Holder -------------------------------------------------------

    /** The mocks one holder is built from. */
    private class Mocks(
        val context: Context,
        val powerManager: PowerManager,
        val wakeLock: PowerManager.WakeLock,
    )

    /**
     * Mocks wired for a healthy holder: the context hands out the
     * PowerManager, the PowerManager hands out the WakeLock, and
     * the lock's calls succeed.
     */
    private fun mocks(): Mocks {
        val context = mockk<Context>()
        val powerManager = mockk<PowerManager>()
        val wakeLock = mockk<PowerManager.WakeLock>()
        every { context.getSystemService(PowerManager::class.java) } returns powerManager
        every { powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Relay:Sync") } returns wakeLock
        every { wakeLock.setReferenceCounted(false) } just Runs
        every { wakeLock.acquire() } just Runs
        every { wakeLock.release() } just Runs
        return Mocks(context, powerManager, wakeLock)
    }

    @Test fun `acquire acquires the underlying lock`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        verify(exactly = 1) { mocks.wakeLock.acquire() }
    }

    @Test fun `repeated acquires acquire once`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        holder.acquire()
        holder.acquire()
        verify(exactly = 1) { mocks.wakeLock.acquire() }
    }

    @Test fun `release releases the underlying lock`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        holder.release()
        verify(exactly = 1) { mocks.wakeLock.release() }
    }

    @Test fun `double release releases once`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        holder.release()
        holder.release()
        verify(exactly = 1) { mocks.wakeLock.release() }
    }

    @Test fun `overlapping acquires release only when the last holder lets go`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        holder.acquire()
        holder.release()
        verify(exactly = 0) { mocks.wakeLock.release() }
        holder.release()
        verify(exactly = 1) { mocks.wakeLock.release() }
    }

    @Test fun `release without acquire is a no-op`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.release()
        verify(exactly = 0) { mocks.wakeLock.release() }
    }

    @Test fun `acquire after release re-acquires`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        holder.release()
        holder.acquire()
        verify(exactly = 2) { mocks.wakeLock.acquire() }
        verify(exactly = 1) { mocks.wakeLock.release() }
    }

    @Test fun `the lock is created non-reference-counted`() {
        val mocks = mocks()
        PowerManagerSyncWakelock(mocks.context)
        verify(exactly = 1) { mocks.wakeLock.setReferenceCounted(false) }
    }

    @Test fun `a throwing acquire never propagates and does not wedge the holder`() {
        val mocks = mocks()
        every { mocks.wakeLock.acquire() } throws SecurityException("no permission")
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        every { mocks.wakeLock.acquire() } just Runs
        holder.acquire()
        verify(exactly = 2) { mocks.wakeLock.acquire() }
    }

    @Test fun `a throwing release never propagates and does not wedge the holder`() {
        val mocks = mocks()
        val holder = PowerManagerSyncWakelock(mocks.context)
        holder.acquire()
        every { mocks.wakeLock.release() } throws RuntimeException("under-locked")
        holder.release()
        holder.acquire()
        verify(exactly = 2) { mocks.wakeLock.acquire() }
        verify(exactly = 1) { mocks.wakeLock.release() }
    }

    @Test fun `no PowerManager is a no-op`() {
        val context = mockk<Context>()
        val wakeLock = mockk<PowerManager.WakeLock>()
        every { context.getSystemService(PowerManager::class.java) } returns null
        val holder = PowerManagerSyncWakelock(context)
        holder.acquire()
        holder.release()
        verify(exactly = 0) { wakeLock.acquire() }
    }

    @Test fun `a failing WakeLock creation is a no-op`() {
        val context = mockk<Context>()
        val powerManager = mockk<PowerManager>()
        val wakeLock = mockk<PowerManager.WakeLock>()
        every { context.getSystemService(PowerManager::class.java) } returns powerManager
        every {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Relay:Sync")
        } throws IllegalStateException("no lock")
        val holder = PowerManagerSyncWakelock(context)
        holder.acquire()
        holder.release()
        verify(exactly = 0) { wakeLock.acquire() }
    }

    // -- Hub wiring ---------------------------------------------------

    /** A wakelock that counts calls, so the engine's wiring is observable. */
    private class RecordingWakelock : SyncWakelock {
        var acquires = 0
        var releases = 0
        override fun acquire() { acquires++ }
        override fun release() { releases++ }
    }

    @Test fun `a sync stream acquires the wakelock and releases it on completion`() = runTest {
        val source = FakeSyncMessageSource()
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(1)
            assertThat(wakelock.releases).isEqualTo(1)
        }
    }

    @Test fun `an unknown sync mode still releases the wakelock`() = runTest {
        val source = FakeSyncMessageSource()
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "sideways"))
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(1)
            assertThat(wakelock.releases).isEqualTo(1)
        }
    }

    @Test fun `a failing stream releases the wakelock`() = runTest {
        val source = FakeSyncMessageSource()
        val wakelock = RecordingWakelock()
        val engine = SyncEngine(
            source,
            this,
            send = { throw IllegalStateException("socket gone") },
            wakelock = wakelock,
        )
        try {
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(1)
            assertThat(wakelock.releases).isEqualTo(1)
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    @Test fun `a cancelled stream releases the wakelock`() = runTest {
        val source = FakeSyncMessageSource()
        source.conversationsGate = CompletableDeferred()
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            runCurrent()
            assertThat(wakelock.acquires).isEqualTo(1)
            engine.stop()
            advanceUntilIdle()
            assertThat(wakelock.releases).isEqualTo(1)
        }
    }

    /**
     * A superseded stream stays balanced: the cancelled stream
     * releases its own count (it still owned the lock when its
     * `finally` ran) and the replacement re-acquires, then
     * releases on completion. Under the test scheduler the
     * cancelled stream's `finally` always runs first, so the
     * counts are 2/2 here; on a real dispatcher the replacement
     * can take over first, and the holder's reference counting
     * keeps that interleaving balanced too.
     */
    @Test fun `a superseded stream stays balanced with its replacement`() = runTest {
        val source = FakeSyncMessageSource()
        val gate = CompletableDeferred<Unit>()
        source.conversationsGate = gate
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            runCurrent()
            assertThat(wakelock.acquires).isEqualTo(1)
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            runCurrent()
            assertThat(wakelock.acquires).isEqualTo(2)
            assertThat(wakelock.releases).isEqualTo(1)
            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(2)
            assertThat(wakelock.releases).isEqualTo(2)
        }
    }

    @Test fun `an attachment download does not take the wakelock`() = runTest {
        val source = FakeSyncMessageSource()
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.onAttachmentRequest(
                SyncProtocol.AttachmentRequest(attachmentId = "1", messageId = "1"),
            )
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(0)
            assertThat(wakelock.releases).isEqualTo(0)
        }
    }

    @Test fun `a messages_request does not take the wakelock`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1))
        source.store(sampleMessage(id = 11, conversationId = 1))
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.onMessagesRequest(SyncProtocol.MessagesRequest(conversationId = "1"))
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(0)
            assertThat(wakelock.releases).isEqualTo(0)
        }
    }

    @Test fun `live push does not take the wakelock`() = runTest {
        val source = FakeSyncMessageSource()
        val wakelock = RecordingWakelock()
        runEngine(source, wakelock) { engine, _ ->
            engine.start()
            advanceUntilIdle()
            assertThat(wakelock.acquires).isEqualTo(0)
            assertThat(wakelock.releases).isEqualTo(0)
        }
    }
}
