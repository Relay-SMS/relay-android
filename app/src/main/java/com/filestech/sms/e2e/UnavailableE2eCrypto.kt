package com.filestech.sms.e2e

/**
 * [E2eCrypto] for when the native library failed to load (design §4):
 * [com.filestech.sms.di.E2eModule] hands this out when
 * `VodozemacCrypto.createNew()` throws — a native-load failure is an
 * [UnsatisfiedLinkError] (an `Error`, not an `Exception`), and letting
 * it escape Hilt provisioning crashed the app on the round-37 release
 * build (R8 had stripped the uncalled JNI bindings, so
 * `libvodozemac.so`'s `JNI_OnLoad` returned `JNI_ERR`).
 *
 * Every operation throws [E2eCryptoException], which is the failure
 * contract every E2E entry point already handles: probes fail silently
 * (no budget consumed), sends stay plain (no peer can be ENCRYPTED),
 * the receive path drops the packet, and the Settings fingerprint row
 * stays hidden — encryption is simply unavailable, never a crash.
 * [hasSession] returns `false` instead of throwing: this instance
 * holds no sessions, so "no live session" is the honest answer.
 */
class UnavailableE2eCrypto(private val cause: Throwable? = null) : E2eCrypto {

    private fun fail(): Nothing =
        throw E2eCryptoException("end-to-end encryption is unavailable on this device", cause)

    override fun identityKeys(): IdentityKeys = fail()

    override fun nextOneTimeKey(): ByteArray? = fail()

    override fun generateOneTimeKeys(): Unit = fail()

    override fun createOutboundSession(
        theirCurveKey: ByteArray,
        theirOneTimeKey: ByteArray,
    ): String = fail()

    override fun createInboundSession(preKeyMessage: ByteArray): InboundResult = fail()

    override fun encrypt(sessionId: String, plaintext: ByteArray): EncryptedMessage = fail()

    override fun decrypt(sessionId: String, message: EncryptedMessage): ByteArray = fail()

    override fun hasSession(sessionId: String): Boolean = false

    override fun pickleAccount(passphrase: ByteArray): String = fail()

    override fun unpickleAccount(pickle: String, passphrase: ByteArray): Unit = fail()

    override fun pickleSession(sessionId: String, passphrase: ByteArray): String = fail()

    override fun unpickleSession(pickle: String, passphrase: ByteArray): String = fail()
}
