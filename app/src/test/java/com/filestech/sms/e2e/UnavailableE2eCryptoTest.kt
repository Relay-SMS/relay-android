package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The unavailable-crypto contract (round 38): when the native library
 * failed to load, every crypto operation throws [E2eCryptoException]
 * — the failure contract every E2E entry point already handles — and
 * [E2eCrypto.hasSession] answers `false` (this instance holds no
 * sessions).
 */
class UnavailableE2eCryptoTest {

    private val crypto = UnavailableE2eCrypto()
    private val key = ByteArray(E2E_KEY_LENGTH) { 1 }
    private val passphrase = "test-passphrase".toByteArray()

    @Test
    fun `identityKeys throws E2eCryptoException`() {
        val thrown = assertThrows(E2eCryptoException::class.java) { crypto.identityKeys() }
        assertThat(thrown.message).contains("unavailable")
    }

    @Test
    fun `nextOneTimeKey throws E2eCryptoException`() {
        assertThrows(E2eCryptoException::class.java) { crypto.nextOneTimeKey() }
        assertThat(crypto.hasSession("any")).isFalse()
    }

    @Test
    fun `generateOneTimeKeys throws E2eCryptoException`() {
        assertThrows(E2eCryptoException::class.java) { crypto.generateOneTimeKeys() }
        assertThat(crypto.hasSession("any")).isFalse()
    }

    @Test
    fun `createOutboundSession throws E2eCryptoException`() {
        assertThrows(E2eCryptoException::class.java) {
            crypto.createOutboundSession(key, key)
        }
        assertThat(crypto.hasSession("any")).isFalse()
    }

    @Test
    fun `createInboundSession throws E2eCryptoException`() {
        assertThrows(E2eCryptoException::class.java) {
            crypto.createInboundSession(ByteArray(0))
        }
        assertThat(crypto.hasSession("any")).isFalse()
    }

    @Test
    fun `encrypt and decrypt throw E2eCryptoException`() {
        assertThrows(E2eCryptoException::class.java) {
            crypto.encrypt("session-1", "hello".toByteArray())
        }
        assertThrows(E2eCryptoException::class.java) {
            crypto.decrypt(
                "session-1",
                EncryptedMessage(OlmMessageType.NORMAL, ByteArray(0)),
            )
        }
        assertThat(crypto.hasSession("session-1")).isFalse()
    }

    @Test
    fun `pickle and unpickle throw E2eCryptoException`() {
        assertThrows(E2eCryptoException::class.java) { crypto.pickleAccount(passphrase) }
        assertThrows(E2eCryptoException::class.java) {
            crypto.unpickleAccount("pickle", passphrase)
        }
        assertThrows(E2eCryptoException::class.java) {
            crypto.pickleSession("session-1", passphrase)
        }
        assertThrows(E2eCryptoException::class.java) {
            crypto.unpickleSession("pickle", passphrase)
        }
        assertThat(crypto.hasSession("session-1")).isFalse()
    }

    @Test
    fun `hasSession is false for any id`() {
        assertThat(crypto.hasSession("session-1")).isFalse()
        assertThat(crypto.hasSession("")).isFalse()
    }
}
