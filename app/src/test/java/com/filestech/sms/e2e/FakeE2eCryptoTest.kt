package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Fake-crypto tests (design §10.1): the [FakeE2eCrypto] contract —
 * deterministic identities, pre-key vs normal types, round-trips
 * (including two-fake interop), the one-time-key pool, and pickle
 * round-trips. These tests also pin the [E2eCrypto] interface behavior
 * the real implementation must match.
 */
class FakeE2eCryptoTest {

    private val passphrase: ByteArray = "test-passphrase".toByteArray()

    @Test
    fun `identity keys are deterministic per seed and 32 bytes each`() {
        val first = FakeE2eCrypto("alice")
        val second = FakeE2eCrypto("alice")
        assertThat(first.identityKeys()).isEqualTo(second.identityKeys())
        assertThat(first.identityKeys().curve25519.size).isEqualTo(32)
        assertThat(first.identityKeys().ed25519.size).isEqualTo(32)
        val other = FakeE2eCrypto("bob")
        assertThat(other.identityKeys()).isNotEqualTo(first.identityKeys())
    }

    @Test
    fun `the one-time-key pool hands out one key then empties until topped up`() {
        val fake = FakeE2eCrypto("alice")
        val first = fake.nextOneTimeKey()
        assertThat(first).isNotNull()
        assertThat(first!!.size).isEqualTo(32)
        // Handing a key out marks the whole unpublished batch published.
        assertThat(fake.nextOneTimeKey()).isNull()
        fake.generateOneTimeKeys()
        val second = fake.nextOneTimeKey()
        assertThat(second).isNotNull()
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `an outbound session id is deterministic from the peer keys`() {
        val bob = FakeE2eCrypto("bob")
        val alice = FakeE2eCrypto("alice")
        val oneTimeKey = bob.nextOneTimeKey()!!
        val first = alice.createOutboundSession(bob.identityKeys().curve25519, oneTimeKey)
        val second = alice.createOutboundSession(bob.identityKeys().curve25519, oneTimeKey)
        assertThat(first).isEqualTo(second)
        assertThat(first).startsWith("fake-")
        assertThat(alice.hasSession(first)).isTrue()
        assertThat(alice.hasSession("fake-nope")).isFalse()
    }

    @Test
    fun `the first encrypt is a pre-key message and the rest are normal`() {
        val alice = FakeE2eCrypto("alice")
        val bob = FakeE2eCrypto("bob")
        val sessionId = alice.createOutboundSession(
            bob.identityKeys().curve25519,
            bob.nextOneTimeKey()!!,
        )
        val first = alice.encrypt(sessionId, "hello".toByteArray())
        assertThat(first.type).isEqualTo(OlmMessageType.PRE_KEY)
        val second = alice.encrypt(sessionId, "again".toByteArray())
        assertThat(second.type).isEqualTo(OlmMessageType.NORMAL)
    }

    @Test
    fun `two fakes interoperate like two real installs`() {
        val alice = FakeE2eCrypto("alice")
        val bob = FakeE2eCrypto("bob")
        val sessionId = alice.createOutboundSession(
            bob.identityKeys().curve25519,
            bob.nextOneTimeKey()!!,
        )
        val first = alice.encrypt(sessionId, "hello bob".toByteArray())
        assertThat(first.type).isEqualTo(OlmMessageType.PRE_KEY)
        val inbound = bob.createInboundSession(first.bytes)
        assertThat(inbound.sessionId).isEqualTo(sessionId)
        assertThat(inbound.plaintext).isEqualTo("hello bob".toByteArray())
        assertThat(bob.hasSession(sessionId)).isTrue()
        val reply = bob.encrypt(sessionId, "hello alice".toByteArray())
        assertThat(reply.type).isEqualTo(OlmMessageType.NORMAL)
        assertThat(alice.decrypt(sessionId, reply)).isEqualTo("hello alice".toByteArray())
        val third = alice.encrypt(sessionId, "third message".toByteArray())
        assertThat(third.type).isEqualTo(OlmMessageType.NORMAL)
        assertThat(bob.decrypt(sessionId, third)).isEqualTo("third message".toByteArray())
    }

    @Test
    fun `a long message round-trips across two fakes`() {
        val alice = FakeE2eCrypto("alice")
        val bob = FakeE2eCrypto("bob")
        val sessionId = alice.createOutboundSession(
            bob.identityKeys().curve25519,
            bob.nextOneTimeKey()!!,
        )
        val longText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(20)
        val first = alice.encrypt(sessionId, longText.toByteArray())
        val inbound = bob.createInboundSession(first.bytes)
        assertThat(inbound.plaintext).isEqualTo(longText.toByteArray())
        val reply = bob.encrypt(sessionId, longText.toByteArray())
        assertThat(alice.decrypt(sessionId, reply)).isEqualTo(longText.toByteArray())
    }

    @Test
    fun `createInboundSession rejects garbage`() {
        val bob = FakeE2eCrypto("bob")
        assertThrows(E2eCryptoException::class.java) {
            bob.createInboundSession("not a pre-key message".toByteArray())
        }
        assertThrows(E2eCryptoException::class.java) {
            bob.createInboundSession(byteArrayOf())
        }
    }

    @Test
    fun `encrypt and decrypt reject unknown sessions`() {
        val fake = FakeE2eCrypto("alice")
        assertThrows(E2eCryptoException::class.java) {
            fake.encrypt("fake-unknown", "hi".toByteArray())
        }
        assertThrows(E2eCryptoException::class.java) {
            fake.decrypt("fake-unknown", EncryptedMessage(OlmMessageType.NORMAL, byteArrayOf(1)))
        }
        assertThrows(E2eCryptoException::class.java) {
            fake.pickleSession("fake-unknown", passphrase)
        }
    }

    @Test
    fun `createOutboundSession rejects keys that are not 32 bytes`() {
        val fake = FakeE2eCrypto("alice")
        assertThrows(IllegalArgumentException::class.java) {
            fake.createOutboundSession(ByteArray(31), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.createOutboundSession(ByteArray(32), ByteArray(33))
        }
    }

    @Test
    fun `an account pickle round-trips the identity and the pool`() {
        val first = FakeE2eCrypto("alice")
        val handedOut = first.nextOneTimeKey()!!
        first.generateOneTimeKeys()
        val pickle = first.pickleAccount(passphrase)
        val second = FakeE2eCrypto("alice")
        second.unpickleAccount(pickle, passphrase)
        assertThat(second.identityKeys()).isEqualTo(first.identityKeys())
        // The pool state round-trips too: the next key out of the
        // restored fake is the next key out of the original.
        assertThat(second.nextOneTimeKey()).isEqualTo(first.nextOneTimeKey())
        assertThat(handedOut.size).isEqualTo(32)
    }

    @Test
    fun `an account pickle rejects a wrong passphrase and a foreign identity`() {
        val first = FakeE2eCrypto("alice")
        val pickle = first.pickleAccount(passphrase)
        val second = FakeE2eCrypto("alice")
        assertThrows(E2eCryptoException::class.java) {
            second.unpickleAccount(pickle, "wrong-passphrase".toByteArray())
        }
        assertThrows(E2eCryptoException::class.java) {
            second.unpickleAccount("not a pickle", passphrase)
        }
        val bob = FakeE2eCrypto("bob")
        assertThrows(E2eCryptoException::class.java) {
            bob.unpickleAccount(pickle, passphrase)
        }
    }

    @Test
    fun `a session pickle round-trips the session and its counter`() {
        val alice = FakeE2eCrypto("alice")
        val bob = FakeE2eCrypto("bob")
        val sessionId = alice.createOutboundSession(
            bob.identityKeys().curve25519,
            bob.nextOneTimeKey()!!,
        )
        val first = alice.encrypt(sessionId, "one".toByteArray())
        bob.createInboundSession(first.bytes)
        val pickle = alice.pickleSession(sessionId, passphrase)
        val restored = FakeE2eCrypto("alice")
        val restoredId = restored.unpickleSession(pickle, passphrase)
        assertThat(restoredId).isEqualTo(sessionId)
        assertThat(restored.hasSession(sessionId)).isTrue()
        // The counter survived: the next message is NORMAL (not a fresh
        // pre-key message) and Bob decrypts it in sequence.
        val next = restored.encrypt(sessionId, "two".toByteArray())
        assertThat(next.type).isEqualTo(OlmMessageType.NORMAL)
        assertThat(bob.decrypt(sessionId, next)).isEqualTo("two".toByteArray())
    }

    @Test
    fun `a session pickle rejects a wrong passphrase and garbage`() {
        val alice = FakeE2eCrypto("alice")
        val bob = FakeE2eCrypto("bob")
        val sessionId = alice.createOutboundSession(
            bob.identityKeys().curve25519,
            bob.nextOneTimeKey()!!,
        )
        val pickle = alice.pickleSession(sessionId, passphrase)
        val restored = FakeE2eCrypto("alice")
        assertThrows(E2eCryptoException::class.java) {
            restored.unpickleSession(pickle, "wrong-passphrase".toByteArray())
        }
        assertThrows(E2eCryptoException::class.java) {
            restored.unpickleSession("not a pickle", passphrase)
        }
    }
}
