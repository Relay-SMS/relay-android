package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.purge.MS_PER_DAY
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.EncryptionSettings
import com.filestech.sms.domain.settings.SendingSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Probe orchestration (design §2.3–§2.4): eligibility, the probe budget,
 * and the fire-and-forget triggers.
 *
 * The suspend cores ([E2eProbeManager.probeConversation],
 * [E2eProbeManager.probeAddress]) are the testable surface; the
 * non-suspend triggers are exercised through a [TestScope] the manager
 * owns, driven with `advanceUntilIdle`.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eProbeManagerTest {

    private companion object {
        const val ALICE = "+33612345678"
        const val NOW = 1_800_000_000_000L
        const val CHOSEN_SUB_ID = 7
    }

    private class SettingsWithSubId(
        private val subId: Int?,
        private val autoDiscover: Boolean = true,
    ) : AppSettingsSource {
        private val value = AppSettings(
            sending = SendingSettings(defaultSubId = subId),
            encryption = EncryptionSettings(autoDiscoverRelayContacts = autoDiscover),
        )
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private class Harness(
        val peerDao: FakeE2ePeerDao = FakeE2ePeerDao(),
        val accountDao: FakeE2eAccountDao = FakeE2eAccountDao(),
        val sender: RecordingDataSmsSender = RecordingDataSmsSender(),
        val blocked: FakeBlockedRepo = FakeBlockedRepo(),
        val conversationDao: ConversationDao = mockk(),
        val settings: AppSettingsSource = SettingsWithSubId(CHOSEN_SUB_ID),
        val crypto: FakeE2eCrypto = FakeE2eCrypto("alice"),
        scope: CoroutineScope,
    ) {
        val peers = E2ePeerRepository(peerDao)
        val accountManager = E2eAccountManager(accountDao, crypto, fakeKeyManager())

        val manager = E2eProbeManager(
            conversationDao = conversationDao,
            blockedRepo = blocked,
            peers = peers,
            accountManager = lazyOf(accountManager),
            dataSmsSender = sender,
            settings = settings,
            scope = scope,
        )

        fun seedPeer(
            address: String = ALICE,
            state: E2ePeerState = E2ePeerState.UNKNOWN,
            attempts: Int = 0,
            windowStartedAt: Long? = null,
            lastProbeAt: Long? = null,
        ) {
            peerDao.peers[address] = E2ePeerEntity(
                address = address,
                state = state,
                probeAttempts = attempts,
                windowStartedAt = windowStartedAt,
                lastProbeAt = lastProbeAt,
                updatedAt = NOW,
            )
        }

        fun conversationWith(addressesCsv: String): ConversationEntity {
            val conv = mockk<ConversationEntity>()
            every { conv.addressesCsv } returns addressesCsv
            return conv
        }
    }

    // ──────────────────────────── Thread-open probe ────────────────────────────

    @Test
    fun `opening a 1-1 thread probes on the settings subscription`() = runTest {
        val harness = Harness(scope = this)
        coEvery { harness.conversationDao.findById(11L) } returns
            harness.conversationWith(ALICE)

        val probed = harness.manager.probeConversation(11L, NOW)

        assertThat(probed).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        val sent = harness.sender.sent.single()
        assertThat(sent.address).isEqualTo(ALICE)
        assertThat(sent.subId).isEqualTo(CHOSEN_SUB_ID)
        assertThat(sent.port).isEqualTo(E2eHandshake.E2E_DATA_SMS_PORT)
        // The probe carries OUR identity keys, decodable by the peer.
        val packet = E2eHandshake.decode(sent.data)
        assertThat(packet).isInstanceOf(E2eHandshake.HandshakePacket.Probe::class.java)
        val probe = packet as E2eHandshake.HandshakePacket.Probe
        assertThat(probe.curveKey).isEqualTo(harness.crypto.identityKeys().curve25519)
        assertThat(probe.edKey).isEqualTo(harness.crypto.identityKeys().ed25519)
        // And the attempt is recorded: PROBING, 1 attempt, window opened.
        val peer = harness.peerDao.peers[ALICE]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(peer?.probeAttempts).isEqualTo(1)
        assertThat(peer?.windowStartedAt).isEqualTo(NOW)
        assertThat(peer?.lastProbeAt).isEqualTo(NOW)
    }

    @Test
    fun `opening a group thread does not probe`() = runTest {
        val harness = Harness(scope = this)
        coEvery { harness.conversationDao.findById(11L) } returns
            harness.conversationWith("$ALICE;+33622222222")

        val probed = harness.manager.probeConversation(11L, NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    @Test
    fun `opening a missing conversation does not probe`() = runTest {
        val harness = Harness(scope = this)
        coEvery { harness.conversationDao.findById(11L) } returns null

        val probed = harness.manager.probeConversation(11L, NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    @Test
    fun `onThreadOpened fires the probe in the background`() = runTest {
        val harness = Harness(scope = this)
        coEvery { harness.conversationDao.findById(11L) } returns
            harness.conversationWith(ALICE)

        harness.manager.onThreadOpened(11L)
        testScheduler.advanceUntilIdle()

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(ALICE)
    }

    // ──────────────────────────── Eligibility ────────────────────────────

    @Test
    fun `a short code is not probed`() = runTest {
        val harness = Harness(scope = this)

        val probed = harness.manager.probeAddress("12345", null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
    }

    @Test
    fun `an alphanumeric sender is not probed`() = runTest {
        val harness = Harness(scope = this)

        val probed = harness.manager.probeAddress("AMAZON", null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    @Test
    fun `a blocked number is not probed`() = runTest {
        val harness = Harness(scope = this, blocked = FakeBlockedRepo(setOf(ALICE)))

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    @Test
    fun `an encrypted peer is not probed`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(state = E2ePeerState.ENCRYPTED)

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    // ──────────────────────────── Discovery toggle (round 40) ────────────────────────────

    @Test
    fun `discovery off sends nothing and records nothing`() = runTest {
        val harness = Harness(
            scope = this,
            settings = SettingsWithSubId(CHOSEN_SUB_ID, autoDiscover = false),
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
        // The gate is before account creation: a disabled feature
        // creates no identity.
        assertThat(harness.accountDao.stored).isNull()
    }

    @Test
    fun `discovery off blocks the decrypt-failure re-probe too`() = runTest {
        val harness = Harness(
            scope = this,
            settings = SettingsWithSubId(CHOSEN_SUB_ID, autoDiscover = false),
        )
        harness.seedPeer(state = E2ePeerState.ENCRYPTED)

        val probed = harness.manager.probeAddress(ALICE, null, allowEncrypted = true, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers[ALICE]?.probeAttempts).isEqualTo(0)
    }

    @Test
    fun `discovery on probes as before`() = runTest {
        val harness = Harness(
            scope = this,
            settings = SettingsWithSubId(CHOSEN_SUB_ID, autoDiscover = true),
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.peerDao.peers[ALICE]?.state).isEqualTo(E2ePeerState.PROBING)
    }

    // ──────────────────────────── Budget ────────────────────────────

    @Test
    fun `the 24h spacing is respected`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(
            state = E2ePeerState.PROBING,
            attempts = 1,
            windowStartedAt = NOW - 2 * MS_PER_DAY,
            lastProbeAt = NOW - 60 * 60 * 1000L,
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    @Test
    fun `a second attempt goes out after 24h`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(
            state = E2ePeerState.PROBING,
            attempts = 1,
            windowStartedAt = NOW - 2 * MS_PER_DAY,
            lastProbeAt = NOW - 2 * MS_PER_DAY,
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isTrue()
        assertThat(harness.peerDao.peers[ALICE]?.probeAttempts).isEqualTo(2)
        assertThat(harness.peerDao.peers[ALICE]?.state).isEqualTo(E2ePeerState.PROBING)
    }

    @Test
    fun `the budget is exhausted at 3 attempts inside the window`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(
            state = E2ePeerState.NOT_RELAY,
            attempts = 3,
            windowStartedAt = NOW - 2 * MS_PER_DAY,
            lastProbeAt = NOW - 2 * MS_PER_DAY,
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }

    @Test
    fun `the third attempt flips the peer to NOT_RELAY`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(
            state = E2ePeerState.PROBING,
            attempts = 2,
            windowStartedAt = NOW - 2 * MS_PER_DAY,
            lastProbeAt = NOW - 2 * MS_PER_DAY,
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isTrue()
        assertThat(harness.peerDao.peers[ALICE]?.probeAttempts).isEqualTo(3)
        assertThat(harness.peerDao.peers[ALICE]?.state).isEqualTo(E2ePeerState.NOT_RELAY)
    }

    @Test
    fun `a new window re-opens the budget`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(
            state = E2ePeerState.NOT_RELAY,
            attempts = 3,
            windowStartedAt = NOW - 31 * MS_PER_DAY,
            lastProbeAt = NOW - 31 * MS_PER_DAY,
        )

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isTrue()
        assertThat(harness.peerDao.peers[ALICE]?.probeAttempts).isEqualTo(1)
        assertThat(harness.peerDao.peers[ALICE]?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(harness.peerDao.peers[ALICE]?.windowStartedAt).isEqualTo(NOW)
    }

    @Test
    fun `a failed send consumes no budget`() = runTest {
        val harness = Harness(scope = this, sender = RecordingDataSmsSender(result = false))

        val probed = harness.manager.probeAddress(ALICE, null, now = NOW)

        assertThat(probed).isFalse()
        // The radio refused: no row, no attempt recorded — the next
        // trigger gets a full budget.
        assertThat(harness.peerDao.peers).isEmpty()
    }

    // ──────────────────────────── The send path + the re-probe ────────────────────────────

    @Test
    fun `probeForSend carries the send subscription`() = runTest {
        val harness = Harness(scope = this)

        harness.manager.probeForSend(ALICE, 3)
        testScheduler.advanceUntilIdle()

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().subId).isEqualTo(3)
    }

    @Test
    fun `the decrypt-failure re-probe reaches an encrypted peer`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(state = E2ePeerState.ENCRYPTED)

        harness.manager.reprobeAfterDecryptFailure(ALICE, null)
        testScheduler.advanceUntilIdle()

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.peerDao.peers[ALICE]?.probeAttempts).isEqualTo(1)
    }

    @Test
    fun `the decrypt-failure re-probe still respects the budget`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(
            state = E2ePeerState.ENCRYPTED,
            attempts = 3,
            windowStartedAt = NOW - 2 * MS_PER_DAY,
            lastProbeAt = NOW - 2 * MS_PER_DAY,
        )

        val probed = harness.manager.probeAddress(ALICE, null, allowEncrypted = true, now = NOW)

        assertThat(probed).isFalse()
        assertThat(harness.sender.sent).isEmpty()
    }
}
