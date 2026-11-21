package com.filestech.sms.crashlog

import android.app.Application
import android.os.Process
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Local-only error log — keeps this session's marker current
 * (see [SessionMarker]).
 *
 * Installed once from `MainApplication.onCreate`, right after
 * the crash handler: [install] reads the previous session's
 * marker (returned for the startup check), writes this session's,
 * and starts the lifecycle tracking. From then on the marker is
 * refreshed on every foreground/background transition, every
 * screen change (via [updateScreen], from the nav graph), every
 * sync-phase change (via [updateSyncState], from the client sync
 * service), and a heartbeat while foregrounded — so a freeze
 * mid-sync leaves a marker that says "foreground, thread open,
 * sync running".
 *
 * The heartbeat runs only while foregrounded (a backgrounded app
 * has nothing new to prove, and waking to write would cost
 * battery for nothing). Writes are small (~200 bytes) and
 * synchronous, serialized through one lock, so a screen change
 * racing the heartbeat can never lose an update or tear a write.
 * Every write is best-effort: marker I/O never throws into its
 * caller.
 *
 * The process id in the marker is captured at construction; the
 * clock is injectable so tests can drive the timestamps.
 *
 * Round 36 — the error-log switch ([CrashLogSettings]): the
 * marker is part of the error log, so it is gated by the same
 * flag. When the log is off, [beginSession] deletes any stale
 * marker instead of writing one, every update is a no-op, and
 * the heartbeat ticks without writing — logging off means no
 * trace, and a session that ran with the log off is never
 * reported as an unexpected stop when the log comes back on.
 * [enabled] is read live on every write, so flipping the
 * switch mid-session takes effect at once; [clearMarker] and
 * [restartSession] are the toggle's edges (see
 * `CrashLogSettingsViewModel`).
 */
class SessionMarkerTracker internal constructor(
    private val store: SessionMarkerStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pid: Int = Process.myPid(),
    private val enabled: () -> Boolean = { true },
) {

    companion object {
        /** How often the marker is refreshed while foregrounded. */
        const val HEARTBEAT_INTERVAL_MS: Long = 60_000L

        /**
         * The installed tracker, or null before [install]. The nav
         * graph and the sync service reach the tracker through
         * here — they cannot be handed an instance (they are
         * created by Compose and the service framework).
         */
        @Volatile
        var current: SessionMarkerTracker? = null
            private set

        /**
         * Installs the tracker for this process and returns the
         * previous session's marker, if any, for the startup
         * check. Idempotent — a second call returns null (there
         * is no second previous session). The marker write is
         * synchronous and tiny (~200 bytes); the exit-reason
         * query and any report write happen off the main thread
         * in the caller's startup check.
         */
        fun install(application: Application): SessionMarker? {
            if (current != null) return null
            val settings = CrashLogSettings(application)
            val tracker = SessionMarkerTracker(
                SessionMarkerStore(File(application.filesDir, CrashReportStore.DIR_NAME)),
                enabled = settings::enabled,
            )
            val previous = tracker.beginSession()
            tracker.startLifecycleTracking()
            current = tracker
            return previous
        }
    }

    /** Guards [currentMarker] and the heartbeat: every mutation goes through here. */
    private val lock = Any()

    /** This session's marker as last written (null before [beginSession]). */
    private var currentMarker: SessionMarker? = null

    /** The heartbeat's scheduler; null until [startLifecycleTracking], and in tests. */
    private var heartbeatScheduler: ScheduledExecutorService? = null

    /** The running heartbeat, if any. */
    private var heartbeatFuture: ScheduledFuture<*>? = null

    /**
     * Reads the previous session's marker (if any) and writes this
     * session's. The new session starts backgrounded: the
     * foreground transition arrives via the lifecycle observer
     * when the first activity starts, and a process that never
     * shows an activity (started for a service or a receiver)
     * correctly stays backgrounded.
     *
     * When the error log is off, no marker is written at all:
     * any stale marker is deleted instead, so re-enabling the
     * log later starts from a clean slate, and null is returned
     * so the startup check has nothing to report.
     */
    internal fun beginSession(): SessionMarker? {
        if (!enabled()) {
            synchronized(lock) {
                currentMarker = null
                runCatching { store.delete() }
            }
            return null
        }
        val previous = runCatching { store.read() }.getOrNull()
        val now = clock()
        val marker = SessionMarker(
            sessionStartMillis = now,
            lastAliveMillis = now,
            pid = pid,
            foreground = false,
        )
        synchronized(lock) {
            currentMarker = marker
            runCatching { store.write(marker) }
        }
        return previous
    }

    /**
     * Drops this session's marker, in memory and on disk, and
     * stops tracking until [restartSession]. The error-log
     * toggle calls this when the log is turned off, so the
     * marker is gone at once instead of lingering until the
     * next cold start. Never throws.
     */
    fun clearMarker() {
        synchronized(lock) {
            currentMarker = null
            runCatching { store.delete() }
        }
    }

    /**
     * Starts tracking this session again after [clearMarker]
     * (or after an install with the log off): writes a fresh
     * marker. The previous marker, if one somehow survived, is
     * discarded — a session that ran with the log off is not
     * reported. The error-log toggle calls this when the log
     * is turned back on. Never throws.
     */
    fun restartSession() {
        runCatching { beginSession() }
    }

    /**
     * Records the current screen. A no-op when the screen has not
     * changed, so navigating back to the same screen (or a
     * re-delivered destination event) does not rewrite the file.
     */
    fun updateScreen(screen: String?) {
        synchronized(lock) {
            updateLocked { it.copy(screen = screen) }
        }
    }

    /**
     * Records the client sync phase ("syncing", "paused", "idle",
     * …). A no-op when the phase has not changed — the engine's
     * state flow emits on every batch, and only phase transitions
     * are worth a write.
     */
    fun updateSyncState(syncState: String?) {
        synchronized(lock) {
            updateLocked { it.copy(syncState = syncState) }
        }
    }

    /** The app came to the foreground: mark it alive and start the heartbeat. */
    internal fun onForeground() {
        synchronized(lock) {
            updateLocked { it.copy(foreground = true, lastAliveMillis = clock()) }
            startHeartbeatLocked()
        }
    }

    /** The app went to the background: mark it, and stop the heartbeat. */
    internal fun onBackground() {
        synchronized(lock) {
            stopHeartbeatLocked()
            updateLocked { it.copy(foreground = false, lastAliveMillis = clock()) }
        }
    }

    /** One heartbeat tick: refresh the last-alive time. Runs on the scheduler thread. */
    internal fun heartbeat() {
        synchronized(lock) {
            updateLocked { it.copy(lastAliveMillis = clock()) }
        }
    }

    /** Registers the foreground/background observer and creates the heartbeat scheduler. */
    private fun startLifecycleTracking() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "session-marker-heartbeat").apply { isDaemon = true }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    onForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    onBackground()
                }
            },
        )
    }

    /** Starts the heartbeat if it is not already running. Callers hold [lock]. */
    private fun startHeartbeatLocked() {
        val scheduler = heartbeatScheduler ?: return
        if (heartbeatFuture?.isCancelled == false) return
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            { runCatching { heartbeat() } },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Stops the heartbeat if it is running. Callers hold [lock]. */
    private fun stopHeartbeatLocked() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    /**
     * Applies a change to the marker and writes it, unless the
     * change is a no-op (the new marker equals the current one)
     * or the error log is off. Callers hold [lock]. Never throws.
     */
    private fun updateLocked(transform: (SessionMarker) -> SessionMarker) {
        if (!enabled()) return
        val current = currentMarker ?: return
        val updated = transform(current)
        if (updated == current) return
        currentMarker = updated
        runCatching { store.write(updated) }
    }
}
