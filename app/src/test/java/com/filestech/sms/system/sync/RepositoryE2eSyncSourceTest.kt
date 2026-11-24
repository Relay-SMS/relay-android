package com.filestech.sms.system.sync

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.e2e.E2eAccountManager
import com.filestech.sms.e2e.E2eMediaKeyStore
import com.filestech.sms.e2e.E2eNoticeStore
import com.filestech.sms.e2e.E2ePeerRepository
import com.filestech.sms.e2e.E2eProbeManager
import com.filestech.sms.e2e.E2eSafetyNumber
import com.filestech.sms.e2e.IdentityKeys
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Round 42 — the E2E sync glue: the safety-number derivation reuse
 * (the desktop number is byte-identical to the phone's), the
 * never-create-an-identity gate, the wire mapping (lowercase
 * states/kinds, decimal-string ids), and the probe-retry
 * address → conversation resolution.
 *
 * Strict MockK collaborators: the peer store, the account, the
 * notice store, the probe manager and the conversation lookup are
 * all stubbed, so each test pins the glue's own decision — not the
 * stack beneath it (which has its own tests).
 */
class RepositoryE2eSyncSourceTest {

    private companion object {
        const val ALICE = "+33612345678"
        const val BOB = "+33687654321"
        const val NOW = 1_800_000_000_000L
        const val CONVERSATION_ID = 11L
    }

    private class Harness {
        val peers = mockk<E2ePeerRepository>()
        val accounts = mockk<E2eAccountManager>()
        val notices = mockk<E2eNoticeStore>()
        val probes = mockk<E2eProbeManager>()
        val conversations = mockk<ConversationDao>()
        val phoneIdentity = mockk<PhoneIdentity>()

        // Round 48 — the media-key arrival signal + the part
        // lookup for the pending → decryptable push.
        val mediaKeys = mockk<E2eMediaKeyStore>()
        val attachmentDao = mockk<AttachmentDao>()
        val messageDao = mockk<MessageDao>()

        val source = RepositoryE2eSyncSource(
            peerRepository = peers,
            accountManager = accounts,
            noticeStore = notices,
            probeManager = probes,
            conversationDao = conversations,
            phoneIdentity = phoneIdentity,
            mediaKeys = mediaKeys,
            attachmentDao = attachmentDao,
            messageDao = messageDao,
        )
    }

    /** Our identity keys, fixed vectors — the other half of every derivation. */
    private fun ourKeys() = IdentityKeys(
        curve25519 = ByteArray(32) { it.toByte() },
        ed25519 = ByteArray(32) { (it + 64).toByte() },
    )

    private fun peer(
        address: String = ALICE,
        state: E2ePeerState = E2ePeerState.ENCRYPTED,
        curveKey: String? = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 32).toByte() }),
        edKey: String? = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 96).toByte() }),
        verified: Boolean = false,
        updatedAt: Long = NOW,
    ) = E2ePeerEntity(
        address = address,
        state = state,
        theirCurveKey = curveKey,
        theirEdKey = edKey,
        verified = verified,
        updatedAt = updatedAt,
    )

    private fun conversation(id: Long, addressesCsv: String): ConversationEntity {
        val conv = mockk<ConversationEntity>()
        every { conv.id } returns id
        every { conv.addressesCsv } returns addressesCsv
        return conv
    }

    @Test fun `safetyNumbers derives the same number as the verification sheet`() = runTest {
        val harness = Harness()
        val ours = ourKeys()
        coEvery { harness.peers.allPeers() } returns listOf(peer())
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ours

        val numbers = harness.source.safetyNumbers()

        assertThat(numbers).hasSize(1)
        assertThat(numbers[0].address).isEqualTo(ALICE)
        assertThat(numbers[0].state).isEqualTo("encrypted")
        assertThat(numbers[0].verified).isFalse()
        // The reuse proof: the synced string equals the canonical
        // derivation for the same keys — the same function the
        // verification sheet calls, so the desktop shows
        // byte-identical numbers to the phone.
        val expected = E2eSafetyNumber.derive(
            ours.curve25519,
            ByteArray(32) { (it + 32).toByte() },
            ours.ed25519,
            ByteArray(32) { (it + 96).toByte() },
        )
        assertThat(numbers[0].safetyNumber).isEqualTo(expected)
    }

    @Test fun `safetyNumbers maps every peer state to its lowercase wire string`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.allPeers() } returns listOf(
            peer(address = ALICE, state = E2ePeerState.ENCRYPTED),
            peer(address = BOB, state = E2ePeerState.NOT_RELAY, curveKey = null, edKey = null),
            peer(address = "+33123456789", state = E2ePeerState.PROBING, curveKey = null, edKey = null),
            peer(address = "+33987654321", state = E2ePeerState.UNKNOWN, curveKey = null, edKey = null),
        )
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ourKeys()

        val numbers = harness.source.safetyNumbers()

        assertThat(numbers.map { it.state })
            .containsExactly("encrypted", "not_relay", "probing", "unknown")
    }

    @Test fun `safetyNumbers never loads our keys when no peer holds keys`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.allPeers() } returns listOf(
            peer(state = E2ePeerState.UNKNOWN, curveKey = null, edKey = null),
            peer(address = BOB, state = E2ePeerState.PROBING, curveKey = null, edKey = null),
        )

        val numbers = harness.source.safetyNumbers()

        assertThat(numbers.map { it.safetyNumber }).containsExactly(null, null)
        // The identity gate: neither the account read nor the key
        // load runs — sync must never create an identity.
        coVerify(exactly = 0) { harness.accounts.hasAccount() }
        coVerify(exactly = 0) { harness.accounts.identityKeys() }
    }

    @Test fun `safetyNumbers never creates an identity when no account exists`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.allPeers() } returns listOf(peer())
        coEvery { harness.accounts.hasAccount() } returns false

        val numbers = harness.source.safetyNumbers()

        // The peer holds keys but we hold no account (an
        // inconsistent store): the number stays null, and the
        // account-creating load never runs.
        assertThat(numbers[0].safetyNumber).isNull()
        coVerify(exactly = 0) { harness.accounts.identityKeys() }
    }

    @Test fun `safetyNumbers degrades to null numbers when our keys are unreadable`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.allPeers() } returns listOf(peer())
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } throws IllegalStateException("keystore unhappy")

        val numbers = harness.source.safetyNumbers()

        // A Keystore failure must not fail the sync: the peer
        // still syncs with its state + verified flag.
        assertThat(numbers).hasSize(1)
        assertThat(numbers[0].state).isEqualTo("encrypted")
        assertThat(numbers[0].safetyNumber).isNull()
    }

    @Test fun `observeSafetyNumbers derives on every emission`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.observeAllPeers() } returns flowOf(listOf(peer(verified = true)))
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ourKeys()

        var emitted = 0
        harness.source.observeSafetyNumbers().collect { numbers ->
            emitted++
            assertThat(numbers).hasSize(1)
            assertThat(numbers[0].verified).isTrue()
            assertThat(numbers[0].safetyNumber).isNotNull()
        }
        assertThat(emitted).isEqualTo(1)
    }

    // ---- Round 48 — canonical-address dedup at the sync boundary ----

    @Test fun `safetyNumbers dedupes a legacy +1 row and its canonical row into one peer`() = runTest {
        val harness = Harness()
        val ours = ourKeys()
        // The DAO's ORDER BY address ASC puts "+1…" first — the
        // shape that used to make the desktop resolve the keyless
        // legacy row and show "No safety number yet" for an
        // encrypted peer (device-proven 2026-11-23).
        coEvery { harness.peers.allPeers() } returns listOf(
            peer(
                address = "+16108423232",
                curveKey = null,
                edKey = null,
                verified = false,
                updatedAt = NOW,
            ),
            peer(
                address = "6108423232",
                verified = true,
                updatedAt = NOW + 1_000,
            ),
        )
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ours

        val numbers = harness.source.safetyNumbers()

        assertThat(numbers).hasSize(1)
        assertThat(numbers[0].address).isEqualTo("6108423232")
        assertThat(numbers[0].verified).isTrue()
        val expected = E2eSafetyNumber.derive(
            ours.curve25519,
            ByteArray(32) { (it + 32).toByte() },
            ours.ed25519,
            ByteArray(32) { (it + 96).toByte() },
        )
        assertThat(numbers[0].safetyNumber).isEqualTo(expected)
    }

    @Test fun `safetyNumbers leaves a single canonical row unchanged`() = runTest {
        val harness = Harness()
        val ours = ourKeys()
        coEvery { harness.peers.allPeers() } returns listOf(
            peer(address = "6108423232", verified = true),
        )
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ours

        val numbers = harness.source.safetyNumbers()

        // The dedup is a no-op for a peer with one row: same
        // address, number and verified flag as before.
        assertThat(numbers).hasSize(1)
        assertThat(numbers[0].address).isEqualTo("6108423232")
        assertThat(numbers[0].verified).isTrue()
        val expected = E2eSafetyNumber.derive(
            ours.curve25519,
            ByteArray(32) { (it + 32).toByte() },
            ours.ed25519,
            ByteArray(32) { (it + 96).toByte() },
        )
        assertThat(numbers[0].safetyNumber).isEqualTo(expected)
    }

    @Test fun `safetyNumbers keeps distinct peers as separate entries`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.allPeers() } returns listOf(
            peer(address = ALICE),
            peer(address = BOB, verified = true),
        )
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ourKeys()

        val numbers = harness.source.safetyNumbers()

        assertThat(numbers).hasSize(2)
        assertThat(numbers.map { it.address }).containsExactly(ALICE, BOB).inOrder()
        assertThat(numbers.map { it.verified }).containsExactly(false, true).inOrder()
        assertThat(numbers[0].safetyNumber).isNotNull()
        assertThat(numbers[1].safetyNumber).isNotNull()
    }

    @Test fun `safetyNumbers prefers the most recently updated row when both hold keys`() = runTest {
        val harness = Harness()
        val ours = ourKeys()
        val oldCurve = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val oldEd = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 16).toByte() })
        coEvery { harness.peers.allPeers() } returns listOf(
            peer(
                address = "+16108423232",
                curveKey = oldCurve,
                edKey = oldEd,
                verified = false,
                updatedAt = NOW,
            ),
            peer(
                address = "6108423232",
                verified = true,
                updatedAt = NOW + 1_000,
            ),
        )
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ours

        val numbers = harness.source.safetyNumbers()

        // Both rows hold keys, so the tie-break is recency: the
        // newer canonical row's number + verified flag win.
        assertThat(numbers).hasSize(1)
        assertThat(numbers[0].address).isEqualTo("6108423232")
        assertThat(numbers[0].verified).isTrue()
        val expected = E2eSafetyNumber.derive(
            ours.curve25519,
            ByteArray(32) { (it + 32).toByte() },
            ours.ed25519,
            ByteArray(32) { (it + 96).toByte() },
        )
        assertThat(numbers[0].safetyNumber).isEqualTo(expected)
    }

    @Test fun `observeSafetyNumbers dedupes on every emission too`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.observeAllPeers() } returns flowOf(
            listOf(
                peer(
                    address = "+16108423232",
                    curveKey = null,
                    edKey = null,
                    verified = false,
                    updatedAt = NOW,
                ),
                peer(
                    address = "6108423232",
                    verified = true,
                    updatedAt = NOW + 1_000,
                ),
            ),
        )
        coEvery { harness.accounts.hasAccount() } returns true
        coEvery { harness.accounts.identityKeys() } returns ourKeys()

        var emitted = 0
        harness.source.observeSafetyNumbers().collect { numbers ->
            emitted++
            assertThat(numbers).hasSize(1)
            assertThat(numbers[0].address).isEqualTo("6108423232")
            assertThat(numbers[0].verified).isTrue()
            assertThat(numbers[0].safetyNumber).isNotNull()
        }
        assertThat(emitted).isEqualTo(1)
    }

    @Test fun `notices maps kinds to lowercase wire strings and ids to decimal strings`() = runTest {
        val harness = Harness()
        coEvery { harness.notices.allNotices() } returns listOf(
            E2eNoticeEntity(id = 12, conversationId = 123, kind = E2eNoticeKind.ACTIVATED, date = NOW),
            E2eNoticeEntity(id = 13, conversationId = 123, kind = E2eNoticeKind.KEY_CHANGED, date = NOW + 1),
            E2eNoticeEntity(id = 14, conversationId = 456, kind = E2eNoticeKind.KEY_CHANGED_VERIFIED, date = NOW + 2),
            E2eNoticeEntity(id = 15, conversationId = 456, kind = E2eNoticeKind.DEACTIVATED, date = NOW + 3),
        )

        val result = harness.source.notices()

        assertThat(result.map { it.id }).containsExactly("12", "13", "14", "15").inOrder()
        assertThat(result.map { it.conversationId })
            .containsExactly("123", "123", "456", "456").inOrder()
        assertThat(result.map { it.kind })
            .containsExactly("activated", "key_changed", "key_changed_verified", "deactivated")
            .inOrder()
        assertThat(result.map { it.date })
            .containsExactly(NOW, NOW + 1, NOW + 2, NOW + 3).inOrder()
    }

    @Test fun `observeNotices maps on every emission`() = runTest {
        val harness = Harness()
        coEvery { harness.notices.observeAllNotices() } returns flowOf(
            listOf(
                E2eNoticeEntity(id = 12, conversationId = 123, kind = E2eNoticeKind.ACTIVATED, date = NOW),
            ),
        )

        var emitted = 0
        harness.source.observeNotices().collect { result ->
            emitted++
            assertThat(result).hasSize(1)
            assertThat(result[0].kind).isEqualTo("activated")
        }
        assertThat(emitted).isEqualTo(1)
    }

    @Test fun `setVerified writes through to the peer store`() = runTest {
        val harness = Harness()
        coEvery { harness.peers.setVerified(any(), any(), any()) } returns Unit

        val result = harness.source.setVerified(ALICE, true)

        assertThat(result.ok).isTrue()
        coVerify(exactly = 1) { harness.peers.setVerified(ALICE, true, any()) }
    }

    @Test fun `retryProbe resolves the conversation by exact CSV and probes manually`() = runTest {
        val harness = Harness()
        coEvery { harness.conversations.findByAddressesCsv(ALICE) } returns
            conversation(CONVERSATION_ID, ALICE)
        coEvery { harness.probes.probeConversation(CONVERSATION_ID, any(), true) } returns true

        val result = harness.source.retryProbe(ALICE)

        assertThat(result.ok).isTrue()
        coVerify(exactly = 1) { harness.probes.probeConversation(CONVERSATION_ID, any(), true) }
        // The exact match wins — the identity fallback never runs.
        coVerify(exactly = 0) { harness.phoneIdentity.snapshot() }
    }

    @Test fun `retryProbe falls back to identity matching when the exact CSV misses`() = runTest {
        val harness = Harness()
        coEvery { harness.conversations.findByAddressesCsv(ALICE) } returns null
        coEvery { harness.conversations.snapshotOneToOneConversations() } returns listOf(
            conversation(CONVERSATION_ID, "0612345678"),
        )
        val snapshot = mockk<PhoneIdentity.Snapshot>()
        every { snapshot.matches("0612345678", ALICE) } returns true
        coEvery { harness.phoneIdentity.snapshot() } returns snapshot
        coEvery { harness.probes.probeConversation(CONVERSATION_ID, any(), true) } returns true

        val result = harness.source.retryProbe(ALICE)

        assertThat(result.ok).isTrue()
        coVerify(exactly = 1) { harness.probes.probeConversation(CONVERSATION_ID, any(), true) }
    }

    @Test fun `retryProbe answers unknown_conversation when no conversation exists`() = runTest {
        val harness = Harness()
        coEvery { harness.conversations.findByAddressesCsv(ALICE) } returns null
        coEvery { harness.conversations.snapshotOneToOneConversations() } returns emptyList()
        val snapshot = mockk<PhoneIdentity.Snapshot>()
        coEvery { harness.phoneIdentity.snapshot() } returns snapshot

        val result = harness.source.retryProbe(ALICE)

        assertThat(result.ok).isFalse()
        assertThat(result.code).isEqualTo(SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION)
        coVerify(exactly = 0) { harness.probes.probeConversation(any(), any(), any()) }
    }

    @Test fun `retryProbe answers send_failed when the probe could not go out`() = runTest {
        val harness = Harness()
        coEvery { harness.conversations.findByAddressesCsv(ALICE) } returns
            conversation(CONVERSATION_ID, ALICE)
        coEvery { harness.probes.probeConversation(CONVERSATION_ID, any(), true) } returns false

        val result = harness.source.retryProbe(ALICE)

        assertThat(result.ok).isFalse()
        assertThat(result.code).isEqualTo(SyncProtocol.MessageFailed.SEND_FAILED)
    }

    // ---- Round 48 — media-key arrivals ----

    @Test
    fun `conversationForMediaKey resolves the part's conversation`() = runTest {
        val harness = Harness()
        val attachment = mockk<AttachmentEntity>()
        every { attachment.messageId } returns 77L
        val message = mockk<MessageEntity>()
        every { message.conversationId } returns CONVERSATION_ID
        coEvery {
            harness.attachmentDao.findByFileName("relay-media-0123456789abcdef.bin")
        } returns attachment
        coEvery { harness.messageDao.findById(77L) } returns message

        val result = harness.source.conversationForMediaKey("0123456789abcdef")

        assertThat(result).isEqualTo(CONVERSATION_ID)
    }

    @Test
    fun `conversationForMediaKey answers null when the part has not arrived`() = runTest {
        val harness = Harness()
        coEvery { harness.attachmentDao.findByFileName(any()) } returns null

        val result = harness.source.conversationForMediaKey("0123456789abcdef")

        assertThat(result).isNull()
        coVerify(exactly = 0) { harness.messageDao.findById(any()) }
    }

    @Test
    fun `conversationForMediaKey answers null when the message is gone`() = runTest {
        val harness = Harness()
        val attachment = mockk<AttachmentEntity>()
        every { attachment.messageId } returns 77L
        coEvery { harness.attachmentDao.findByFileName(any()) } returns attachment
        coEvery { harness.messageDao.findById(77L) } returns null

        val result = harness.source.conversationForMediaKey("0123456789abcdef")

        assertThat(result).isNull()
    }

    @Test
    fun `observeMediaKeyArrivals delegates to the store flow`() = runTest {
        val harness = Harness()
        every { harness.mediaKeys.keyArrivals } returns flowOf("0123456789abcdef")

        val arrivals = mutableListOf<String>()
        harness.source.observeMediaKeyArrivals().collect { arrivals += it }

        assertThat(arrivals).containsExactly("0123456789abcdef")
    }
}
