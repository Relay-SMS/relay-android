package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections

/**
 * Slice 5 — the engine's contact-sync handlers, driven without a
 * socket: the fake source holds the contact snapshot (or the avatar
 * bytes), and the harness records every outbound frame. The
 * load-bearing invariant is pinned here: contacts failing must
 * NEVER fail message sync — a contacts failure rides as
 * `contacts_error` and the stream proceeds to `sync_complete`.
 */
class SyncEngineSlice5Test {

    // -- Slice 5: the contact snapshot inside sync_request -------------

    @Test fun `full sync streams contacts after sync_started and before conversations`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsSnapshot = listOf(
            sampleContact(id = 1L, displayName = "Marie"),
            sampleContact(id = 2L, displayName = "Curt", phones = listOf("+15557654321")),
        )
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly(
                "sync_started",
                "contacts",
                "conversations",
                "sync_complete",
                // Category mutes — the full muted set rides right
                // after the snapshot (empty here: the fake mutes
                // nothing).
                "category_mutes",
            ).inOrder()

            val started = sent.first { it.frameType() == "sync_started" }.asObject()
            assertThat(started["total_contacts"]?.jsonPrimitive?.content).isEqualTo("2")

            val batch = sent.first { it.frameType() == "contacts" }.asObject()
            assertThat(batch["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
            val names = batch["contacts"]!!.jsonArray
                .map { it.jsonObject["display_name"]?.jsonPrimitive?.content }
            assertThat(names).containsExactly("Marie", "Curt").inOrder()

            // Read once: the count feeds `total_contacts` and the
            // same list is streamed — no double read.
            assertThat(source.contactsCalls).isEqualTo(1)
        }
    }

    @Test fun `contacts stream in batches of 100 with has_more`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsSnapshot = (1..250).map {
            sampleContact(id = it.toLong(), displayName = "Contact $it")
        }
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "new_only"))
            advanceUntilIdle()
            val batches = sent.filter { it.frameType() == "contacts" }.map { it.asObject() }
            assertThat(batches).hasSize(3)
            assertThat(batches[0]["contacts"]!!.jsonArray).hasSize(100)
            assertThat(batches[0]["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(batches[1]["contacts"]!!.jsonArray).hasSize(100)
            assertThat(batches[1]["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(batches[2]["contacts"]!!.jsonArray).hasSize(50)
            assertThat(batches[2]["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
            val started = sent.first { it.frameType() == "sync_started" }.asObject()
            assertThat(started["total_contacts"]?.jsonPrimitive?.content).isEqualTo("250")
        }
    }

    @Test fun `an empty contact list still sends one empty contacts batch`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsSnapshot = emptyList()
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val batches = sent.filter { it.frameType() == "contacts" }.map { it.asObject() }
            assertThat(batches).hasSize(1)
            assertThat(batches[0]["contacts"]!!.jsonArray).isEmpty()
            // `has_more: false` is the prune signal: the desktop
            // deletes every local number absent from the snapshot.
            assertThat(batches[0]["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
        }
    }

    @Test fun `contacts sync in new_only mode too`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsSnapshot = listOf(sampleContact(id = 1L))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "new_only"))
            advanceUntilIdle()
            // The mode choice is about message history; the contact
            // snapshot is a sub-second prefix either way.
            assertThat(sent.map { it.frameType() }).contains("contacts")
            assertThat(sent.map { it.frameType() }).doesNotContain("messages_batch")
        }
    }

    // -- Slice 5: contacts_request --------------------------------------

    @Test fun `contacts_request re-streams the snapshot without touching messages`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsSnapshot = listOf(sampleContact(id = 1L), sampleContact(id = 2L))
        runEngine(source) { engine, sent ->
            engine.onContactsRequest()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("contacts").inOrder()
            val batch = sent.first().asObject()
            assertThat(batch["contacts"]!!.jsonArray).hasSize(2)
            assertThat(batch["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
        }
    }

    @Test fun `contacts_request with no permission answers contacts_error`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsReadiness = ContactReadiness.NoContactsPermission
        runEngine(source) { engine, sent ->
            engine.onContactsRequest()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("contacts_error").inOrder()
            val error = sent.first().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS)
            assertThat(source.contactsCalls).isEqualTo(0)
        }
    }

    /**
     * Contacts-permission fix — the revoke race: the readiness check
     * passed, but the permission was revoked before the read. The
     * refused read answers `contacts_error` (same code as never
     * having the permission), never an empty snapshot — an empty
     * snapshot would read as "zero contacts" on the desktop.
     */
    @Test fun `a contacts read refused mid-sync answers contacts_error`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsError =
            SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
        runEngine(source) { engine, sent ->
            engine.onContactsRequest()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("contacts_error").inOrder()
            val error = sent.first().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS)
        }
    }

    // -- Slice 5: failure isolation — contacts never fail message sync --

    @Test fun `no contacts permission answers contacts_error and message sync still proceeds`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsReadiness = ContactReadiness.NoContactsPermission
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly(
                "sync_started",
                "contacts_error",
                "conversations",
                "sync_complete",
                // Category mutes — the full muted set still rides
                // after the snapshot (a contacts failure never fails
                // message sync, and it never fails this either).
                "category_mutes",
            ).inOrder()
            val started = sent.first { it.frameType() == "sync_started" }.asObject()
            assertThat(started.containsKey("total_contacts")).isFalse()
            val error = sent.first { it.frameType() == "contacts_error" }.asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS)
            // And it is `contacts_error`, never `sync_error`.
            assertThat(sent.map { it.frameType() }).doesNotContain("sync_error")
        }
    }

    @Test fun `a throwing contacts read answers contacts_error internal and message sync still proceeds`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsError = IllegalStateException("boom")
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val error = sent.first { it.frameType() == "contacts_error" }.asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.ContactsError.INTERNAL)
            assertThat(error["message"]?.jsonPrimitive?.content).doesNotContain("boom")
            assertThat(sent.map { it.frameType() }).contains("sync_complete")
        }
    }

    @Test fun `a SecurityException from the contacts read answers hub_no_contacts_access`() = runTest {
        val source = FakeSyncMessageSource()
        source.contactsError = SecurityException("permission revoked mid-flight")
        runEngine(source) { engine, sent ->
            engine.onContactsRequest()
            advanceUntilIdle()
            val error = sent.first { it.frameType() == "contacts_error" }.asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS)
        }
    }

    // -- Slice 5: avatars through the Slice 4 chunk sender --------------

    @Test fun `avatar_request streams one chunk for a small photo`() {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val source = FakeSyncMessageSource()
        source.openAvatarResult = AvatarOpenResult.Opened(ByteArrayInputStream(bytes))
        runEngineReal(source) { engine, sent ->
            engine.onAvatarRequest(SyncProtocol.AvatarRequest(address = "+15551234567"))
            sent.awaitFrames(1)
            assertThat(sent.map { it.frameType() }).containsExactly("attachment_chunk").inOrder()
            val chunk = sent.first().asObject()
            assertThat(chunk["attachment_id"]?.jsonPrimitive?.content)
                .isEqualTo("avatar:+15551234567")
            assertThat(chunk["offset"]?.jsonPrimitive?.content).isEqualTo("0")
            assertThat(chunk["final"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(chunk["sha256"]?.jsonPrimitive?.content).isEqualTo(sha256Hex(bytes))
            assertThat(chunk["size_bytes"]?.jsonPrimitive?.content).isEqualTo("1024")
            val reassembled = Base64.getUrlDecoder().decode(
                QrPairing.padBase64(chunk["data_base64url"]!!.jsonPrimitive.content),
            )
            assertThat(reassembled).isEqualTo(bytes)
            assertThat(source.avatarRequests).containsExactly("+15551234567")
        }
    }

    @Test fun `avatar_request splits a large photo at 64 KiB`() {
        val bytes = ByteArray(150 * 1024) { (it % 251).toByte() }
        val source = FakeSyncMessageSource()
        source.openAvatarResult = AvatarOpenResult.Opened(ByteArrayInputStream(bytes))
        runEngineReal(source) { engine, sent ->
            engine.onAvatarRequest(SyncProtocol.AvatarRequest(address = "+15551234567"))
            sent.awaitFrames(3)
            val chunks = sent.map { it.asObject() }
            assertThat(chunks[0]["offset"]?.jsonPrimitive?.content).isEqualTo("0")
            assertThat(chunks[0]["final"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(chunks[1]["offset"]?.jsonPrimitive?.content).isEqualTo("65536")
            assertThat(chunks[2]["offset"]?.jsonPrimitive?.content).isEqualTo("131072")
            assertThat(chunks[2]["final"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(chunks[2]["sha256"]?.jsonPrimitive?.content).isEqualTo(sha256Hex(bytes))
            val reassembled = chunks.flatMap { chunk ->
                Base64.getUrlDecoder()
                    .decode(QrPairing.padBase64(chunk["data_base64url"]!!.jsonPrimitive.content))
                    .toList()
            }.toByteArray()
            assertThat(reassembled).isEqualTo(bytes)
        }
    }

    @Test fun `avatar_request for an unknown number answers unknown_attachment`() {
        val source = FakeSyncMessageSource()
        source.openAvatarResult = AvatarOpenResult.Unknown
        runEngineReal(source) { engine, sent ->
            engine.onAvatarRequest(SyncProtocol.AvatarRequest(address = "+15550000000"))
            sent.awaitFrames(1)
            assertThat(sent.map { it.frameType() }).containsExactly("attachment_error").inOrder()
            val error = sent.first().asObject()
            assertThat(error["attachment_id"]?.jsonPrimitive?.content)
                .isEqualTo("avatar:+15550000000")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT)
        }
    }

    @Test fun `a throwing avatar read answers attachment_error internal`() {
        val source = FakeSyncMessageSource()
        source.openAvatarError = IllegalStateException("boom")
        runEngineReal(source) { engine, sent ->
            engine.onAvatarRequest(SyncProtocol.AvatarRequest(address = "+15551234567"))
            sent.awaitFrames(1)
            val error = sent.first { it.frameType() == "attachment_error" }.asObject()
            assertThat(error["attachment_id"]?.jsonPrimitive?.content)
                .isEqualTo("avatar:+15551234567")
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.AttachmentError.INTERNAL)
            assertThat(error["message"]?.jsonPrimitive?.content).doesNotContain("boom")
        }
    }

    /**
     * Runs the engine on a real scope (not the test scheduler): the
     * download stream reads on `Dispatchers.IO`, which virtual time
     * can't wait for. Frames are awaited in real time (see
     * [MutableList.awaitFrames]). The sent list is synchronized —
     * the engine job appends from an IO thread.
     */
    private fun runEngineReal(
        source: FakeSyncMessageSource,
        block: (SyncEngine, MutableList<String>) -> Unit,
    ) {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val engine = SyncEngine(source, scope, send = { sent += it }, wakelock = NoOpSyncWakelock)
        try {
            block(engine, sent)
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /** Polls (real time) until [count] frames arrived, or fails after 10 s. */
    private fun MutableList<String>.awaitFrames(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (size < count) {
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for $count frames, got $size"
            }
            Thread.sleep(10)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
