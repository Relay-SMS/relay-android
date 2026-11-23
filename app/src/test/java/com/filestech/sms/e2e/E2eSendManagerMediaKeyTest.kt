package com.filestech.sms.e2e

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Media key message send tests (round 48, design §3): a key
 * message encrypts under the current session as a MEDIA_KEY
 * payload, travels as text fragments through the diagnostic-only
 * sender (never the row-coupled fragment sender), lands in the
 * system sent box, and fails when the radio refuses.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eSendManagerMediaKeyTest {

    private companion object {
        const val BOB = "+33612345678"
        const val NOW = 1_800_000_000_000L
        const val SUB_ID = 2
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

        val peerDao = FakeE2ePeerDao()
        val sessionDao = FakeE2eSessionDao()
        val accountDao = FakeE2eAccountDao()

        val sender = mockk<SmsSender>()
        val recorder = RecordingRecorder()
        val messageDao = mockk<MessageDao>()
        val probeManager = mockk<E2eProbeManager>()
        val dataSmsSender = RecordingDataSmsSender()

        val peers = E2ePeerRepository(peerDao)
        val sessions = E2eSessionStore(sessionDao, aliceCrypto, fakeKeyManager())
        val accountManager = E2eAccountManager(accountDao, aliceCrypto, fakeKeyManager())

        val manager = E2eSendManager(
            peers = peers,
            sessions = lazyOf(sessions),
            accountManager = lazyOf(accountManager),
            crypto = lazyOf(aliceCrypto as E2eCrypto),
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
            every { probeManager.probeForSend(any(), any()) } just Runs
        }

        fun seedPeer(state: E2ePeerState = E2ePeerState.ENCRYPTED) {
            peerDao.peers[BOB] = E2ePeerEntity(address = BOB, state = state, updatedAt = NOW)
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

        /** Bob decrypts a fragment set alice sent (first message: pre-key). */
        fun bobDecrypts(fragments: List<String>): Pair<E2eWireFormat.PayloadType, String> {
            val parsed = fragments.map { E2eWireFormat.parseFragment(it)!! }
            val payload = E2eWireFormat.parsePayload(E2eWireFormat.assemble(parsed))
            check(payload.message.type == OlmMessageType.PRE_KEY) {
                "this helper only covers the first (pre-key) message"
            }
            val inbound = bobCrypto.createInboundSession(payload.message.bytes)
            return payload.payloadType to inbound.plaintext.toString(Charsets.UTF_8)
        }
    }

    private fun keyJson() = E2eMediaKeyMessage.encode(
        E2eMediaKeyMessage.MediaKey(
            id = "0123456789abcdef",
            key = ByteArray(32) { it.toByte() },
            mime = "image/jpeg",
            sizeBytes = 123_456L,
            sha256Hex = "a".repeat(64),
            fileName = "photo.jpg",
        ),
    )

    @Test
    fun `sendMediaKey encrypts a MEDIA_KEY payload bob can decrypt`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()
        val json = keyJson()

        val result = harness.manager.sendMediaKey(BOB, json, NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val (type, plaintext) = harness.bobDecrypts(harness.dataSmsSender.sentTexts.map { it.text })
        assertThat(type).isEqualTo(E2eWireFormat.PayloadType.MEDIA_KEY)
        assertThat(plaintext).isEqualTo(json)
    }

    @Test
    fun `sendMediaKey uses the text sender with the MEDIA_KEY kind, not the fragment sender`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()

        harness.manager.sendMediaKey(BOB, keyJson(), NOW, SUB_ID)

        // The key fragments travel like handshake texts: the
        // diagnostic-only sender, whose verdict never touches a
        // message row — never the row-coupled SmsSender.
        assertThat(harness.dataSmsSender.sentTexts).isNotEmpty()
        // One entry per fragment (the key JSON spans several
        // fragments) — every one must carry the MEDIA_KEY kind.
        assertThat(harness.dataSmsSender.sentTexts.map { it.kind }.toSet())
            .containsExactly(DataSmsKind.MEDIA_KEY)
        assertThat(harness.dataSmsSender.sentTexts.map { it.text })
            .containsExactlyElementsIn(harness.recorder.rows)
        verify(exactly = 0) {
            harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `sendMediaKey writes every fragment to the system sent box`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()

        harness.manager.sendMediaKey(BOB, keyJson(), NOW, SUB_ID)

        assertThat(harness.recorder.rows).isEqualTo(
            harness.dataSmsSender.sentTexts.map { it.text },
        )
    }

    @Test
    fun `sendMediaKey fails when the radio refuses a fragment`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()
        harness.dataSmsSender.result = false

        val result = harness.manager.sendMediaKey(BOB, keyJson(), NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `sendMediaKey fails with no session`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        // No establishSession: no session exists.

        val result = harness.manager.sendMediaKey(BOB, keyJson(), NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(harness.dataSmsSender.sentTexts).isEmpty()
    }

    @Test
    fun `two key messages use different fragment-set ids`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()

        harness.manager.sendMediaKey(BOB, keyJson(), NOW, SUB_ID)
        // The second message is a follow-up (not pre-key), so bob
        // cannot decrypt it with the pre-key helper — but the
        // fragment-set ids must still differ.
        harness.manager.sendMediaKey(BOB, keyJson(), NOW, SUB_ID)

        // One id per key message, repeated across its fragments —
        // distinct ids prove the two messages got different sets.
        val ids = harness.dataSmsSender.sentTexts.map {
            E2eWireFormat.parseFragment(it.text)!!.messageId
        }.distinct()
        assertThat(ids).hasSize(2)
        assertThat(ids[0]).isNotEqualTo(ids[1])
    }
}
