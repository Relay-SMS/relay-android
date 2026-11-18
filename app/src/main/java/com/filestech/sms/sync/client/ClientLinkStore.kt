package com.filestech.sms.sync.client

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.clientLinkDataStore by preferencesDataStore(name = "client_link")

/**
 * Client mode — what this device knows about its link to a hub phone.
 *
 * [deviceId] and [displayName] are this device's own identity (created
 * lazily at first link); the `hub_*` fields are the *remote* hub's
 * identity and last-known address, learned from `auth_ok` and kept for
 * reconnects without a scan. [linkedAt] is the pairing time (not
 * reset by later reconnects to the same hub); [lastSeen] is the last
 * authenticated contact, throttled to one write a minute.
 */
data class ClientLink(
    val deviceId: String,
    val displayName: String,
    val hubId: String? = null,
    val hubName: String? = null,
    val hubHost: String? = null,
    val hubPort: Int? = null,
    val linkedAt: Long? = null,
    val lastSeen: Long? = null,
) {
    /** True once a hub has authenticated (not merely been scanned). */
    val isLinked: Boolean get() = hubId != null
}

/**
 * Client mode — the client side of pairing state, in its own small
 * DataStore Preferences file (`client_link`), deliberately separate
 * from [com.filestech.sms.sync.LinkedDeviceStore]: that store's
 * `hubId()` is *this* device's hub identity, while the client needs
 * the *remote* hub's id and name. Overloading one store with both
 * meanings would be the naming collision the design flagged.
 *
 * The device key seed and the TOFU pin live in files next to each
 * other ([ClientIdentityStore], [TofuPinStore]), not here.
 *
 * Corruption is fail-soft, like the hub store: an unreadable link
 * reads as "no identity yet" instead of crashing startup. The device
 * id is generated once, on first link, and never changes — the hub's
 * `addDevice` replaces the record for a known id, so re-pairing is a
 * repair, not a second device.
 */
@Singleton
class ClientLinkStore(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * The application constructor: the single store for the process.
     * The primary constructor takes the [DataStore] so a test can hand
     * in its own (the [com.filestech.sms.data.local.datastore
     * .SettingsRepository] pattern — the `preferencesDataStore`
     * delegate keeps the first instance of the JVM, which shared one
     * file across tests).
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.clientLinkDataStore)

    /** The current link, or null while no identity exists yet. */
    val link: Flow<ClientLink?> = dataStore.data
        .map { prefs ->
            val link = prefs.toClientLink()
            // TEMP-DIAG "Not linked": one line per emission, presence only — never values.
            Timber.d("ClientLinkStore: link emitted %s", describeLink(link))
            link
        }
        .catch { t ->
            Timber.w(t, "ClientLinkStore: link read failed, serving null")
            emit(null)
        }

    /** One-shot read of [link]. */
    suspend fun snapshot(): ClientLink? = link.first()

    /**
     * This device's stable id, generated on first read. The
     * check-and-set runs inside one DataStore edit, so two concurrent
     * first reads still produce exactly one id.
     */
    suspend fun deviceIdOrCreate(): String {
        val generated = UUID.randomUUID().toString()
        runCatching {
            dataStore.edit { prefs ->
                if (prefs[KEY_DEVICE_ID].isNullOrBlank()) prefs[KEY_DEVICE_ID] = generated
            }
            return dataStore.data.first()[KEY_DEVICE_ID] ?: generated
        }.onFailure { t ->
            Timber.w(t, "ClientLinkStore: deviceId read failed, using an ephemeral id")
        }
        return generated
    }

    /**
     * This device's display name, defaulting to [default] (the model
     * name) on first read. Same check-and-set shape as
     * [deviceIdOrCreate]: the name is written once and kept, so a
     * later default change does not rename a linked device.
     */
    suspend fun displayNameOrCreate(default: String): String {
        runCatching {
            dataStore.edit { prefs ->
                if (prefs[KEY_DISPLAY_NAME].isNullOrBlank()) prefs[KEY_DISPLAY_NAME] = default
            }
            return dataStore.data.first()[KEY_DISPLAY_NAME] ?: default
        }.onFailure { t ->
            Timber.w(t, "ClientLinkStore: displayName read failed, using the default")
        }
        return default
    }

    /**
     * Records an authenticated hub. [linkedAt] is set only when the
     * hub is new (or never recorded): reconnects to the same hub keep
     * the original pairing time.
     */
    suspend fun setHub(
        hubId: String,
        hubName: String,
        host: String,
        port: Int,
        nowMs: Long,
    ) {
        runCatching {
            dataStore.edit { prefs ->
                val sameHub = prefs[KEY_HUB_ID] == hubId
                prefs[KEY_HUB_ID] = hubId
                prefs[KEY_HUB_NAME] = hubName
                prefs[KEY_HUB_HOST] = host
                prefs[KEY_HUB_PORT] = port
                if (!sameHub || prefs[KEY_LINKED_AT] == null) prefs[KEY_LINKED_AT] = nowMs
                prefs[KEY_LAST_SEEN] = nowMs
            }
        }.onSuccess {
            // TEMP-DIAG "Not linked": catches WHEN hubId gets written.
            Timber.d(
                "ClientLinkStore: setHub succeeded hubId=%s hubName=%s hubHost=%s hubPort=%s",
                hubId.isNotBlank(),
                hubName.isNotBlank(),
                host.isNotBlank(),
                true,
            )
        }.onFailure { t ->
            Timber.w(t, "ClientLinkStore: setHub failed")
        }
    }

    /** Updates the last-known address after re-discovery moved the hub. */
    suspend fun updateLastKnownAddress(host: String, port: Int) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[KEY_HUB_HOST] = host
                prefs[KEY_HUB_PORT] = port
            }
        }.onFailure { t ->
            Timber.w(t, "ClientLinkStore: updateLastKnownAddress failed")
        }
    }

    /**
     * Records an authenticated contact. Throttled to one write a
     * minute: the client pings every 30 s, and a DataStore write per
     * ping would churn the file for a timestamp nobody watches that
     * closely (the hub store's rule).
     */
    suspend fun touchLastSeen(nowMs: Long) {
        runCatching {
            dataStore.edit { prefs ->
                val lastSeen = prefs[KEY_LAST_SEEN]
                if (lastSeen != null && nowMs - lastSeen < TOUCH_THROTTLE_MS) return@edit
                prefs[KEY_LAST_SEEN] = nowMs
            }
        }.onFailure { t ->
            Timber.w(t, "ClientLinkStore: touchLastSeen failed")
        }
    }

    /**
     * Drops the hub record (Unlink / mode switch). The device identity
     * ([deviceId], [displayName]) survives — re-linking later reuses
     * it, and the hub treats the re-pair as a repair.
     */
    suspend fun clearHub() {
        // TEMP-DIAG "Not linked": catches what removes hubId.
        Timber.d("ClientLinkStore: clearHub called")
        runCatching {
            dataStore.edit { prefs ->
                prefs.remove(KEY_HUB_ID)
                prefs.remove(KEY_HUB_NAME)
                prefs.remove(KEY_HUB_HOST)
                prefs.remove(KEY_HUB_PORT)
                prefs.remove(KEY_LINKED_AT)
                prefs.remove(KEY_LAST_SEEN)
            }
        }.onFailure { t ->
            Timber.w(t, "ClientLinkStore: clearHub failed")
        }
    }

    /**
     * TEMP-DIAG for the "Not linked" investigation: which fields of
     * the link record are present — booleans only, never the values.
     */
    private fun describeLink(link: ClientLink?): String =
        "isLinked=${link?.isLinked} deviceId=${link?.deviceId != null} " +
            "displayName=${link?.displayName != null} hubId=${link?.hubId != null} " +
            "hubName=${link?.hubName != null} hubHost=${link?.hubHost != null} " +
            "hubPort=${link?.hubPort != null}"

    private fun Preferences.toClientLink(): ClientLink? {
        val deviceId = this[KEY_DEVICE_ID]?.takeIf { it.isNotBlank() } ?: return null
        val displayName = this[KEY_DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: return null
        return ClientLink(
            deviceId = deviceId,
            displayName = displayName,
            hubId = this[KEY_HUB_ID]?.takeIf { it.isNotBlank() },
            hubName = this[KEY_HUB_NAME]?.takeIf { it.isNotBlank() },
            hubHost = this[KEY_HUB_HOST]?.takeIf { it.isNotBlank() },
            hubPort = this[KEY_HUB_PORT],
            linkedAt = this[KEY_LINKED_AT],
            lastSeen = this[KEY_LAST_SEEN],
        )
    }

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("client.device_id")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("client.display_name")
        private val KEY_HUB_ID = stringPreferencesKey("client.hub_id")
        private val KEY_HUB_NAME = stringPreferencesKey("client.hub_name")
        private val KEY_HUB_HOST = stringPreferencesKey("client.hub_host")
        private val KEY_HUB_PORT = intPreferencesKey("client.hub_port")
        private val KEY_LINKED_AT = longPreferencesKey("client.linked_at")
        private val KEY_LAST_SEEN = longPreferencesKey("client.last_seen")

        /** Minimum gap between `last_seen` writes. */
        private const val TOUCH_THROTTLE_MS = 60_000L
    }
}
