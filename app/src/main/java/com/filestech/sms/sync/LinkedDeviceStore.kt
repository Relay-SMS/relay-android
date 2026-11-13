package com.filestech.sms.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.linkedDevicesDataStore by preferencesDataStore(name = "linked_devices")

/**
 * The read side the auth path needs, kept as an interface so the
 * session state machine ([SyncSessionHandler]) is unit-testable without
 * a DataStore.
 */
interface LinkedDeviceLookup {
    suspend fun findDevice(deviceId: String): LinkedDevice?
    suspend fun touchLastSeen(deviceId: String, nowMs: Long)
}

/**
 * The phone's side of pairing state: the hub id, the server toggle,
 * and the linked devices — all in one small DataStore Preferences
 * file (`linked_devices`), following the same `preferencesDataStore`
 * pattern as the recent-reactions store.
 *
 * ## Why DataStore and not Room
 *
 * The linked-device list is tiny (a handful of entries), always read
 * whole, and never queried relationally — no joins, no search, no
 * pagination. The records hold *public* keys, so they need none of the
 * SQLCipher encryption the message database exists for (the TLS private
 * key, the one real secret here, lives in the AndroidKeyStore — see
 * [TlsCertificateProvider]). A Room entity would have bought a database
 * migration plus a schema export for a list that fits in one JSON
 * string. If a later slice needs per-device sync cursors or message
 * state, that is the moment to revisit — the store's API is the seam.
 *
 * Corruption is fail-soft: an unreadable devices list reads as empty
 * (and is overwritten on the next write) instead of crashing startup.
 * The hub id is generated once, on first read, and never changes —
 * the TLS certificate's CN and the mDNS TXT record both derive from it.
 */
@Singleton
class LinkedDeviceStore @Inject constructor(
    @ApplicationContext context: Context,
) : LinkedDeviceLookup {

    private val dataStore: DataStore<Preferences> = context.linkedDevicesDataStore

    /** Linked devices, in pairing order. Empty when nothing is linked yet. */
    val devices: Flow<List<LinkedDevice>> = dataStore.data
        .map { prefs -> decodeDevices(prefs[KEY_DEVICES]) }
        .catch { t ->
            Timber.w(t, "LinkedDeviceStore: devices read failed, serving empty")
            emit(emptyList())
        }

    /**
     * Whether the LAN server runs. Default ON: the phone is the hub, and
     * linking should just work out of the box; the toggle on the Linked
     * devices screen stops it, and the app works fully standalone either
     * way.
     */
    val serverEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_SERVER_ENABLED] ?: true }
        .catch { t ->
            Timber.w(t, "LinkedDeviceStore: serverEnabled read failed, serving true")
            emit(true)
        }

    /**
     * The phone's stable hub id, generated on first read. The
     * check-and-set runs inside one DataStore edit, so two concurrent
     * first reads still produce exactly one id.
     */
    suspend fun hubId(): String {
        val generated = UUID.randomUUID().toString()
        runCatching {
            dataStore.edit { prefs ->
                if (prefs[KEY_HUB_ID].isNullOrBlank()) prefs[KEY_HUB_ID] = generated
            }
            return dataStore.data.first()[KEY_HUB_ID] ?: generated
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: hubId read failed, using an ephemeral id")
        }
        return generated
    }

    suspend fun setServerEnabled(enabled: Boolean) {
        runCatching {
            dataStore.edit { prefs -> prefs[KEY_SERVER_ENABLED] = enabled }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: setServerEnabled failed")
        }
    }

    /**
     * Stores a confirmed pairing. Re-pairing the same device id replaces
     * its record (new name/key, fresh linked time) instead of duplicating
     * it — the user re-scanning a code they already linked is a repair,
     * not a second device.
     */
    suspend fun addDevice(invite: QrPairing.Invite, nowMs: Long): LinkedDevice {
        val device = LinkedDevice(
            deviceId = invite.deviceId,
            kind = invite.kind,
            displayName = invite.displayName,
            publicKeyBase64Url = invite.publicKeyBase64Url,
            linkedAt = nowMs,
            lastSeen = null,
        )
        runCatching {
            dataStore.edit { prefs ->
                val current = decodeDevices(prefs[KEY_DEVICES])
                    .filter { it.deviceId != device.deviceId }
                prefs[KEY_DEVICES] = SyncProtocol.json.encodeToString(current + device)
            }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: addDevice failed")
        }
        return device
    }

    /** Deletes the stored key. The live socket, if any, is closed by the caller first. */
    suspend fun removeDevice(deviceId: String) {
        runCatching {
            dataStore.edit { prefs ->
                val current = decodeDevices(prefs[KEY_DEVICES])
                    .filter { it.deviceId != deviceId }
                prefs[KEY_DEVICES] = SyncProtocol.json.encodeToString(current)
            }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: removeDevice failed")
        }
    }

    override suspend fun findDevice(deviceId: String): LinkedDevice? {
        return runCatching {
            decodeDevices(dataStore.data.first()[KEY_DEVICES])
                .firstOrNull { it.deviceId == deviceId }
        }.getOrNull()
    }

    /**
     * Records an authenticated contact. Throttled to one write a minute
     * per device: the client pings every 30 s, and a DataStore write per
     * ping would churn the file for a timestamp nobody watches that
     * closely.
     */
    override suspend fun touchLastSeen(deviceId: String, nowMs: Long) {
        runCatching {
            dataStore.edit { prefs ->
                val devices = decodeDevices(prefs[KEY_DEVICES])
                val current = devices.firstOrNull { it.deviceId == deviceId } ?: return@edit
                if (current.lastSeen != null && nowMs - current.lastSeen < TOUCH_THROTTLE_MS) {
                    return@edit
                }
                prefs[KEY_DEVICES] = SyncProtocol.json.encodeToString(
                    devices.map { if (it.deviceId == deviceId) it.copy(lastSeen = nowMs) else it },
                )
            }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: touchLastSeen failed")
        }
    }

    private fun decodeDevices(raw: String?): List<LinkedDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            SyncProtocol.json.decodeFromString<List<LinkedDevice>>(raw)
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: devices JSON unreadable, serving empty")
        }.getOrDefault(emptyList())
    }

    companion object {
        private val KEY_HUB_ID = stringPreferencesKey("sync.hub_id")
        private val KEY_SERVER_ENABLED = booleanPreferencesKey("sync.server_enabled")
        private val KEY_DEVICES = stringPreferencesKey("sync.devices_json")

        /** Minimum gap between `last_seen` writes for one device. */
        private const val TOUCH_THROTTLE_MS = 60_000L
    }
}
