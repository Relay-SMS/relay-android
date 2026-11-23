package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The encrypted-reaction round trip (design §3.2): Alice encrypts a
 * reaction with payload type REACTION through the real send manager,
 * the fragments cross to Bob's real receive manager, Bob decrypts,
 * and the decoder folds the tapback onto its target — no new message
 * row, no bubble, exactly like a plain tapback.
 *
 * Two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer); the
 * session is established the way the handshake handler does it
 * (outbound session from bob's bundle + [E2eSessionStore.saveNewSession]).
 * Bob's mirror and notifier are mocked: what folds and what notifies
 * is asserted on the mocks.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eReactionRoundTripTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33612345678"
        const val CONV_ID = 11L
        const val TARGET_ID = 55L
        const val NOW = 1_800_000_000_000L
        const val LOCAL_ID = 42L
    }

    private class RecordingRecorder : SentSmsRecorder {
        val rows = mutableListOf<String>()

        override fun insertSentSms(
            address: String,
            body: String,
            date: Long,
            threadId: Long?,
            subId: Int?,
        ): String? {
            rows += body
            return "content://sms/1"
        }
    }

    private class Harness {
        val aliceCrypto = FakeE2eCrypto("alice")
        val bobCrypto = FakeE2eCrypto("bob")

        // ── Alice (sender) ──
        val alicePeerDao = FakeE2ePeerDao()
        val aliceSessionDao = FakeE2eSessionDao()
        val aliceAccountDao = FakeE2eAccountDao()
        val sender = mockk<SmsSender>()
        val recorder = RecordingRecorder()
        val aliceMessageDao = mockk<MessageDao>()
        val aliceProbeManager = mockk<E2eProbeManager>()
        // Round 48 — the text sender for media key messages.
        val dataSmsSender = RecordingDataSmsSender()

        val alicePeers = E2ePeerRepository(alicePeerDao)
        val aliceSessions = E2eSessionStore(aliceSessionDao, aliceCrypto, fakeKeyManager())
        val aliceAccountManager = E2eAccountManager(aliceAccountDao, aliceCrypto, fakeKeyManager())

        val aliceSendManager = E2eSendManager(
            peers = alicePeers,
            sessions = lazyOf(aliceSessions),
            accountManager = lazyOf(aliceAccountManager),
            crypto = lazyOf(aliceCrypto as E2eCrypto),
            sender = sender,
            recorder = recorder,
            messageDao = aliceMessageDao,
            probeManager = aliceProbeManager,
            dataSmsSender = dataSmsSender,
        )

        // ── Bob (receiver) ──
        val fragmentDao = FakeE2eFragmentDao()
        val bobPeerDao = FakeE2ePeerDao()
        val bobSessionDao = FakeE2eSessionDao()
        val bobAccountDao = FakeE2eAccountDao()
        val noticeDao = FakeE2eNoticeDao()

        val mirror = mockk<ConversationMirror>()
        val bobMessageDao = mockk<MessageDao>()
        val conversationRepo = mockk<ConversationRepository>()
        val notifier = mockk<IncomingMessageNotifier>()
        val bobProbeManager = mockk<E2eProbeManager>()
        val handshakeHandler = mockk<E2eHandshakeHandler>()
        // Round 48 — relaxed: the declaration is best-effort and
        // invisible to the existing assertions.
        val mediaCapability = mockk<E2eMediaCapabilityManager>(relaxed = true)
        // Round 48 — relaxed: this test never sends a MEDIA_KEY
        // payload, so the store is never touched.
        val mediaKeys = mockk<E2eMediaKeyStore>(relaxed = true)
        val context = mockk<Context>()

        val bobPeers = E2ePeerRepository(bobPeerDao)
        val bobSessions = E2eSessionStore(bobSessionDao, bobCrypto, fakeKeyManager())
        val bobAccountManager = E2eAccountManager(bobAccountDao, bobCrypto, fakeKeyManager())
        val decryptor = E2eSessionDecryptor(
            lazyOf(bobCrypto as E2eCrypto),
            lazyOf(bobSessions),
            lazyOf(bobAccountManager),
        )

        val bobReceiveManager = E2eReceiveManager(
            fragments = E2eFragmentStore(fragmentDao),
            peers = bobPeers,
            decryptor = decryptor,
            notices = E2eNoticeStore(noticeDao),
            mirror = mirror,
            messageDao = bobMessageDao,
            conversationRepo = conversationRepo,
            notifier = notifier,
            probeManager = bobProbeManager,
            handshakeHandler = handshakeHandler,
            mediaCapability = mediaCapability,
            mediaKeys = mediaKeys,
            context = context,
        )

        init {
            every {
                sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
            } returns Outcome.Success(Unit)
            coEvery { aliceMessageDao.findById(any()) } returns null
            coEvery { aliceMessageDao.setCiphertext(any(), any()) } just Runs
            every { aliceProbeManager.probeForSend(any(), any()) } just Runs

            coEvery {
                mirror.applyIncomingReaction(any(), any(), any(), any(), any(), any(), any())
            } returns ConversationMirror.ReactionApplied(
                conversationId = CONV_ID,
                targetMessageId = TARGET_ID,
                targetBody = "Hello",
            )
            coEvery { conversationRepo.findMessageForResend(any()) } answers {
                Message(
                    id = firstArg(),
                    conversationId = CONV_ID,
                    address = ALICE,
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
            }
            coEvery { notifier.notifyIncoming(any(), any(), any(), any()) } just Runs
            coEvery { handshakeHandler.retryDecryptFailed(any()) } returns 0
            every { bobProbeManager.reprobeAfterDecryptFailure(any(), any()) } just Runs
            every {
                context.getString(R.string.reaction_notif_body_with_preview, any(), any())
            } returns "reaction"
            every { context.getString(R.string.reaction_notif_body_no_preview, any()) } returns
                "reaction"
        }

        /** Alice's peer row + outbound session to bob, as the handshake handler builds them. */
        suspend fun establishAliceSession() {
            alicePeerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = E2ePeerState.ENCRYPTED,
                updatedAt = NOW,
            )
            val bobOtk = bobCrypto.nextOneTimeKey() ?: error("bob has no one-time key")
            val sessionId = aliceCrypto.createOutboundSession(
                bobCrypto.identityKeys().curve25519,
                bobOtk,
            )
            aliceSessions.saveNewSession(BOB, sessionId, NOW)
        }

        /**
         * Alice encrypts [tapback] as a REACTION and the fragments cross
         * to bob's receive manager. Returns the fragment strings alice
         * sent, for wire assertions.
         */
        suspend fun aliceReacts(tapback: String): List<String> {
            val sent = mutableListOf<String>()
            every {
                sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                sent += thirdArg<String>()
                Outcome.Success(Unit)
            }
            val outcome = aliceSendManager.sendEncrypted(
                LOCAL_ID,
                BOB,
                tapback,
                NOW,
                null,
                false,
                0,
                E2ePayloadType.REACTION,
            )
            assertThat(outcome).isEqualTo(Outcome.Success(Unit))
            for (text in sent) {
                val fragment = E2eWireFormat.parseFragment(text)
                    ?: error("not a fragment: $text")
                bobReceiveManager.handleFragment(ALICE, fragment, "content://sms/1", NOW, 3, NOW)
            }
            return sent
        }
    }

    @Test
    fun `alice encrypts a reaction - bob decrypts and folds it onto the target`() = runTest {
        val harness = Harness()
        harness.establishAliceSession()

        val sent = harness.aliceReacts("Liked \"Hello\"")

        // The wire payload carries the REACTION type.
        val parsed = sent.map { E2eWireFormat.parseFragment(it)!! }
        val payload = E2eWireFormat.parsePayload(E2eWireFormat.assemble(parsed))
        assertThat(payload.payloadType).isEqualTo(E2eWireFormat.PayloadType.REACTION)
        // Bob folded the tapback onto the message whose body starts
        // with "Hello" — no new message row, no bubble.
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            harness.mirror.applyIncomingReaction(
                address = ALICE,
                emoji = "👍",
                bodyPrefix = "Hello",
                kind = IncomingReactionDecoder.DecodedReaction.Kind.Tapback,
                wasTruncated = false,
                isRemoval = false,
                reactionDateMs = NOW,
            )
        }
        // Bob is notified about the reaction, on the target message.
        coVerify(exactly = 1) {
            harness.notifier.notifyIncoming(ALICE, "reaction", TARGET_ID, CONV_ID)
        }
        // The pre-key message flipped bob's peer to ENCRYPTED with the
        // one-time ACTIVATED notice.
        assertThat(harness.bobPeerDao.peers[ALICE]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().kind).isEqualTo(E2eNoticeKind.ACTIVATED)
        assertThat(harness.fragmentDao.fragments).isEmpty()
    }

    @Test
    fun `alice encrypts a removal - bob clears the badge and stays silent`() = runTest {
        val harness = Harness()
        harness.establishAliceSession()

        harness.aliceReacts("Removed a like from \"Hello\"")

        // The removal decodes as a removal and folds — still no bubble.
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            harness.mirror.applyIncomingReaction(
                address = ALICE,
                emoji = "👍",
                bodyPrefix = "Hello",
                kind = IncomingReactionDecoder.DecodedReaction.Kind.Tapback,
                wasTruncated = false,
                isRemoval = true,
                reactionDateMs = NOW,
            )
        }
        // A removal only clears the badge — nothing to notify about.
        coVerify(exactly = 0) { harness.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test
    fun `an encrypted reaction with no target becomes a message on bob side`() = runTest {
        val harness = Harness()
        harness.establishAliceSession()
        // No fold: the mirror finds no matching outgoing message.
        coEvery {
            harness.mirror.applyIncomingReaction(any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery { harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any()) } returns 100L

        harness.aliceReacts("Liked \"Hello\"")

        // The decrypted body is stored verbatim so nothing is silently
        // lost — the plain path's fall-through.
        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingSms(
                address = ALICE,
                body = "Liked \"Hello\"",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
    }
}
