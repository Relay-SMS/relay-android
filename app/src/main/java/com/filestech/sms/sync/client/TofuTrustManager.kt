package com.filestech.sms.sync.client

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Client mode — the TOFU pin files: the raw 32-byte SHA-256 of a
 * hub's certificate, next to the device key (the desktop pattern).
 * One file per hub, `hub_fingerprint_<hub-id>.bin`: with two hubs
 * on one network a single pin deadlocks pairing (the first hub's
 * handshake pins its cert, the second hub then hard-fails TLS).
 * The legacy single `hub_fingerprint.bin` is still the fallback
 * when the hub id is unknown — an old phone whose mDNS record
 * carries none — and is no longer read for a hub with a known id
 * (its first connect after the upgrade re-pins via TOFU).
 *
 * Separate from the hub's own TLS identity (Keystore alias
 * `relay-sync-tls` / `sync-tls-*.der` files), so the two roles can
 * never collide in one install.
 *
 * Unlink (and a mode switch) clears only that hub's pin — the
 * "forget hub" affordance. Other hubs' pins survive, so re-pairing
 * to a hub this device already trusted does not re-pin.
 *
 * Pure file I/O: unit-tested on the JVM against a temp directory.
 */
class TofuPinStore(
    /** `filesDir/sync-client/` in production; a temp dir in tests. */
    private val clientDir: File,
) {

    private fun pinFile(hubId: String): File = File(clientDir, pinFileName(hubId))

    /**
     * The pinned fingerprint for [hubId], or null when nothing is
     * pinned yet for that hub (or the stored pin is malformed — a
     * wrong-length file reads as "not pinned", and the next connect
     * re-pins honestly). Another hub's pin never applies here.
     */
    fun load(hubId: String): ByteArray? {
        val file = pinFile(hubId)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { it.size == FINGERPRINT_BYTES }
    }

    /**
     * Persists [fingerprint] (32 bytes) for [hubId]. Throws on
     * failure — the caller ([TofuTrustManager]) fails the handshake
     * rather than continuing unpinned.
     */
    fun store(hubId: String, fingerprint: ByteArray) {
        require(fingerprint.size == FINGERPRINT_BYTES) {
            "TofuPinStore: fingerprint must be $FINGERPRINT_BYTES bytes"
        }
        clientDir.mkdirs()
        val pinFile = pinFile(hubId)
        val tmp = File(clientDir, pinFileName(hubId) + TMP_SUFFIX)
        tmp.writeBytes(fingerprint)
        if (!tmp.renameTo(pinFile)) {
            tmp.delete()
            error("TofuPinStore: could not move the pin into place")
        }
    }

    /** Deletes [hubId]'s pin (Unlink / mode switch). Never throws. */
    fun clear(hubId: String) {
        runCatching { pinFile(hubId).delete() }
    }

    companion object {
        /**
         * The pre-multi-hub single pin file. No longer written for
         * hubs with a known id — kept as the fallback when the hub
         * id is unknown.
         */
        const val PIN_FILE_NAME = "hub_fingerprint.bin"

        /** Per-hub pin files are `hub_fingerprint_<hub-id>.bin`. */
        private const val PIN_FILE_PREFIX = "hub_fingerprint_"
        private const val PIN_FILE_SUFFIX = ".bin"

        private const val TMP_SUFFIX = ".tmp"

        /** A SHA-256 fingerprint is 32 bytes. */
        const val FINGERPRINT_BYTES = 32

        /**
         * Pin file name for one hub. The id is a UUID from the
         * phone, but it arrives over mDNS / the stored link, so it
         * is sanitized for the path (anything outside
         * `[A-Za-z0-9_-]` becomes `_`) — a hostile id must never
         * escape the client dir (`..`, `/`, `\` all collapse). An
         * empty id (an old phone whose mDNS record carries none)
         * falls back to the legacy single file, the pre-multi-hub
         * behavior: without an id there is nothing to key the pin
         * by. (The desktop's `fingerprint_file_name`, same rules.)
         */
        fun pinFileName(hubId: String): String {
            if (hubId.isEmpty()) return PIN_FILE_NAME
            val safe = hubId.map { c ->
                if (isAsciiLetterOrDigit(c) || c == '-' || c == '_') c else '_'
            }.joinToString("")
            return "$PIN_FILE_PREFIX$safe$PIN_FILE_SUFFIX"
        }

        /** ASCII letters and digits — the only letters a pin file name keeps. */
        private fun isAsciiLetterOrDigit(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    }
}

/**
 * Client mode — trust-on-first-use for the hub's self-signed TLS
 * certificate: the SHA-256 of the DER bytes of the presented
 * end-entity certificate is compared to the pinned fingerprint
 * for [hubId] — the expected hub, from the mDNS TXT record or the
 * stored link (empty when unknown, an old phone whose record
 * carries none). One trust manager per attempt, so a mismatch
 * fails that hub only and the auto-link loop can try the next.
 *
 * - First connect (no pin stored for this hub): pin + persist. A
 *   persistence failure **fails the handshake** rather than
 *   continuing unpinned (the desktop rule).
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
    private val hubId: String,
) : X509TrustManager {

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client auth is not used")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val presented = chain.firstOrNull()
            ?: throw CertificateException("no certificate presented")
        val fingerprint = sha256(presented.encoded)
        val pinned = pinStore.load(hubId)
        if (pinned == null) {
            runCatching { pinStore.store(hubId, fingerprint) }.onFailure { t ->
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
