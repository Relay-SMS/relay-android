package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Local-only error log — the session tracker: a new session
 * returns the previous marker and writes its own; screen and
 * sync updates record their value and skip unchanged ones (the
 * sync flow emits on every batch); foreground/background flip
 * the flag and refresh last-alive; the heartbeat refreshes
 * last-alive only. The lifecycle observer and the scheduler are
 * not exercised here — they only call these same transitions —
 * so this runs on plain JVM with a fake clock.
 */
class SessionMarkerTrackerTest {

    @TempDir
    lateinit var tempDir: File

    private var now = 10_000L
    private val clock = { now }

    private fun store(): SessionMarkerStore = SessionMarkerStore(File(tempDir, "crash-reports"))

    private fun tracker(): SessionMarkerTracker = SessionMarkerTracker(store(), clock, pid = 4321)

    @Test
    fun `beginSession writes a marker and returns null with no previous session`() {
        val tracker = tracker()
        assertThat(tracker.beginSession()).isNull()
        val written = store().read()
        assertThat(written).isNotNull()
        assertThat(written!!.sessionStartMillis).isEqualTo(10_000L)
        assertThat(written.lastAliveMillis).isEqualTo(10_000L)
        assertThat(written.pid).isEqualTo(4321)
        assertThat(written.foreground).isFalse()
    }

    @Test
    fun `beginSession returns the previous marker and replaces it`() {
        val markerStore = store()
        val previous = SessionMarker(1_000L, 2_000L, 1111, true, "Thread", "syncing")
        markerStore.write(previous)
        val tracker = tracker()
        assertThat(tracker.beginSession()).isEqualTo(previous)
        val written = markerStore.read()
        assertThat(written).isNotNull()
        assertThat(written!!.pid).isEqualTo(4321)
        assertThat(written.sessionStartMillis).isEqualTo(10_000L)
    }

    @Test
    fun `beginSession treats a corrupt previous marker as no marker`() {
        val dir = File(tempDir, "crash-reports")
        dir.mkdirs()
        File(dir, SessionMarkerStore.FILE_NAME).writeText("garbage")
        assertThat(tracker().beginSession()).isNull()
        assertThat(store().read()).isNotNull()
    }

    @Test
    fun `updateScreen records the screen and skips an unchanged screen`() {
        val tracker = tracker()
        tracker.beginSession()
        tracker.updateScreen("Thread")
        assertThat(store().read()?.screen).isEqualTo("Thread")
        now = 20_000L
        tracker.updateScreen("Thread")
        // No rewrite: the last-alive time is untouched by a no-op update.
        assertThat(store().read()?.lastAliveMillis).isEqualTo(10_000L)
        tracker.updateScreen("Settings")
        assertThat(store().read()?.screen).isEqualTo("Settings")
    }

    @Test
    fun `updateSyncState records the phase and skips an unchanged phase`() {
        val tracker = tracker()
        tracker.beginSession()
        tracker.updateSyncState("syncing")
        assertThat(store().read()?.syncState).isEqualTo("syncing")
        tracker.updateSyncState("syncing")
        assertThat(store().read()?.syncState).isEqualTo("syncing")
        tracker.updateSyncState("paused")
        assertThat(store().read()?.syncState).isEqualTo("paused")
    }

    @Test
    fun `foreground and background flip the flag and refresh last-alive`() {
        val tracker = tracker()
        tracker.beginSession()
        now = 20_000L
        tracker.onForeground()
        val fore = store().read()
        assertThat(fore?.foreground).isTrue()
        assertThat(fore?.lastAliveMillis).isEqualTo(20_000L)
        now = 30_000L
        tracker.onBackground()
        val back = store().read()
        assertThat(back?.foreground).isFalse()
        assertThat(back?.lastAliveMillis).isEqualTo(30_000L)
    }

    @Test
    fun `heartbeat refreshes last-alive without touching anything else`() {
        val tracker = tracker()
        tracker.beginSession()
        tracker.updateScreen("Thread")
        tracker.updateSyncState("syncing")
        now = 70_000L
        tracker.heartbeat()
        val written = store().read()
        assertThat(written).isNotNull()
        assertThat(written!!.lastAliveMillis).isEqualTo(70_000L)
        assertThat(written.screen).isEqualTo("Thread")
        assertThat(written.syncState).isEqualTo("syncing")
        assertThat(written.sessionStartMillis).isEqualTo(10_000L)
    }

    @Test
    fun `updates before beginSession are dropped, not crashed`() {
        val tracker = tracker()
        tracker.updateScreen("Thread")
        tracker.updateSyncState("syncing")
        tracker.heartbeat()
        assertThat(store().read()).isNull()
    }
}
