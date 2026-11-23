package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.system.notifications.IncomingMessageNotifier
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
 * The ciphertext receive path (design §3.5): fragment reassembly,
 * pre-key inbound, fallback decrypt, placeholders, reaction folding,
 * and the plain-body regression (§12.2).
 *
 * Two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer); the
 * stores are the real ones over in-memory fake DAOs. The mirror,
 * notifier, and probe manager are mocked: what lands in Room and what
 * notifies is asserted on the mocks.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eReceiveManagerTest {

    private companion object {
        const val BOB = "+33612345678"
        const val CONV_ID = 11L
        const val NOW = 1_800_000_000_000L
        const val MSG_ID = "a1b2c3"
    }

    /** A crypto that throws when decrypting with [failingSessionId] (a corrupt current session). */
    private class ThrowingForSession(
        private val delegate: E2eCrypto,
        var failingSessionId: String? = null,
    ) : E2eCrypto by delegate {
        override fun decrypt(sessionId: String, message: EncryptedMessage): ByteArray {
            if (sessionId == failingSessionId) {
                throw E2eCryptoException("simulated corrupt session")
            }
            return delegate.decrypt(sessionId, message)
        }
    }

    private class Harness(
        cryptoOverride: E2eCrypto? = null,
    ) {
        val aliceCrypto = FakeE2eCrypto("alice")
        val bobCrypto = FakeE2eCrypto("bob")
        val bob2Crypto = FakeE2eCrypto("bob2")

        /** The crypto the receive path uses (a throwing wrapper for the fallback test). */
        val crypto: E2eCrypto = cryptoOverride ?: aliceCrypto

        val fragmentDao = FakeE2eFragmentDao()
        val peerDao = FakeE2ePeerDao()
        val sessionDao = FakeE2eSessionDao()
        val accountDao = FakeE2eAccountDao()
        val noticeDao = FakeE2eNoticeDao()

        val mirror = mockk<ConversationMirror>()
        val messageDao = mockk<com.filestech.sms.data.local.db.dao.MessageDao>()
        val conversationRepo = mockk<ConversationRepository>()
        val notifier = mockk<IncomingMessageNotifier>()
        val probeManager = mockk<E2eProbeManager>()
        val handshakeHandler = mockk<E2eHandshakeHandler>()
        val context = mockk<Context>()

        val peers = E2ePeerRepository(peerDao)
        val sessions = E2eSessionStore(sessionDao, crypto, fakeKeyManager())
        val accountManager = E2eAccountManager(accountDao, crypto, fakeKeyManager())
        val decryptor = E2eSessionDecryptor(lazyOf(crypto), lazyOf(sessions), lazyOf(accountManager))

        val manager = E2eReceiveManager(
            fragments = E2eFragmentStore(fragmentDao),
            peers = peers,
            decryptor = decryptor,
            notices = E2eNoticeStore(noticeDao),
            mirror = mirror,
            messageDao = messageDao,
            conversationRepo = conversationRepo,
            notifier = notifier,
            probeManager = probeManager,
            handshakeHandler = handshakeHandler,
            context = context,
        )

        private var nextMsgId = 100L

        init {
            coEvery { mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any()) } answers {
                nextMsgId++
            }
            coEvery { mirror.applyIncomingReaction(any(), any(), any(), any(), any(), any(), any()) } returns null
            coEvery { messageDao.markDecryptFailed(any(), any()) } just Runs
            coEvery { conversationRepo.findMessageForResend(any()) } answers {
                messageInConv(firstArg())
            }
            coEvery { notifier.notifyIncoming(any(), any(), any(), any()) } just Runs
            coEvery { handshakeHandler.retryDecryptFailed(any()) } returns 0
            every { probeManager.reprobeAfterDecryptFailure(any(), any()) } just Runs
            every { probeManager.reprobeAfterPeerLeft(any(), any()) } just Runs
            every { context.getString(R.string.e2e_decrypt_failed_notif) } returns
                "This message couldn't be decrypted."
            every {
                context.getString(R.string.reaction_notif_body_with_preview, any(), any())
            } returns "reaction"
            every { context.getString(R.string.reaction_notif_body_no_preview, any()) } returns
                "reaction"
        }

        private fun messageInConv(id: Long) = Message(
            id = id,
            conversationId = CONV_ID,
            address = BOB,
            body = "",
            type = Message.Type.SMS,
            direction = Message.Direction.INCOMING,
            date = NOW,
            dateSent = null,
            read = false,
            starred = false,
            status = Message.Status.RECEIVED,
            errorCode = null,
            attachmentsCount = 0,
            subId = null,
            scheduledAt = null,
        )

        /**
         * Bob encrypts [plaintext] on his outbound session to alice
         * (created on first use) and returns the fragment strings.
         */
        fun bobFragments(
            plaintext: String,
            bob: FakeE2eCrypto = bobCrypto,
            payloadType: E2eWireFormat.PayloadType = E2eWireFormat.PayloadType.TEXT,
            messageId: String = MSG_ID,
        ): List<String> {
            val sessionId = bobSessions.getOrPut(bob) {
                // Handing a key out empties the pool (vodozemac marks
                // the whole unpublished batch published): top it up
                // first so a second bob still finds a key.
                aliceCrypto.generateOneTimeKeys()
                val otk = aliceCrypto.nextOneTimeKey() ?: error("alice has no one-time key")
                bob.createOutboundSession(aliceCrypto.identityKeys().curve25519, otk)
            }
            val encrypted = bob.encrypt(sessionId, plaintext.toByteArray(Charsets.UTF_8))
            return E2eWireFormat.encode(messageId, encrypted, payloadType)
        }

        private val bobSessions = mutableMapOf<FakeE2eCrypto, String>()

        /** Feeds every fragment string of a set through the manager, in order. */
        suspend fun feed(fragments: List<String>, subId: Int? = 3) {
            for (text in fragments) {
                val fragment = E2eWireFormat.parseFragment(text)
                    ?: error("not a fragment: $text")
                manager.handleFragment(BOB, fragment, "content://sms/1", NOW, subId, NOW)
            }
        }

        /** Seeds bob as an ENCRYPTED peer (verified when asked). */
        fun seedEncryptedPeer(verified: Boolean = false, updatedAt: Long = NOW) {
            peerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = E2ePeerState.ENCRYPTED,
                verified = verified,
                updatedAt = updatedAt,
            )
        }
    }

    // ──────────────────────────── Reassembly + decrypt ────────────────────────────

    @Test
    fun `a pre-key message establishes the session and inserts one decrypted message`() = runTest {
        val harness = Harness()

        harness.feed(harness.bobFragments("hello alice"))

        // One message, decrypted, encrypted-flagged.
        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = "hello alice",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        // The peer flipped to ENCRYPTED with the one-time ACTIVATED notice.
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().kind).isEqualTo(E2eNoticeKind.ACTIVATED)
        // The fragment rows are gone, the user is notified with plaintext.
        assertThat(harness.fragmentDao.fragments).isEmpty()
        coVerify(exactly = 1) {
            harness.notifier.notifyIncoming(BOB, "hello alice", 100L, CONV_ID)
        }
        // A new session means kept ciphertext gets retried.
        coVerify(exactly = 1) { harness.handshakeHandler.retryDecryptFailed(BOB) }
    }

    @Test
    fun `a later normal message decrypts with the current session`() = runTest {
        val harness = Harness()
        harness.feed(harness.bobFragments("first"))
        harness.noticeDao.notices.clear()

        harness.feed(harness.bobFragments("second", messageId = "d4e5f6"))

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = "second",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        // No new session, no new notice, no retry.
        assertThat(harness.noticeDao.notices).isEmpty()
        coVerify(exactly = 1) { harness.handshakeHandler.retryDecryptFailed(BOB) }
    }

    @Test
    fun `out-of-order fragments with duplicates reassemble into one message`() = runTest {
        val harness = Harness()
        val fragments = harness.bobFragments("x".repeat(300))
        assertThat(fragments.size).isGreaterThan(2)
        val shuffled = listOf(
            fragments[2],
            fragments[0],
            fragments[1],
            fragments[1],
            fragments[2],
        ) + fragments.drop(3)

        harness.feed(shuffled)

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = "x".repeat(300),
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        assertThat(harness.fragmentDao.fragments).isEmpty()
    }

    @Test
    fun `an incomplete set inserts nothing and keeps its fragments`() = runTest {
        val harness = Harness()
        val fragments = harness.bobFragments("x".repeat(300))

        harness.feed(fragments.dropLast(1))

        coVerify(exactly = 0) { harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { harness.notifier.notifyIncoming(any(), any(), any(), any()) }
        assertThat(harness.fragmentDao.fragments).hasSize(fragments.size - 1)
    }

    @Test
    fun `a re-sent set after success inserts no duplicate`() = runTest {
        val harness = Harness()
        val fragments = harness.bobFragments("hello alice")

        harness.feed(fragments)
        harness.feed(fragments)

        coVerify(exactly = 1) { harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { harness.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    // ──────────────────────────── Fallback decrypt ────────────────────────────

    @Test
    fun `a corrupt current session falls back to the older session`() = runTest {
        val throwing = ThrowingForSession(FakeE2eCrypto("alice"))
        val harness = Harness(cryptoOverride = throwing)
        // Session 1: bob's first install.
        harness.feed(harness.bobFragments("from bob 1", messageId = "111111"))
        val session1 = harness.sessions.currentSessionId(BOB)
        // Session 2: bob's reinstall — current, session 1 a fallback.
        harness.feed(harness.bobFragments("from bob 2", bob = harness.bob2Crypto, messageId = "222222"))
        val session2 = harness.sessions.currentSessionId(BOB)
        assertThat(session2).isNotEqualTo(session1)
        // Now the current session is corrupt: decrypt throws on it.
        throwing.failingSessionId = session2

        // A late NORMAL message on session 1 (bob's old install).
        harness.feed(harness.bobFragments("late on session 1", messageId = "333333"))

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = "late on session 1",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
    }

    // ──────────────────────────── Placeholders ────────────────────────────

    @Test
    fun `an undecryptable message becomes a placeholder and triggers a re-probe`() = runTest {
        val harness = Harness()
        // A NORMAL message for a session alice never saw: bob encrypts
        // twice (pre-key, then normal) and only the normal half arrives.
        val otk = harness.aliceCrypto.nextOneTimeKey()!!
        val bobSession = harness.bobCrypto.createOutboundSession(
            harness.aliceCrypto.identityKeys().curve25519,
            otk,
        )
        harness.bobCrypto.encrypt(bobSession, "unseen pre-key".toByteArray(Charsets.UTF_8))
        val normal = harness.bobCrypto.encrypt(bobSession, "undecryptable".toByteArray(Charsets.UTF_8))
        assertThat(normal.type).isEqualTo(OlmMessageType.NORMAL)
        val fragments = E2eWireFormat.encode(MSG_ID, normal, E2eWireFormat.PayloadType.TEXT)

        harness.feed(fragments)

        // A placeholder row: empty body, encrypted, decrypt-failed with
        // the ciphertext kept for a later retry.
        val bodySlot = slot<String>()
        val cipherSlot = slot<String>()
        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = capture(bodySlot),
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        assertThat(bodySlot.captured).isEmpty()
        coVerify(exactly = 1) { harness.messageDao.markDecryptFailed(100L, capture(cipherSlot)) }
        assertThat(cipherSlot.captured).isNotEmpty()
        // The notification names the failure, never the ciphertext.
        coVerify(exactly = 1) {
            harness.notifier.notifyIncoming(BOB, "This message couldn't be decrypted.", 100L, CONV_ID)
        }
        // And the re-probe goes out (the peer probably reinstalled).
        verify(exactly = 1) { harness.probeManager.reprobeAfterDecryptFailure(BOB, 3) }
        assertThat(harness.fragmentDao.fragments).isEmpty()
    }

    @Test
    fun `a malformed payload becomes a placeholder`() = runTest {
        val harness = Harness()
        val garbage = Base64.getEncoder().encodeToString("garbage, not a payload".toByteArray())
        val fragmentText = "RLY1:$MSG_ID:01/01:$garbage"

        harness.feed(listOf(fragmentText))

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = "",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        coVerify(exactly = 1) { harness.messageDao.markDecryptFailed(any(), any()) }
        verify(exactly = 1) { harness.probeManager.reprobeAfterDecryptFailure(BOB, 3) }
    }

    // ──────────────────────────── Encrypted reactions ────────────────────────────

    @Test
    fun `an encrypted reaction folds onto its target`() = runTest {
        val harness = Harness()
        coEvery {
            harness.mirror.applyIncomingReaction(any(), any(), any(), any(), any(), any(), any())
        } returns ConversationMirror.ReactionApplied(
            conversationId = CONV_ID,
            targetMessageId = 55L,
            targetBody = "Hello",
        )

        harness.feed(
            harness.bobFragments(
                "Reacted ❤️ to «Hello»",
                payloadType = E2eWireFormat.PayloadType.REACTION,
            ),
        )

        // No bubble: the reaction folded.
        coVerify(exactly = 0) { harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            harness.mirror.applyIncomingReaction(
                address = BOB,
                emoji = "❤️",
                bodyPrefix = "Hello",
                kind = any(),
                wasTruncated = false,
                isRemoval = false,
                reactionDateMs = NOW,
            )
        }
        coVerify(exactly = 1) { harness.notifier.notifyIncoming(BOB, "reaction", 55L, CONV_ID) }
    }

    @Test
    fun `an encrypted reaction with no target becomes a message`() = runTest {
        val harness = Harness()
        // applyIncomingReaction returns null (the default stub): no fold.

        harness.feed(
            harness.bobFragments(
                "Reacted ❤️ to «Hello»",
                payloadType = E2eWireFormat.PayloadType.REACTION,
            ),
        )

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = BOB,
                body = "Reacted ❤️ to «Hello»",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
    }

    // ──────────────────────────── Plain-body regression (§12.2) ────────────────────────────

    @Test
    fun `ordinary texts never parse as fragments`() {
        assertThat(E2eWireFormat.parseFragment("Your code is 123456")).isNull()
        assertThat(E2eWireFormat.parseFragment("Reacted ❤️ to «Hello»")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:zzz")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abcdef:01/01:")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abcdef:03/02:AAAA")).isNull()
        assertThat(E2eWireFormat.parseFragment("rly1:abcdef:01/01:AAAA")).isNull()
        assertThat(E2eWireFormat.parseFragment("")).isNull()
    }

    // ──────────────────── Peer-deleted-Relay fallback (round 41) ────────────────────
    // MMS has no test here: it never reaches onPlainSmsReceived — the MMS
    // receivers (TraitementMmsRecu / MmsWapPushReceiver / MmsDownloadedReceiver)
    // never reference E2eReceiveManager, so the exclusion is structural.

    @Test
    fun `a plain SMS from an encrypted peer downgrades to unknown and re-probes`() = runTest {
        val harness = Harness()
        // Long-encrypted (2 h): past the round-46 grace window, so the
        // fallback fires exactly as before.
        harness.seedEncryptedPeer(verified = true, updatedAt = NOW - 2 * E2eReceiveManager.DEACTIVATED_GRACE_MS)

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        // UNKNOWN, not NOT_RELAY — the re-probe must still be able to go out.
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.UNKNOWN)
        // The verified flag clears, as on a key change.
        assertThat(harness.peerDao.peers[BOB]?.verified).isFalse()
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().kind).isEqualTo(E2eNoticeKind.DEACTIVATED)
        assertThat(harness.noticeDao.notices.single().conversationId).isEqualTo(CONV_ID)
        verify(exactly = 1) { harness.probeManager.reprobeAfterPeerLeft(BOB, 3) }
    }

    @Test
    fun `a plain SMS from a peer with no row does nothing`() = runTest {
        val harness = Harness()
        // No peer row: UNKNOWN by default.

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        // No row is created, no notice, no re-probe.
        assertThat(harness.peerDao.peers).isEmpty()
        assertThat(harness.noticeDao.notices).isEmpty()
        verify(exactly = 0) { harness.probeManager.reprobeAfterPeerLeft(any(), any()) }
    }

    @Test
    fun `a plain SMS from a probing peer does nothing`() = runTest {
        val harness = Harness()
        harness.peerDao.peers[BOB] = E2ePeerEntity(
            address = BOB,
            state = E2ePeerState.PROBING,
            updatedAt = NOW,
        )

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(harness.noticeDao.notices).isEmpty()
        verify(exactly = 0) { harness.probeManager.reprobeAfterPeerLeft(any(), any()) }
    }

    @Test
    fun `an encrypted message from an encrypted peer does not downgrade`() = runTest {
        val harness = Harness()
        harness.feed(harness.bobFragments("first"))
        // Established: ENCRYPTED + the one-time ACTIVATED notice.
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        harness.noticeDao.notices.clear()

        harness.feed(harness.bobFragments("second", messageId = "d4e5f6"))

        // Still encrypted, no DEACTIVATED notice, no peer-left re-probe.
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices).isEmpty()
        verify(exactly = 0) { harness.probeManager.reprobeAfterPeerLeft(any(), any()) }
    }

    @Test
    fun `a plain SMS from a freshly encrypted peer does not downgrade`() = runTest {
        val harness = Harness()
        // Round 46 — grace guard: encrypted 5 minutes ago, so the peer
        // may not have flipped yet (it needs our first encrypted
        // message to build its session); the plain reply is expected.
        harness.seedEncryptedPeer(verified = true, updatedAt = NOW - 5 * 60 * 1000L)

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        // Nothing changes: state, verified flag, notices, re-probe.
        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.peerDao.peers[BOB]?.verified).isTrue()
        assertThat(harness.noticeDao.notices).isEmpty()
        verify(exactly = 0) { harness.probeManager.reprobeAfterPeerLeft(any(), any()) }
    }

    @Test
    fun `a plain SMS at the exact grace edge fires the fallback`() = runTest {
        val harness = Harness()
        // Boundary: `now - updatedAt` is exactly the grace window, and
        // the guard is a strict `<` — so the window has expired and the
        // fallback fires, as for a long-encrypted peer.
        harness.seedEncryptedPeer(
            verified = true,
            updatedAt = NOW - E2eReceiveManager.DEACTIVATED_GRACE_MS,
        )

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        assertThat(harness.peerDao.peers[BOB]?.state).isEqualTo(E2ePeerState.UNKNOWN)
        assertThat(harness.peerDao.peers[BOB]?.verified).isFalse()
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().kind).isEqualTo(E2eNoticeKind.DEACTIVATED)
        verify(exactly = 1) { harness.probeManager.reprobeAfterPeerLeft(BOB, 3) }
    }

    @Test
    fun `a second plain SMS after the fallback triggers nothing more`() = runTest {
        val harness = Harness()
        // Long-encrypted (2 h): past the round-46 grace window, so the
        // first plain SMS fires the fallback.
        harness.seedEncryptedPeer(updatedAt = NOW - 2 * E2eReceiveManager.DEACTIVATED_GRACE_MS)

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)
        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        // One notice, one re-probe: the state gate dedupes.
        assertThat(harness.noticeDao.notices).hasSize(1)
        verify(exactly = 1) { harness.probeManager.reprobeAfterPeerLeft(BOB, 3) }
    }
}
