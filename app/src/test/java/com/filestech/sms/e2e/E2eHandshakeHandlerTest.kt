package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
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
        // Round 48 — relaxed: the declaration is best-effort and
        // invisible to the existing assertions.
        val mediaCapability = mockk<E2eMediaCapabilityManager>(relaxed = true)

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
            mediaCapability = mediaCapability,
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
        fun probeFrom(crypto: FakeE2eCrypto, manual: Boolean = false): ByteArray {
            val keys = crypto.identityKeys()
            return E2eHandshake.encodeProbe(keys.curve25519, keys.ed25519, manual = manual)
        }

        /** A bundle from [crypto]: its identity keys + one one-time key. */
        fun bundleFrom(crypto: FakeE2eCrypto): ByteArray {
            val keys = crypto.identityKeys()
            val otk = crypto.nextOneTimeKey() ?: error("no one-time key left")
            return E2eHandshake.encodeBundle(keys.curve25519, keys.ed25519, otk)
        }

        /** A probe from [crypto]'s identity, as a regular SMS (round 45). */
        fun probeSmsFrom(crypto: FakeE2eCrypto, manual: Boolean = false): String =
            E2eHandshakeSms.encode(probeFrom(crypto, manual))

        /** A bundle from [crypto], as a regular SMS (round 45). */
        fun bundleSmsFrom(crypto: FakeE2eCrypto): String =
            E2eHandshakeSms.encode(bundleFrom(crypto))
    }

    // ──────────────────────────── Probe → bundle reply ────────────────────────────

    @Test
    fun `a probe records the keys and earns a bundle reply`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), 3, NOW)

        assertThat(handled).isTrue()
        // One bundle reply, on the handshake port, carrying OUR keys.
        assertThat(harness.sender.sent).hasSize(1)
        // A data-SMS probe earns a data-SMS reply — never a text.
        assertThat(harness.sender.sentTexts).isEmpty()
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

    // ──────────────────── Round 47: manual-probe bypass ────────────────────
    // A manual probe (flag bit, bit 7 of the type byte) bypasses the
    // receiver's one-bundle-per-day rate limit: the sender's user
    // tapped Retry, and the tap is pointless if the reply is still
    // gated. Automatic probes are never marked manual, so the limit
    // still applies to them — and a successful manual bundle send
    // still consumes the limit, so the next automatic probe is
    // gated. Both channels (data SMS + `RLYH:` text) honor it.

    @Test
    fun `a manual probe bypasses the bundle rate limit`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        val handled = harness.handler.handleDataSms(
            BOB,
            harness.probeFrom(harness.bobCrypto, manual = true),
            null,
            NOW,
        )

        assertThat(handled).isTrue()
        // A second bundle went out the same day — the manual flag
        // bypassed the limit.
        assertThat(harness.sender.sent).hasSize(2)
        verify(exactly = 2) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `an automatic probe after a manual bundle is still rate-limited`() = runTest {
        val harness = Harness()
        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)
        harness.handler.handleDataSms(
            BOB,
            harness.probeFrom(harness.bobCrypto, manual = true),
            null,
            NOW,
        )

        val handled = harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isTrue()
        // The manual bundle consumed the day's reply: the automatic
        // probe earns nothing.
        assertThat(harness.sender.sent).hasSize(2)
        assertThat(harness.peerDao.peers[BOB]?.lastBundleReplyAt).isEqualTo(NOW)
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a manual SMS probe bypasses the bundle rate limit`() = runTest {
        val harness = Harness()
        harness.handler.handleSms(BOB, harness.probeSmsFrom(harness.bobCrypto), null, NOW)

        val handled = harness.handler.handleSms(
            BOB,
            harness.probeSmsFrom(harness.bobCrypto, manual = true),
            null,
            NOW,
        )

        assertThat(handled).isTrue()
        // The second bundle goes out by text, like the first — the
        // reply follows the probe's arrival channel.
        assertThat(harness.sender.sentTexts).hasSize(2)
        assertThat(harness.sender.sent).isEmpty()
        verify(exactly = 2) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a manual probe from a peer with no prior bundle still works`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleDataSms(
            BOB,
            harness.probeFrom(harness.bobCrypto, manual = true),
            null,
            NOW,
        )

        assertThat(handled).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        val peer = harness.peerDao.peers[BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(peer?.theirCurveKey).isNotNull()
        assertThat(peer?.lastBundleReplyAt).isEqualTo(NOW)
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

    // ──────────────────── Handshake over regular SMS (round 45) ────────────────────

    @Test
    fun `an SMS probe earns a bundle reply by text, not by data SMS`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleSms(BOB, harness.probeSmsFrom(harness.bobCrypto), 3, NOW)

        assertThat(handled).isTrue()
        // One bundle reply, as a regular SMS carrying OUR keys.
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.sender.sent).isEmpty()
        val reply = harness.sender.sentTexts.single()
        assertThat(reply.address).isEqualTo(BOB)
        assertThat(reply.subId).isEqualTo(3)
        assertThat(reply.kind).isEqualTo(DataSmsKind.BUNDLE)
        assertThat(reply.text).startsWith("RLYH:")
        val bundle = E2eHandshake.decode(E2eHandshakeSms.parse(reply.text)!!)
            as E2eHandshake.HandshakePacket.Bundle
        assertThat(bundle.curveKey).isEqualTo(harness.aliceCrypto.identityKeys().curve25519)
        assertThat(bundle.edKey).isEqualTo(harness.aliceCrypto.identityKeys().ed25519)
        // The peer row: PROBING, bob's keys on file, reply timestamped.
        val peer = harness.peerDao.peers[BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(peer?.lastBundleReplyAt).isEqualTo(NOW)
        // Same diagnostic tags as the data path — the channel is not logged.
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `an SMS bundle creates the session and flips the peer to ENCRYPTED`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleSms(BOB, harness.bundleSmsFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isTrue()
        assertThat(harness.peers.state(BOB)).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `a non-handshake text is ignored`() = runTest {
        val harness = Harness()

        val plain = harness.handler.handleSms(BOB, "hello", null, NOW)
        val fragment = harness.handler.handleSms(
            BOB,
            "RLY1:abc123:01/01:AQEB3q2+7w==",
            null,
            NOW,
        )
        val badMagic = harness.handler.handleSms(BOB, "RLYH:!!!", null, NOW)

        assertThat(plain).isFalse()
        assertThat(fragment).isFalse()
        assertThat(badMagic).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
        verify(exactly = 0) { harness.recorder.recordEvent(any(), any()) }
    }

    @Test
    fun `a second SMS probe the same day earns no second bundle text`() = runTest {
        val harness = Harness()
        harness.handler.handleSms(BOB, harness.probeSmsFrom(harness.bobCrypto), null, NOW)

        val handled = harness.handler.handleSms(BOB, harness.probeSmsFrom(harness.bobCrypto), null, NOW)

        assertThat(handled).isTrue()
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.sender.sent).isEmpty()
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a failed text bundle send does not consume the day's reply`() = runTest {
        val harness = Harness()
        harness.sender.result = false

        val failed = harness.handler.handleSms(BOB, harness.probeSmsFrom(harness.bobCrypto), null, NOW)

        assertThat(failed).isFalse()
        assertThat(harness.peerDao.peers[BOB]?.lastBundleReplyAt).isNull()
        // The keys are still recorded: only the reply is gated.
        assertThat(harness.peerDao.peers[BOB]?.theirCurveKey).isNotNull()
        // And a retry the same day may still send (the fake records
        // the failed attempt too, so the retry is the second entry).
        harness.sender.result = true
        val retried = harness.handler.handleSms(BOB, harness.probeSmsFrom(harness.bobCrypto), null, NOW)

        assertThat(retried).isTrue()
        assertThat(harness.sender.sentTexts).hasSize(2)
        assertThat(harness.peerDao.peers[BOB]?.lastBundleReplyAt).isEqualTo(NOW)
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
    fun `a bundle after a deactivation records a second ACTIVATED notice`() = runTest {
        val harness = Harness()
        // First establishment: one ACTIVATED notice.
        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED })
            .hasSize(1)
        // The peer-left fallback tore the session down (state + notice).
        harness.peers.setState(BOB, E2ePeerState.UNKNOWN, NOW + 1000)
        E2eNoticeStore(harness.noticeDao)
            .record(CONV_ID, E2eNoticeKind.DEACTIVATED, NOW + 1000)
        // The old session is gone (round 46: sessions stored under the
        // pre-canonical key are unreachable), so the re-probe's bundle
        // is NOT a duplicate — it re-establishes. Handing a key out
        // empties the pool (vodozemac marks the whole unpublished batch
        // published), so top it up before taking a second key.
        harness.sessionDao.deleteByAddress(BOB)
        harness.bobCrypto.generateOneTimeKeys()

        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW + 2000)

        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED })
            .hasSize(2)
        assertThat(harness.noticeDao.notices.map { it.kind }).containsExactly(
            E2eNoticeKind.ACTIVATED,
            E2eNoticeKind.DEACTIVATED,
            E2eNoticeKind.ACTIVATED,
        ).inOrder()
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
        // ACTIVATED: the thread already shows the encryption as active (no
        // DEACTIVATED since the first ACTIVATED), so recordActivated skips.
        // (The notice list is NOT cleared between bundles: the store dedupes
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

    // ──────────────────────────── Round 48: media capability ────────────────────────────

    @Test
    fun `a capability packet by data SMS is handled and delegated, with no bundle reply`() = runTest {
        val harness = Harness()

        val handled = harness.handler.handleDataSms(BOB, E2eHandshake.encodeMediaCapable(), 3, NOW)

        assertThat(handled).isTrue()
        coVerify { harness.mediaCapability.onDeclarationReceived(BOB, 3, NOW) }
        // A declaration earns no bundle reply — it is not a probe.
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `a capability packet by text SMS is handled and delegated`() = runTest {
        val harness = Harness()
        val text = E2eHandshakeSms.encode(E2eHandshake.encodeMediaCapable())

        val handled = harness.handler.handleSms(BOB, text, null, NOW)

        assertThat(handled).isTrue()
        coVerify { harness.mediaCapability.onDeclarationReceived(BOB, null, NOW) }
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `a bundle receipt records the channel and announces our capability`() = runTest {
        val harness = Harness()

        harness.handler.handleDataSms(BOB, harness.bundleFrom(harness.bobCrypto), null, NOW)

        // The bundle arrived on data SMS, so our probe reached them
        // on it: the channel is proven for our declaration.
        assertThat(harness.peerDao.peers[BOB]!!.handshakeChannel).isEqualTo("DATA_SMS")
        coVerify { harness.mediaCapability.announceNow(BOB, null, NOW) }
    }

    @Test
    fun `a probe receipt records the channel while not yet ENCRYPTED`() = runTest {
        val harness = Harness()

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        assertThat(harness.peerDao.peers[BOB]!!.handshakeChannel).isEqualTo("DATA_SMS")
    }

    @Test
    fun `a probe from an already-ENCRYPTED peer does not overwrite the recorded channel`() = runTest {
        val harness = Harness()
        harness.peerDao.peers[BOB] = E2ePeerEntity(
            address = BOB,
            state = E2ePeerState.ENCRYPTED,
            handshakeChannel = "TEXT_SMS",
            updatedAt = NOW,
        )

        harness.handler.handleDataSms(BOB, harness.probeFrom(harness.bobCrypto), null, NOW)

        // The proven channel survives a later exchange on another
        // channel (a key-change probe is not proof the new
        // channel delivers).
        assertThat(harness.peerDao.peers[BOB]!!.handshakeChannel).isEqualTo("TEXT_SMS")
    }
}
