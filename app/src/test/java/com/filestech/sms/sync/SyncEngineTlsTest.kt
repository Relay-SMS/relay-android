package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore

/**
 * Regression test for the 2026-11-13 crash: the sync server ran on
 * Ktor's CIO engine, which has no HTTPS support — it throws
 * UnsupportedOperationException on any `sslConnector`, in the engine's
 * own start job, so the throw escapes every `runCatching` around
 * `start()` and kills the app. This test boots the exact wiring
 * [SyncServer] uses (Netty engine + TLS-only connector + WebSockets)
 * and probes the port: with an engine that cannot serve TLS the
 * connect never succeeds and the test fails, instead of the app
 * crashing on the phone.
 *
 * Pure JVM (no Android APIs): the TLS identity is Netty's own
 * [SelfSignedCertificate], and the port is a free ephemeral one, so
 * the test binds nothing stable and leaves nothing behind.
 */
class SyncEngineTlsTest {

    @Test fun `the sync engine serves TLS on its port`() {
        val port = freePort()
        val identity = testIdentity()
        val server = embeddedServer(
            Netty,
            applicationEnvironment { },
            configure = {
                sslConnector(
                    identity.keyStore,
                    identity.alias,
                    { identity.password },
                    { identity.password },
                ) {
                    host = "127.0.0.1"
                    this.port = port
                    enabledProtocols = SyncProtocol.TLS_PROTOCOLS
                }
            },
        ) {
            install(WebSockets)
            routing {
                webSocket("/sync") { }
            }
        }
        try {
            server.start(wait = false)
            assertThat(waitForListening(port)).isTrue()
        } finally {
            runCatching { server.stop(500L, 2_000L) }
        }
    }

    /**
     * TCP-connect probe: a plain socket handshake proves the engine is
     * listening (no TLS handshake needed — the point is the port, and
     * a failed engine binds nothing). Polls because `start(wait =
     * false)` returns before the bind completes.
     */
    private fun waitForListening(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_CONNECT_MS)
                }
                return true
            } catch (_: IOException) {
                Thread.sleep(PROBE_INTERVAL_MS)
            }
        }
        return false
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val PROBE_INTERVAL_MS = 50L
        private const val PROBE_CONNECT_MS = 200
    }
}
