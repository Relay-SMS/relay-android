package com.filestech.sms.e2e

import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.SimSelfNumber
import com.filestech.sms.domain.e2e.E2eGroupSendGate
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.sender.SentSmsRecorder
import com.filestech.sms.domain.sender.SmsSender
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.SendingSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Encrypted-group session manager tests (round 49, design §13):
 * the send gate, sender-key distribution, the two-party
 * encrypt/decrypt round-trip, member add/remove re-keying, the
 * non-Relay member keeping the group plain, key-change handling,
 * key requests, and group notices.
 *
 * Two real sides (alice + bob, each with its own stores, crypto
 * and send manager) talk through the recorded fragment sender:
 * alice's distributions are decrypted with bob's fake crypto and
 * fed to bob's manager, like the media-key round-trip test.
 * Carol is a third member with a session target only — she never
 * decrypts in these tests.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eGroupSessionManagerTest {

    private companion object {
        const val ALICE = "6108423232"
        const val BOB = "6108423233"
        const val CAROL = "6108423234"
        const val DANA = "6108423235"
        const val CONV_ID = 11L
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

    /** One side of the exchange: its own stores, crypto, send manager, group manager. */
    private class Side(
        val ownAddress: String,
        val crypto: FakeE2eCrypto,
        ownNumberFromSettings: String? = ownAddress,
        simNumber: String? = null,
    ) {
        val peerDao = FakeE2ePeerDao()
        val sessionDao = FakeE2eSessionDao()
        val accountDao = FakeE2eAccountDao()
        val groupKeyDao = FakeE2eGroupKeyDao()
        val groupDistDao = FakeE2eGroupDistributionDao()
        val noticeDao = FakeE2eNoticeDao()
        val conversationDao = mockk<ConversationDao>()
        val mirror = mockk<ConversationMirror>()
        val settings = mockk<AppSettingsSource>()
        val sim = mockk<SimSelfNumber>()
        val dataSmsSender = RecordingDataSmsSender()
        val recorder = RecordingRecorder()

        val peers = E2ePeerRepository(peerDao)
        val groupKeys = E2eGroupSenderKeyStore(groupKeyDao, groupDistDao)
        val sessions = E2eSessionStore(sessionDao, crypto, fakeKeyManager())
        val accountManager = E2eAccountManager(accountDao, crypto, fakeKeyManager())
        val sendManager = E2eSendManager(
            peers = peers,
            sessions = lazyOf(sessions),
            accountManager = lazyOf(accountManager),
            crypto = lazyOf(crypto as E2eCrypto),
            sender = mockk<SmsSender>().also {
                every {
                    it.sendFragment(any(), any(), any(), any(), any(), any(), any(), any())
                } returns Outcome.Success(Unit)
            },
            recorder = recorder,
            messageDao = mockk<MessageDao>(),
            probeManager = mockk<E2eProbeManager>(),
            dataSmsSender = dataSmsSender,
        )
        val stateProvider = E2eGroupStateProvider(peers, FakeBlockedRepo())
        val manager = E2eGroupSessionManager(
            peers = peers,
            groupKeys = groupKeys,
            sendManager = sendManager,
            aead = AeadCipher(),
            conversationDao = conversationDao,
            notices = E2eNoticeStore(noticeDao),
            settings = settings,
            simSelfNumber = sim,
            stateProvider = stateProvider,
            mirror = mirror,
        )

        init {
            coEvery { settings.hydratedOrNull() } returns AppSettings(
                sending = SendingSettings(userMsisdn = ownNumberFromSettings),
            )
            every { sim.ownNumber(any()) } returns simNumber
            coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
            coEvery { conversationDao.findById(any()) } returns null
            coEvery { mirror.findGroupConversationId(any()) } returns null
            coEvery { mirror.ensureGroupConversation(any()) } returns CONV_ID
        }

        fun seedPeer(
            address: String,
            state: E2ePeerState = E2ePeerState.ENCRYPTED,
            groupCapable: Boolean = true,
            curveKey: String? = null,
        ) {
            peerDao.peers[address] = E2ePeerEntity(
                address = address,
                state = state,
                groupCapable = groupCapable,
                theirCurveKey = curveKey,
                updatedAt = NOW,
            )
        }

        /** Establishes this side's outbound session to [other] (as the handshake does). */
        suspend fun establishSessionTo(other: FakeE2eCrypto, otherAddress: String) {
            val otk = other.nextOneTimeKey() ?: error("$otherAddress has no one-time key")
            val sessionId = crypto.createOutboundSession(
                other.identityKeys().curve25519,
                otk,
            )
            sessions.saveNewSession(otherAddress, sessionId, NOW)
        }
    }

    private class Harness {
        val aliceCrypto = FakeE2eCrypto("alice")
        val bobCrypto = FakeE2eCrypto("bob")
        val carolCrypto = FakeE2eCrypto("carol")

        val alice = Side(ALICE, aliceCrypto)
        val bob = Side(BOB, bobCrypto)

        /** The fragments this side sent to [toAddress] with this kind, in send order. */
        fun sentTextsTo(side: Side, toAddress: String, kind: DataSmsKind): List<String> =
            side.dataSmsSender.sentTexts
                .filter { it.address == toAddress && it.kind == kind }
                .map { it.text }

        /**
         * Decrypts the FIRST control message one side sent to
         * another (a pre-key message — the first message in a
         * direction). Returns the payload type + plaintext.
         */
        fun firstControlDecrypts(
            from: Side,
            toCrypto: FakeE2eCrypto,
            toAddress: String,
            kind: DataSmsKind,
        ): Pair<E2eWireFormat.PayloadType, String> {
            val fragments = sentTextsTo(from, toAddress, kind)
                .map { E2eWireFormat.parseFragment(it)!! }
            val payload = E2eWireFormat.parsePayload(E2eWireFormat.assemble(fragments))
            check(payload.message.type == OlmMessageType.PRE_KEY) {
                "this helper only covers the first (pre-key) message"
            }
            val inbound = toCrypto.createInboundSession(payload.message.bytes)
            return payload.payloadType to inbound.plaintext.toString(Charsets.UTF_8)
        }
    }

    // ──────────────────────────── Send gate ────────────────────────────

    @Test
    fun `decideGroupSend is Encrypted when every member is ENCRYPTED and capable`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)

        val decision = harness.alice.manager.decideGroupSend(listOf(BOB, CAROL), "hello group")

        assertThat(decision).isEqualTo(E2eGroupSendGate.Decision.Encrypted)
    }

    @Test
    fun `decideGroupSend is Plain when a member is PROBING`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL, E2ePeerState.PROBING)

        val decision = harness.alice.manager.decideGroupSend(listOf(BOB, CAROL), "hello group")

        assertThat(decision).isEqualTo(E2eGroupSendGate.Decision.Plain)
    }

    @Test
    fun `decideGroupSend refuses an over-cap body for an encrypted group`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)

        val decision = harness.alice.manager.decideGroupSend(
            listOf(BOB, CAROL),
            "x".repeat(8_001),
        )

        assertThat(decision).isEqualTo(E2eGroupSendGate.Decision.RefuseOverCap)
    }

    @Test
    fun `decideGroupSend is Plain for an over-cap body when the group is plain anyway`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB, E2ePeerState.NOT_RELAY)
        harness.alice.seedPeer(CAROL)

        val decision = harness.alice.manager.decideGroupSend(
            listOf(BOB, CAROL),
            "x".repeat(8_001),
        )

        // The cap only refuses when the group WOULD encrypt: a
        // plain group sends plain, cap or no cap.
        assertThat(decision).isEqualTo(E2eGroupSendGate.Decision.Plain)
    }

    // ──────────────────────────── Distribution + round-trip ────────────────────────────

    @Test
    fun `encryptGroupMessage distributes our key and bob can decrypt the message`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)

        val result = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL),
            "hello group",
            SUB_ID,
            NOW,
        )

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val encrypted = (result as Outcome.Success).value
        // The distribution went out to both members as SENDER_KEY
        // payloads, each decryptable by its recipient.
        val (bobType, bobJson) = harness.firstControlDecrypts(
            harness.alice,
            harness.bobCrypto,
            BOB,
            DataSmsKind.SENDER_KEY,
        )
        assertThat(bobType).isEqualTo(E2eWireFormat.PayloadType.SENDER_KEY)
        assertThat(harness.bob.manager.onSenderKeyReceived(ALICE, bobJson, SUB_ID, NOW)).isTrue()
        // Bob decrypts alice's group message with her sender key.
        val payload = E2eWireFormat.parsePayload(
            E2eWireFormat.assemble(encrypted.fragments.map { E2eWireFormat.parseFragment(it)!! }),
        )
        assertThat(payload.message.type).isEqualTo(OlmMessageType.GROUP)
        assertThat(payload.payloadType).isEqualTo(E2eWireFormat.PayloadType.GROUP_TEXT)
        val decrypted = harness.bob.manager.decryptGroupMessage(ALICE, payload.message.bytes)
        assertThat(decrypted).isNotNull()
        assertThat(decrypted!!.plaintext).isEqualTo("hello group")
        assertThat(decrypted.groupId).isEqualTo(encrypted.groupId)
        assertThat(decrypted.members).containsExactly(ALICE, BOB, CAROL)
    }

    @Test
    fun `a second send does not re-distribute the key`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)

        harness.alice.manager.encryptGroupMessage(listOf(BOB, CAROL), "one", SUB_ID, NOW)
        val firstCount = harness.sentTextsTo(harness.alice, BOB, DataSmsKind.SENDER_KEY).size
        harness.alice.manager.encryptGroupMessage(listOf(BOB, CAROL), "two", SUB_ID, NOW)

        // The distribution ledger skips members that already have
        // this key: no second distribution to bob.
        assertThat(harness.sentTextsTo(harness.alice, BOB, DataSmsKind.SENDER_KEY)).hasSize(firstCount)
    }

    @Test
    fun `a group reaction encrypts as GROUP_REACTION and bob can decrypt it`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)

        val result = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL),
            "Reacted \u2764\uFE0F to \u00ABhello\u00BB",
            SUB_ID,
            NOW,
            E2ePayloadType.REACTION,
        )

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val encrypted = (result as Outcome.Success).value
        val (_, bobJson) = harness.firstControlDecrypts(
            harness.alice,
            harness.bobCrypto,
            BOB,
            DataSmsKind.SENDER_KEY,
        )
        assertThat(harness.bob.manager.onSenderKeyReceived(ALICE, bobJson, SUB_ID, NOW)).isTrue()
        val payload = E2eWireFormat.parsePayload(
            E2eWireFormat.assemble(encrypted.fragments.map { E2eWireFormat.parseFragment(it)!! }),
        )
        assertThat(payload.payloadType).isEqualTo(E2eWireFormat.PayloadType.GROUP_REACTION)
        val decrypted = harness.bob.manager.decryptGroupMessage(ALICE, payload.message.bytes)
        assertThat(decrypted!!.plaintext).isEqualTo("Reacted \u2764\uFE0F to \u00ABhello\u00BB")
    }

    @Test
    fun `encryptGroupMessage refuses MEDIA_KEY`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)

        val result = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL),
            "x",
            SUB_ID,
            NOW,
            E2ePayloadType.MEDIA_KEY,
        )

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `encryptGroupMessage fails fast without our own number`() = runTest {
        val harness = Harness()
        // Alice with no number anywhere: settings blank, SIM blank,
        // no stored distribution to fall back on.
        val noNumber = Side(ALICE, FakeE2eCrypto("alice2"), ownNumberFromSettings = null)

        val result = noNumber.manager.encryptGroupMessage(listOf(BOB, CAROL), "hi", SUB_ID, NOW)

        assertThat(result).isEqualTo(Outcome.Failure(AppError.OwnNumberMissing))
    }

    @Test
    fun `the own number falls back to the SIM when settings are blank`() = runTest {
        val side = Side(ALICE, FakeE2eCrypto("alice3"), ownNumberFromSettings = null, simNumber = ALICE)

        assertThat(side.manager.ownNumber()).isEqualTo(ALICE)
    }

    @Test
    fun `the member list falls back to a stored distribution when the number is unknown`() = runTest {
        val harness = Harness()
        // Bob's side knows the group (he received alice's key),
        // but his own number is unknown: the stored member list
        // still resolves the group for a send.
        harness.bob.manager.onSenderKeyReceived(
            ALICE,
            E2eGroupSenderKeyMessage.encodeDistribution(
                E2eGroupSenderKeyMessage.SenderKeyDistribution(
                    groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
                    keyId = "aaaaaaaaaaaaaaaa",
                    key = ByteArray(32) { 1 },
                    members = listOf(ALICE, BOB, CAROL),
                    generation = 1,
                ),
            ),
            SUB_ID,
            NOW,
        )
        val noNumberBob = Side(BOB, FakeE2eCrypto("bob2"), ownNumberFromSettings = null)
        // Move bob's stored key into the no-number side's store.
        val stored = harness.bob.groupKeys.keyFor(
            E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
            ALICE,
            "aaaaaaaaaaaaaaaa",
        )!!
        noNumberBob.groupKeys.storePeerKey(
            E2eGroupSenderKeyMessage.SenderKeyDistribution(
                groupId = stored.groupId,
                keyId = stored.keyId,
                key = stored.key,
                members = stored.members,
                generation = stored.generation,
            ),
            ALICE,
            null,
            NOW,
        )

        val members = noNumberBob.manager.resolveMemberList(listOf(ALICE, CAROL))

        assertThat(members).containsExactly(ALICE, BOB, CAROL)
    }

    // ──────────────────────────── Distribution validation ────────────────────────────

    @Test
    fun `a distribution from a non-member is dropped`() = runTest {
        val harness = Harness()
        val json = E2eGroupSenderKeyMessage.encodeDistribution(
            E2eGroupSenderKeyMessage.SenderKeyDistribution(
                groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
                keyId = "aaaaaaaaaaaaaaaa",
                key = ByteArray(32) { 1 },
                members = listOf(ALICE, BOB, CAROL),
                generation = 1,
            ),
        )

        // Dana sends a distribution for a group she is not in.
        val stored = harness.bob.manager.onSenderKeyReceived(DANA, json, SUB_ID, NOW)

        assertThat(stored).isFalse()
    }

    @Test
    fun `a distribution for a group we are not in is dropped`() = runTest {
        val harness = Harness()
        val json = E2eGroupSenderKeyMessage.encodeDistribution(
            E2eGroupSenderKeyMessage.SenderKeyDistribution(
                groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, CAROL, DANA)),
                keyId = "aaaaaaaaaaaaaaaa",
                key = ByteArray(32) { 1 },
                members = listOf(ALICE, CAROL, DANA),
                generation = 1,
            ),
        )

        // Bob is not a member of this group (and his number is known).
        val stored = harness.bob.manager.onSenderKeyReceived(ALICE, json, SUB_ID, NOW)

        assertThat(stored).isFalse()
    }

    @Test
    fun `a malformed distribution is dropped silently`() = runTest {
        val harness = Harness()

        val stored = harness.bob.manager.onSenderKeyReceived(ALICE, "not json", SUB_ID, NOW)

        assertThat(stored).isFalse()
    }

    @Test
    fun `receiving a key announces our key back to a sender who lacks it`() = runTest {
        val harness = Harness()
        harness.bob.seedPeer(ALICE)
        harness.bob.establishSessionTo(harness.aliceCrypto, ALICE)
        val json = E2eGroupSenderKeyMessage.encodeDistribution(
            E2eGroupSenderKeyMessage.SenderKeyDistribution(
                groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
                keyId = "aaaaaaaaaaaaaaaa",
                key = ByteArray(32) { 1 },
                members = listOf(ALICE, BOB, CAROL),
                generation = 1,
            ),
        )

        assertThat(harness.bob.manager.onSenderKeyReceived(ALICE, json, SUB_ID, NOW)).isTrue()

        // Bob announced his own key back to alice (she had no
        // record for it): one SENDER_KEY message to alice.
        val announce = harness.sentTextsTo(harness.bob, ALICE, DataSmsKind.SENDER_KEY)
        assertThat(announce).isNotEmpty()
        val (type, backJson) = harness.firstControlDecrypts(
            harness.bob,
            harness.aliceCrypto,
            ALICE,
            DataSmsKind.SENDER_KEY,
        )
        assertThat(type).isEqualTo(E2eWireFormat.PayloadType.SENDER_KEY)
        assertThat(harness.alice.manager.onSenderKeyReceived(BOB, backJson, SUB_ID, NOW)).isTrue()
    }

    // ──────────────────────────── Membership re-keying ────────────────────────────

    @Test
    fun `adding a member re-keys - a new group id, fresh keys, old keys still decrypt`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.seedPeer(DANA)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)

        // The 3-member group: alice encrypts, bob receives the key.
        val first = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL),
            "before",
            SUB_ID,
            NOW,
        ) as Outcome.Success
        val (_, bobJson) = harness.firstControlDecrypts(
            harness.alice,
            harness.bobCrypto,
            BOB,
            DataSmsKind.SENDER_KEY,
        )
        assertThat(harness.bob.manager.onSenderKeyReceived(ALICE, bobJson, SUB_ID, NOW)).isTrue()

        // Dana joins: the 4-member group is a NEW group id, so
        // alice creates a fresh key (generation 1) for it.
        val second = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL, DANA),
            "after",
            SUB_ID,
            NOW,
        ) as Outcome.Success

        assertThat(second.value.groupId).isNotEqualTo(first.value.groupId)
        // The old key still decrypts the in-flight message.
        val oldPayload = E2eWireFormat.parsePayload(
            E2eWireFormat.assemble(first.value.fragments.map { E2eWireFormat.parseFragment(it)!! }),
        )
        val oldDecrypted = harness.bob.manager.decryptGroupMessage(
            ALICE,
            oldPayload.message.bytes,
        )
        assertThat(oldDecrypted!!.plaintext).isEqualTo("before")
    }

    @Test
    fun `decrypting under an unknown key returns null`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)

        val encrypted = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL),
            "secret",
            SUB_ID,
            NOW,
        ) as Outcome.Success
        val payload = E2eWireFormat.parsePayload(
            E2eWireFormat.assemble(encrypted.value.fragments.map { E2eWireFormat.parseFragment(it)!! }),
        )

        // Bob never received alice's key: no decrypt.
        assertThat(harness.bob.manager.decryptGroupMessage(ALICE, payload.message.bytes)).isNull()
    }

    @Test
    fun `a tampered group ciphertext fails the AEAD open`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)

        val encrypted = harness.alice.manager.encryptGroupMessage(
            listOf(BOB, CAROL),
            "secret",
            SUB_ID,
            NOW,
        ) as Outcome.Success
        val (_, bobJson) = harness.firstControlDecrypts(
            harness.alice,
            harness.bobCrypto,
            BOB,
            DataSmsKind.SENDER_KEY,
        )
        assertThat(harness.bob.manager.onSenderKeyReceived(ALICE, bobJson, SUB_ID, NOW)).isTrue()
        val payload = E2eWireFormat.parsePayload(
            E2eWireFormat.assemble(encrypted.value.fragments.map { E2eWireFormat.parseFragment(it)!! }),
        )
        val tampered = payload.message.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertThat(harness.bob.manager.decryptGroupMessage(ALICE, tampered)).isNull()
    }

    // ──────────────────────────── Key requests ────────────────────────────

    @Test
    fun `a key request goes out once per hour per sender`() = runTest {
        val harness = Harness()
        harness.bob.seedPeer(ALICE)
        harness.bob.establishSessionTo(harness.aliceCrypto, ALICE)
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL))

        harness.bob.manager.requestSenderKey(groupId, ALICE, SUB_ID, NOW)
        harness.bob.manager.requestSenderKey(groupId, ALICE, SUB_ID, NOW + 30 * 60 * 1000L)
        harness.bob.manager.requestSenderKey(groupId, ALICE, SUB_ID, NOW + 61 * 60 * 1000L)

        // Two requests (the 30-minute one was rate-limited): each
        // is one fragment set, so the fragment count is 2 sets.
        val ids = harness.sentTextsTo(harness.bob, ALICE, DataSmsKind.GROUP_KEY_REQUEST)
            .map { E2eWireFormat.parseFragment(it)!!.messageId }.distinct()
        assertThat(ids).hasSize(2)
    }

    @Test
    fun `a key request re-sends our key, at most once per day`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)
        harness.alice.manager.encryptGroupMessage(listOf(BOB, CAROL), "hi", SUB_ID, NOW)
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL))
        val request = E2eGroupSenderKeyMessage.encodeKeyRequest(groupId)
        val initial = harness.sentTextsTo(harness.alice, BOB, DataSmsKind.SENDER_KEY).size

        // Bob asks again within the day: alice already sent him
        // this key at NOW, so no re-send.
        harness.alice.manager.onKeyRequestReceived(BOB, request, SUB_ID, NOW + 1_000)
        assertThat(harness.sentTextsTo(harness.alice, BOB, DataSmsKind.SENDER_KEY))
            .hasSize(initial)
        // A day later: the window elapsed, the key goes out again.
        harness.alice.manager.onKeyRequestReceived(BOB, request, SUB_ID, NOW + 25 * 3_600_000L)
        assertThat(harness.sentTextsTo(harness.alice, BOB, DataSmsKind.SENDER_KEY).size)
            .isGreaterThan(initial)
    }

    @Test
    fun `a key request from a non-member is ignored`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.establishSessionTo(harness.bobCrypto, BOB)
        harness.alice.establishSessionTo(harness.carolCrypto, CAROL)
        harness.alice.manager.encryptGroupMessage(listOf(BOB, CAROL), "hi", SUB_ID, NOW)
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL))

        harness.alice.manager.onKeyRequestReceived(
            DANA,
            E2eGroupSenderKeyMessage.encodeKeyRequest(groupId),
            SUB_ID,
            NOW + 25 * 3_600_000L,
        )

        assertThat(harness.sentTextsTo(harness.alice, DANA, DataSmsKind.SENDER_KEY)).isEmpty()
    }

    // ──────────────────────────── Notices ────────────────────────────

    @Test
    fun `recordGroupActivated records once per conversation`() = runTest {
        val harness = Harness()

        assertThat(harness.alice.manager.recordGroupActivated(CONV_ID, NOW)).isTrue()
        assertThat(harness.alice.manager.recordGroupActivated(CONV_ID, NOW)).isFalse()
        assertThat(harness.alice.noticeDao.countByKind(CONV_ID, E2eNoticeKind.GROUP_ACTIVATED))
            .isEqualTo(1)
    }

    @Test
    fun `a key change fires GROUP_KEY_CHANGED in every group where the member held a key`() = runTest {
        val harness = Harness()
        harness.alice.manager.onSenderKeyReceived(
            BOB,
            E2eGroupSenderKeyMessage.encodeDistribution(
                E2eGroupSenderKeyMessage.SenderKeyDistribution(
                    groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
                    keyId = "aaaaaaaaaaaaaaaa",
                    key = ByteArray(32) { 1 },
                    members = listOf(ALICE, BOB, CAROL),
                    generation = 1,
                ),
            ),
            SUB_ID,
            NOW,
        )
        coEvery { harness.alice.mirror.findGroupConversationId(any()) } returns CONV_ID

        harness.alice.manager.onPeerKeyChanged(BOB, wasVerified = false, NOW)

        assertThat(harness.alice.noticeDao.countByKind(CONV_ID, E2eNoticeKind.GROUP_KEY_CHANGED))
            .isEqualTo(1)
        // The notice names its member: the UI renders the name
        // from the subject address.
        val notice = harness.alice.noticeDao.noticesForConversation(CONV_ID)
            .first { it.kind == E2eNoticeKind.GROUP_KEY_CHANGED }
        assertThat(notice.subject).isEqualTo(BOB)
    }

    @Test
    fun `a key change for a verified member fires GROUP_KEY_CHANGED_VERIFIED`() = runTest {
        val harness = Harness()
        harness.alice.manager.onSenderKeyReceived(
            BOB,
            E2eGroupSenderKeyMessage.encodeDistribution(
                E2eGroupSenderKeyMessage.SenderKeyDistribution(
                    groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
                    keyId = "aaaaaaaaaaaaaaaa",
                    key = ByteArray(32) { 1 },
                    members = listOf(ALICE, BOB, CAROL),
                    generation = 1,
                ),
            ),
            SUB_ID,
            NOW,
        )
        coEvery { harness.alice.mirror.findGroupConversationId(any()) } returns CONV_ID

        harness.alice.manager.onPeerKeyChanged(BOB, wasVerified = true, NOW)

        assertThat(
            harness.alice.noticeDao.countByKind(CONV_ID, E2eNoticeKind.GROUP_KEY_CHANGED_VERIFIED),
        ).isEqualTo(1)
    }

    @Test
    fun `leaving Relay fires GROUP_DEACTIVATED in groups that were encrypted`() = runTest {
        val harness = Harness()
        harness.alice.seedPeer(BOB)
        harness.alice.seedPeer(CAROL)
        harness.alice.manager.onSenderKeyReceived(
            BOB,
            E2eGroupSenderKeyMessage.encodeDistribution(
                E2eGroupSenderKeyMessage.SenderKeyDistribution(
                    groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL)),
                    keyId = "aaaaaaaaaaaaaaaa",
                    key = ByteArray(32) { 1 },
                    members = listOf(ALICE, BOB, CAROL),
                    generation = 1,
                ),
            ),
            SUB_ID,
            NOW,
        )
        coEvery { harness.alice.mirror.findGroupConversationId(any()) } returns CONV_ID
        val conversation = mockk<ConversationEntity>()
        every { conversation.id } returns CONV_ID
        every { conversation.addressesCsv } returns "$BOB;$CAROL"
        coEvery { harness.alice.conversationDao.findById(CONV_ID) } returns conversation

        // Called BEFORE the peer flips (the receive manager's
        // contract): the group still reads ENCRYPTED.
        harness.alice.manager.onPeerLeftRelay(BOB, NOW)

        assertThat(harness.alice.noticeDao.countByKind(CONV_ID, E2eNoticeKind.GROUP_DEACTIVATED))
            .isEqualTo(1)
    }

    // ──────────────────────────── Conversation resolution ────────────────────────────

    @Test
    fun `conversationIdForGroup finds the group by candidate id when no key row exists`() = runTest {
        val harness = Harness()
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL))
        val conversation = mockk<ConversationEntity>()
        every { conversation.id } returns CONV_ID
        every { conversation.addressesCsv } returns "$BOB;$CAROL"
        coEvery { harness.alice.conversationDao.snapshotGroupConversations() } returns
            listOf(conversation)

        val found = harness.alice.manager.conversationIdForGroup(groupId, members = null)

        assertThat(found).isEqualTo(CONV_ID)
    }

    @Test
    fun `conversationIdForGroup returns null when nothing matches`() = runTest {
        val harness = Harness()
        val groupId = E2eGroupWireFormat.deriveGroupIdHex(listOf(ALICE, BOB, CAROL))

        val found = harness.alice.manager.conversationIdForGroup(groupId, members = null)

        assertThat(found).isNull()
    }
}
