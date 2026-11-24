package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.domain.repository.ConversationRepository
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
 * Group receive-path tests (round 49, design §13): a GROUP_TEXT
 * message decrypts and lands in the group conversation, a
 * GROUP_REACTION folds onto its target, an undecryptable group
 * message becomes a placeholder + a key request, the SENDER_KEY
 * / GROUP_KEY_REQUEST control payloads route to the group session
 * manager, and the plain-SMS fallback notifies the group manager
 * before the peer flips.
 *
 * The group session manager is mocked (its own tests cover the
 * decrypt / store / notice logic); the fragment store, peer
 * store, session decryptor and crypto are real, so the control
 * payloads travel a real Olm session like the 1:1 tests.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eReceiveManagerGroupTest {

    private companion object {
        const val BOB = "+33612345678"
        const val ALICE = "6108423232"
        const val CAROL = "6108423234"
        const val CONV_ID = 11L
        const val MSG_ID = 101L
        const val NOW = 1_800_000_000_000L
        const val FRAG_ID = "a1b2c3"

        // The member list holds bob's CANONICAL address
        // ("+33612345678" → "33612345678"), like a real key row.
        val MEMBERS = listOf(ALICE, "33612345678", CAROL)
        val GROUP_ID = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS)
        const val KEY_ID = "0123456789abcdef"
    }

    private class Harness {
        val aliceCrypto = FakeE2eCrypto("alice")
        val bobCrypto = FakeE2eCrypto("bob")

        val fragmentDao = FakeE2eFragmentDao()
        val peerDao = FakeE2ePeerDao()
        val sessionDao = FakeE2eSessionDao()
        val accountDao = FakeE2eAccountDao()
        val noticeDao = FakeE2eNoticeDao()

        val mirror = mockk<ConversationMirror>()
        val messageDao = mockk<MessageDao>()
        val conversationRepo = mockk<ConversationRepository>()
        val notifier = mockk<IncomingMessageNotifier>()
        val probeManager = mockk<E2eProbeManager>()
        val handshakeHandler = mockk<E2eHandshakeHandler>()
        val mediaCapability = mockk<E2eMediaCapabilityManager>(relaxed = true)
        val mediaKeys = mockk<E2eMediaKeyStore>(relaxed = true)
        val groupSessions = mockk<E2eGroupSessionManager>()
        val context = mockk<Context>()

        val peers = E2ePeerRepository(peerDao)
        val sessions = E2eSessionStore(sessionDao, aliceCrypto, fakeKeyManager())
        val accountManager = E2eAccountManager(accountDao, aliceCrypto, fakeKeyManager())
        val decryptor = E2eSessionDecryptor(
            lazyOf(aliceCrypto as E2eCrypto),
            lazyOf(sessions),
            lazyOf(accountManager),
        )

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
            mediaCapability = mediaCapability,
            mediaKeys = mediaKeys,
            groupSessions = groupSessions,
            context = context,
        )

        init {
            coEvery { mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any()) } returns MSG_ID
            coEvery { mirror.upsertIncomingGroupSms(any(), any(), any(), any(), any(), any(), any()) } returns MSG_ID
            coEvery { mirror.applyIncomingReaction(any(), any(), any(), any(), any(), any(), any()) } returns null
            coEvery { messageDao.markDecryptFailed(any(), any()) } just Runs
            coEvery { messageDao.firstOutgoingEncryptedSince(any(), any()) } returns null
            coEvery { messageDao.countPlainIncomingSinceLastEncrypted(any(), any()) } returns 1
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

        /** Bob encrypts [plaintext] on his outbound session to alice and fragments it. */
        fun bobFragments(
            plaintext: String,
            payloadType: E2eWireFormat.PayloadType,
            messageId: String = FRAG_ID,
        ): List<String> {
            val sessionId = bobSessions.getOrPut(bobCrypto) {
                aliceCrypto.generateOneTimeKeys()
                val otk = aliceCrypto.nextOneTimeKey() ?: error("alice has no one-time key")
                bobCrypto.createOutboundSession(aliceCrypto.identityKeys().curve25519, otk)
            }
            val encrypted = bobCrypto.encrypt(sessionId, plaintext.toByteArray(Charsets.UTF_8))
            return E2eWireFormat.encode(messageId, encrypted, payloadType)
        }

        private val bobSessions = mutableMapOf<FakeE2eCrypto, String>()

        /** A GROUP fragment set around [envelopeBytes] (the envelope is opaque here). */
        fun groupFragments(
            envelopeBytes: ByteArray,
            payloadType: E2eWireFormat.PayloadType,
            messageId: String = FRAG_ID,
        ): List<String> = E2eWireFormat.encode(
            messageId,
            EncryptedMessage(OlmMessageType.GROUP, envelopeBytes),
            payloadType,
        )

        /** A well-formed envelope for this group (the receive path parses it on failure). */
        fun envelope(): ByteArray = E2eGroupWireFormat.encodeEnvelope(
            GROUP_ID,
            KEY_ID,
            ByteArray(40) { it.toByte() },
        )!!

        /** Feeds every fragment string of a set through the manager, in order. */
        suspend fun feed(fragments: List<String>, subId: Int? = 3) {
            for (text in fragments) {
                val fragment = E2eWireFormat.parseFragment(text)
                    ?: error("not a fragment: $text")
                manager.handleFragment(BOB, fragment, "content://sms/1", NOW, subId, NOW)
            }
        }

        fun seedEncryptedPeer() {
            peerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = E2ePeerState.ENCRYPTED,
                updatedAt = NOW,
            )
        }
    }

    // ──────────────────────────── GROUP_TEXT ────────────────────────────

    @Test
    fun `a group text decrypts and lands in the group conversation`() = runTest {
        val harness = Harness()
        val decrypted = GroupDecrypted("hello group", GROUP_ID, MEMBERS, KEY_ID)
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns decrypted
        coEvery {
            harness.groupSessions.conversationIdForGroup(GROUP_ID, MEMBERS)
        } returns CONV_ID
        coEvery { harness.groupSessions.recordGroupActivated(CONV_ID, NOW) } returns true

        harness.feed(
            harness.groupFragments(harness.envelope(), E2eWireFormat.PayloadType.GROUP_TEXT),
        )

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingGroupSms(
                conversationId = CONV_ID,
                senderAddress = BOB,
                body = "hello group",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        coVerify(exactly = 1) { harness.groupSessions.recordGroupActivated(CONV_ID, NOW) }
        coVerify(exactly = 1) { harness.notifier.notifyIncoming(BOB, "hello group", MSG_ID, CONV_ID) }
        // Never the 1:1 insert: a group message has no 1:1
        // conversation to fall back to.
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a group text with no resolvable conversation is dropped, not misrouted`() = runTest {
        val harness = Harness()
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns GroupDecrypted("hello group", GROUP_ID, MEMBERS, KEY_ID)
        coEvery {
            harness.groupSessions.conversationIdForGroup(GROUP_ID, MEMBERS)
        } returns null

        harness.feed(
            harness.groupFragments(harness.envelope(), E2eWireFormat.PayloadType.GROUP_TEXT),
        )

        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingGroupSms(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { harness.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    // ──────────────────────────── GROUP_REACTION ────────────────────────────

    @Test
    fun `a group reaction folds onto its target and records the activation`() = runTest {
        val harness = Harness()
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns GroupDecrypted("Reacted ❤️ to «Hello»", GROUP_ID, MEMBERS, KEY_ID)
        coEvery {
            harness.mirror.applyIncomingReaction(any(), any(), any(), any(), any(), any(), any())
        } returns ConversationMirror.ReactionApplied(
            conversationId = CONV_ID,
            targetMessageId = 55L,
            targetBody = "Hello",
        )
        coEvery { harness.groupSessions.recordGroupActivated(CONV_ID, NOW) } returns true

        harness.feed(
            harness.groupFragments(harness.envelope(), E2eWireFormat.PayloadType.GROUP_REACTION),
        )

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
        coVerify(exactly = 1) { harness.groupSessions.recordGroupActivated(CONV_ID, NOW) }
        coVerify(exactly = 1) { harness.notifier.notifyIncoming(BOB, "reaction", 55L, CONV_ID) }
    }

    @Test
    fun `a group reaction with no target becomes a message in the group conversation`() = runTest {
        val harness = Harness()
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns GroupDecrypted("Reacted ❤️ to «Hello»", GROUP_ID, MEMBERS, KEY_ID)
        coEvery {
            harness.groupSessions.conversationIdForGroup(GROUP_ID, MEMBERS)
        } returns CONV_ID
        coEvery { harness.groupSessions.recordGroupActivated(CONV_ID, NOW) } returns true
        // applyIncomingReaction returns null (the default stub): no fold.

        harness.feed(
            harness.groupFragments(harness.envelope(), E2eWireFormat.PayloadType.GROUP_REACTION),
        )

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingGroupSms(
                conversationId = CONV_ID,
                senderAddress = BOB,
                body = "Reacted ❤️ to «Hello»",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
    }

    // ──────────────────────────── Undecryptable group messages ────────────────────────────

    @Test
    fun `an undecryptable group message becomes a placeholder plus a key request`() = runTest {
        val harness = Harness()
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns null
        coEvery {
            harness.groupSessions.conversationIdForGroup(GROUP_ID, null)
        } returns CONV_ID
        coEvery { harness.groupSessions.requestSenderKey(any(), any(), any(), any()) } just Runs

        harness.feed(
            harness.groupFragments(harness.envelope(), E2eWireFormat.PayloadType.GROUP_TEXT),
        )

        coVerify(exactly = 1) {
            harness.mirror.upsertIncomingGroupSms(
                conversationId = CONV_ID,
                senderAddress = BOB,
                body = "",
                date = NOW,
                telephonyUri = "content://sms/1",
                subId = 3,
                encrypted = true,
            )
        }
        coVerify(exactly = 1) { harness.messageDao.markDecryptFailed(MSG_ID, any()) }
        coVerify(exactly = 1) {
            harness.notifier.notifyIncoming(
                BOB,
                "This message couldn't be decrypted.",
                MSG_ID,
                CONV_ID,
            )
        }
        coVerify(exactly = 1) { harness.groupSessions.requestSenderKey(GROUP_ID, BOB, 3, NOW) }
        // No 1:1 re-probe: a group decrypt failure is a missing
        // sender key, not a broken Olm session.
        coVerify(exactly = 0) { harness.probeManager.reprobeAfterDecryptFailure(any(), any()) }
    }

    @Test
    fun `an undecryptable group message with no conversation still requests the key`() = runTest {
        val harness = Harness()
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns null
        coEvery {
            harness.groupSessions.conversationIdForGroup(GROUP_ID, null)
        } returns null
        coEvery { harness.groupSessions.requestSenderKey(any(), any(), any(), any()) } just Runs

        harness.feed(
            harness.groupFragments(harness.envelope(), E2eWireFormat.PayloadType.GROUP_TEXT),
        )

        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingGroupSms(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { harness.groupSessions.requestSenderKey(GROUP_ID, BOB, 3, NOW) }
    }

    @Test
    fun `a group message with a malformed envelope is dropped without a key request`() = runTest {
        val harness = Harness()
        coEvery {
            harness.groupSessions.decryptGroupMessage(any(), any(), any())
        } returns null
        coEvery { harness.groupSessions.requestSenderKey(any(), any(), any(), any()) } just Runs

        // 10 bytes: too short to hold an envelope header.
        harness.feed(
            harness.groupFragments(ByteArray(10), E2eWireFormat.PayloadType.GROUP_TEXT),
        )

        coVerify(exactly = 0) { harness.groupSessions.requestSenderKey(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingGroupSms(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ──────────────────────────── Control payloads ────────────────────────────

    @Test
    fun `a SENDER_KEY payload routes to the group session manager`() = runTest {
        val harness = Harness()
        val json = E2eGroupSenderKeyMessage.encodeDistribution(
            E2eGroupSenderKeyMessage.SenderKeyDistribution(
                groupId = GROUP_ID,
                keyId = KEY_ID,
                key = ByteArray(32) { it.toByte() },
                members = MEMBERS,
                generation = 1,
            ),
        )
        coEvery {
            harness.groupSessions.onSenderKeyReceived(any(), any(), any(), any())
        } returns true

        harness.feed(harness.bobFragments(json, E2eWireFormat.PayloadType.SENDER_KEY))

        coVerify(exactly = 1) {
            harness.groupSessions.onSenderKeyReceived(BOB, json, 3, NOW)
        }
        // Silent, like a media key: no message, no notification.
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { harness.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test
    fun `a GROUP_KEY_REQUEST payload routes to the group session manager`() = runTest {
        val harness = Harness()
        val json = E2eGroupSenderKeyMessage.encodeKeyRequest(GROUP_ID)
        coEvery {
            harness.groupSessions.onKeyRequestReceived(any(), any(), any(), any())
        } just Runs

        harness.feed(harness.bobFragments(json, E2eWireFormat.PayloadType.GROUP_KEY_REQUEST))

        coVerify(exactly = 1) {
            harness.groupSessions.onKeyRequestReceived(BOB, json, 3, NOW)
        }
        coVerify(exactly = 0) {
            harness.mirror.upsertIncomingSms(any(), any(), any(), any(), any(), any())
        }
    }

    // ──────────────────────────── Plain-SMS fallback ────────────────────────────

    @Test
    fun `a plain SMS from an ENCRYPTED peer notifies the group manager before the flip`() = runTest {
        val harness = Harness()
        harness.seedEncryptedPeer()
        coEvery { harness.groupSessions.onPeerLeftRelay(any(), any()) } just Runs
        // Past the grace window: our first encrypted send was long ago.
        coEvery { harness.messageDao.firstOutgoingEncryptedSince(any(), any()) } returns
            NOW - 2 * E2eReceiveManager.DEACTIVATED_GRACE_MS

        harness.manager.onPlainSmsReceived(BOB, CONV_ID, 3, NOW)

        coVerify(exactly = 1) { harness.groupSessions.onPeerLeftRelay(BOB, NOW) }
        assertThat(harness.peerDao.peers[BOB]!!.state).isEqualTo(E2ePeerState.UNKNOWN)
    }
}
