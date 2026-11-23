package com.filestech.sms.e2e

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.local.db.entity.E2eSessionEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
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

/**
 * The send manager (design §3.4): the send decision, encrypt +
 * fragment + dispatch, and the byte-identical retry.
 *
 * Two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer); the
 * session is established the way the handshake handler does it
 * (outbound session from bob's bundle + [E2eSessionStore.saveNewSession]).
 * Bob's side decrypts what alice sends, end to end.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eSendManagerTest {

    private companion object {
        const val BOB = "+33612345678"
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

    private class Harness(unavailableCrypto: Boolean = false) {
        val aliceCrypto = FakeE2eCrypto("alice")
        val bobCrypto = FakeE2eCrypto("bob")

        // The backend under test: the fake normally, the unavailable
        // backend for the degraded-crypto test (round 38).
        private val sendCrypto: E2eCrypto =
            if (unavailableCrypto) UnavailableE2eCrypto() else aliceCrypto

        val peerDao = FakeE2ePeerDao()
        val sessionDao = FakeE2eSessionDao()
        val accountDao = FakeE2eAccountDao()

        val sender = mockk<SmsSender>()
        val recorder = RecordingRecorder()
        val messageDao = mockk<MessageDao>()
        val probeManager = mockk<E2eProbeManager>()
        // Round 48 — the text sender for media key messages.
        val dataSmsSender = RecordingDataSmsSender()

        val peers = E2ePeerRepository(peerDao)
        val sessions = E2eSessionStore(sessionDao, sendCrypto, fakeKeyManager())
        val accountManager = E2eAccountManager(accountDao, sendCrypto, fakeKeyManager())

        val manager = E2eSendManager(
            peers = peers,
            sessions = lazyOf(sessions),
            accountManager = lazyOf(accountManager),
            crypto = lazyOf(sendCrypto),
            sender = sender,
            recorder = recorder,
            messageDao = messageDao,
            probeManager = probeManager,
            dataSmsSender = dataSmsSender,
        )

        init {
            every {
                sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
            } returns Outcome.Success(Unit)
            coEvery { messageDao.findById(any()) } returns null
            coEvery { messageDao.setCiphertext(any(), any()) } just Runs
            every { probeManager.probeForSend(any(), any()) } just Runs
        }

        fun seedPeer(state: E2ePeerState) {
            peerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = state,
                updatedAt = NOW,
            )
        }

        /** Establishes alice's outbound session to bob (as the handshake handler does). */
        suspend fun establishSession() {
            val bobOtk = bobCrypto.nextOneTimeKey() ?: error("bob has no one-time key")
            val sessionId = aliceCrypto.createOutboundSession(
                bobCrypto.identityKeys().curve25519,
                bobOtk,
            )
            sessions.saveNewSession(BOB, sessionId, NOW)
        }

        /** Bob decrypts a fragment set alice sent, end to end (first message: pre-key). */
        fun bobDecrypts(fragments: List<String>): String {
            val parsed = fragments.map { E2eWireFormat.parseFragment(it)!! }
            val payload = E2eWireFormat.parsePayload(E2eWireFormat.assemble(parsed))
            check(payload.message.type == OlmMessageType.PRE_KEY) {
                "this helper only covers the first (pre-key) message"
            }
            val inbound = bobCrypto.createInboundSession(payload.message.bytes)
            return inbound.plaintext.toString(Charsets.UTF_8)
        }

        fun messageRow(ciphertext: String?) = MessageEntity(
            id = LOCAL_ID,
            conversationId = 11L,
            telephonyUri = null,
            address = BOB,
            body = "hello bob",
            type = MessageType.SMS,
            direction = MessageDirection.OUTGOING,
            date = NOW,
            dateSent = null,
            encrypted = true,
            ciphertext = ciphertext,
        )
    }

    // ──────────────────────────── The decision ────────────────────────────

    @Test
    fun `decideSend is Plain for an unknown peer`() = runTest {
        val harness = Harness()

        val decision = harness.manager.decideSend(BOB, "hi")

        assertThat(decision).isEqualTo(E2eSendGate.Decision.Plain)
    }

    @Test
    fun `decideSend is Encrypted for an encrypted peer within the cap`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)

        val decision = harness.manager.decideSend(BOB, "x".repeat(8_000))

        assertThat(decision).isEqualTo(E2eSendGate.Decision.Encrypted)
    }

    @Test
    fun `decideSend refuses an over-cap encrypted send`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)

        val decision = harness.manager.decideSend(BOB, "x".repeat(8_001))

        assertThat(decision).isEqualTo(E2eSendGate.Decision.RefuseOverCap)
    }

    @Test
    fun `decideSend is Plain for a not-relay peer even over the cap`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.NOT_RELAY)

        // The cap only applies to encrypted sends: a plain peer is
        // never refused (the plain path sends long texts as multi-part).
        val decision = harness.manager.decideSend(BOB, "x".repeat(8_001))

        assertThat(decision).isEqualTo(E2eSendGate.Decision.Plain)
    }

    // ──────────────────────────── Encrypt + fragment + dispatch ────────────────────────────

    @Test
    fun `sendEncrypted fragments, stores, and dispatches - and bob reads it`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()
        val sentFragments = slot<String>()
        every {
            harness.sender.sendFragment(any(), any(), capture(sentFragments), any(), any(), any(), any(), any())
        } returns Outcome.Success(Unit)

        val outcome = harness.manager.sendEncrypted(LOCAL_ID, BOB, "hello bob", NOW, 3, true, 0, E2ePayloadType.TEXT)

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        // One fragment: one system row, one sendFragment call.
        assertThat(harness.recorder.rows).hasSize(1)
        assertThat(sentFragments.isCaptured).isTrue()
        assertThat(harness.recorder.rows.single()).isEqualTo(sentFragments.captured)
        assertThat(sentFragments.captured).startsWith("RLY1:")
        verify(exactly = 1) {
            harness.sender.sendFragment(LOCAL_ID, BOB, sentFragments.captured, 0, 1, 3, true, 0)
        }
        // The payload bytes are kept on the row for a byte-identical retry.
        coVerify(exactly = 1) { harness.messageDao.setCiphertext(LOCAL_ID, any()) }
        // And bob decrypts the fragment set end to end.
        assertThat(harness.bobDecrypts(listOf(sentFragments.captured))).isEqualTo("hello bob")
    }

    @Test
    fun `a long message fragments with sequential indices`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()
        val sentFragments = mutableListOf<String>()
        val indices = mutableListOf<Int>()
        val totals = mutableListOf<Int>()
        every {
            harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            sentFragments += thirdArg<String>()
            indices += arg<Int>(3)
            totals += arg<Int>(4)
            Outcome.Success(Unit)
        }

        val body = "y".repeat(500)
        val outcome = harness.manager.sendEncrypted(LOCAL_ID, BOB, body, NOW, null, false, 0, E2ePayloadType.TEXT)

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        assertThat(sentFragments.size).isGreaterThan(1)
        assertThat(indices).containsExactlyElementsIn(0 until sentFragments.size)
        assertThat(totals.toSet()).containsExactly(sentFragments.size)
        assertThat(harness.recorder.rows).containsExactlyElementsIn(sentFragments).inOrder()
        assertThat(harness.bobDecrypts(sentFragments)).isEqualTo(body)
    }

    @Test
    fun `no session means failure, never a plain fallback`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        // No session established.

        val outcome = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "hello bob",
            NOW,
            null,
            false,
            0,
            E2ePayloadType.TEXT,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        verify(exactly = 0) { harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(harness.recorder.rows).isEmpty()
        coVerify(exactly = 0) { harness.messageDao.setCiphertext(any(), any()) }
    }

    @Test
    fun `an unavailable crypto backend fails the encrypted send instead of throwing`() = runTest {
        val harness = Harness(unavailableCrypto = true)
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        // A session row exists, so the send reaches the Account load —
        // which throws E2eCryptoException under the unavailable backend.
        // Seed the row directly: saveNewSession would pickle through
        // crypto, which is exactly what is unavailable here.
        harness.sessionDao.sessions += E2eSessionEntity(
            id = 0,
            address = BOB,
            sessionId = "session-1",
            pickle = "stored-pickle",
            isCurrent = true,
            createdAt = NOW,
            lastUsedAt = NOW,
        )

        val outcome = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "hello bob",
            NOW,
            null,
            false,
            0,
            E2ePayloadType.TEXT,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        verify(exactly = 0) { harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(harness.recorder.rows).isEmpty()
        coVerify(exactly = 0) { harness.messageDao.setCiphertext(any(), any()) }
    }

    @Test
    fun `an over-cap body is refused before anything is sent`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()

        val outcome = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "x".repeat(8_001),
            NOW,
            null,
            false,
            0,
            E2ePayloadType.TEXT,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        verify(exactly = 0) { harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(harness.recorder.rows).isEmpty()
    }

    @Test
    fun `a body inside the cap but over the fragment budget is refused`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()

        // 8,000 emoji = 32,000 bytes: inside the character cap, far over
        // the 99-fragment budget. The send refuses, never half-sends.
        val outcome = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "🔒".repeat(8_000),
            NOW,
            null,
            false,
            0,
            E2ePayloadType.TEXT,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        verify(exactly = 0) { harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(harness.recorder.rows).isEmpty()
    }

    // ──────────────────────────── Reactions ────────────────────────────

    @Test
    fun `sendEncrypted with REACTION tags the payload - and bob decrypts the tapback`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()
        val sentFragments = slot<String>()
        every {
            harness.sender.sendFragment(any(), any(), capture(sentFragments), any(), any(), any(), any(), any())
        } returns Outcome.Success(Unit)

        val outcome = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "Liked \"Hello\"",
            NOW,
            null,
            false,
            0,
            E2ePayloadType.REACTION,
        )

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        assertThat(sentFragments.isCaptured).isTrue()
        // The wire payload carries the REACTION type, not TEXT.
        val parsed = E2eWireFormat.parseFragment(sentFragments.captured)!!
        val payload = E2eWireFormat.parsePayload(E2eWireFormat.assemble(listOf(parsed)))
        assertThat(payload.payloadType).isEqualTo(E2eWireFormat.PayloadType.REACTION)
        // And bob decrypts the tapback body verbatim.
        assertThat(harness.bobDecrypts(listOf(sentFragments.captured))).isEqualTo("Liked \"Hello\"")
    }

    @Test
    fun `a reaction retry re-sends the stored REACTION bytes`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()
        val sent = mutableListOf<String>()
        every {
            harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            sent += thirdArg<String>()
            Outcome.Success(Unit)
        }
        val storedCiphertext = slot<String>()
        coEvery { harness.messageDao.setCiphertext(any(), capture(storedCiphertext)) } just Runs

        val first = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "Liked \"Hello\"",
            NOW,
            null,
            false,
            0,
            E2ePayloadType.REACTION,
        )
        assertThat(first).isEqualTo(Outcome.Success(Unit))
        assertThat(storedCiphertext.isCaptured).isTrue()

        // The retry finds the stored bytes on the row.
        coEvery { harness.messageDao.findById(LOCAL_ID) } returns
            harness.messageRow(ciphertext = storedCiphertext.captured)
        sent.clear()
        val retry = harness.manager.sendEncrypted(
            LOCAL_ID,
            BOB,
            "Liked \"Hello\"",
            NOW,
            null,
            false,
            1,
            E2ePayloadType.REACTION,
        )

        assertThat(retry).isEqualTo(Outcome.Success(Unit))
        // Byte-identical: the same fragment strings — and the stored
        // bytes keep their REACTION type on the retry.
        val firstSet = harness.recorder.rows.take(1)
        assertThat(sent).containsExactlyElementsIn(firstSet).inOrder()
        val parsed = E2eWireFormat.parseFragment(sent.single())!!
        val payload = E2eWireFormat.parsePayload(E2eWireFormat.assemble(listOf(parsed)))
        assertThat(payload.payloadType).isEqualTo(E2eWireFormat.PayloadType.REACTION)
    }

    // ──────────────────────────── Retry ────────────────────────────

    @Test
    fun `a retry re-sends the stored bytes under the same message id`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()
        val firstFragments = mutableListOf<String>()
        every {
            harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            firstFragments += thirdArg<String>()
            Outcome.Success(Unit)
        }
        val storedCiphertext = slot<String>()
        coEvery { harness.messageDao.setCiphertext(any(), capture(storedCiphertext)) } just Runs

        val first = harness.manager.sendEncrypted(LOCAL_ID, BOB, "hello bob", NOW, null, false, 0, E2ePayloadType.TEXT)
        assertThat(first).isEqualTo(Outcome.Success(Unit))
        assertThat(storedCiphertext.isCaptured).isTrue()

        // The retry finds the stored bytes on the row.
        coEvery { harness.messageDao.findById(LOCAL_ID) } returns
            harness.messageRow(ciphertext = storedCiphertext.captured)
        firstFragments.clear()
        val retry = harness.manager.sendEncrypted(LOCAL_ID, BOB, "hello bob", NOW, null, false, 1, E2ePayloadType.TEXT)

        assertThat(retry).isEqualTo(Outcome.Success(Unit))
        // Byte-identical: the same fragment strings, the same id.
        val firstSet = harness.recorder.rows.take(1)
        assertThat(firstFragments).containsExactlyElementsIn(firstSet).inOrder()
        val firstId = E2eWireFormat.parseFragment(firstSet.single())?.messageId
        val retryId = E2eWireFormat.parseFragment(firstFragments.single())?.messageId
        assertThat(retryId).isEqualTo(firstId)
    }

    @Test
    fun `a retry without stored bytes re-encrypts under a fresh id`() = runTest {
        val harness = Harness()
        harness.seedPeer(E2ePeerState.ENCRYPTED)
        harness.establishSession()
        val sent = mutableListOf<String>()
        every {
            harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            sent += thirdArg<String>()
            Outcome.Success(Unit)
        }

        harness.manager.sendEncrypted(LOCAL_ID, BOB, "hello bob", NOW, null, false, 0, E2ePayloadType.TEXT)
        val firstId = E2eWireFormat.parseFragment(sent.single())?.messageId
        // The row holds no ciphertext (an older build's row, or the
        // store failed): the retry re-encrypts, and must NOT reuse the
        // first attempt's id — its set could mix with the old partial one.
        coEvery { harness.messageDao.findById(LOCAL_ID) } returns harness.messageRow(ciphertext = null)
        sent.clear()
        val retry = harness.manager.sendEncrypted(LOCAL_ID, BOB, "hello bob", NOW, null, false, 1, E2ePayloadType.TEXT)

        assertThat(retry).isEqualTo(Outcome.Success(Unit))
        val retryId = E2eWireFormat.parseFragment(sent.single())?.messageId
        assertThat(retryId).isNotEqualTo(firstId)
    }

    // ──────────────────────────── Message ids ────────────────────────────

    @Test
    fun `message ids are deterministic per attempt and 6 hex chars`() {
        val first = E2eSendManager.messageIdForLocalId(LOCAL_ID, 0)
        val again = E2eSendManager.messageIdForLocalId(LOCAL_ID, 0)
        val otherAttempt = E2eSendManager.messageIdForLocalId(LOCAL_ID, 1)
        val otherMessage = E2eSendManager.messageIdForLocalId(43L, 0)

        assertThat(first).isEqualTo(again)
        assertThat(first).isNotEqualTo(otherAttempt)
        assertThat(first).isNotEqualTo(otherMessage)
        assertThat(first.length).isEqualTo(6)
        assertThat(first.matches(Regex("[0-9a-f]+"))).isTrue()
    }

    // ──────────────────────────── Probe delegation ────────────────────────────

    @Test
    fun `probeForSend delegates to the probe manager`() {
        val harness = Harness()

        harness.manager.probeForSend(BOB, 3)

        verify(exactly = 1) { harness.probeManager.probeForSend(BOB, 3) }
    }
}
