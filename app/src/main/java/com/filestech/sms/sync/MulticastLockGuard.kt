package com.filestech.sms.sync

import android.content.Context
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Holds a Wi-Fi multicast lock while the sync server runs, so a
 * linked computer can still find the phone when the screen is off.
 *
 * The Wi-Fi stack filters out packets not explicitly addressed to
 * this device — and multicast is filtered hardest once the screen
 * goes off. mDNS discovery is multicast in both directions (the
 * computer's query, the phone's answer), so without the lock the
 * phone stops answering "Find your phone" the moment it locks, even
 * though the server itself keeps listening: the NSD registration
 * survives (it lives in the system server), but its traffic never
 * reaches the app. Acquiring a [WifiManager.MulticastLock] tells
 * the stack to deliver multicast packets to this device.
 *
 * Held only while [SyncService] runs — acquired when the service
 * starts, released when it is destroyed — so the extra battery cost
 * of receiving multicast traffic applies only while the server
 * toggle is on. If the app crashes, the system releases the lock.
 *
 * The lock operations sit behind [Lock] so the acquire/release
 * lifecycle is unit-testable without a device: production uses the
 * real [WifiManager.MulticastLock], tests use a fake. A lock that
 * cannot be created or acquired (no Wi-Fi service, permission
 * missing) degrades to "no lock" and only logs: discovery failing
 * must never take the server down with it.
 */
class MulticastLockGuard private constructor(
    private val lock: Lock?,
) {
    /** The multicast-lock operations the guard needs. */
    interface Lock {
        fun acquire()
        fun release()
        val isHeld: Boolean
    }

    /** The real lock: a non-reference-counted [WifiManager.MulticastLock]. */
    private class RealLock(wifiManager: WifiManager) : Lock {
        private val lock = wifiManager.createMulticastLock(TAG).apply {
            setReferenceCounted(false)
        }

        override fun acquire() = lock.acquire()
        override fun release() = lock.release()
        override val isHeld: Boolean get() = lock.isHeld
    }

    /**
     * Acquires the lock. No-op if already held or if no lock could
     * be created. Never throws.
     */
    fun acquire() {
        val current = lock ?: return
        if (current.isHeld) return
        runCatching { current.acquire() }
            .onFailure { t -> Timber.w(t, "MulticastLockGuard: acquire failed") }
    }

    /**
     * Releases the lock. No-op if not held or if no lock exists.
     * Never throws.
     */
    fun release() {
        val current = lock ?: return
        if (!current.isHeld) return
        runCatching { current.release() }
            .onFailure { t -> Timber.w(t, "MulticastLockGuard: release failed") }
    }

    /** True while the underlying lock is held. */
    val isHeld: Boolean
        get() = lock?.isHeld == true

    companion object {
        private const val TAG = "RelaySync"

        /**
         * Builds a guard for the given context, or a no-op guard
         * when this device has no Wi-Fi service or the lock cannot
         * be created (needs `CHANGE_WIFI_MULTICAST_STATE`, a normal
         * install-time permission declared in the manifest).
         */
        fun from(context: Context): MulticastLockGuard {
            val wifiManager = context.getSystemService<WifiManager>()
            if (wifiManager == null) {
                Timber.w("MulticastLockGuard: no WifiManager on this device, no multicast lock")
                return MulticastLockGuard(null)
            }
            val lock = runCatching { RealLock(wifiManager) }.getOrNull()
            if (lock == null) {
                Timber.w("MulticastLockGuard: could not create multicast lock")
            }
            return MulticastLockGuard(lock)
        }

        /** Test-only: builds a guard around a fake lock. */
        internal fun withLock(lock: Lock?): MulticastLockGuard = MulticastLockGuard(lock)
    }
}
