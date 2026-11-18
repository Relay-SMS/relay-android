package com.filestech.sms.sync

import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * A bounded partial wakelock, held only while a sync run is
 * actively streaming, so a brief screen-off doesn't instantly
 * freeze the sync. Acquire/release are reference-counted — two
 * linked devices can sync at once, and the underlying lock is
 * released only when the last holder lets go — and never throw:
 * a wakelock failure must never break sync, so every failure
 * degrades to "no lock" and only logs.
 *
 * The hub engine ([SyncEngine]) and the client engine share the
 * one [SyncWakelock] binding (see
 * [com.filestech.sms.di.SyncWakelockModule]) — a device is either
 * a hub or a client, never both at once, so the two engines never
 * compete for it. The lock is held for sync streams only: never
 * for attachment downloads/uploads, live push, or anything else.
 */
interface SyncWakelock {
    /**
     * Acquires the lock (reference-counted: every acquire needs
     * a matching [release]). Never throws.
     */
    fun acquire()

    /**
     * Releases one acquire; the underlying lock is released only
     * when the last holder lets go. No-op when not held. Never
     * throws.
     */
    fun release()
}

/**
 * The real wakelock: one non-reference-counted
 * [PowerManager.PARTIAL_WAKE_LOCK] ("Relay:Sync"), created once.
 * The holder counts holders itself (the underlying lock stays
 * non-reference-counted so a miscount can never wedge it held):
 * the first acquire takes the lock, the last release drops it.
 * A partial lock keeps the CPU running with the screen off — it
 * does not turn the screen on. Needs `WAKE_LOCK`, a normal
 * install-time permission declared in the manifest.
 *
 * Creation failure (no PowerManager on the device, or the lock
 * cannot be created) degrades to a holder with no lock: acquire
 * and release become no-ops and sync proceeds unlocked.
 */
class PowerManagerSyncWakelock @Inject constructor(
    @ApplicationContext context: Context,
) : SyncWakelock {

    private val wakeLock: PowerManager.WakeLock? = runCatching {
        context.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull().also {
        if (it == null) Timber.w("SyncWakelock: no wakelock available, sync runs unlocked")
    }

    /**
     * Active holders. Two linked devices can sync at once, and a
     * superseded stream releases while its replacement still runs
     * — the count keeps the lock held until the last holder lets
     * go. Guarded by [countLock]; acquire/release run on
     * engine-scope coroutines on a multi-threaded dispatcher.
     */
    private val countLock = Any()
    private var holders: Int = 0

    override fun acquire() {
        val lock = wakeLock ?: return
        synchronized(countLock) {
            if (holders == 0) {
                runCatching { lock.acquire() }
                    .onSuccess { holders = 1 }
                    .onFailure { t -> Timber.w(t, "SyncWakelock: acquire failed") }
            } else {
                holders++
            }
        }
    }

    override fun release() {
        val lock = wakeLock ?: return
        synchronized(countLock) {
            if (holders == 0) return
            if (holders > 1) {
                holders--
                return
            }
            runCatching { lock.release() }
                .onFailure { t -> Timber.w(t, "SyncWakelock: release failed") }
            // Clear the count even when the release threw: a release
            // only throws when the lock is not held ("under-locked"),
            // so a throw means "not held" — and a stuck count would
            // block every later acquire.
            holders = 0
        }
    }

    companion object {
        private const val TAG = "Relay:Sync"
    }
}

/**
 * A wakelock that does nothing — for tests, and for any engine
 * built without a lock (a stream without one just runs unlocked).
 */
object NoOpSyncWakelock : SyncWakelock {
    override fun acquire() = Unit
    override fun release() = Unit
}
