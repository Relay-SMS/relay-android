package com.filestech.sms.e2e

import java.security.MessageDigest
import java.util.Base64

/**
 * A deterministic, reversible [E2eCrypto] for JVM unit tests. It loads
 * NO native code (the real implementation would load `libvodozemac.so`,
 * which does not exist on the JVM) and implements the same contract:
 * identity keys, a one-time-key pool, outbound/inbound sessions,
 * pre-key vs normal message types, and pickle round-trips with a
 * passphrase check.
 *
 * This is NOT encryption: the "ciphertext" is plaintext XORed with a
 * SHA-256 keystream derived from the session id and a message counter.
 * It exists only so the wire format, state machine, and send/receive
 * paths can be tested without a device. Two fakes with different seeds
 * interoperate (Alice encrypts, Bob creates an inbound session and
 * decrypts), like two real installs.
 *
 * Determinism: every key and session id derives from the [seed], so a
 * fresh fake with the same seed has the same identity. Tests use
 * distinct seeds per party ("alice" / "bob").
 */
class FakeE2eCrypto(private val seed: String = "default") : E2eCrypto {

    private data class FakeSession(
        val id: String,
        var counter: Long = 0,
        var isNew: Boolean = true,
    )

    private val sessions: MutableMap<String, FakeSession> = mutableMapOf()
    private var otkCounter: Long = 0
    private var unpublished: MutableList<ByteArray> = mutableListOf()

    init {
        topUpPool()
    }

    override fun identityKeys(): IdentityKeys = IdentityKeys(
        curve25519 = deriveKey("fake-curve-v1"),
        ed25519 = deriveKey("fake-ed-v1"),
    )

    override fun nextOneTimeKey(): ByteArray? {
        // Mirrors the real pool: handing a key out marks the whole
        // unpublished batch published, so the pool empties until the
        // caller tops it back up with generateOneTimeKeys().
        val key = unpublished.firstOrNull() ?: return null
        unpublished = mutableListOf()
        return key.copyOf()
    }

    override fun generateOneTimeKeys() {
        topUpPool()
    }

    override fun createOutboundSession(theirCurveKey: ByteArray, theirOneTimeKey: ByteArray): String {
        require(theirCurveKey.size == E2E_KEY_LENGTH) { "their curve key must be 32 bytes" }
        require(theirOneTimeKey.size == E2E_KEY_LENGTH) { "their one-time key must be 32 bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(theirCurveKey)
        digest.update(theirOneTimeKey)
        val id = "fake-" + digest.digest().toHex().substring(0, 16)
        sessions.getOrPut(id) { FakeSession(id) }
        return id
    }

    override fun createInboundSession(preKeyMessage: ByteArray): InboundResult {
        val parsed = parsePreKey(preKeyMessage)
            ?: throw E2eCryptoException("not a fake pre-key message")
        val session = FakeSession(id = parsed.sessionId, isNew = false)
        sessions[parsed.sessionId] = session
        val plaintext = xor(parsed.ciphertext, keystream(parsed.sessionId, session.counter))
        session.counter++
        return InboundResult(sessionId = parsed.sessionId, plaintext = plaintext)
    }

    override fun encrypt(sessionId: String, plaintext: ByteArray): EncryptedMessage {
        val session = requireSession(sessionId)
        val type = if (session.isNew) OlmMessageType.PRE_KEY else OlmMessageType.NORMAL
        session.isNew = false
        val ciphertext = xor(plaintext, keystream(sessionId, session.counter))
        session.counter++
        return if (type == OlmMessageType.PRE_KEY) {
            // The pre-key message carries the session id so the receiver
            // can rebuild the same session (the real pre-key message
            // carries the sender's identity + ephemeral keys instead).
            val header = (PRE_KEY_PREFIX + sessionId).toByteArray(Charsets.UTF_8) + SEPARATOR
            EncryptedMessage(type, header + ciphertext)
        } else {
            EncryptedMessage(type, ciphertext)
        }
    }

    override fun decrypt(sessionId: String, message: EncryptedMessage): ByteArray {
        val session = requireSession(sessionId)
        val ciphertext = when (message.type) {
            OlmMessageType.PRE_KEY -> {
                val parsed = parsePreKey(message.bytes)
                    ?: throw E2eCryptoException("not a fake pre-key message")
                if (parsed.sessionId != sessionId) {
                    throw E2eCryptoException("pre-key message is for another session")
                }
                parsed.ciphertext
            }
            OlmMessageType.NORMAL -> message.bytes
            // Round 49 — a GROUP envelope is a sender-key AEAD blob,
            // not an Olm message: the real implementation throws the
            // same way (see VodozemacCrypto.decrypt).
            OlmMessageType.GROUP -> throw E2eCryptoException(
                "a sender-key group envelope cannot be decrypted by vodozemac",
            )
        }
        val plaintext = xor(ciphertext, keystream(sessionId, session.counter))
        session.counter++
        return plaintext
    }

    override fun hasSession(sessionId: String): Boolean = sessions.containsKey(sessionId)

    override fun pickleAccount(passphrase: ByteArray): String {
        val check = passphraseCheck(passphrase)
        val keys = unpublished.joinToString(",") { Base64.getEncoder().encodeToString(it) }
        val seedB64 = Base64.getEncoder().encodeToString(seed.toByteArray(Charsets.UTF_8))
        return "$ACCOUNT_PICKLE_PREFIX:$seedB64:$otkCounter:$keys:$check"
    }

    override fun unpickleAccount(pickle: String, passphrase: ByteArray) {
        val parts = pickle.split(":")
        if (parts.size != 5 || parts[0] != ACCOUNT_PICKLE_PREFIX) {
            throw E2eCryptoException("not a fake account pickle")
        }
        if (parts[4] != passphraseCheck(passphrase)) {
            throw E2eCryptoException("wrong pickle passphrase")
        }
        val restoredSeed = Base64.getDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        val restoredCounter = parts[2].toLongOrNull()
            ?: throw E2eCryptoException("corrupt fake account pickle")
        val restoredKeys = if (parts[3].isEmpty()) {
            emptyList()
        } else {
            parts[3].split(",").map { Base64.getDecoder().decode(it) }
        }
        if (restoredSeed != seed) {
            throw E2eCryptoException("account pickle is for another identity")
        }
        otkCounter = restoredCounter
        unpublished = restoredKeys.toMutableList()
        sessions.clear()
    }

    override fun pickleSession(sessionId: String, passphrase: ByteArray): String {
        val session = requireSession(sessionId)
        val check = passphraseCheck(passphrase)
        return "$SESSION_PICKLE_PREFIX:${session.id}:${session.counter}:${session.isNew}:$check"
    }

    override fun unpickleSession(pickle: String, passphrase: ByteArray): String {
        val parts = pickle.split(":")
        if (parts.size != 5 || parts[0] != SESSION_PICKLE_PREFIX) {
            throw E2eCryptoException("not a fake session pickle")
        }
        if (parts[4] != passphraseCheck(passphrase)) {
            throw E2eCryptoException("wrong pickle passphrase")
        }
        val counter = parts[2].toLongOrNull()
            ?: throw E2eCryptoException("corrupt fake session pickle")
        val isNew = parts[3].toBooleanStrictOrNull()
            ?: throw E2eCryptoException("corrupt fake session pickle")
        val session = FakeSession(id = parts[1], counter = counter, isNew = isNew)
        sessions[session.id] = session
        return session.id
    }

    private data class ParsedPreKey(val sessionId: String, val ciphertext: ByteArray)

    private fun topUpPool() {
        while (unpublished.size < ONE_TIME_KEY_POOL_SIZE) {
            unpublished.add(deriveKey("fake-otk-v1:$otkCounter"))
            otkCounter++
        }
    }

    private fun deriveKey(label: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(label.toByteArray(Charsets.UTF_8))
        digest.update(":".toByteArray(Charsets.UTF_8))
        digest.update(seed.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private fun passphraseCheck(passphrase: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("fake-pickle-check".toByteArray(Charsets.UTF_8))
        digest.update(passphrase)
        return digest.digest().toHex().substring(0, 16)
    }

    private fun parsePreKey(bytes: ByteArray): ParsedPreKey? {
        val prefix = PRE_KEY_PREFIX.toByteArray(Charsets.UTF_8)
        if (bytes.size <= prefix.size || !bytes.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            return null
        }
        var separatorAt = -1
        for (i in prefix.size until bytes.size) {
            if (bytes[i] == SEPARATOR) {
                separatorAt = i
                break
            }
        }
        if (separatorAt < 0) return null
        val sessionId = bytes.copyOfRange(prefix.size, separatorAt).toString(Charsets.UTF_8)
        val ciphertext = bytes.copyOfRange(separatorAt + 1, bytes.size)
        return ParsedPreKey(sessionId, ciphertext)
    }

    private fun keystream(sessionId: String, counter: Long): ByteArray {
        // One 32-byte block per 8-byte counter value; the caller XORs only
        // what it needs, so a single block covers messages up to 32 bytes
        // and longer messages re-key per block below.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sessionId.toByteArray(Charsets.UTF_8))
        digest.update(longBytes(counter))
        return digest.digest()
    }

    private fun xor(data: ByteArray, firstBlock: ByteArray): ByteArray {
        // Re-key per 32-byte block so long messages do not reuse keystream:
        // block i is SHA-256(first block || i).
        val out = ByteArray(data.size)
        var offset = 0
        var blockIndex = 0
        while (offset < data.size) {
            val block = if (blockIndex == 0) {
                firstBlock
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(firstBlock)
                digest.update(longBytes(blockIndex.toLong()))
                digest.digest()
            }
            val take = minOf(block.size, data.size - offset)
            for (i in 0 until take) {
                out[offset + i] = (data[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += take
            blockIndex++
        }
        return out
    }

    private fun requireSession(sessionId: String): FakeSession =
        sessions[sessionId] ?: throw E2eCryptoException("no live session for this id")

    private fun longBytes(value: Long): ByteArray =
        ByteArray(8) { i -> (value shr (56 - 8 * i)).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val PRE_KEY_PREFIX: String = "FAKEPK1:"
        const val SEPARATOR: Byte = 0x00
        const val ACCOUNT_PICKLE_PREFIX: String = "FAKE-ACCOUNT-v1"
        const val SESSION_PICKLE_PREFIX: String = "FAKE-SESSION-v1"
    }
}
