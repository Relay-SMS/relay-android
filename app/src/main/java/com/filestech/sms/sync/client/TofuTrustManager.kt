package com.filestech.sms.sync.client

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Client mode — the TOFU pin file: the raw 32-byte SHA-256 of the
 * hub's certificate, next to the device key (the desktop pattern).
 * Separate from the hub's own TLS identity (Keystore alias
 * `relay-sync-tls` / `sync-tls-*.der` files), so the two roles can
 * never collide in one install.
 *
 * Unlink (and a mode switch) clears the pin — the "forget hub"
 * affordance. Without it, pairing a second hub later would hard-fail
 * on the stale pin, with no way out short of clearing app data.
 *
 * Pure file I/O: unit-tested on the JVM against a temp directory.
 */
class TofuPinStore(
    /** `filesDir/sync-client/` in production; a temp dir in tests. */
    private val clientDir: File,
) {

    private val pinFile: File get() = File(clientDir, PIN_FILE_NAME)

    /**
     * The pinned fingerprint, or null when nothing is pinned yet (or
     * the stored pin is malformed — a wrong-length file reads as
     * "not pinned", and the next connect re-pins honestly).
     */
    fun load(): ByteArray? {
        val file = pinFile
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { it.size == FINGERPRINT_BYTES }
    }

    /**
     * Persists [fingerprint] (32 bytes). Throws on failure — the
     * caller ([TofuTrustManager]) fails the handshake rather than
     * continuing unpinned.
     */
    fun store(fingerprint: ByteArray) {
        require(fingerprint.size == FINGERPRINT_BYTES) {
            "TofuPinStore: fingerprint must be $FINGERPRINT_BYTES bytes"
        }
        clientDir.mkdirs()
        val tmp = File(clientDir, PIN_FILE_NAME + TMP_SUFFIX)
        tmp.writeBytes(fingerprint)
        if (!tmp.renameTo(pinFile)) {
            tmp.delete()
            error("TofuPinStore: could not move the pin into place")
        }
    }

    /** Deletes the pin (Unlink / mode switch). Never throws. */
    fun clear() {
        runCatching { pinFile.delete() }
    }

    companion object {
        /** The raw 32-byte SHA-256 pin, next to the device key. */
        const val PIN_FILE_NAME = "hub_fingerprint.bin"

        private const val TMP_SUFFIX = ".tmp"

        /** A SHA-256 fingerprint is 32 bytes. */
        const val FINGERPRINT_BYTES = 32
    }
}

/**
 * Client mode — trust-on-first-use for the hub's self-signed TLS
 * certificate: the SHA-256 of the DER bytes of the presented
 * end-entity certificate is compared to the pinned fingerprint.
 *
 * - First connect (no pin stored): pin + persist. A persistence
 *   failure **fails the handshake** rather than continuing unpinned
 *   (the desktop rule).
 * - Later mismatch: hard refusal — the hub's certificate changed,
 *   which means a different hub or an attack; re-pair to trust it.
 * - Hostname verification is deliberately skipped (the transport's
 *   hostname verifier always passes): the fingerprint IS the
 *   identity check. TLS 1.2 minimum comes from OkHttp's default
 *   connection spec. There is no unencrypted fallback.
 *
 * `getAcceptedIssuers` returns empty: OkHttp never chain-cleans a
 * connection (the cleaner runs only for certificate pinning, which
 * this client does not configure) — the handshake's trust decision
 * is made here, in `checkServerTrusted`, by the SSL engine itself.
 */
class TofuTrustManager(
    private val pinStore: TofuPinStore,
) : X509TrustManager {

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client auth is not used")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val presented = chain.firstOrNull()
            ?: throw CertificateException("no certificate presented")
        val fingerprint = sha256(presented.encoded)
        val pinned = pinStore.load()
        if (pinned == null) {
            runCatching { pinStore.store(fingerprint) }.onFailure { t ->
                throw CertificateException("could not persist the hub fingerprint", t)
            }
            return
        }
        if (!pinned.contentEquals(fingerprint)) {
            throw CertificateException(
                "hub TLS certificate changed: its fingerprint does not match the pinned one",
            )
        }
    }

    companion object {
        /** SHA-256 of the DER bytes — the fingerprint compared to the pin. */
        fun sha256(der: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(der)
    }
}
