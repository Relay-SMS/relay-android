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
 * After auth, the sync frames (`sync_request`, `messages_request`)
 * and the Slice 3 send + write-back frames (`send_message`,
 * `mark_read`, `mark_all_read`, `set_pin`, `set_archived`,
 * `set_muted`, `set_reaction`, `set_pinned_message`) are dispatched
 * to the per-session [SyncEngine]; the engine starts its live
 * collectors the moment auth succeeds. Slice 4 adds the
 * attachment-byte frames (`attachment_request`,
 * `attachment_upload_start`, `attachment_upload_chunk`), dispatched
 * the same way. Phase 2 adds the scheduled-send frame
 * (`schedule_message`) and the scheduled-cancel frame
 * (`cancel_scheduled`), dispatched the same way. Slice 5 adds the
 * contact frames (`contacts_request`, `avatar_request`), dispatched
 * the same way.
 *
 * @param send carries one outbound text frame to the client.
 * @param close closes the socket; [reason] is a short human-readable
 * cause (never a secret) for the close frame.
 * @param engine the session's sync engine, or null when the session
 * has none (tests): sync frames then answer `error` instead of
 * crashing.
 */
class SyncSessionHandler(
    private val lookup: LinkedDeviceLookup,
    private val hubId: String,
    private val send: suspend (String) -> Unit,
    private val close: suspend (String) -> Unit,
    private val engine: SyncEngine? = null,
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
            is SyncProtocol.Parsed.SyncRequest -> onSyncRequest(parsed.value)
            is SyncProtocol.Parsed.MessagesRequest -> onMessagesRequest(parsed.value)
            is SyncProtocol.Parsed.SendMessage -> onSendMessage(parsed.value)
            is SyncProtocol.Parsed.MarkRead -> onMarkRead(parsed.value)
            is SyncProtocol.Parsed.MarkAllRead -> onMarkAllRead()
            is SyncProtocol.Parsed.SetPin -> onSetPin(parsed.value)
            is SyncProtocol.Parsed.SetArchived -> onSetArchived(parsed.value)
            is SyncProtocol.Parsed.SetMuted -> onSetMuted(parsed.value)
            is SyncProtocol.Parsed.SetReaction -> onSetReaction(parsed.value)
            is SyncProtocol.Parsed.SetPinnedMessage -> onSetPinnedMessage(parsed.value)
            is SyncProtocol.Parsed.AttachmentRequest -> onAttachmentRequest(parsed.value)
            is SyncProtocol.Parsed.AttachmentUploadStart -> onUploadStart(parsed.value)
            is SyncProtocol.Parsed.AttachmentUploadChunk -> onUploadChunk(parsed.value)
            is SyncProtocol.Parsed.ScheduleMessage -> onScheduleMessage(parsed.value)
            is SyncProtocol.Parsed.CancelScheduled -> onCancelScheduled(parsed.value)
            is SyncProtocol.Parsed.ContactsRequest -> onContactsRequest()
            is SyncProtocol.Parsed.AvatarRequest -> onAvatarRequest(parsed.value)
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

    /** Sends one outbound frame (the server's broadcasts use this). */
    internal suspend fun sendText(text: String) {
        send(text)
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
        // Live push runs from auth onwards, concurrently with the
        // initial stream the client is about to request.
        engine?.start()
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

    /**
     * Sync frames need auth, like `ping`. The engine only starts the
     * stream job here — the stream itself runs in the engine's scope,
     * never on the frame loop.
     */
    private suspend fun onSyncRequest(request: SyncProtocol.SyncRequest) {
        withEngine { it.onSyncRequest(request) }
    }

    private suspend fun onMessagesRequest(request: SyncProtocol.MessagesRequest) {
        withEngine { it.onMessagesRequest(request) }
    }

    /**
     * Slice 3 — send + write-back frames need auth too: an
     * unauthenticated send frame is a protocol violation, like an
     * unauthenticated sync frame. The engine runs each in its own
     * job, never on the frame loop.
     */
    private suspend fun onSendMessage(request: SyncProtocol.SendMessage) {
        withEngine { it.onSendMessage(request) }
    }

    private suspend fun onMarkRead(request: SyncProtocol.MarkRead) {
        withEngine { it.onMarkRead(request) }
    }

    private suspend fun onMarkAllRead() {
        withEngine { it.onMarkAllRead() }
    }

    private suspend fun onSetPin(request: SyncProtocol.SetPin) {
        withEngine { it.onSetPin(request) }
    }

    private suspend fun onSetArchived(request: SyncProtocol.SetArchived) {
        withEngine { it.onSetArchived(request) }
    }

    private suspend fun onSetMuted(request: SyncProtocol.SetMuted) {
        withEngine { it.onSetMuted(request) }
    }

    private suspend fun onSetReaction(request: SyncProtocol.SetReaction) {
        withEngine { it.onSetReaction(request) }
    }

    private suspend fun onSetPinnedMessage(request: SyncProtocol.SetPinnedMessage) {
        withEngine { it.onSetPinnedMessage(request) }
    }

    /**
     * Slice 4 — attachment-byte frames need auth too: an
     * unauthenticated download or upload frame is a protocol
     * violation, like an unauthenticated send. The engine runs each
     * in its own job, never on the frame loop.
     */
    private suspend fun onAttachmentRequest(request: SyncProtocol.AttachmentRequest) {
        withEngine { it.onAttachmentRequest(request) }
    }

    private suspend fun onUploadStart(request: SyncProtocol.AttachmentUploadStart) {
        withEngine { it.onUploadStart(request) }
    }

    private suspend fun onUploadChunk(request: SyncProtocol.AttachmentUploadChunk) {
        withEngine { it.onUploadChunk(request) }
    }

    /**
     * Phase 2 — the scheduled-send frame needs auth too: an
     * unauthenticated schedule frame is a protocol violation, like an
     * unauthenticated send. The engine runs it in its own job, never
     * on the frame loop.
     */
    private suspend fun onScheduleMessage(request: SyncProtocol.ScheduleMessage) {
        withEngine { it.onScheduleMessage(request) }
    }

    /**
     * Phase 2 — the scheduled-cancel frame needs auth too, like the
     * schedule frame. The engine runs it in its own job, never on
     * the frame loop.
     */
    private suspend fun onCancelScheduled(request: SyncProtocol.CancelScheduled) {
        withEngine { it.onCancelScheduled(request) }
    }

    /**
     * Slice 5 — the contact frames need auth too: an unauthenticated
     * contacts or avatar frame is a protocol violation, like an
     * unauthenticated send. The engine runs each in its own job,
     * never on the frame loop.
     */
    private suspend fun onContactsRequest() {
        withEngine { it.onContactsRequest() }
    }

    private suspend fun onAvatarRequest(request: SyncProtocol.AvatarRequest) {
        withEngine { it.onAvatarRequest(request) }
    }

    /**
     * The shared auth + engine gate for every post-auth frame: unauthenticated
     * frames are protocol violations; a session without an engine (tests)
     * answers `error` instead of crashing.
     */
    private suspend fun withEngine(block: (SyncEngine) -> Unit) {
        if (!isAuthenticated) {
            onProtocolViolation()
            return
        }
        val syncEngine = engine
        if (syncEngine == null) {
            send(SyncProtocol.encodeError("sync unavailable"))
            return
        }
        block(syncEngine)
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
