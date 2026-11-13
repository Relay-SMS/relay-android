package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * Slice 1 — Ed25519 verification (BouncyCastle lightweight API, so it
 * works below API 33 where `java.security` has no EdDSA). The keypair
 * and the signatures are generated with the same library the verifier
 * uses — the point is the *verify* path: a genuine signature passes,
 * anything else fails, and nothing throws.
 */
class Ed25519Test {

    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKey = privateKey.generatePublicKey()

    @Test fun `a genuine signature verifies`() {
        val message = "challenge-bytes".toByteArray()
        val signature = sign(privateKey, message)
        assertThat(Ed25519.verify(publicKey.encoded, message, signature)).isTrue()
    }

    @Test fun `a tampered message does not verify`() {
        val message = "challenge-bytes".toByteArray()
        val signature = sign(privateKey, message)
        val tampered = "challenge-byteS".toByteArray()
        assertThat(Ed25519.verify(publicKey.encoded, tampered, signature)).isFalse()
    }

    @Test fun `a tampered signature does not verify`() {
        val message = "challenge-bytes".toByteArray()
        val signature = sign(privateKey, message).copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThat(Ed25519.verify(publicKey.encoded, message, signature)).isFalse()
    }

    @Test fun `a signature from another key does not verify`() {
        val otherKey = Ed25519PrivateKeyParameters(SecureRandom())
        val message = "challenge-bytes".toByteArray()
        val signature = sign(otherKey, message)
        assertThat(Ed25519.verify(publicKey.encoded, message, signature)).isFalse()
    }

    @Test fun `a short public key returns false instead of throwing`() {
        val message = "challenge-bytes".toByteArray()
        val signature = sign(privateKey, message)
        assertThat(Ed25519.verify(ByteArray(31), message, signature)).isFalse()
    }

    @Test fun `a short signature returns false instead of throwing`() {
        val message = "challenge-bytes".toByteArray()
        assertThat(Ed25519.verify(publicKey.encoded, message, ByteArray(63))).isFalse()
    }

    @Test fun `all-zero key and signature return false instead of throwing`() {
        val message = "challenge-bytes".toByteArray()
        assertThat(Ed25519.verify(ByteArray(32), message, ByteArray(64))).isFalse()
    }

    companion object {
        /** Signs with BouncyCastle — the same primitive the desktop side uses. */
        fun sign(privateKey: Ed25519PrivateKeyParameters, message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }
    }
}
