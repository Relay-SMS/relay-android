package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Slice 1 — the auth + heartbeat state machine, driven without a
 * socket: the fake lookup holds one linked device with a real Ed25519
 * keypair, and the harness records every outbound frame and close.
 * This is the contract the desktop speaks against, so every rejection
 * path is pinned here: the reason string AND the close.
 */
class SyncSessionHandlerTest {

    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKeyB64 = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(privateKey.generatePublicKey().encoded)
    private val device = LinkedDevice(
        deviceId = DEVICE_ID,
        kind = DeviceKind.DESKTOP,
        displayName = "Kristin's MacBook",
        publicKeyBase64Url = publicKeyB64,
        linkedAt = 0L,
    )

    /** One handler plus the frames it sent and the closes it asked for. */
    private class Harness(
        devices: Map<String, LinkedDevice>,
        engine: SyncEngine? = null,
        val sent: MutableList<String> = mutableListOf(),
    ) {
        val lookup = FakeLookup(devices)
        val closes = mutableListOf<String>()
        val handler = SyncSessionHandler(
            lookup = lookup,
            hubId = HUB_ID,
            send = { sent += it },
            close = { closes += it },
            engine = engine,
        )

        /** The reason of the last `auth_failed` frame, or null. */
        fun lastAuthFailedReason(): String? {
            val obj = Json.parseToJsonElement(sent.last()).jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "auth_failed") return null
            return obj["reason"]?.jsonPrimitive?.content
        }
    }

    private class FakeLookup(private val devices: Map<String, LinkedDevice>) :
        LinkedDeviceLookup {
        val touched = mutableListOf<String>()
        override suspend fun findDevice(deviceId: String) = devices[deviceId]
        override suspend fun touchLastSeen(deviceId: String, nowMs: Long) {
            touched += deviceId
        }
    }

    @Test fun `happy path authenticates and answers auth_ok`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        assertThat(harness.sent).hasSize(1)
        assertThat(harness.closes).isEmpty()

        harness.handler.onText(authJson(harness.challengeBytes()))
        assertThat(harness.handler.isAuthenticated).isTrue()
        assertThat(harness.handler.deviceId).isEqualTo(DEVICE_ID)
        assertThat(harness.lookup.touched).contains(DEVICE_ID)
        val ok = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(ok["type"]?.jsonPrimitive?.content).isEqualTo("auth_ok")
        assertThat(ok["hub_id"]?.jsonPrimitive?.content).isEqualTo(HUB_ID)
        assertThat(ok["hub_name"]?.jsonPrimitive?.content).isEqualTo("Relay")
        assertThat(harness.closes).isEmpty()
    }

    @Test fun `auth before hello fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(authJson(ByteArray(32)))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
        assertThat(harness.handler.isAuthenticated).isFalse()
    }

    @Test fun `auth for a different device than hello fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        val other = "123e4567-e89b-12d3-a456-426614174001"
        harness.handler.onText(authJson(harness.challengeBytes(), deviceId = other))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `unknown device fails with unknown_device and closes`() = runTest {
        val harness = Harness(emptyMap())
        harness.handler.onText(helloJson())
        harness.handler.onText(authJson(harness.challengeBytes()))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.UNKNOWN_DEVICE)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `bad signature fails with bad_signature and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        val wrongKey = Ed25519PrivateKeyParameters(SecureRandom())
        harness.handler.onText(authJson(harness.challengeBytes(), key = wrongKey))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.BAD_SIGNATURE)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `hello with a wrong protocol version fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(
            SyncProtocol.encodeHello(
                SyncProtocol.Hello(protocol = 99, deviceId = DEVICE_ID, kind = "desktop", name = "X"),
            ),
        )
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `a second hello fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        harness.handler.onText(helloJson())
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `ping before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(SyncProtocol.encodePing(SyncProtocol.Ping(t = 1L)))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `ping after auth answers pong echoing t`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText(SyncProtocol.encodePing(SyncProtocol.Ping(t = 4242L)))
        assertThat(SyncProtocol.parse(harness.sent.last()))
            .isEqualTo(SyncProtocol.Parsed.Pong(SyncProtocol.Ping(t = 4242L)))
        assertThat(harness.lookup.touched).contains(DEVICE_ID)
        assertThat(harness.closes).isEmpty()
    }

    @Test fun `sync_request before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"sync_request","mode":"full"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `messages_request before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"messages_request","conversation_id":"1"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `sync_request after auth without an engine answers error and stays open`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText("""{"type":"sync_request","mode":"full"}""")
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).isEmpty()
        assertThat(harness.handler.isAuthenticated).isTrue()
    }

    @Test fun `sync_request after auth dispatches to the engine`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1))
        val sent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { sent += it })
        try {
            val harness = Harness(mapOf(DEVICE_ID to device), engine, sent)
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()
            advanceUntilIdle()
            harness.handler.onText("""{"type":"sync_request","mode":"full"}""")
            advanceUntilIdle()
            val types = sent.map {
                Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content
            }
            assertThat(types).contains("sync_started")
            assertThat(types).contains("sync_complete")
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
        }
    }

    @Test fun `malformed frame before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("not json at all")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `malformed frame after auth sends error and closes`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText("{broken")
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `unknown type before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"future_thing"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `unknown type after auth sends error and stays open`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText("""{"type":"future_thing"}""")
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).isEmpty()
        assertThat(harness.handler.isAuthenticated).isTrue()
    }

    @Test fun `auth timeout closes an unauthenticated socket only`() = runTest {
        val unauthed = Harness(mapOf(DEVICE_ID to device))
        unauthed.handler.onAuthTimeout()
        assertThat(unauthed.closes).hasSize(1)

        val authed = authenticatedHarness()
        authed.handler.onAuthTimeout()
        assertThat(authed.closes).isEmpty()
    }

    @Test fun `revoke sends revoked first, then closes`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.revoke()
        assertThat(harness.sent.last()).isEqualTo("""{"type":"revoked"}""")
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `lastReceivedAtMs moves on every frame`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        val before = harness.handler.lastReceivedAtMs
        harness.handler.onText(helloJson())
        assertThat(harness.handler.lastReceivedAtMs).isAtLeast(before)
    }

    // -- Slice 3: send + write-back frames need auth ------------------

    @Test fun `an unauthenticated send_message fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(
            """{"type":"send_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","body":"Hi"}""",
        )
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
        assertThat(harness.handler.isAuthenticated).isFalse()
    }

    @Test fun `an unauthenticated action frame fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"set_pin","conversation_id":"123","value":true}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `an authenticated send_message reaches the engine and answers message_sent`() = runTest {
        val source = FakeSyncMessageSource()
        val engineSent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { engineSent += it })
        val harness = Harness(mapOf(DEVICE_ID to device), engine = engine)
        try {
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()

            harness.handler.onText(
                """{"type":"send_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","body":"See you at 6"}""",
            )
            advanceUntilIdle()
            assertThat(source.sends).containsExactly(
                SendCall(CLIENT_KEY, 123L, "See you at 6", null),
            )
            assertThat(engineSent).hasSize(1)
            val ack = Json.parseToJsonElement(engineSent.first()).jsonObject
            assertThat(ack["type"]?.jsonPrimitive?.content).isEqualTo("message_sent")
            assertThat(ack["client_message_id"]?.jsonPrimitive?.content).isEqualTo(CLIENT_KEY)
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    @Test fun `an authenticated action frame reaches the engine`() = runTest {
        val source = FakeSyncMessageSource()
        val engineSent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { engineSent += it })
        val harness = Harness(mapOf(DEVICE_ID to device), engine = engine)
        try {
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))

            harness.handler.onText("""{"type":"mark_read","conversation_id":"123"}""")
            harness.handler.onText("""{"type":"mark_all_read"}""")
            harness.handler.onText("""{"type":"set_muted","conversation_id":"456","value":true}""")
            harness.handler.onText("""{"type":"set_blocked","conversation_id":"789","value":true}""")
            advanceUntilIdle()
            assertThat(source.actions).containsExactly(
                ActionCall("mark_read", 123L),
                ActionCall("mark_all_read"),
                ActionCall("set_muted", 456L, value = true),
                ActionCall("set_blocked", 789L, value = true),
            ).inOrder()
            assertThat(engineSent).hasSize(4)
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    // -- Slice 5: contact frames ----------------------------------------

    @Test fun `an unauthenticated contacts_request fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"contacts_request"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `an unauthenticated avatar_request fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"avatar_request","address":"+15551234567"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `an authenticated contacts_request reaches the engine`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsSnapshot = listOf(sampleContact(id = 1L))
        val engineSent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { engineSent += it })
        val harness = Harness(mapOf(DEVICE_ID to device), engine = engine)
        try {
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()

            harness.handler.onText("""{"type":"contacts_request"}""")
            advanceUntilIdle()
            assertThat(source.contactsCalls).isEqualTo(1)
            assertThat(engineSent).hasSize(1)
            val batch = Json.parseToJsonElement(engineSent.first()).jsonObject
            assertThat(batch["type"]?.jsonPrimitive?.content).isEqualTo("contacts")
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    @Test fun `an authenticated avatar_request reaches the engine`() = runTest {
        val source = FakeSyncMessageSource()
        source.openAvatarResult = AvatarOpenResult.Unknown
        val engineSent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { engineSent += it })
        val harness = Harness(mapOf(DEVICE_ID to device), engine = engine)
        try {
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()

            harness.handler.onText("""{"type":"avatar_request","address":"+15551234567"}""")
            advanceUntilIdle()
            assertThat(source.avatarRequests).containsExactly("+15551234567")
            assertThat(engineSent).hasSize(1)
            val error = Json.parseToJsonElement(engineSent.first()).jsonObject
            assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("attachment_error")
            assertThat(error["attachment_id"]?.jsonPrimitive?.content)
                .isEqualTo("avatar:+15551234567")
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    // -- Create conversation --------------------------------------------

    @Test fun `an unauthenticated create_conversation fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(
            """{"type":"create_conversation","addresses":["+15551234567"],"custom_name":"Family"}""",
        )
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `an authenticated create_conversation reaches the engine and answers conversation_created`() = runTest {
        val source = FakeSyncMessageSource()
        val engineSent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { engineSent += it })
        val harness = Harness(mapOf(DEVICE_ID to device), engine = engine)
        try {
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()

            harness.handler.onText(
                """{"type":"create_conversation","addresses":["+15551234567","+15550001111"],"custom_name":"Family"}""",
            )
            advanceUntilIdle()
            assertThat(source.creates).containsExactly(
                CreateCall(listOf("+15551234567", "+15550001111"), "Family"),
            )
            assertThat(engineSent).hasSize(1)
            val ack = Json.parseToJsonElement(engineSent.first()).jsonObject
            assertThat(ack["type"]?.jsonPrimitive?.content).isEqualTo("conversation_created")
            assertThat(ack["conversation"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                .isEqualTo("123")
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    @Test fun `a send frame with no engine answers error and stays open`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText(
            """{"type":"send_message","client_message_id":"$CLIENT_KEY","conversation_id":"123","body":"Hi"}""",
        )
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).isEmpty()
        assertThat(harness.handler.isAuthenticated).isTrue()
    }

    // -- Slice 4: attachment-byte frames need auth ----------------------

    @Test fun `an unauthenticated attachment frame fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(
            """{"type":"attachment_request","attachment_id":"12","message_id":"789"}""",
        )
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)

        val uploadHarness = Harness(mapOf(DEVICE_ID to device))
        uploadHarness.handler.onText(
            """{"type":"attachment_upload_start","client_message_id":"$CLIENT_KEY","index":0,""" +
                """"mime_type":"image/jpeg","size_bytes":1024,"sha256":"ab12"}""",
        )
        assertThat(uploadHarness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(uploadHarness.closes).hasSize(1)
    }

    @Test fun `authenticated attachment frames reach the engine`() = runTest {
        val source = FakeSyncMessageSource()
        val engineSent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { engineSent += it })
        val harness = Harness(mapOf(DEVICE_ID to device), engine = engine)
        try {
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()

            harness.handler.onText(
                """{"type":"attachment_upload_start","client_message_id":"$CLIENT_KEY","index":0,""" +
                    """"mime_type":"image/jpeg","file_name":"photo.jpg","size_bytes":1024,"sha256":"ab12"}""",
            )
            harness.handler.onText(
                """{"type":"attachment_request","attachment_id":"12","message_id":"789"}""",
            )
            advanceUntilIdle()
            assertThat(source.uploadStarts).containsExactly(
                UploadStartCall(CLIENT_KEY, 0, "image/jpeg", "photo.jpg", 1024, "ab12"),
            )
            assertThat(source.attachmentRequests).containsExactly(12L to 789L)
            val types = engineSent.map {
                Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content
            }
            // The fake answers `ready` for the upload and `unknown`
            // for the download (no attachment is staged).
            assertThat(types).containsExactly("attachment_upload_ready", "attachment_error").inOrder()
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    private fun helloJson(): String = SyncProtocol.encodeHello(
        SyncProtocol.Hello(
            protocol = SyncProtocol.PROTOCOL_VERSION,
            deviceId = DEVICE_ID,
            kind = "desktop",
            name = "Kristin's MacBook",
        ),
    )

    /** Signs [challenge] with [key] (default: the linked device's key) and builds the auth frame. */
    private fun authJson(
        challenge: ByteArray,
        deviceId: String = DEVICE_ID,
        key: Ed25519PrivateKeyParameters = privateKey,
    ): String {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(challenge, 0, challenge.size)
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signer.generateSignature())
        return """{"type":"auth","device_id":"$deviceId","signature":"$signature"}"""
    }

    /** Decodes the challenge the handler just sent (the last frame). */
    private fun Harness.challengeBytes(): ByteArray {
        val obj = Json.parseToJsonElement(sent.last()).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("challenge")
        val b64 = obj["challenge"]!!.jsonPrimitive.content
        return Base64.getUrlDecoder().decode(QrPairing.padBase64(b64))
    }

    /** A harness that has completed the hello → challenge → auth flow. */
    private suspend fun authenticatedHarness(): Harness {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        harness.handler.onText(authJson(harness.challengeBytes()))
        assertThat(harness.handler.isAuthenticated).isTrue()
        return harness
    }

    companion object {
        private const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"
        private const val HUB_ID = "123e4567-e89b-12d3-a456-426614174099"
        private const val CLIENT_KEY = "3f2a1b00-0000-4000-8000-00000000c41d"
    }
}
