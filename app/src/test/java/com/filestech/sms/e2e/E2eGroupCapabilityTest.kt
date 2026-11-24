package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.e2e.E2eHandshake.HandshakePacket
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Group-capability signaling tests (round 49, design §13): the
 * flags byte (bit 1 = groups), the round-48 packet's forward
 * compatibility, and the capability manager's group paths (the
 * combined declaration, the per-flag announce, the announce-back
 * when either flag is unset).
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eGroupCapabilityTest {

    private companion object {
        const val BOB = "+33612345678"
        const val SUB_ID = 2
        const val NOW = 1_800_000_000_000L
        const val FLAGS_OFFSET = 6
    }

    private class Harness(
        val peerDao: FakeE2ePeerDao = FakeE2ePeerDao(),
        val sender: RecordingDataSmsSender = RecordingDataSmsSender(),
    ) {
        val peers = E2ePeerRepository(peerDao)
        val manager = E2eMediaCapabilityManager(
            peers = peers,
            dataSmsSender = sender,
            blockedRepo = FakeBlockedRepo(),
        )

        fun seedPeer(
            state: E2ePeerState = E2ePeerState.ENCRYPTED,
            mediaCapable: Boolean = false,
            mediaAnnounced: Boolean = false,
            groupCapable: Boolean = false,
            groupAnnounced: Boolean = false,
            handshakeChannel: String? = "DATA_SMS",
        ) {
            peerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = state,
                mediaCapable = mediaCapable,
                mediaAnnounced = mediaAnnounced,
                groupCapable = groupCapable,
                groupAnnounced = groupAnnounced,
                handshakeChannel = handshakeChannel,
                updatedAt = NOW,
            )
        }
    }

    // ──────────────────────────── The flags byte ────────────────────────────

    @Test
    fun `our declaration sets both the media and the group flag`() {
        val decoded = E2eHandshake.decode(E2eHandshake.encodeCapabilityDeclaration())
            as HandshakePacket.Capability

        assertThat(decoded.mediaCapable).isTrue()
        assertThat(decoded.groupCapable).isTrue()
    }

    @Test
    fun `a round-48 packet (media bit only) decodes as media-only`() {
        // A round-48 build's declaration: flags 0x01 — the group
        // bit is clear, so the peer is media-capable but not
        // group-capable, and groups with them stay plain.
        val bytes = E2eHandshake.encodeCapabilityDeclaration()
        bytes[FLAGS_OFFSET] = 0x01

        val decoded = E2eHandshake.decode(bytes) as HandshakePacket.Capability

        assertThat(decoded.mediaCapable).isTrue()
        assertThat(decoded.groupCapable).isFalse()
    }

    @Test
    fun `the capability packet is still 70 bytes (the old-build swallow property)`() {
        assertThat(E2eHandshake.encodeCapabilityDeclaration().size).isEqualTo(70)
        assertThat(E2eHandshake.CAPABILITY_SIZE).isEqualTo(E2eHandshake.PROBE_SIZE)
    }

    // ──────────────────────────── The manager's group paths ────────────────────────────

    @Test
    fun `onDeclarationReceived with the group flag records group capability`() = runTest {
        val harness = Harness()
        harness.seedPeer()

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW, mediaCapable = true, groupCapable = true)

        assertThat(harness.peerDao.peers[BOB]!!.mediaCapable).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupCapable).isTrue()
    }

    @Test
    fun `onDeclarationReceived without the group flag records group incapable`() = runTest {
        val harness = Harness()
        harness.seedPeer(groupCapable = true)

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW, mediaCapable = true, groupCapable = false)

        // The flags are the peer's own statement, recorded as
        // decoded — a newer declaration that drops the group bit
        // clears the flag.
        assertThat(harness.peerDao.peers[BOB]!!.groupCapable).isFalse()
    }

    @Test
    fun `onDeclarationReceived announces back when only the group flag is unset`() = runTest {
        val harness = Harness()
        harness.seedPeer(mediaAnnounced = true, groupAnnounced = false)

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW, mediaCapable = true, groupCapable = true)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isTrue()
    }

    @Test
    fun `announceIfNeeded re-fires when only the group flag is set`() = runTest {
        val harness = Harness()
        harness.seedPeer(mediaAnnounced = false, groupAnnounced = true)

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isTrue()
    }

    @Test
    fun `a successful announce sets both announced flags`() = runTest {
        val harness = Harness()
        harness.seedPeer()

        val sent = harness.manager.announceNow(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isTrue()
    }

    @Test
    fun `a radio refusal leaves both announced flags clear`() = runTest {
        val harness = Harness(sender = RecordingDataSmsSender(result = false))
        harness.seedPeer()

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isFalse()
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isFalse()
    }
}
