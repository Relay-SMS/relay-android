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
 * `_relay._tcp.`, instance name the device model (e.g. "Pixel 8"),
 * port 47831, TXT `v=1`, `hub=<hub-id>` and — while a VPN tunnel is
 * the phone's active network — `tip=<tunnel-ipv4>` (SYNC_PROTOCOL.md).
 * Plain `android.net.nsd` — no third-party discovery library, no
 * cloud.
 *
 * The instance name is the model so two hubs on one network are
 * distinguishable (in the desktop's phone picker, and in the
 * auto-link status). If another device on the network already
 * advertises the same instance name, Android renames ours
 * (`Pixel 8 (2)`, …) — that is logged, not fought: clients match
 * on the service type and the `hub` TXT record, not on the exact
 * instance name.
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

    /**
     * The instance name [start] requested: the rename check in the
     * registration listener compares against it (the name is the
     * device model now, not a constant).
     */
    @Volatile
    private var requestedName: String? = null

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            if (info.serviceName != requestedName) {
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
     * failing must never take the server down with it). The instance
     * name is [hubName] — the device model, so two hubs on one
     * network are distinguishable. [tunnelIp] is the phone's tunnel
     * IPv4 address, advertised as the TXT `tip` record so a linked
     * device can dial through the tunnel; absent (null) means no
     * tunnel path right now and the record is omitted.
     */
    fun start(hubId: String, hubName: String, tunnelIp: String? = null) {
        val manager = nsdManager ?: run {
            Timber.w("MdnsAdvertiser: no NsdManager on this device, not advertising")
            return
        }
        if (advertising) return
        advertising = true
        requestedName = hubName
        val info = NsdServiceInfo().apply {
            serviceName = hubName
            serviceType = SyncProtocol.SERVICE_TYPE
            port = SyncProtocol.PORT
            setAttribute("v", SyncProtocol.PROTOCOL_VERSION.toString())
            setAttribute("hub", hubId)
            if (tunnelIp != null) setAttribute("tip", tunnelIp)
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { t ->
            advertising = false
            requestedName = null
            Timber.w(t, "MdnsAdvertiser: registerService failed")
        }
    }

    /** Stops advertising. No-op if not advertising. */
    fun stop() {
        if (!advertising) return
        advertising = false
        requestedName = null
        val manager = nsdManager ?: return
        runCatching {
            manager.unregisterService(listener)
        }.onFailure { t ->
            Timber.w(t, "MdnsAdvertiser: unregisterService failed")
        }
    }

    /**
     * Re-advertises with a new tunnel address: NSD TXT records are
     * fixed at registration, so a tunnel change needs unregister +
     * re-register. No-op if not advertising (nothing to
     * re-advertise). The instance name is the one [start]
     * requested. There is a sub-second window in which the phone
     * is not advertised; a scan in that window just misses the
     * phone once, and scans are user-initiated or periodic.
     */
    fun restart(hubId: String, tunnelIp: String?) {
        val name = requestedName ?: return
        stop()
        start(hubId, name, tunnelIp)
    }

    /** True while a registration is requested (or confirmed). */
    val isAdvertising: Boolean
        get() = advertising
}
