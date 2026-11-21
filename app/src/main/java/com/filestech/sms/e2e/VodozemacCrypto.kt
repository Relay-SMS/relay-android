package com.filestech.sms.e2e

import de.connect2x.trixnity.vodozemac.Curve25519PublicKey
import de.connect2x.trixnity.vodozemac.PickleKey
import de.connect2x.trixnity.vodozemac.olm.Account
import de.connect2x.trixnity.vodozemac.olm.OlmMessage
import de.connect2x.trixnity.vodozemac.olm.Session

/**
 * The real [E2eCrypto] implementation over vodozemac (via Trixnity
 * `trixnity-vodozemac-android:5.11.0`, embedding vodozemac 0.11.2).
 * This is the ONLY file that imports Trixnity types — everything else
 * talks to the [E2eCrypto] interface, so the library can be swapped
 * (design §1.3) by replacing this one file.
 *
 * Lifecycle: [Account] and [Session] are native-handle objects
 * (`Managed` — AutoCloseable, backed by a Cleaner). This class owns
 * them: it closes the Account and every cached Session on [close] and
 * when an account is swapped in via [unpickleAccount]. Short-lived key
 * wrappers are closed as soon as their bytes are extracted. Parsed
 * [OlmMessage] objects are deliberately left to the Cleaner — the
 * wrapper types expose no `close()`, and closing their internals by
 * hand risks freeing a default/empty handle, so GC + Cleaner is the
 * safe path for those short-lived objects.
 *
 * Threading: the native objects are not thread-safe, so every public
 * method serializes on one lock. Crypto calls are short (sub-ms for a
 * text message), so a single lock is fine.
 *
 * One-time-key pool: handing a key out via [nextOneTimeKey] marks the
 * whole unpublished batch published (vodozemac has no per-key publish),
 * so the caller tops the pool back up with [generateOneTimeKeys] right
 * after building a bundle reply. The unpublished keys that were never
 * handed out are simply never used — wasted key material, not a
 * correctness problem.
 *
 * This class must never be instantiated in a plain-JVM unit test: the
 * first native call loads `libvodozemac.so`, which does not exist on
 * the JVM. Tests use [FakeE2eCrypto]; the real implementation is
 * covered by instrumented tests (CI, emulator) and device checks.
 */
class VodozemacCrypto private constructor(
    private var account: Account,
) : E2eCrypto, AutoCloseable {

    private val lock: Any = Any()
    private val sessions: MutableMap<String, Session> = mutableMapOf()
    private var closed: Boolean = false

    companion object {

        /** Create a wrapper around a brand-new Account (fresh identity). */
        fun createNew(): VodozemacCrypto = VodozemacCrypto(Account())

        /** Create a wrapper around the Account stored in this pickle. */
        fun fromPickle(pickle: String, passphrase: ByteArray): VodozemacCrypto {
            val loaded = runCatching { Account.fromPickle(pickle, PickleKey(passphrase)) }
                .getOrElse { throw E2eCryptoException("cannot unpickle account", it) }
            return VodozemacCrypto(loaded)
        }
    }

    override fun identityKeys(): IdentityKeys = synchronized(lock) {
        requireOpen()
        catchingNative("read identity keys") {
            account.identityKeys.use { keys ->
                IdentityKeys(curve25519 = keys.curve25519.bytes, ed25519 = keys.ed25519.bytes)
            }
        }
    }

    override fun nextOneTimeKey(): ByteArray? = synchronized(lock) {
        requireOpen()
        catchingNative("take a one-time key") {
            val unpublished = account.oneTimeKeys
            val keyBytes = unpublished.values.firstOrNull()?.bytes
            unpublished.values.forEach { it.close() }
            if (keyBytes != null) account.markKeysAsPublished()
            keyBytes
        }
    }

    override fun generateOneTimeKeys() {
        synchronized(lock) {
            requireOpen()
            catchingNative("generate one-time keys") {
                val unpublished = account.oneTimeKeys
                val missing = ONE_TIME_KEY_POOL_SIZE - unpublished.size
                unpublished.values.forEach { it.close() }
                if (missing > 0) account.generateOneTimeKeys(missing)
            }
        }
    }

    override fun createOutboundSession(theirCurveKey: ByteArray, theirOneTimeKey: ByteArray): String {
        require(theirCurveKey.size == E2E_KEY_LENGTH) { "their curve key must be 32 bytes" }
        require(theirOneTimeKey.size == E2E_KEY_LENGTH) { "their one-time key must be 32 bytes" }
        return synchronized(lock) {
            requireOpen()
            catchingNative("create outbound session") {
                Curve25519PublicKey(theirCurveKey).use { curve ->
                    Curve25519PublicKey(theirOneTimeKey).use { oneTime ->
                        val session = account.createOutboundSession(curve, oneTime)
                        sessions[session.sessionId] = session
                        session.sessionId
                    }
                }
            }
        }
    }

    override fun createInboundSession(preKeyMessage: ByteArray): InboundResult {
        require(preKeyMessage.isNotEmpty()) { "pre-key message must not be empty" }
        return synchronized(lock) {
            requireOpen()
            catchingNative("create inbound session") {
                val preKey = OlmMessage.PreKey.Bytes(preKeyMessage)
                // The sender's identity key defaults to the one carried in
                // the pre-key message itself — exactly what the protocol wants.
                val result = account.createInboundSession(preKey)
                sessions[result.session.sessionId] = result.session
                InboundResult(
                    sessionId = result.session.sessionId,
                    plaintext = result.plaintext.value,
                )
            }
        }
    }

    override fun encrypt(sessionId: String, plaintext: ByteArray): EncryptedMessage =
        synchronized(lock) {
            requireOpen()
            catchingNative("encrypt") {
                val session = requireSession(sessionId)
                val encrypted = session.encrypt(plaintext)
                val type = if (encrypted is OlmMessage.PreKey) {
                    OlmMessageType.PRE_KEY
                } else {
                    OlmMessageType.NORMAL
                }
                EncryptedMessage(type = type, bytes = encrypted.bytes)
            }
        }

    override fun decrypt(sessionId: String, message: EncryptedMessage): ByteArray =
        synchronized(lock) {
            requireOpen()
            catchingNative("decrypt") {
                val session = requireSession(sessionId)
                val parsed = when (message.type) {
                    OlmMessageType.PRE_KEY -> OlmMessage.PreKey.Bytes(message.bytes)
                    OlmMessageType.NORMAL -> OlmMessage.Normal.Bytes(message.bytes)
                }
                session.decrypt(parsed)
            }
        }

    override fun hasSession(sessionId: String): Boolean = synchronized(lock) {
        !closed && sessions.containsKey(sessionId)
    }

    override fun pickleAccount(passphrase: ByteArray): String = synchronized(lock) {
        requireOpen()
        catchingNative("pickle account") { account.pickle(PickleKey(passphrase)) }
    }

    override fun unpickleAccount(pickle: String, passphrase: ByteArray) {
        synchronized(lock) {
            requireOpen()
            // Load first: if the pickle is bad, the current account (and
            // its sessions) stay untouched.
            val fresh = catchingNative("unpickle account") {
                Account.fromPickle(pickle, PickleKey(passphrase))
            }
            closeSessionsLocked()
            account.close()
            account = fresh
        }
    }

    override fun pickleSession(sessionId: String, passphrase: ByteArray): String =
        synchronized(lock) {
            requireOpen()
            catchingNative("pickle session") {
                requireSession(sessionId).pickle(PickleKey(passphrase))
            }
        }

    override fun unpickleSession(pickle: String, passphrase: ByteArray): String =
        synchronized(lock) {
            requireOpen()
            catchingNative("unpickle session") {
                val session = Session.fromPickle(pickle, PickleKey(passphrase))
                sessions[session.sessionId]?.close()
                sessions[session.sessionId] = session
                session.sessionId
            }
        }

    /**
     * Close the Account and every cached Session. Idempotent; the
     * instance must not be used afterwards (methods throw
     * [E2eCryptoException]).
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeSessionsLocked()
            account.close()
        }
    }

    private fun requireOpen() {
        if (closed) throw E2eCryptoException("VodozemacCrypto is closed")
    }

    private fun requireSession(sessionId: String): Session =
        sessions[sessionId] ?: throw E2eCryptoException("no live session for this id")

    private fun closeSessionsLocked() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private inline fun <T> catchingNative(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: E2eCryptoException) {
            throw e
        } catch (e: Exception) {
            throw E2eCryptoException("vodozemac failed to $what", e)
        }
}
