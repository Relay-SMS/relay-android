package com.filestech.sms.sync.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Client mode — the "Not linked" bug (tablet, 2026-11-17): the
 * Linked devices screen showed "Not linked" although the tablet was
 * linked and syncing, and only the QR detour (open QR, back) made
 * the linked row appear. The video proved the `link` flow DID emit
 * on entry — the "This device" section rendered (it needs a
 * non-null link) — but the record it delivered had no hub id
 * (`isLinked == false`).
 *
 * These tests reproduce the screen's exact wiring
 * (`linkStore.link.stateIn(viewModelScope, WhileSubscribed(5_000),
 * null)`) against a real Preferences DataStore — and the bug does
 * NOT reproduce: a stored link is delivered on the first collection,
 * survives re-collection and a fresh stateIn, and the flow follows a
 * hub link that appears later. The wiring is correct; the device
 * symptom has no mechanism in the flow, the store, or the
 * persistence (the file holds the hub record after setHub). The
 * tests stand as regression pins for that behavior, and the device
 * diagnosis needs an on-device check next (a Timber line in the
 * link flow + a debug-APK logcat — Timber is silent in release).
 */
class ClientLinkFlowTest {

    @TempDir
    lateinit var tempDir: File

    @Test fun `a pre-populated link record arrives linked on the first collection`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        val viewModelScope = viewModelScope()
        val link = linkStore.link
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        link.test {
            // The stateIn initial value: null, before the upstream
            // has emitted.
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            val first = awaitItem()
            assertThat(first).isNotNull()
            assertThat(first!!.isLinked).isTrue()
            assertThat(first.hubId).isEqualTo(HUB_ID)
            assertThat(first.hubName).isEqualTo(HUB_NAME)
            assertThat(first.displayName).isEqualTo(DISPLAY_NAME)
        }
    }

    @Test fun `the flow follows a hub link that appears after collection starts`() = runTest {
        val linkStore = linkStore()
        populateIdentityOnly(linkStore)
        val viewModelScope = viewModelScope()
        val link = linkStore.link
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        link.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            // Identity exists, no hub yet: the "Not linked" state
            // the screen showed on entry.
            val unlinked = awaitItem()
            assertThat(unlinked).isNotNull()
            assertThat(unlinked!!.isLinked).isFalse()
            assertThat(unlinked.displayName).isEqualTo(DISPLAY_NAME)
            // Linking completes in the background (the hub's
            // setHub on auth).
            linkStore.setHub(HUB_ID, HUB_NAME, HUB_HOST, HUB_PORT, NOW_MS)
            advanceUntilIdle()
            val linked = awaitItem()
            assertThat(linked).isNotNull()
            assertThat(linked!!.isLinked).isTrue()
            assertThat(linked.hubId).isEqualTo(HUB_ID)
        }
    }

    @Test fun `re-collection after the WhileSubscribed timeout re-emits the stored link`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        val viewModelScope = viewModelScope()
        val link = linkStore.link
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        link.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            assertThat(awaitItem()?.isLinked).isTrue()
        }
        // The screen is away: the collector leaves, the upstream
        // stops after the 5 s timeout.
        advanceTimeBy(6_000)
        advanceUntilIdle()
        // Re-entry (same ViewModel, same StateFlow): the upstream
        // restarts and must replay the stored link.
        link.test {
            assertThat(awaitItem()?.isLinked).isTrue()
        }
    }

    @Test fun `a fresh stateIn on the same store replays the stored link`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        val firstScope = viewModelScope()
        val first = linkStore.link
            .stateIn(firstScope, SharingStarted.WhileSubscribed(5_000), null)
        first.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            assertThat(awaitItem()?.isLinked).isTrue()
        }
        firstScope.cancel()
        // A new ViewModel (a new stateIn) on the same store: the
        // first emission must still be the stored link.
        val secondScope = viewModelScope()
        val second = linkStore.link
            .stateIn(secondScope, SharingStarted.WhileSubscribed(5_000), null)
        second.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            val replayed = awaitItem()
            assertThat(replayed).isNotNull()
            assertThat(replayed!!.isLinked).isTrue()
            assertThat(replayed.hubId).isEqualTo(HUB_ID)
        }
    }

    @Test fun `a link stored while nobody collects is visible to the next collector`() = runTest {
        val linkStore = linkStore()
        // The screen was opened before linking (identity only),
        // then left; linking completes with no collector.
        populateIdentityOnly(linkStore)
        val firstScope = viewModelScope()
        val first = linkStore.link
            .stateIn(firstScope, SharingStarted.WhileSubscribed(5_000), null)
        first.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            assertThat(awaitItem()?.isLinked).isFalse()
        }
        firstScope.cancel()
        advanceTimeBy(6_000)
        linkStore.setHub(HUB_ID, HUB_NAME, HUB_HOST, HUB_PORT, NOW_MS)
        advanceUntilIdle()
        // Re-entry after linking: the stored link must show.
        val secondScope = viewModelScope()
        val second = linkStore.link
            .stateIn(secondScope, SharingStarted.WhileSubscribed(5_000), null)
        second.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            assertThat(awaitItem()?.isLinked).isTrue()
        }
    }

    @Test fun `a no-op identity read emits nothing new`() = runTest {
        val linkStore = linkStore()
        populateIdentityOnly(linkStore)
        // The QR detour's identity() re-reads deviceId/displayName
        // with the keys already present: the edit changes nothing.
        // The raw flow must not re-emit (and so the screen's
        // StateFlow cannot be "fixed" by the QR detour's reads).
        linkStore.link.test {
            advanceUntilIdle()
            val first = awaitItem()
            assertThat(first).isNotNull()
            assertThat(first!!.isLinked).isFalse()
            linkStore.deviceIdOrCreate()
            linkStore.displayNameOrCreate(DISPLAY_NAME)
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test fun `a throwing upstream completes and re-collection restarts it`() = runTest {
        // The store's catch shape in isolation: an upstream that
        // emits once and then throws completes after the catch's
        // null — and a fresh collection restarts the cold flow.
        val viewModelScope = viewModelScope()
        val flaky = flow<ClientLink?> {
            emit(ClientLink(deviceId = "d", displayName = DISPLAY_NAME))
            throw IOException("boom")
        }.catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        flaky.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            val unlinked = awaitItem()
            assertThat(unlinked).isNotNull()
            assertThat(unlinked!!.isLinked).isFalse()
            assertThat(awaitItem()).isNull()
        }
        advanceTimeBy(6_000)
        advanceUntilIdle()
        flaky.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            val again = awaitItem()
            assertThat(again).isNotNull()
            assertThat(again!!.isLinked).isFalse()
            assertThat(awaitItem()).isNull()
        }
    }

    @Test fun `snapshot and the flow agree on a populated store`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        // The service's snapshot() and the screen's flow read the
        // same store: both must see the link.
        assertThat(linkStore.snapshot()?.isLinked).isTrue()
        val viewModelScope = viewModelScope()
        val link = linkStore.link
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        link.test {
            assertThat(awaitItem()).isNull()
            advanceUntilIdle()
            assertThat(awaitItem()?.isLinked).isTrue()
        }
    }

    @Test fun `setHub persists the hub record to the preferences file`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        advanceUntilIdle()
        // The raw file must hold the hub record: a fresh process
        // reads the link from disk, not from memory.
        val bytes = prefsFile().readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertThat(text).contains(HUB_ID)
        assertThat(text).contains(HUB_NAME)
        assertThat(text).contains(HUB_HOST)
    }

    // -- Tunnel address + last-working (VPN dial) -------------------

    @Test fun `noteTunnelIp stores the tunnel address`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        linkStore.noteTunnelIp(TUNNEL_IP)
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubTunnelIp).isEqualTo(TUNNEL_IP)
    }

    @Test fun `a null or blank tunnel report never forgets the stored address`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        linkStore.noteTunnelIp(TUNNEL_IP)
        linkStore.noteTunnelIp(null)
        linkStore.noteTunnelIp("   ")
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubTunnelIp).isEqualTo(TUNNEL_IP)
    }

    @Test fun `noteTunnelIp writes nothing before a hub is linked`() = runTest {
        val linkStore = linkStore()
        populateIdentityOnly(linkStore)
        linkStore.noteTunnelIp(TUNNEL_IP)
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubTunnelIp).isNull()
    }

    @Test fun `noteConnected records a tunnel win without touching the lan host`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        linkStore.noteConnected(HUB_ID, HUB_NAME, AddressKind.TUNNEL, TUNNEL_IP, HUB_PORT, NOW_MS)
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubTunnelIp).isEqualTo(TUNNEL_IP)
        assertThat(link.hubHost).isEqualTo(HUB_HOST)
        assertThat(link.lastWorking).isEqualTo(AddressKind.TUNNEL)
    }

    @Test fun `noteConnected records a lan win without forgetting the tunnel address`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        linkStore.noteTunnelIp(TUNNEL_IP)
        linkStore.noteConnected(HUB_ID, HUB_NAME, AddressKind.LAN, "192.168.1.9", HUB_PORT, NOW_MS)
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubHost).isEqualTo("192.168.1.9")
        assertThat(link.hubTunnelIp).isEqualTo(TUNNEL_IP)
        assertThat(link.lastWorking).isEqualTo(AddressKind.LAN)
    }

    @Test fun `noteConnected to a different hub clears the old hub addresses`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        linkStore.noteTunnelIp(TUNNEL_IP)
        linkStore.noteConnected("hub-999", "Other", AddressKind.TUNNEL, "10.7.0.2", HUB_PORT, NOW_MS)
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubId).isEqualTo("hub-999")
        assertThat(link.hubHost).isNull()
        assertThat(link.hubTunnelIp).isEqualTo("10.7.0.2")
        assertThat(link.lastWorking).isEqualTo(AddressKind.TUNNEL)
        assertThat(link.linkedAt).isEqualTo(NOW_MS)
    }

    @Test fun `clearHub removes the tunnel fields too`() = runTest {
        val linkStore = linkStore()
        populateFullLink(linkStore)
        linkStore.noteTunnelIp(TUNNEL_IP)
        linkStore.noteConnected(HUB_ID, HUB_NAME, AddressKind.TUNNEL, TUNNEL_IP, HUB_PORT, NOW_MS)
        linkStore.clearHub()
        val link = linkStore.snapshot()
        assertThat(link).isNotNull()
        assertThat(link!!.hubTunnelIp).isNull()
        assertThat(link.lastWorking).isNull()
        assertThat(link.hubId).isNull()
    }

    // NOTE (2026-11-17): a fresh DataStore instance on the same file
    // while the first is still alive is NOT tested here — DataStore
    // 1.3 throws IllegalStateException ("There are multiple
    // DataStores active for the same file") by design, and the
    // store's catch turns that into a null emission. Production has
    // exactly one instance (Hilt @Singleton + the singleton
    // preferencesDataStore delegate, verified from the 1.3.0 jar),
    // so the two-instance case cannot occur there. Disk persistence
    // across processes is covered by the byte-level test above
    // instead (the file holds the hub record after setHub).

    private fun TestScope.viewModelScope(): TestScope {
        // The viewModelScope stand-in: a separate TestScope on the
        // same scheduler, so advanceUntilIdle drives the stateIn
        // sharing coroutine (backgroundScope collectors are NOT
        // driven by it in this coroutines-test version) without
        // runTest waiting for it at teardown.
        val scope = TestScope(testScheduler)
        coroutineContext[Job]?.invokeOnCompletion { scope.cancel() }
        return scope
    }

    private fun TestScope.linkStore(): ClientLinkStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
        ) { prefsFile() }
        return ClientLinkStore(dataStore)
    }

    private fun prefsFile(): File = File(tempDir, "client_link.preferences_pb")

    /** Identity + hub, written through the store's public API (the real write path). */
    private suspend fun populateFullLink(linkStore: ClientLinkStore) {
        populateIdentityOnly(linkStore)
        linkStore.setHub(HUB_ID, HUB_NAME, HUB_HOST, HUB_PORT, NOW_MS)
    }

    /** Identity only (the pre-link state): device id + display name, no hub. */
    private suspend fun populateIdentityOnly(linkStore: ClientLinkStore) {
        linkStore.deviceIdOrCreate()
        linkStore.displayNameOrCreate(DISPLAY_NAME)
    }

    companion object {
        private const val DISPLAY_NAME = "SM-T720"
        private const val HUB_ID = "hub-123"
        private const val HUB_NAME = "Relay"
        private const val HUB_HOST = "192.168.1.2"
        private const val HUB_PORT = 47831
        private const val TUNNEL_IP = "10.6.0.2"
        private const val NOW_MS = 1_792_000_000_000L
    }
}
