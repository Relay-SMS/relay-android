package com.filestech.sms.e2e

/**
 * End-to-end encryption for Relay-to-Relay 1:1 texts (design §1.4).
 *
 * This interface is the ONLY seam the rest of the app touches for crypto:
 * the real implementation ([VodozemacCrypto]) is the only file that imports
 * the Trixnity/vodozemac types, and the tests use [FakeE2eCrypto]
 * (deterministic, reversible, no native code) so JVM unit tests never load
 * the native library.
 *
 * A session is an Olm double-ratchet session with one peer. Sessions are
 * held in memory by the implementation, keyed by their vodozemac session
 * id; the storage layer persists them with [pickleSession] /
 * [unpickleSession] and the identity (the Account) with [pickleAccount] /
 * [unpickleAccount]. The passphrase is the Keystore-wrapped pickle key —
 * never logged, never stored in the clear.
 *
 * Failure contract: crypto operations throw [E2eCryptoException] when the
 * operation itself fails (unknown session, bad pickle, undecryptable
 * message). Input validation failures (a key that is not 32 bytes) throw
 * [IllegalArgumentException] via `require`.
 */
interface E2eCrypto {

    /** Our public identity keys (Curve25519 + Ed25519), 32 bytes each. */
    fun identityKeys(): IdentityKeys

    /** One unpublished one-time public key (32 bytes), or null if the pool is empty. */
    fun nextOneTimeKey(): ByteArray?

    /** Top the one-time-key pool back up to [ONE_TIME_KEY_POOL_SIZE]. */
    fun generateOneTimeKeys()

    /** Create an outbound session from a peer's bundle; returns the session id. */
    fun createOutboundSession(theirCurveKey: ByteArray, theirOneTimeKey: ByteArray): String

    /** Create an inbound session from a pre-key message; returns (session id, plaintext). */
    fun createInboundSession(preKeyMessage: ByteArray): InboundResult

    /** Encrypt with a session. The first message on a fresh session is a pre-key message. */
    fun encrypt(sessionId: String, plaintext: ByteArray): EncryptedMessage

    /** Decrypt with a session. The message type selects the pre-key / normal path. */
    fun decrypt(sessionId: String, message: EncryptedMessage): ByteArray

    /** Whether a live (in-memory) session exists for this id. */
    fun hasSession(sessionId: String): Boolean

    /** Serialize the Account (identity + one-time-key pool) as an encrypted pickle. */
    fun pickleAccount(passphrase: ByteArray): String

    /** Replace the current Account with the one stored in this pickle. */
    fun unpickleAccount(pickle: String, passphrase: ByteArray)

    /** Serialize one session as an encrypted pickle. */
    fun pickleSession(sessionId: String, passphrase: ByteArray): String

    /** Restore a session from a pickle; returns its session id. */
    fun unpickleSession(pickle: String, passphrase: ByteArray): String
}

/** Number of unpublished one-time keys the pool holds (design §4.1). */
const val ONE_TIME_KEY_POOL_SIZE: Int = 10

/** Length in bytes of every Curve25519 / Ed25519 public key in the protocol. */
const val E2E_KEY_LENGTH: Int = 32

/** A crypto failure: unknown session, bad pickle, undecryptable message. */
class E2eCryptoException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Our public identity keys. Plain class (not a data class): data-class
 * equality on [ByteArray] is referential, which would silently break
 * key-change comparisons.
 */
class IdentityKeys(val curve25519: ByteArray, val ed25519: ByteArray) {

    init {
        require(curve25519.size == E2E_KEY_LENGTH) { "curve25519 key must be 32 bytes" }
        require(ed25519.size == E2E_KEY_LENGTH) { "ed25519 key must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKeys) return false
        return curve25519.contentEquals(other.curve25519) &&
            ed25519.contentEquals(other.ed25519)
    }

    override fun hashCode(): Int {
        var result = curve25519.contentHashCode()
        result = 31 * result + ed25519.contentHashCode()
        return result
    }

    // Never print key material: the default toString would print array
    // identities, which is harmless, but an explicit override keeps it so.
    override fun toString(): String = "IdentityKeys(<32-byte curve25519>, <32-byte ed25519>)"
}

/** The result of creating an inbound session: the new session id + the decrypted first message. */
class InboundResult(val sessionId: String, val plaintext: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboundResult) return false
        return sessionId == other.sessionId && plaintext.contentEquals(other.plaintext)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + plaintext.contentHashCode()
        return result
    }

    override fun toString(): String = "InboundResult(sessionId=$sessionId, plaintext=<${plaintext.size} bytes>)"
}

/** The two Olm message kinds. A pre-key message carries the session-establishment keys. */
enum class OlmMessageType {
    PRE_KEY,
    NORMAL,
}

/** An encrypted message: its Olm type plus the serialized Olm message bytes. */
class EncryptedMessage(val type: OlmMessageType, val bytes: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedMessage) return false
        return type == other.type && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String = "EncryptedMessage(type=$type, bytes=<${bytes.size} bytes>)"
}
