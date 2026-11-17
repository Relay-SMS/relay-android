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
 */
class TofuTrustManagerTest {

    @TempDir
    lateinit var tempDir: File

    @Test fun `pin store loads null when nothing is pinned`() {
        assertThat(TofuPinStore(tempDir).load()).isNull()
    }

    @Test fun `pin store round-trips a 32 byte fingerprint`() {
        val store = TofuPinStore(tempDir)
        val fingerprint = ByteArray(32) { it.toByte() }
        store.store(fingerprint)
        assertThat(store.load()).isEqualTo(fingerprint)
    }

    @Test fun `pin store rejects a wrong size fingerprint`() {
        val thrown = assertThrows<IllegalArgumentException> {
            TofuPinStore(tempDir).store(ByteArray(16))
        }
        assertThat(thrown.message).contains("32 bytes")
    }

    @Test fun `pin store reads a wrong size file as not pinned`() {
        File(tempDir, TofuPinStore.PIN_FILE_NAME).writeBytes(ByteArray(16))
        assertThat(TofuPinStore(tempDir).load()).isNull()
    }

    @Test fun `pin store clear removes the pin`() {
        val store = TofuPinStore(tempDir)
        store.store(ByteArray(32) { 7 })
        store.clear()
        assertThat(store.load()).isNull()
        assertThat(File(tempDir, TofuPinStore.PIN_FILE_NAME).exists()).isFalse()
    }

    @Test fun `first connect pins and persists the certificate fingerprint`() {
        val store = TofuPinStore(tempDir)
        val trustManager = TofuTrustManager(store)
        val cert = selfSignedCert("hub-1")
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        assertThat(store.load()).isEqualTo(TofuTrustManager.sha256(cert.encoded))
    }

    @Test fun `second connect with the same certificate succeeds`() {
        val store = TofuPinStore(tempDir)
        val trustManager = TofuTrustManager(store)
        val cert = selfSignedCert("hub-1")
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        assertThat(store.load()).isEqualTo(TofuTrustManager.sha256(cert.encoded))
    }

    @Test fun `a different certificate after pinning is a hard refusal`() {
        val store = TofuPinStore(tempDir)
        val trustManager = TofuTrustManager(store)
        trustManager.checkServerTrusted(arrayOf(selfSignedCert("hub-1")), "RSA")
        val thrown = assertThrows<CertificateException> {
            trustManager.checkServerTrusted(arrayOf(selfSignedCert("hub-1")), "RSA")
        }
        assertThat(thrown.message).contains("fingerprint does not match")
    }

    @Test fun `an empty chain is refused`() {
        val thrown = assertThrows<CertificateException> {
            TofuTrustManager(TofuPinStore(tempDir))
                .checkServerTrusted(emptyArray(), "RSA")
        }
        assertThat(thrown.message).contains("no certificate presented")
    }

    @Test fun `client auth is refused`() {
        val thrown = assertThrows<CertificateException> {
            TofuTrustManager(TofuPinStore(tempDir))
                .checkClientTrusted(arrayOf(selfSignedCert("hub-1")), "RSA")
        }
        assertThat(thrown.message).contains("client auth is not used")
    }

    @Test fun `accepted issuers is empty`() {
        val issuers = TofuTrustManager(TofuPinStore(tempDir)).acceptedIssuers
        assertThat(issuers).isEmpty()
    }

    private fun selfSignedCert(hubId: String): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        return buildSelfSignedCertificate(keyPair, hubId, NOW_MS)
    }

    companion object {
        private const val NOW_MS = 1_791_000_000_000L
    }
}
