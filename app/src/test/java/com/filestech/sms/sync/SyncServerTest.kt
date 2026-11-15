package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.KeyStore

/**
 * The server's default-SMS re-check: [SyncServer.refreshHubStatus]
 * is what drives the desktop's warning banner — called instantly
 * from the default-SMS-change broadcast, and every 30 s by the
 * watchdog as a safety net. The broadcast itself cannot be tested
 * on the JVM (no system to send it), so these tests pin the
 * re-check/broadcast logic: broadcast on a change, silence when
 * nothing changed. The other three constructor dependencies are
 * never touched by the re-check, so relaxed mocks stand in for
 * them.
 *
 * Plus the start-failure diagnostic (§6 of the release
 * investigation): a failed start records its step + exception in
 * the store (and still rethrows, so the service stops itself), and
 * a successful start clears the recorded error.
 */
class SyncServerTest {

    @Test fun `refreshHubStatus broadcasts the first time it is checked`() = runTest {
        val source = FakeSyncMessageSource()
        val server = spyk(serverWith(source))
        server.refreshHubStatus()
        coVerify(exactly = 1) { server.broadcastHubStatus(true) }
    }

    @Test fun `refreshHubStatus stays silent when the role did not change`() = runTest {
        val source = FakeSyncMessageSource()
        val server = spyk(serverWith(source))
        server.refreshHubStatus()
        server.refreshHubStatus()
        coVerify(exactly = 1) { server.broadcastHubStatus(true) }
    }

    @Test fun `refreshHubStatus broadcasts again when the role flips`() = runTest {
        val source = FakeSyncMessageSource()
        val server = spyk(serverWith(source))
        server.refreshHubStatus()
        source.isDefaultSms = false
        server.refreshHubStatus()
        coVerify(exactly = 1) { server.broadcastHubStatus(true) }
        coVerify(exactly = 1) { server.broadcastHubStatus(false) }
    }

    @Test fun `a failed identity step records the step and exception`() = runTest {
        val store = mockk<LinkedDeviceStore>(relaxed = true)
        coEvery { store.hubId() } returns "test-hub"
        val failure = IllegalStateException("no identity")
        val tlsProvider = mockk<TlsCertificateProvider>()
        coEvery { tlsProvider.ensureIdentity(any()) } throws failure
        val server = SyncServer(
            store = store,
            tlsProvider = tlsProvider,
            mdnsAdvertiser = mockk(),
            messageSource = FakeSyncMessageSource(),
        )
        val thrown = runCatching { server.start() }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(failure)
        coVerify { store.recordStartError("identity", failure) }
    }

    @Test fun `a successful start clears a previously recorded error`() = runTest {
        val store = mockk<LinkedDeviceStore>(relaxed = true)
        coEvery { store.hubId() } returns "test-hub"
        val identity = testIdentity()
        val tlsProvider = mockk<TlsCertificateProvider>()
        coEvery { tlsProvider.ensureIdentity(any()) } returns TlsIdentity(
            identity.keyStore,
            identity.alias,
            identity.password,
            identity.password,
        )
        val server = SyncServer(
            store = store,
            tlsProvider = tlsProvider,
            mdnsAdvertiser = mockk(relaxed = true),
            messageSource = FakeSyncMessageSource(),
        )
        try {
            server.start()
            assertThat(server.running.value).isTrue()
        } finally {
            runCatching { server.stop() }
        }
        coVerify { store.clearStartError() }
    }

    private fun serverWith(source: FakeSyncMessageSource): SyncServer = SyncServer(
        store = mockk(),
        tlsProvider = mockk(),
        mdnsAdvertiser = mockk(),
        messageSource = source,
    )

    /** A real TLS identity (Netty's self-signed certificate), as in [SyncEngineTlsTest]. */
    private fun testIdentity(): TestIdentity {
        val cert = SelfSignedCertificate.builder().build()
        val password = "test".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(ALIAS, cert.key(), password, arrayOf(cert.cert()))
        }
        return TestIdentity(keyStore, ALIAS, password)
    }

    private data class TestIdentity(
        val keyStore: KeyStore,
        val alias: String,
        val password: CharArray,
    )

    companion object {
        private const val ALIAS = "test"
    }
}
