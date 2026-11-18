package com.filestech.sms.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The start-failure diagnostic format (§6 of the release
 * investigation): `<ISO-8601 time> | <step> | <SimpleName>: <first line>`.
 * [formatStartError] is a pure function, so the format is pinned here
 * without a DataStore (which needs an Android Context).
 *
 * The server default + upgrade migration tests below use a real
 * Preferences DataStore from [PreferenceDataStoreFactory] (the
 * [com.filestech.sms.sync.client.ClientLinkFlowTest] pattern): the
 * store's primary constructor takes the DataStore, so no Android
 * Context is needed.
 */
class LinkedDeviceStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test fun `formatStartError carries time step name and first line`() {
        val formatted = formatStartError(
            1_789_000_000_000L,
            "identity",
            IllegalStateException("no identity\nsecond line"),
        )
        assertThat(formatted).startsWith("2026-")
        assertThat(formatted).contains("| identity | IllegalStateException: no identity")
        assertThat(formatted).doesNotContain("second line")
    }

    @Test fun `formatStartError truncates a long message to 200 chars`() {
        val formatted = formatStartError(0L, "engine", IllegalStateException("x".repeat(300)))
        assertThat(formatted).contains("IllegalStateException: " + "x".repeat(200))
        assertThat(formatted).doesNotContain("x".repeat(201))
    }

    @Test fun `formatStartError omits the message when there is none`() {
        val formatted = formatStartError(0L, "mdns", IllegalStateException())
        assertThat(formatted).isEqualTo("1970-01-01T00:00:00Z | mdns | IllegalStateException")
    }

    @Test fun `server is off by default on a fresh store`() = runTest {
        val store = linkedStore()
        assertThat(store.serverEnabled.first()).isFalse()
    }

    @Test fun `upgrade with no explicit toggle keeps the server on`() = runTest {
        val store = linkedStore()
        store.migrateServerDefaultOnce(isUpgrade = true)
        assertThat(store.serverEnabled.first()).isTrue()
    }

    @Test fun `upgrade with an explicit off stays off`() = runTest {
        val store = linkedStore()
        store.setServerEnabled(false)
        store.migrateServerDefaultOnce(isUpgrade = true)
        assertThat(store.serverEnabled.first()).isFalse()
    }

    @Test fun `fresh install stays off`() = runTest {
        val store = linkedStore()
        store.migrateServerDefaultOnce(isUpgrade = false)
        assertThat(store.serverEnabled.first()).isFalse()
    }

    @Test fun `a later upgrade after a fresh install stays off`() = runTest {
        // The migration flag is what makes this hold: without it, a
        // fresh install of this build would look like an upgrade at
        // the next update (install time < update time by then) and
        // the server would silently switch on.
        val store = linkedStore()
        store.migrateServerDefaultOnce(isUpgrade = false)
        store.migrateServerDefaultOnce(isUpgrade = true)
        assertThat(store.serverEnabled.first()).isFalse()
    }

    @Test fun `migration is idempotent and a later toggle wins`() = runTest {
        val store = linkedStore()
        store.migrateServerDefaultOnce(isUpgrade = true)
        store.migrateServerDefaultOnce(isUpgrade = true)
        assertThat(store.serverEnabled.first()).isTrue()
        store.setServerEnabled(false)
        store.migrateServerDefaultOnce(isUpgrade = true)
        assertThat(store.serverEnabled.first()).isFalse()
    }

    @Test fun `toggle still switches the server`() = runTest {
        val store = linkedStore()
        store.setServerEnabled(true)
        assertThat(store.serverEnabled.first()).isTrue()
        store.setServerEnabled(false)
        assertThat(store.serverEnabled.first()).isFalse()
    }

    @Test fun `contacts write denial is false on a fresh store`() = runTest {
        val store = linkedStore()
        assertThat(store.contactsWriteDenied.first()).isFalse()
    }

    @Test fun `a contacts write denial persists`() = runTest {
        val store = linkedStore()
        store.setContactsWriteDenied(true)
        assertThat(store.contactsWriteDenied.first()).isTrue()
    }

    private fun TestScope.linkedStore(): LinkedDeviceStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
        ) { File(tempDir, "linked_devices.preferences_pb") }
        return LinkedDeviceStore(dataStore)
    }
}
