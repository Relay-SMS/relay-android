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
import java.time.Instant
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
class LinkedDeviceStore(
    private val dataStore: DataStore<Preferences>,
) : LinkedDeviceLookup {

    /**
     * The application constructor: the single store for the process.
     * The primary constructor takes the [DataStore] so a test can hand
     * in its own (the [com.filestech.sms.sync.client.ClientLinkStore]
     * pattern — the `preferencesDataStore` delegate keeps the first
     * instance of the JVM, which shared one file across tests).
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.linkedDevicesDataStore)

    /** Linked devices, in pairing order. Empty when nothing is linked yet. */
    val devices: Flow<List<LinkedDevice>> = dataStore.data
        .map { prefs -> decodeDevices(prefs[KEY_DEVICES]) }
        .catch { t ->
            Timber.w(t, "LinkedDeviceStore: devices read failed, serving empty")
            emit(emptyList())
        }

    /**
     * Whether the LAN server runs. Default OFF: the server starts
     * only when the user toggles it on — a fresh install (a SIM-less
     * tablet, say) must never auto-run a server it cannot use as a
     * hub. Upgrades from a build where the server defaulted ON keep
     * it on when the user never toggled it; see
     * [migrateServerDefaultOnce].
     */
    val serverEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_SERVER_ENABLED] ?: false }
        .catch { t ->
            Timber.w(t, "LinkedDeviceStore: serverEnabled read failed, serving false")
            emit(false)
        }

    /**
     * The last server start failure, one line
     * (`<ISO-8601 time> | <step> | <SimpleName>: <first line>`), or
     * null when the server last started cleanly. The release build
     * logs nothing, so this is the only record of a start failure —
     * the Linked devices screen shows it when the server is not
     * running. On-device only: never logged, never sent anywhere.
     */
    val lastStartError: Flow<String?> = dataStore.data
        .map { prefs -> prefs[KEY_LAST_START_ERROR] }
        .catch { t ->
            Timber.w(t, "LinkedDeviceStore: lastStartError read failed, serving null")
            emit(null)
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
     * One-shot migration for the default-off change, run once at
     * startup from [com.filestech.sms.MainApplication] with [isUpgrade] from the
     * PackageManager (`firstInstallTime < lastUpdateTime`).
     *
     * The server used to default ON, so an upgrade must not silently
     * switch it off for someone who linked a computer and never
     * touched the toggle: when the key is absent (never explicitly
     * toggled) on an upgrade, the server is written ON. An explicit
     * off stays off, and a fresh install stays off — the key is
     * left absent, which reads as off.
     *
     * The [KEY_SERVER_DEFAULT_MIGRATED] flag is what makes this
     * one-shot: without it, a fresh install of this build would
     * look like an upgrade at the NEXT update (install time <
     * update time by then) and the server would silently switch
     * on. Once the flag is set the migration never runs again, and
     * a later toggle is the user's own. Idempotent, fail-soft — a
     * failed edit simply retries at the next launch.
     */
    suspend fun migrateServerDefaultOnce(isUpgrade: Boolean) {
        runCatching {
            dataStore.edit { prefs ->
                if (prefs[KEY_SERVER_DEFAULT_MIGRATED] == true) return@edit
                if (prefs[KEY_SERVER_ENABLED] == null && isUpgrade) {
                    prefs[KEY_SERVER_ENABLED] = true
                }
                prefs[KEY_SERVER_DEFAULT_MIGRATED] = true
            }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: migrateServerDefaultOnce failed")
        }
    }

    /**
     * Records a server start failure (called by [SyncServer.start]
     * from its catch block, with the step it reached). Itself
     * `runCatching`, so a persistence failure never masks the
     * original throw. The stored string is never logged — exception
     * messages can contain paths, and this is shown on-device only.
     */
    suspend fun recordStartError(step: String, throwable: Throwable) {
        runCatching {
            val formatted = formatStartError(System.currentTimeMillis(), step, throwable)
            dataStore.edit { prefs -> prefs[KEY_LAST_START_ERROR] = formatted }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: recordStartError failed")
        }
    }

    /** Clears the recorded start failure — a successful start erases the stale error. */
    suspend fun clearStartError() {
        runCatching {
            dataStore.edit { prefs -> prefs.remove(KEY_LAST_START_ERROR) }
        }.onFailure { t ->
            Timber.w(t, "LinkedDeviceStore: clearStartError failed")
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
        private val KEY_SERVER_DEFAULT_MIGRATED =
            booleanPreferencesKey("sync.server_default_migrated")
        private val KEY_DEVICES = stringPreferencesKey("sync.devices_json")
        private val KEY_LAST_START_ERROR = stringPreferencesKey("sync_last_start_error")

        /** Minimum gap between `last_seen` writes for one device. */
        private const val TOUCH_THROTTLE_MS = 60_000L
    }
}

/** Maximum characters of an exception message kept in a recorded start error. */
private const val MAX_START_ERROR_MESSAGE_CHARS = 200

/**
 * Formats a server start failure for [LinkedDeviceStore.recordStartError]:
 * `<ISO-8601 time> | <step> | <SimpleName>: <first line of message, max 200 chars>`.
 * A message-less exception formats without the `: message` part. Pure function,
 * so the format is unit-testable without a DataStore (which needs an Android
 * Context). The result is shown on-device only — never logged, never sent.
 */
internal fun formatStartError(nowMs: Long, step: String, throwable: Throwable): String {
    val time = Instant.ofEpochMilli(nowMs).toString()
    val name = throwable.javaClass.simpleName
    val firstLine = throwable.message
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.take(MAX_START_ERROR_MESSAGE_CHARS)
    return if (firstLine.isNullOrEmpty()) {
        "$time | $step | $name"
    } else {
        "$time | $step | $name: $firstLine"
    }
}
