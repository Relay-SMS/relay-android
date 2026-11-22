package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.purge.MS_PER_DAY
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The handshake handler (design §2.3–§2.4, §4.4): probes, bundle
 * replies, bundle receipt, first-reply-wins, key changes, and the kept
 * ciphertext retry.
 *
 * Two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer) with a
 * third ("carol") for key changes. The stores are the real ones over
 * in-memory fake DAOs — the handler's state transitions are the thing
 * under test, and they must compose with the real repository/store
 * behavior.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eHandshakeHandlerTest {

    private companion object {
        const val BOB = "+33612345678"
        const val CONV_ID = 11L
        const val NOW = 1_800_000_000_000L
    }

    private class Harness {
        val aliceCrypto = FakeE2eCrypto("alice")
        val bobCrypto = FakeE2eCrypto("bob")
        val carolCrypto = FakeE2eCrypto("carol")

        val peerDao = FakeE2ePeerDao()
        val sessionDao = FakeE2eSessionDao()
        val accountDao = FakeE2eAccountDao()
        val noticeDao = FakeE2eNoticeDao()
        val sender = RecordingDataSmsSender()

        val conversationDao = mockk<ConversationDao>()
        val messageDao = mockk<MessageDao>()
        val phoneIdentity = mockk<PhoneIdentity>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val peers = E2ePeerRepository(peerDao)
        val sessions = E2eSessionStore(sessionDao, aliceCrypto, fakeKeyManager())
        val accountManager = E2eAccountManager(accountDao, aliceCrypto, fakeKeyManager())
        val decryptor = E2eSessionDecryptor(
            lazyOf(aliceCrypto as E2eCrypto),
            lazyOf(sessions),
            lazyOf(accountManager),
        )

        val handler = E2eHandshakeHandler(
            peers = peers,
            sessions = lazyOf(sessions),
            accountManager = lazyOf(accountManager),
            crypto = lazyOf(aliceCrypto as E2eCrypto),
            decryptor = decryptor,
            notices = E2eNoticeStore(noticeDao),
            dataSmsSender = sender,
            conversationDao = conversationDao,
            messageDao = messageDao,
            phoneIdentity = phoneIdentity,
            blockedRepo = FakeBlockedRepo(),
            recorder = recorder,
        )

        init {
            // Default: the conversation exists (exact-match path), no
            // kept ciphertext, the identity fallback finds nothing.
            val conv = mockk<ConversationEntity>()
            every { conv.id } returns CONV_ID
            coEvery { conversationDao.findByAddressesCsv(any()) } returns conv
            coEvery { conversationDao.snapshotOneToOneConversations() } returns emptyList()
            coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot("FR")
            coEvery { messageDao.findDecryptFailed(any()) } returns emptyList()
            coEvery { messageDao.update(any()) } just Runs
            coEvery { messageDao.refreshConversationPreview(any()) } just Runs
        }

        /** A probe from [crypto]'s identity (as if it sent one). */
        fun probeFrom(crypto: FakeE2eCrypto): ByteArray {
            val keys = crypto.identityKeys()
            return E2eHandshake.encodeProbe(keys.curve25519, keys.ed25519)
        }

        /** A bundle from [crypto]: its identity keys + one one-time key. */
        fun bundleFrom(crypto: FakeE2eCrypto): ByteArray {
            val keys = crypto.identityKeys()
            val otk = crypto.nextOneTimeKey() ?: error("no one-time key left")
            return E2eHandshake.encodeBundle(keys.curve25519, keys.ed25519, otk)
        }
    }

    // ──────────────────────────── Probe → bundle reply ────────────────────────────

    @Test
    fun `a probe records the keys and earns a bundle reply`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), 3, NOW)

        assertThat(handled).isTrue()
        // One bundle reply, on the handshake port, carrying OUR keys.
        assertThat(harness.sender.sent).hasSize(1)
        val reply = harness.sender.sent.single()
        assertThat(reply.address).isEqualTo(BOB)
        assertThat(reply.subId).isEqualTo(3)
        assertThat(reply.port).isEqualTo(E2eHandshake.E2E_DATA_SMS_PORT)
        val bundle = E2eHandshake.decode(reply.data) as E2eHandshake.HandshakePacket.Bundle
        assertThat(bundle.curveKey).isEqualTo(harness.aliceCrypto.identityKeys().curve25519)
        assertThat(bundle.edKey).isEqualTo(harness.aliceCrypto.identityKeys().ed25519)
        // The peer row: PROBING, bob's keys on file, reply timestamped.
        val peer = harness.peerDao.peers[BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        val b64 = Base64.getEncoder()
        assertThat(peer?.theirCurveKey).isEqualTo(
            b64.encodeToString(harness.bobCrypto.identityKeys().curve25519),
        )
        assertThat(peer?.theirEdKey).isEqualTo(
            b64.encodeToString(harness.bobCrypto.identityKeys().ed25519),
        )
        assertThat(peer?.lastBundleReplyAt).isEqualTo(NOW)
    }

    @Test
    fun `a second probe the same day earns no second bundle`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        val handled = harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
    }

    @Test
    fun `a probe the next day earns a bundle again`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        harness.handler.handleDataSms(
            BOB,
            harness.probeFrom(harness.bobCrypto),
            null,
            NOW + MS_PER_DAY + 1,
        )

        assertThat(harness.sender.sent).hasSize(2)
    }

    @Test
    fun `a failed bundle send does not consume the day's reply`() = runTest {
        val harness = Harness()
        harness.sender.result = false

        val handled = harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isFalse()
        assertThat(harness.peerDao.peers[BOB]?.lastBundleReplyAt).isNull()
        // The keys are still recorded: only the reply is gated.
        assertThat(harness.peerDao.peers[BOB]?.theirCurveKey).isNotNull()
        // And a retry the same day may still send.
        harness.sender.result = true
        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)
        assertThat(harness.sender.sent).hasSize(2)
    }

    // ──────────────────── Round 42: inbound-path diagnostics ────────────────────
    // The inbound path is Timber-only, and Timber is a no-op in
    // release builds — so the handler records the handshake events
    // in the error log (fixed messages, no keys, no addresses).
    // The three tags make every outcome separable: no
    // `e2e-probe-received` means the probe never arrived;
    // `e2e-probe-received` + `e2e-bundle-sent` means inbound +
    // reply work; `e2e-bundle-rate-limited` means the path worked
    // recently (a reply already went out today).

    @Test
    fun `a probe records probe-received and bundle-sent`() = runTest {
        val harness = Harness()

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        verify(exactly = 1) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a rate-limited probe records rate-limited and no second bundle-sent`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        verify(exactly = 2) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a failed bundle send records probe-received but no bundle-sent`() = runTest {
        val harness = Harness()
        harness.sender.result = false

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        verify(exactly = 1) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a dropped packet records nothing`() = runTest {
        val harness = Harness()

        harness.handler.handleDataSms(BOB, "not a handshake".toByteArray(), null, NOW)

        verify(exactly = 0) { harness.recorder.recordEvent(any(), any()) }
    }

    @Test
    fun `the bundle reply send passes the BUNDLE kind`() = runTest {
        val harness = Harness()

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().kind).isEqualTo(DataSmsKind.BUNDLE)
    }

    // ──────────────────────────── Bundle → ENCRYPTED ────────────────────────────

    @Test
    fun `a bundle creates the session and flips the peer to ENCRYPTED`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isTrue()
        val peer = harness.peerDao.peers[BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(peer?.theirCurveKey).isNotNull()
        // A current session exists, and it can encrypt to bob.
        val sessionId = harness.sessions.currentSessionId(BOB)
        assertThat(sessionId).isNotNull()
        val encrypted = harness.aliceCrypto.encrypt(sessionId!!, "hi bob".toByteArray(Charsets.UTF_8))
        assertThat(encrypted.type).isEqualTo(OlmMessageType.PRE_KEY)
        // The one-time ACTIVATED notice, in bob's thread.
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().conversationId).isEqualTo(CONV_ID)
        assertThat(harness.noticeDao.notices.single().kind).isEqualTo(E2eNoticeKind.ACTIVATED)
    }

    @Test
    fun `first reply wins - a duplicate bundle is ignored`() = runTest {
        val harness = Harness()
        val bundle = harness.bundleFrom(harness.bobCrypto)
        harness.handler.handleDataSms(BOB, bundle, null, NOW)
        val firstSessionId = harness.sessions.currentSessionId(BOB)

        val handled = harness.handler.handleDataSms(BOB, bundle, null, NOW)

        assertThat(handled).isTrue()
        // No second session, no second notice, same current session.
        assertThat(harness.sessionDao.sessions).hasSize(1)
        assertThat(harness.sessions.currentSessionId(BOB)).isEqualTo(firstSessionId)
        assertThat(harness.noticeDao.notices).hasSize(1)
    }

    @Test
    fun `a bundle with no conversation still encrypts, without a notice`() = runTest {
        val harness = Harness()
        coEvery { harness.conversationDao.findByAddressesCsv(any()) } returns null

        val handled = harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isTrue()
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices).isEmpty()
    }

    @Test
    fun `a non-handshake packet is dropped silently`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleDataSms(BOB, "not a handshake".toByteArray(), null, NOW)

        assertThat(handled).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
    }

    // ──────────────────────────── Key change ────────────────────────────

    @Test
    fun `a probe with different keys clears verified and records KEY_CHANGED_VERIFIED`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)
        harness.peers.setVerified(BOB, true, NOW)
        harness.noticeDao.notices.clear()

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.carolCrypto), null, NOW)

        val peer = harness.peerDao.peers[BOB]
        assertThat(peer?.verified).isFalse()
        // The state stays ENCRYPTED: messages keep flowing (TOFU + notice).
        assertThat(peer?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        // Carol's keys are now on file, and the notice names the change.
        val b64 = Base64.getEncoder()
        assertThat(peer?.theirCurveKey).isEqualTo(
            b64.encodeToString(harness.carolCrypto.identityKeys().curve25519),
        )
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().kind)
            .isEqualTo(E2eNoticeKind.KEY_CHANGED_VERIFIED)
    }

    @Test
    fun `a bundle with different keys re-establishes and keeps the old session as fallback`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)
        val oldSessionId = harness.sessions.currentSessionId(BOB)

        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.carolCrypto), null, NOW)

        // Two sessions: the new one current, the old one a fallback.
        assertThat(harness.sessionDao.sessions).hasSize(2)
        val current = harness.sessions.currentSessionId(BOB)
        assertThat(current).isNotEqualTo(oldSessionId)
        assertThat(harness.sessionDao.sessionsForPeer(BOB).map { it.sessionId })
            .contains(oldSessionId)
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        // Unverified before, so the plain KEY_CHANGED notice — and no second
        // ACTIVATED: the first bundle recorded it once per conversation.
        // (The notice list is NOT cleared between bundles: recordOnce dedupes
        // against the stored ACTIVATED record, and clearing it would fake a
        // duplicate.)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.KEY_CHANGED })
            .hasSize(1)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED })
            .hasSize(1)
    }

    // ──────────────────────────── Kept ciphertext retry ────────────────────────────

    @Test
    fun `a bundle retries kept ciphertext and restores the message`() = runTest {
        val harness = Harness()
        // Bob's pre-key message to alice, kept because an earlier
        // decrypt attempt failed (the row is seeded directly — the
        // retry plumbing, not the original failure, is under test).
        val aliceOtk = harness.aliceCrypto.nextOneTimeKey()!!
        val bobSession = harness.bobCrypto.createOutboundSession(
            harness.aliceCrypto.identityKeys().curve25519,
            aliceOtk,
        )
        val encrypted = harness.bobCrypto.encrypt(bobSession, "kept secret".toByteArray(Charsets.UTF_8))
        val base64 = Base64.getEncoder().encodeToString(
            E2eWireFormat.encodePayload(encrypted, E2eWireFormat.PayloadType.TEXT),
        )
        val kept = MessageEntity(
            id = 99L,
            conversationId = CONV_ID,
            telephonyUri = null,
            address = BOB,
            body = "",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = NOW,
            dateSent = null,
            encrypted = true,
            decryptFailed = true,
            ciphertext = base64,
        )
        coEvery { harness.messageDao.findDecryptFailed(BOB) } returns listOf(kept)
        val updated = slot<MessageEntity>()
        coEvery { harness.messageDao.update(capture(updated)) } just Runs

        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)

        // The placeholder became the readable message.
        assertThat(updated.isCaptured).isTrue()
        assertThat(updated.captured.body).isEqualTo("kept secret")
        assertThat(updated.captured.decryptFailed).isFalse()
        assertThat(updated.captured.ciphertext).isNull()
        coVerify(exactly = 1) { harness.messageDao.refreshConversationPreview(CONV_ID) }
    }

    @Test
    fun `kept ciphertext that still cannot decrypt stays a placeholder`() = runTest {
        val harness = Harness()
        val kept = MessageEntity(
            id = 99L,
            conversationId = CONV_ID,
            telephonyUri = null,
            address = BOB,
            body = "",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = NOW,
            dateSent = null,
            encrypted = true,
            decryptFailed = true,
            ciphertext = Base64.getEncoder().encodeToString("garbage, not a payload".toByteArray()),
        )
        coEvery { harness.messageDao.findDecryptFailed(BOB) } returns listOf(kept)

        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)

        coVerify(exactly = 0) { harness.messageDao.update(any()) }
        coVerify(exactly = 0) { harness.messageDao.refreshConversationPreview(any()) }
    }
}
