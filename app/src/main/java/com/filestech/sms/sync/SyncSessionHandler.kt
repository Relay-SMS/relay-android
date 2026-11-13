package com.filestech.sms.sync

import java.security.SecureRandom
import java.util.Base64

/**
 * One client's auth + heartbeat state machine (SYNC_PROTOCOL.md),
 * kept free of any transport: the Ktor session feeds it text frames
 * and carries its outbound messages. That split is what makes the auth
 * flow unit-testable without a socket.
 *
 * States: awaiting `hello` → awaiting `auth` (challenge issued) →
 * authenticated. A message out of order, or a malformed frame, is a
 * protocol violation: `auth_failed` + close before auth, an `error` +
 * close after it. The challenge is issued to any client — the device
 * check happens at `auth`, against the stored public key.
 *
 * @param send carries one outbound text frame to the client.
 * @param close closes the socket; [reason] is a short human-readable
 * cause (never a secret) for the close frame.
 */
class SyncSessionHandler(
    private val lookup: LinkedDeviceLookup,
    private val hubId: String,
    private val send: suspend (String) -> Unit,
    private val close: suspend (String) -> Unit,
) {
    /** The linked device id once auth has succeeded, null before. */
    var deviceId: String? = null
        private set

    val isAuthenticated: Boolean
        get() = deviceId != null

    /** Unix ms of the last frame received (any kind); the silence watchdog reads it. */
    @Volatile
    var lastReceivedAtMs: Long = System.currentTimeMillis()
        private set

    private var pendingChallenge: ByteArray? = null
    private var pendingHelloDeviceId: String? = null

    /** Feeds one incoming text frame into the state machine. Never throws. */
    suspend fun onText(text: String) {
        lastReceivedAtMs = System.currentTimeMillis()
        when (val parsed = SyncProtocol.parse(text)) {
            is SyncProtocol.Parsed.Hello -> onHello(parsed.value)
            is SyncProtocol.Parsed.Auth -> onAuth(parsed.value)
            is SyncProtocol.Parsed.Ping -> onPing(parsed.value)
            is SyncProtocol.Parsed.Pong -> Unit // the server never pings; a stray pong is harmless
            is SyncProtocol.Parsed.Unknown -> onUnknown(parsed.type)
            SyncProtocol.Parsed.Malformed -> onProtocolViolation()
        }
    }

    /** The server's 10 s auth watchdog calls this when the socket never authenticated. */
    suspend fun onAuthTimeout() {
        if (!isAuthenticated) {
            close("auth timeout")
        }
    }

    /** Revocation: the device is told first, then its socket is closed. */
    suspend fun revoke() {
        runCatching { send(SyncProtocol.REVOKED_JSON) }
        close("revoked")
    }

    /** Drops the socket without a protocol message (silence watchdog). */
    suspend fun drop(reason: String) {
        close(reason)
    }

    private suspend fun onHello(hello: SyncProtocol.Hello) {
        if (isAuthenticated || pendingChallenge != null) {
            // A second hello is a protocol violation, not a re-auth.
            onProtocolViolation()
            return
        }
        if (hello.protocol != SyncProtocol.PROTOCOL_VERSION) {
            failAuth(SyncProtocol.AuthFailed.PROTOCOL)
            return
        }
        pendingHelloDeviceId = hello.deviceId
        val challenge = ByteArray(SyncProtocol.CHALLENGE_BYTES).also { secureRandom.nextBytes(it) }
        pendingChallenge = challenge
        send(SyncProtocol.encodeChallenge(SyncProtocol.Challenge(base64Url(challenge))))
    }

    private suspend fun onAuth(auth: SyncProtocol.Auth) {
        val challenge = pendingChallenge
        val helloDeviceId = pendingHelloDeviceId
        if (challenge == null || helloDeviceId == null || auth.deviceId != helloDeviceId) {
            // Auth before hello, or for a different device than the hello named.
            failAuth(SyncProtocol.AuthFailed.PROTOCOL)
            return
        }
        val device = lookup.findDevice(auth.deviceId)
        if (device == null) {
            failAuth(SyncProtocol.AuthFailed.UNKNOWN_DEVICE)
            return
        }
        val publicKey = decodeBase64Url(device.publicKeyBase64Url)
        val signature = decodeBase64Url(auth.signature)
        val verified = publicKey != null && signature != null &&
            Ed25519.verify(publicKey, challenge, signature)
        if (!verified) {
            failAuth(SyncProtocol.AuthFailed.BAD_SIGNATURE)
            return
        }
        pendingChallenge = null
        pendingHelloDeviceId = null
        deviceId = device.deviceId
        lookup.touchLastSeen(device.deviceId, System.currentTimeMillis())
        send(
            SyncProtocol.encodeAuthOk(
                SyncProtocol.AuthOk(hubId = hubId, hubName = SyncProtocol.HUB_NAME),
            ),
        )
    }

    private suspend fun onPing(ping: SyncProtocol.Ping) {
        val id = deviceId
        if (id == null) {
            onProtocolViolation()
            return
        }
        lookup.touchLastSeen(id, System.currentTimeMillis())
        send(SyncProtocol.encodePong(ping))
    }

    private suspend fun onUnknown(type: String?) {
        if (!isAuthenticated) {
            onProtocolViolation()
        } else {
            send(SyncProtocol.encodeError("unknown message type: ${type ?: "?"}"))
        }
    }

    private suspend fun onProtocolViolation() {
        if (!isAuthenticated) {
            failAuth(SyncProtocol.AuthFailed.PROTOCOL)
        } else {
            runCatching { send(SyncProtocol.encodeError("protocol violation")) }
            close("protocol violation")
        }
    }

    private suspend fun failAuth(reason: String) {
        runCatching { send(SyncProtocol.encodeAuthFailed(SyncProtocol.AuthFailed(reason))) }
        close("auth failed: $reason")
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeBase64Url(value: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(QrPairing.padBase64(value)) }.getOrNull()

    companion object {
        /** One shared generator: constructing SecureRandom per hello would be wasteful. */
        private val secureRandom = SecureRandom()
    }
}
