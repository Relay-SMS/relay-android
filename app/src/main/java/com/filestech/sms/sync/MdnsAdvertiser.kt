package com.filestech.sms.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advertises the phone on the local network so a linking device can
 * find it without the user typing an address: mDNS service type
 * `_relay._tcp.`, instance `Relay`, port 47831, TXT `v=1` and
 * `hub=<hub-id>` (SYNC_PROTOCOL.md). Plain `android.net.nsd` — no
 * third-party discovery library, no cloud.
 *
 * If another device on the network already advertises the same
 * instance name, Android renames ours (`Relay (2)`, …) — that is
 * logged, not fought: the desktop matches on the service type and the
 * `hub` TXT record, not on the exact instance name.
 */
@Singleton
class MdnsAdvertiser @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val nsdManager: NsdManager? = context.getSystemService<NsdManager>()

    /**
     * Registration requested (not just confirmed): `stop()` can run
     * before the system's `onServiceRegistered` callback fires, and
     * gating on the callback would leak the registration.
     */
    @Volatile
    private var advertising = false

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            if (info.serviceName != SyncProtocol.SERVICE_NAME) {
                Timber.w(
                    "MdnsAdvertiser: instance renamed to '%s' (name clash on this network)",
                    info.serviceName,
                )
            } else {
                Timber.i("MdnsAdvertiser: advertising %s on port %d", info.serviceName, info.port)
            }
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            advertising = false
            Timber.w("MdnsAdvertiser: registration failed (error=%d)", errorCode)
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Timber.i("MdnsAdvertiser: unregistered")
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Timber.w("MdnsAdvertiser: unregistration failed (error=%d)", errorCode)
        }
    }

    /**
     * Starts advertising. No-op if already advertising or if this
     * device has no NSD service (it should not happen, but discovery
     * failing must never take the server down with it).
     */
    fun start(hubId: String) {
        val manager = nsdManager ?: run {
            Timber.w("MdnsAdvertiser: no NsdManager on this device, not advertising")
            return
        }
        if (advertising) return
        advertising = true
        val info = NsdServiceInfo().apply {
            serviceName = SyncProtocol.SERVICE_NAME
            serviceType = SyncProtocol.SERVICE_TYPE
            port = SyncProtocol.PORT
            setAttribute("v", SyncProtocol.PROTOCOL_VERSION.toString())
            setAttribute("hub", hubId)
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { t ->
            advertising = false
            Timber.w(t, "MdnsAdvertiser: registerService failed")
        }
    }

    /** Stops advertising. No-op if not advertising. */
    fun stop() {
        if (!advertising) return
        advertising = false
        val manager = nsdManager ?: return
        runCatching {
            manager.unregisterService(listener)
        }.onFailure { t ->
            Timber.w(t, "MdnsAdvertiser: unregisterService failed")
        }
    }

    /** True while a registration is requested (or confirmed). */
    val isAdvertising: Boolean
        get() = advertising
}
