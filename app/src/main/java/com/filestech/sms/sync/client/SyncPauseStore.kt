package com.filestech.sms.sync.client

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.clientSyncPauseDataStore by preferencesDataStore(name = "client_sync_pause")

/**
 * Client mode — one interrupted full sync run, durably persisted
 * so a pause (or a process death) never restarts the counter at 0.
 *
 * [reason] is null while the run is merely in progress: the engine
 * rewrites the record after every batch (a small DataStore write
 * per 200-message batch), so the counter is right even after a
 * process death *without* a pause. A non-null reason means the run
 * parked: [SyncPauseReason.DEVICE_ASLEEP] (Doze, unexempted) or
 * [SyncPauseReason.NO_RESPONSE] (the stream stalled).
 *
 * [snapshotComplete] says whether the hub's conversation snapshot
 * finished before the interruption: a resume gap-fills from the
 * per-conversation frontiers when it did, and re-requests the
 * whole run when it did not (gap-fill cannot find conversations
 * the snapshot never named). [maxStoredId] seeds the cursor on a
 * gap-fill resume: gap-fill pages only ever carry *older* messages,
 * so without it the resumed run would persist a regressed cursor.
 *
 * The record is cleared only when the run completes (or on
 * re-sync, unlink, or a hub change) — never at resume start, so a
 * process death mid-resume still resumes.
 */
data class PausedRun(
    val hubId: String,
    val mode: String,
    val imported: Long,
    val total: Long?,
    val reason: SyncPauseReason?,
    val pausedAt: Long,
    val snapshotComplete: Boolean,
    val maxStoredId: Long,
)

/**
 * Client mode — the durable pause record, in its own small
 * DataStore Preferences file (`client_sync_pause`), following the
 * [ClientLinkStore] pattern (the primary constructor takes the
 * [DataStore] so a test can hand in its own). One record at a
 * time: a device syncs with one hub, so one interrupted run is
 * all there ever is.
 *
 * No Room change: the record lives outside the sync-state tables
 * on purpose — the frontiers + cursor stay the source of truth
 * for *what* is stored, and this record only says *where the run
 * stopped*.
 */
interface SyncPauseStore {
    /** The stored record, or null when no run is interrupted. */
    val paused: Flow<PausedRun?>

    /** One-shot read of [paused]. */
    suspend fun snapshot(): PausedRun?

    /** Persists (or overwrites) the record. */
    suspend fun save(run: PausedRun)

    /** Drops the record (run completed, re-sync, unlink, hub change). */
    suspend fun clear()
}

/** Client mode — the [SyncPauseStore] backed by DataStore Preferences. */
@Singleton
class DataStoreSyncPauseStore(
    private val dataStore: DataStore<Preferences>,
) : SyncPauseStore {

    /**
     * The application constructor: the single store for the process.
     * (See [ClientLinkStore] for why the primary constructor takes
     * the [DataStore].)
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.clientSyncPauseDataStore)

    override val paused: Flow<PausedRun?> = dataStore.data
        .map { prefs -> prefs.toPausedRun() }
        .catch { t ->
            Timber.w(t, "SyncPauseStore: pause read failed, serving null")
            emit(null)
        }

    override suspend fun snapshot(): PausedRun? = paused.first()

    override suspend fun save(run: PausedRun) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[KEY_HUB_ID] = run.hubId
                prefs[KEY_MODE] = run.mode
                prefs[KEY_IMPORTED] = run.imported
                prefs[KEY_TOTAL] = run.total ?: NO_TOTAL
                if (run.reason == null) {
                    prefs.remove(KEY_REASON)
                } else {
                    prefs[KEY_REASON] = run.reason.name
                }
                prefs[KEY_PAUSED_AT] = run.pausedAt
                prefs[KEY_SNAPSHOT_COMPLETE] = run.snapshotComplete
                prefs[KEY_MAX_STORED_ID] = run.maxStoredId
            }
        }.onFailure { t ->
            Timber.w(t, "SyncPauseStore: save failed")
        }
    }

    override suspend fun clear() {
        runCatching {
            dataStore.edit { prefs ->
                prefs.remove(KEY_HUB_ID)
                prefs.remove(KEY_MODE)
                prefs.remove(KEY_IMPORTED)
                prefs.remove(KEY_TOTAL)
                prefs.remove(KEY_REASON)
                prefs.remove(KEY_PAUSED_AT)
                prefs.remove(KEY_SNAPSHOT_COMPLETE)
                prefs.remove(KEY_MAX_STORED_ID)
            }
        }.onFailure { t ->
            Timber.w(t, "SyncPauseStore: clear failed")
        }
    }

    private fun Preferences.toPausedRun(): PausedRun? {
        val hubId = this[KEY_HUB_ID]?.takeIf { it.isNotBlank() } ?: return null
        val mode = this[KEY_MODE]?.takeIf { it.isNotBlank() } ?: return null
        return PausedRun(
            hubId = hubId,
            mode = mode,
            imported = this[KEY_IMPORTED] ?: 0L,
            total = (this[KEY_TOTAL] ?: NO_TOTAL).takeIf { it >= 0L },
            reason = this[KEY_REASON]?.let { name ->
                runCatching { SyncPauseReason.valueOf(name) }.getOrNull()
            },
            pausedAt = this[KEY_PAUSED_AT] ?: 0L,
            snapshotComplete = this[KEY_SNAPSHOT_COMPLETE] ?: false,
            maxStoredId = this[KEY_MAX_STORED_ID] ?: 0L,
        )
    }

    companion object {
        /** [PausedRun.total] is null until `sync_started` announces it; -1 stands in. */
        private const val NO_TOTAL = -1L

        private val KEY_HUB_ID = stringPreferencesKey("pause.hub_id")
        private val KEY_MODE = stringPreferencesKey("pause.mode")
        private val KEY_IMPORTED = longPreferencesKey("pause.imported")
        private val KEY_TOTAL = longPreferencesKey("pause.total")
        private val KEY_REASON = stringPreferencesKey("pause.reason")
        private val KEY_PAUSED_AT = longPreferencesKey("pause.paused_at")
        private val KEY_SNAPSHOT_COMPLETE = booleanPreferencesKey("pause.snapshot_complete")
        private val KEY_MAX_STORED_ID = longPreferencesKey("pause.max_stored_id")
    }
}
