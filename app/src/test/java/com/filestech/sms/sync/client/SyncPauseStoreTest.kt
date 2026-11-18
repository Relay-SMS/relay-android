package com.filestech.sms.sync.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Client mode — the durable pause record, against a real
 * Preferences DataStore (the [ClientLinkFlowTest] pattern: the
 * store's primary constructor takes the DataStore, so no Android
 * Context is needed). Disk persistence is proven byte-level (the
 * file holds the record after a save): a second DataStore
 * instance on the same file cannot test it in-process — DataStore
 * 1.3 throws "multiple DataStores active for the same file" by
 * design, and the store's catch turns that into null (see the
 * note in [ClientLinkFlowTest]).
 */
class SyncPauseStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun TestScope.pauseStore(): DataStoreSyncPauseStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
        ) { prefsFile() }
        return DataStoreSyncPauseStore(dataStore)
    }

    private fun prefsFile(): File = File(tempDir, "client_sync_pause.preferences_pb")

    @Test fun `a fresh store reads null`() = runTest {
        val store = pauseStore()

        assertThat(store.snapshot()).isNull()
    }

    @Test fun `save then snapshot returns the run`() = runTest {
        val store = pauseStore()

        store.save(SAMPLE)

        assertThat(store.snapshot()).isEqualTo(SAMPLE)
    }

    @Test fun `save persists the run to the preferences file`() = runTest {
        val store = pauseStore()
        store.save(SAMPLE)
        advanceUntilIdle()
        // The raw file must hold the record: a fresh process reads
        // it from disk, not from memory.
        val text = prefsFile().readBytes().toString(Charsets.UTF_8)
        assertThat(text).contains(HUB_ID)
        assertThat(text).contains("full")
        assertThat(text).contains(SyncPauseReason.DEVICE_ASLEEP.name)
    }

    @Test fun `clear removes the run`() = runTest {
        val store = pauseStore()
        store.save(SAMPLE)
        assertThat(store.snapshot()).isNotNull()

        store.clear()

        assertThat(store.snapshot()).isNull()
    }

    @Test fun `an in-progress run with a null reason and null total round-trips`() = runTest {
        val store = pauseStore()
        val inProgress = PausedRun(
            hubId = HUB_ID,
            mode = "full",
            imported = 42L,
            total = null,
            reason = null,
            pausedAt = 1_789_000_000_000L,
            snapshotComplete = false,
            maxStoredId = 99L,
        )

        store.save(inProgress)

        assertThat(store.snapshot()).isEqualTo(inProgress)
    }

    @Test fun `save overwrites the previous run`() = runTest {
        val store = pauseStore()
        store.save(SAMPLE)

        val updated = SAMPLE.copy(imported = 20_000L, reason = SyncPauseReason.NO_RESPONSE)
        store.save(updated)

        assertThat(store.snapshot()).isEqualTo(updated)
    }

    private companion object {
        const val HUB_ID = "hub-1"

        val SAMPLE = PausedRun(
            hubId = HUB_ID,
            mode = "full",
            imported = 12_345L,
            total = 21_000L,
            reason = SyncPauseReason.DEVICE_ASLEEP,
            pausedAt = 1_789_000_000_000L,
            snapshotComplete = true,
            maxStoredId = 50_000L,
        )
    }
}
