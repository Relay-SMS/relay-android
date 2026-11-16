package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Round 3 — the engine's contact-photo handler, driven without a
 * socket: the fake source records the photo call and answers from a
 * configurable result, and the harness records every outbound frame.
 * The routing and the ack shape are the contract the desktop sets
 * photos against, so they are pinned here. The provider write and
 * the permission check live in the glue
 * ([com.filestech.sms.system.sync.RepositorySyncMessageSource]) and
 * the data layer — the engine only decodes, caps, and forwards.
 */
class SyncEngineContactPhotoTest {

    /** Three JPEG-magic bytes as base64url (`FF D8 FF` → `_9j_`). */
    private val photoBase64Url = "_9j_"
    private val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    @Test fun `set_contact_photo routes to the source and answers action_result ok true`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onSetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = photoBase64Url,
                ),
            )
            advanceUntilIdle()
            assertThat(source.photoSets.map { it.address }).containsExactly("+15551234567")
            assertThat(source.photoSets.first().photoBytes).isEqualTo(photoBytes)
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val ack = sent.first().asObject()
            assertThat(ack["action"]?.jsonPrimitive?.content).isEqualTo("set_contact_photo")
            assertThat(ack["address"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
            assertThat(ack["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(ack.containsKey("conversation_id")).isFalse()
        }
    }

    @Test fun `a refused photo answers action_result ok false with the code and the address`() = runTest {
        val source = FakeSyncMessageSource()
        source.photoSetResult = ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.UNKNOWN_CONTACT,
            message = "There's no contact for this number on the phone.",
        )
        runEngine(source) { engine, sent ->
            engine.onSetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = photoBase64Url,
                ),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["action"]?.jsonPrimitive?.content).isEqualTo("set_contact_photo")
            assertThat(failed["address"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("unknown_contact")
            assertThat(failed["message"]?.jsonPrimitive?.content)
                .isEqualTo("There's no contact for this number on the phone.")
        }
    }

    @Test fun `undecodable bytes answer invalid_request and never reach the source`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onSetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = "!!!not-base64!!!",
                ),
            )
            advanceUntilIdle()
            assertThat(source.photoSets).isEmpty()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("invalid_request")
            assertThat(failed["address"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        }
    }

    @Test fun `empty bytes answer invalid_request and never reach the source`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onSetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = "",
                ),
            )
            advanceUntilIdle()
            assertThat(source.photoSets).isEmpty()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("invalid_request")
        }
    }

    @Test fun `an oversized photo answers photo_too_large and never reaches the source`() = runTest {
        val source = FakeSyncMessageSource()
        val oversized = ByteArray(SyncProtocol.MAX_CONTACT_PHOTO_BYTES + 1)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(oversized)
        runEngine(source) { engine, sent ->
            engine.onSetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = encoded,
                ),
            )
            advanceUntilIdle()
            assertThat(source.photoSets).isEmpty()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("photo_too_large")
            assertThat(failed["address"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        }
    }

    @Test fun `a throwing source answers action_result internal and keeps the socket up`() = runTest {
        val source = FakeSyncMessageSource()
        source.photoSetError = IllegalStateException("boom")
        runEngine(source) { engine, sent ->
            engine.onSetContactPhoto(
                SyncProtocol.SetContactPhoto(
                    address = "+15551234567",
                    photoBase64Url = photoBase64Url,
                ),
            )
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("action_result")
            val failed = sent.first().asObject()
            assertThat(failed["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(failed["code"]?.jsonPrimitive?.content).isEqualTo("internal")
            assertThat(failed["address"]?.jsonPrimitive?.content).isEqualTo("+15551234567")
        }
    }
}
