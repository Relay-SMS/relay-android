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
 * Sender-key distribution / key-request send tests (round 49,
 * design §13): a distribution encrypts under the current session
 * as a SENDER_KEY payload, a key request as a GROUP_KEY_REQUEST
 * payload, both travel as text fragments through the
 * diagnostic-only sender (never the row-coupled fragment sender),
 * land in the system sent box, and fail when the radio refuses or
 * no session exists. Mirrors [E2eSendManagerMediaKeyTest] — the
 * control-message shape is shared ([E2eSendManager.sendMediaKey]
 * is the same helper).
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eSendManagerGroupKeyTest {

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

    private fun distributionJson() = E2eGroupSenderKeyMessage.encodeDistribution(
        E2eGroupSenderKeyMessage.SenderKeyDistribution(
            groupId = E2eGroupWireFormat.deriveGroupIdHex(
                listOf("6108423232", "6108423233", "6108423234"),
            ),
            keyId = "0123456789abcdef",
            key = ByteArray(32) { it.toByte() },
            members = listOf("6108423232", "6108423233", "6108423234"),
            generation = 1,
        ),
    )

    private fun requestJson() = E2eGroupSenderKeyMessage.encodeKeyRequest(
        E2eGroupWireFormat.deriveGroupIdHex(listOf("6108423232", "6108423233", "6108423234")),
    )

    @Test
    fun `sendSenderKey encrypts a SENDER_KEY payload bob can decrypt`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()
        val json = distributionJson()

        val result = harness.manager.sendSenderKey(BOB, json, NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val (type, plaintext) = harness.bobDecrypts(harness.dataSmsSender.sentTexts.map { it.text })
        assertThat(type).isEqualTo(E2eWireFormat.PayloadType.SENDER_KEY)
        assertThat(plaintext).isEqualTo(json)
    }

    @Test
    fun `sendSenderKey uses the text sender with the SENDER_KEY kind, not the fragment sender`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()

        harness.manager.sendSenderKey(BOB, distributionJson(), NOW, SUB_ID)

        assertThat(harness.dataSmsSender.sentTexts).isNotEmpty()
        assertThat(harness.dataSmsSender.sentTexts.map { it.kind }.toSet())
            .containsExactly(DataSmsKind.SENDER_KEY)
        assertThat(harness.dataSmsSender.sentTexts.map { it.text })
            .containsExactlyElementsIn(harness.recorder.rows)
        verify(exactly = 0) {
            harness.sender.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `sendGroupKeyRequest encrypts a GROUP_KEY_REQUEST payload bob can decrypt`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()
        val json = requestJson()

        val result = harness.manager.sendGroupKeyRequest(BOB, json, NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val (type, plaintext) = harness.bobDecrypts(harness.dataSmsSender.sentTexts.map { it.text })
        assertThat(type).isEqualTo(E2eWireFormat.PayloadType.GROUP_KEY_REQUEST)
        assertThat(plaintext).isEqualTo(json)
        assertThat(harness.dataSmsSender.sentTexts.map { it.kind }.toSet())
            .containsExactly(DataSmsKind.GROUP_KEY_REQUEST)
    }

    @Test
    fun `sendSenderKey fails when the radio refuses a fragment`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        harness.establishSession()
        harness.dataSmsSender.result = false

        val result = harness.manager.sendSenderKey(BOB, distributionJson(), NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `sendSenderKey fails with no session`() = runTest {
        val harness = Harness()
        harness.seedPeer()
        // No establishSession: no session exists.

        val result = harness.manager.sendSenderKey(BOB, distributionJson(), NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(harness.dataSmsSender.sentTexts).isEmpty()
    }

    @Test
    fun `sendGroupKeyRequest fails with no session`() = runTest {
        val harness = Harness()
        harness.seedPeer()

        val result = harness.manager.sendGroupKeyRequest(BOB, requestJson(), NOW, SUB_ID)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(harness.dataSmsSender.sentTexts).isEmpty()
    }
}
