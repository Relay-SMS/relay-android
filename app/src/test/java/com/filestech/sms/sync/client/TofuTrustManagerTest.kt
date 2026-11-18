package com.filestech.sms.sync.client

import com.filestech.sms.sync.buildSelfSignedCertificate
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * Client mode — trust on first use: the first connect pins the hub
 * certificate's SHA-256 fingerprint and persists it; a later
 * different certificate is a hard refusal. Certificates are real
 * self-signed ones from the hub's own builder
 * ([buildSelfSignedCertificate]).
 *
 * Pins are per hub (`hub_fingerprint_<hub-id>.bin`): with two hubs
 * on one network a single pin deadlocked pairing (the first hub's
 * handshake pinned its cert, the second hub then hard-failed TLS).
 */
class TofuTrustManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test fun `pin store loads null when nothing is pinned`() {
        assertThat(TofuPinStore(tempDir).load(HUB_A)).isNull()
    }

    @Test fun `pin store round-trips a 32 byte fingerprint`() {
        val store = TofuPinStore(tempDir)
        val fingerprint = ByteArray(32) { it.toByte() }
        store.store(HUB_A, fingerprint)
        assertThat(store.load(HUB_A)).isEqualTo(fingerprint)
    }

    @Test fun `pin store rejects a wrong size fingerprint`() {
        val thrown = assertThrows<IllegalArgumentException> {
            TofuPinStore(tempDir).store(HUB_A, ByteArray(16))
        }
        assertThat(thrown.message).contains("32 bytes")
    }

    @Test fun `pin store reads a wrong size file as not pinned`() {
        File(tempDir, TofuPinStore.pinFileName(HUB_A)).writeBytes(ByteArray(16))
        assertThat(TofuPinStore(tempDir).load(HUB_A)).isNull()
    }

    @Test fun `pin store clear removes the pin`() {
        val store = TofuPinStore(tempDir)
        store.store(HUB_A, ByteArray(32) { 7 })
        store.clear(HUB_A)
        assertThat(store.load(HUB_A)).isNull()
        assertThat(File(tempDir, TofuPinStore.pinFileName(HUB_A)).exists()).isFalse()
    }

    @Test fun `pins are per hub`() {
        val store = TofuPinStore(tempDir)
        store.store(HUB_A, ByteArray(32) { 1 })
        store.store(HUB_B, ByteArray(32) { 2 })
        assertThat(store.load(HUB_A)).isEqualTo(ByteArray(32) { 1 })
        assertThat(store.load(HUB_B)).isEqualTo(ByteArray(32) { 2 })
        assertThat(store.load(HUB_C)).isNull()
    }

    @Test fun `clear removes only that hub's pin`() {
        val store = TofuPinStore(tempDir)
        store.store(HUB_A, ByteArray(32) { 1 })
        store.store(HUB_B, ByteArray(32) { 2 })
        store.clear(HUB_A)
        assertThat(store.load(HUB_A)).isNull()
        assertThat(store.load(HUB_B)).isEqualTo(ByteArray(32) { 2 })
    }

    @Test fun `pin file name passes a uuid through`() {
        assertThat(TofuPinStore.pinFileName("11111111-2222-3333-4444-555555555555"))
            .isEqualTo("hub_fingerprint_11111111-2222-3333-4444-555555555555.bin")
    }

    @Test fun `pin file name sanitizes a hostile id`() {
        val name = TofuPinStore.pinFileName("../../etc/passwd")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("\\")
        assertThat(name).doesNotContain("..")
    }

    @Test fun `an empty hub id falls back to the legacy file`() {
        assertThat(TofuPinStore.pinFileName("")).isEqualTo(TofuPinStore.PIN_FILE_NAME)
        val store = TofuPinStore(tempDir)
        val fingerprint = ByteArray(32) { 9 }
        store.store("", fingerprint)
        assertThat(File(tempDir, TofuPinStore.PIN_FILE_NAME).exists()).isTrue()
        assertThat(store.load("")).isEqualTo(fingerprint)
        // The legacy file is no longer read for a hub with a known
        // id — its first connect after the upgrade re-pins.
        assertThat(store.load(HUB_A)).isNull()
    }

    @Test fun `first connect pins and persists the certificate fingerprint`() {
        val store = TofuPinStore(tempDir)
        val trustManager = TofuTrustManager(store, HUB_A)
        val cert = selfSignedCert("hub-a")
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        assertThat(store.load(HUB_A)).isEqualTo(TofuTrustManager.sha256(cert.encoded))
    }

    @Test fun `second connect with the same certificate succeeds`() {
        val store = TofuPinStore(tempDir)
        val trustManager = TofuTrustManager(store, HUB_A)
        val cert = selfSignedCert("hub-a")
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        assertThat(store.load(HUB_A)).isEqualTo(TofuTrustManager.sha256(cert.encoded))
    }

    @Test fun `a different certificate after pinning is a hard refusal`() {
        val store = TofuPinStore(tempDir)
        val trustManager = TofuTrustManager(store, HUB_A)
        trustManager.checkServerTrusted(arrayOf(selfSignedCert("hub-a")), "RSA")
        val thrown = assertThrows<CertificateException> {
            trustManager.checkServerTrusted(arrayOf(selfSignedCert("hub-a")), "RSA")
        }
        assertThat(thrown.message).contains("fingerprint does not match")
    }

    @Test fun `two hubs pin different certificates independently`() {
        val store = TofuPinStore(tempDir)
        val certA = selfSignedCert("hub-a")
        val certB = selfSignedCert("hub-b")
        // The single-pin deadlock: hub A's handshake used to pin its
        // cert, and hub B then hard-failed TLS. Per-hub pins: both
        // pin, and both still verify afterwards.
        TofuTrustManager(store, HUB_A).checkServerTrusted(arrayOf(certA), "RSA")
        TofuTrustManager(store, HUB_B).checkServerTrusted(arrayOf(certB), "RSA")
        assertThat(store.load(HUB_A)).isEqualTo(TofuTrustManager.sha256(certA.encoded))
        assertThat(store.load(HUB_B)).isEqualTo(TofuTrustManager.sha256(certB.encoded))
        TofuTrustManager(store, HUB_A).checkServerTrusted(arrayOf(certA), "RSA")
        TofuTrustManager(store, HUB_B).checkServerTrusted(arrayOf(certB), "RSA")
    }

    @Test fun `a mismatch on one hub leaves the other hub pinned`() {
        val store = TofuPinStore(tempDir)
        val certA = selfSignedCert("hub-a")
        val certB = selfSignedCert("hub-b")
        TofuTrustManager(store, HUB_A).checkServerTrusted(arrayOf(certA), "RSA")
        TofuTrustManager(store, HUB_B).checkServerTrusted(arrayOf(certB), "RSA")
        val thrown = assertThrows<CertificateException> {
            TofuTrustManager(store, HUB_A).checkServerTrusted(arrayOf(selfSignedCert("hub-a")), "RSA")
        }
        assertThat(thrown.message).contains("fingerprint does not match")
        TofuTrustManager(store, HUB_B).checkServerTrusted(arrayOf(certB), "RSA")
        assertThat(store.load(HUB_B)).isEqualTo(TofuTrustManager.sha256(certB.encoded))
    }

    @Test fun `an empty chain is refused`() {
        val thrown = assertThrows<CertificateException> {
            TofuTrustManager(TofuPinStore(tempDir), HUB_A)
                .checkServerTrusted(emptyArray(), "RSA")
        }
        assertThat(thrown.message).contains("no certificate presented")
    }

    @Test fun `client auth is refused`() {
        val thrown = assertThrows<CertificateException> {
            TofuTrustManager(TofuPinStore(tempDir), HUB_A)
                .checkClientTrusted(arrayOf(selfSignedCert("hub-a")), "RSA")
        }
        assertThat(thrown.message).contains("client auth is not used")
    }

    @Test fun `accepted issuers is empty`() {
        val issuers = TofuTrustManager(TofuPinStore(tempDir), HUB_A).acceptedIssuers
        assertThat(issuers).isEmpty()
    }

    private fun selfSignedCert(hubId: String): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        return buildSelfSignedCertificate(keyPair, hubId, NOW_MS)
    }

    companion object {
        private const val NOW_MS = 1_791_000_000_000L
        private const val HUB_A = "hub-a"
        private const val HUB_B = "hub-b"
        private const val HUB_C = "hub-c"
    }
}
