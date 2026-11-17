package com.filestech.sms.sync.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.filestech.sms.sync.DeviceKind
import com.filestech.sms.sync.Ed25519
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * Client mode — the device identity: a stable UUID + Ed25519 keypair
 * whose 32-byte seed lives in a file next to the TOFU pin. The
 * identity survives process restarts (a fresh store on the same
 * directory reads the same key); a corrupt seed fails honestly and
 * is never silently regenerated.
 */
class ClientIdentityStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test fun `identity is stable across calls`() = runTest {
        val store = identityStore()
        val first = store.identity()
        val second = store.identity()
        assertThat(second.deviceId).isEqualTo(first.deviceId)
        assertThat(second.publicKeyBase64Url).isEqualTo(first.publicKeyBase64Url)
        assertThat(second.displayName).isEqualTo(first.displayName)
    }

    @Test fun `identity survives a fresh store on the same directory`() = runTest {
        // One link store (one DataStore) for both identity stores:
        // two DataStore instances on the same file is unsupported,
        // and in production the link store is a singleton — the
        // fresh store under test is the identity store, whose
        // persistence is the seed file.
        val sharedLinkStore = linkStore()
        val first = ClientIdentityStore(clientDir(), sharedLinkStore, "Pixel 8").identity()
        val second = ClientIdentityStore(clientDir(), sharedLinkStore, "Pixel 8").identity()
        assertThat(second.deviceId).isEqualTo(first.deviceId)
        assertThat(second.publicKeyBase64Url).isEqualTo(first.publicKeyBase64Url)
    }

    @Test fun `device id is a uuid and the display name defaults to the model`() = runTest {
        val identity = identityStore().identity()
        assertThat(UUID.fromString(identity.deviceId).toString()).isEqualTo(identity.deviceId)
        assertThat(identity.displayName).isEqualTo("Pixel 8")
    }

    @Test fun `the seed file holds 32 bytes`() = runTest {
        identityStore().identity()
        val seed = File(clientDir(), ClientIdentityStore.KEY_FILE_NAME).readBytes()
        assertThat(seed.size).isEqualTo(32)
    }

    @Test fun `sign produces a 64 byte signature that verifies`() = runTest {
        val store = identityStore()
        val identity = store.identity()
        val message = ByteArray(32) { it.toByte() }
        val signature = store.sign(message)
        assertThat(signature.size).isEqualTo(64)
        val publicKey = Base64Url.decode(identity.publicKeyBase64Url)
        assertThat(publicKey).isNotNull()
        assertThat(Ed25519.verify(publicKey!!, message, signature)).isTrue()
    }

    @Test fun `sign is deterministic`() = runTest {
        val store = identityStore()
        val message = "challenge".toByteArray()
        assertThat(store.sign(message)).isEqualTo(store.sign(message))
    }

    @Test fun `a corrupt seed fails honestly and is never regenerated`() = runTest {
        val keyFile = File(clientDir(), ClientIdentityStore.KEY_FILE_NAME)
        keyFile.parentFile?.mkdirs()
        val corrupt = ByteArray(16) { 3 }
        keyFile.writeBytes(corrupt)
        // Same shared link store as the fresh-store test: one
        // DataStore per file (see above).
        val sharedLinkStore = linkStore()
        val thrown = runCatching {
            ClientIdentityStore(clientDir(), sharedLinkStore, "Pixel 8").identity()
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown?.message).contains("device key")
        assertThat(keyFile.readBytes()).isEqualTo(corrupt)
        val thrownAgain = runCatching {
            ClientIdentityStore(clientDir(), sharedLinkStore, "Pixel 8").identity()
        }.exceptionOrNull()
        assertThat(thrownAgain).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrownAgain?.message).contains("device key")
    }

    @Test fun `device kind is a tablet at 600dp and a phone below`() {
        assertThat(deviceKindFor(599)).isEqualTo(DeviceKind.PHONE)
        assertThat(deviceKindFor(600)).isEqualTo(DeviceKind.TABLET)
        assertThat(deviceKindFor(840)).isEqualTo(DeviceKind.TABLET)
        assertThat(deviceKindFor(0)).isEqualTo(DeviceKind.PHONE)
    }

    private fun clientDir(): File = File(tempDir, "sync-client")

    private fun TestScope.linkStore(): ClientLinkStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
        ) { File(tempDir, "client_link.preferences_pb") }
        return ClientLinkStore(dataStore)
    }

    private fun TestScope.identityStore(): ClientIdentityStore =
        ClientIdentityStore(clientDir(), linkStore(), "Pixel 8")
}
