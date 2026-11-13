package com.filestech.sms.sync

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 signature verification for the challenge/response auth of
 * SYNC_PROTOCOL.md: the client signs the raw 32 challenge bytes with
 * its private key, and the phone verifies against the public key stored
 * at pairing time.
 *
 * BouncyCastle's lightweight API is used deliberately, not
 * `java.security.Signature("EdDSA")`: EdDSA only exists in the platform
 * from API 33, and this app's minSdk is 26. The lightweight signer
 * needs no provider registration, so it also sidesteps the platform's
 * own bundled BouncyCastle entirely — these classes load from the app.
 *
 * Standalone pure Kotlin + BouncyCastle: no Android imports, no app
 * imports. The phone only ever *verifies* in Slice 1 — it never signs,
 * so there is no signing helper here (tests sign with BouncyCastle
 * directly).
 */
object Ed25519 {

    /**
     * Verifies [signature] (64 bytes) over [message] against the
     * [publicKey] (32 bytes). Any malformed input — wrong lengths,
     * a bad key, a bad signature — is simply `false`, never an
     * exception: this runs on bytes from the network.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != SyncProtocol.PUBLIC_KEY_BYTES ||
            signature.size != SyncProtocol.SIGNATURE_BYTES
        ) {
            return false
        }
        return runCatching {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        }.getOrDefault(false)
    }
}
