package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.e2e.E2eGroupSendGate
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.sender.SmsSender
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The encrypted-group send path in [SendSmsUseCase] (design §13):
 * a group text encrypts when EVERY member is ENCRYPTED +
 * group-capable — the same fragment set fans out to each member as
 * individual SMS, each member's 1:1 mirror row carries the
 * plaintext with `encrypted = 1`, and the group echo does too.
 *
 * The gate is faked here: the real gate ([com.filestech.sms.e2e
 * .E2eGroupSessionManager]) is covered by its own tests in the app
 * module. What this file locks is the USE-CASE side of the
 * contract:
 *
 * - Encrypted → the same fragments go to every member (each tied
 *   to that member's row id, so delivery tracks per member), the
 *   plain sender is never called, the fragment ciphertexts — not
 *   the plaintext — land in the system sent box, and the
 *   activation notice is recorded.
 * - Plain → the members are probed and the send goes out exactly
 *   as it did before encrypted groups existed.
 * - RefuseOverCap → the send is refused BEFORE any row is written.
 * - Encrypt failure → the group thread gets a FAILED echo, never
 *   a plain send, and nothing goes out on the radio.
 * - A multi-recipient send WITHOUT an echo (Safety call, emergency
 *   mode) and a group reaction never consult the group gate.
 */
class SendSmsGroupE2eTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
        const val BODY = "hello, encrypted group"
        val FRAGMENTS = listOf("FRAG-1", "FRAG-2")
        const val GROUP_ID = "0123456789abcdef0123456789abcdef"
    }

    /** A recording [E2eGroupSendGate]: the test chooses the decision and the encrypt outcome. */
    private class FakeGroupGate(
        var decision: E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain,
        var encryptError: AppError? = null,
    ) : E2eGroupSendGate {
        val decidedFor = mutableListOf<List<String>>()
        val encryptedFor = mutableListOf<List<String>>()
        val activatedFor = mutableListOf<List<String>>()

        override suspend fun decideGroupSend(
            memberAddresses: List<String>,
            body: String,
        ): E2eGroupSendGate.Decision {
            decidedFor += memberAddresses
            return decision
        }

        override suspend fun encryptGroupMessage(
            memberAddresses: List<String>,
            body: String,
            subId: Int?,
            date: Long,
            payloadType: E2ePayloadType,
        ): Outcome<E2eGroupSendGate.EncryptedGroupMessage> {
            encryptedFor += memberAddresses
            val error = encryptError
            return if (error != null) {
                Outcome.Failure(error)
            } else {
                Outcome.Success(E2eGroupSendGate.EncryptedGroupMessage(FRAGMENTS, GROUP_ID))
            }
        }

        override suspend fun recordGroupActivated(memberAddresses: List<String>, now: Long) {
            activatedFor += memberAddresses
        }
    }

    /** Records every fragment dispatch (and every plain send, which must not happen). */
    private class RecordingFragmentSender(
        var outcome: Outcome<Unit> = Outcome.Success(Unit),
    ) : SmsSender {
        data class FragmentCall(
            val localMessageId: Long,
            val destination: String,
            val text: String,
            val fragmentIndex: Int,
            val fragmentTotal: Int,
        )

        val fragments = mutableListOf<FragmentCall>()
        val plainSends = mutableListOf<String>()

        override fun send(
            localMessageId: Long,
            destination: String,
            text: String,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
        ): Outcome<Unit> {
            plainSends += text
            return outcome
        }

        override fun sendFragment(
            localMessageId: Long,
            destination: String,
            text: String,
            fragmentIndex: Int,
            fragmentTotal: Int,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
        ): Outcome<Unit> {
            fragments += FragmentCall(localMessageId, destination, text, fragmentIndex, fragmentTotal)
            return outcome
        }
    }

    /** Records the background probes (the 1:1 gate's only role on the group path). */
    private class ProbingGate : E2eSendGate {
        val probed = mutableListOf<String>()

        override suspend fun decideSend(address: String, body: String): E2eSendGate.Decision =
            E2eSendGate.Decision.Plain

        override suspend fun sendEncrypted(
            localMessageId: Long,
            address: String,
            body: String,
            date: Long,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
            payloadType: E2ePayloadType,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override fun probeForSend(address: String, subId: Int?) {
            probed += address
        }
    }

    private fun sendGroup(
        recipients: List<String> = listOf(ALICE, BOB),
        body: String = BODY,
        groupGate: E2eGroupSendGate,
        sender: SmsSender = RecordingFragmentSender(),
        mirror: NoopMirror = NoopMirror(),
        recorder: RecordingRecorder = RecordingRecorder(),
        echoInGroup: Boolean = true,
        localMirrorBody: String? = null,
        e2eGate: E2eSendGate = E2eSendGate.NoOp,
    ): Outcome<SendReport> = runBlocking {
        sendSmsUseCase(
            settings = DefaultSettingsSource(),
            sender = sender,
            recorder = recorder,
            mirror = mirror,
            groupGate = groupGate,
            e2eGate = e2eGate,
        ).invoke(
            recipients = recipients.map { PhoneAddress.of(it) },
            body = body,
            echoInGroup = echoInGroup,
            localMirrorBody = localMirrorBody,
        )
    }

    // ──────────────────────────── The encrypted path ────────────────────────────

    @Test
    fun `encrypted group fans the same fragments out to every member`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(gate.encryptedFor).containsExactly(listOf(ALICE, BOB))
        // The SAME fragment set to each member, in order, each tied
        // to that member's mirror row — so delivery tracks per
        // member (the tracker is keyed by message id + attempt).
        assertThat(sender.fragments).hasSize(4)
        val aliceFragments = sender.fragments.filter { it.destination == ALICE }
        val bobFragments = sender.fragments.filter { it.destination == BOB }
        assertThat(aliceFragments.map { it.text }).containsExactly("FRAG-1", "FRAG-2").inOrder()
        assertThat(bobFragments.map { it.text }).containsExactly("FRAG-1", "FRAG-2").inOrder()
        assertThat(aliceFragments.map { it.fragmentIndex }).containsExactly(0, 1).inOrder()
        assertThat(bobFragments.map { it.fragmentIndex }).containsExactly(0, 1).inOrder()
        assertThat(aliceFragments.map { it.fragmentTotal }.toSet()).containsExactly(2)
        assertThat(bobFragments.map { it.fragmentTotal }.toSet()).containsExactly(2)
        assertThat(aliceFragments.map { it.localMessageId }.toSet()).hasSize(1)
        assertThat(bobFragments.map { it.localMessageId }.toSet()).hasSize(1)
        assertThat(aliceFragments.first().localMessageId)
            .isNotEqualTo(bobFragments.first().localMessageId)
        // The plain sender never sees the message.
        assertThat(sender.plainSends).isEmpty()
        // The fragment ciphertexts — not the plaintext — land in the
        // system sent box, two per member.
        assertThat(recorder.adressesEcrites).containsExactly(ALICE, ALICE, BOB, BOB)
    }

    @Test
    fun `encrypted group mirrors encrypted rows and an encrypted echo`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val mirror = NoopMirror()

        val outcome = sendGroup(groupGate = gate, mirror = mirror)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // One 1:1 row per member, each carrying the plaintext WITH
        // the encrypted flag and no system row (the plaintext never
        // enters the system sent box).
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE, BOB)
        assertThat(mirror.lignes.all { it.encrypted }).isTrue()
        assertThat(mirror.lignes.all { it.telephonyUri == null }).isTrue()
        // The group echo carries the flag too, with the frozen
        // whole-send status.
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isTrue()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.SENT)
        assertThat(mirror.echos.single().corps).isEqualTo(BODY)
    }

    @Test
    fun `encrypted group records the activation notice on dispatch`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)

        val outcome = sendGroup(groupGate = gate)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.activatedFor).containsExactly(listOf(ALICE, BOB))
    }

    @Test
    fun `encrypted group radio failure fails the member row and records no activation`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender(outcome = radioFailure())
        val mirror = NoopMirror()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror)

        // Nothing dispatched, so the use case reports the failure.
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { it.encrypted }).isTrue()
        assertThat(mirror.statuts.map { it.second })
            .containsExactly(MessageStatus.FAILED, MessageStatus.FAILED)
        // The echo still carries the flag, with the frozen FAILED
        // status — the user sees the failure where they sent it.
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isTrue()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.FAILED)
        // No member's fragments dispatched, so no activation notice.
        assertThat(gate.activatedFor).isEmpty()
    }

    // ──────────────────────────── The plain path ────────────────────────────

    @Test
    fun `plain group sends exactly as before and probes its members`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Plain)
        val probeGate = ProbingGate()
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            e2eGate = probeGate,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(gate.activatedFor).isEmpty()
        // Every member gets a background probe: sending to the
        // thread is intent to talk, and the exchange that flips
        // the group to encrypted starts here.
        assertThat(probeGate.probed).containsExactly(ALICE, BOB)
        // Byte-identical plain behavior: system rows written, plain
        // sender called once per member, mirror rows unencrypted,
        // echo unencrypted.
        assertThat(recorder.adressesEcrites).containsExactly(ALICE, BOB)
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.lignes.all { it.telephonyUri != null }).isTrue()
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isFalse()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun `noop group gate behaves byte-identically to the pre-e2e group send`() {
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(
            groupGate = E2eGroupSendGate.NoOp,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(recorder.adressesEcrites).containsExactly(ALICE, BOB)
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isFalse()
    }

    // ──────────────────────────── Refusal and encrypt failure ────────────────────────────

    @Test
    fun `over-cap encrypted group is refused before any row is written`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.RefuseOverCap)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        // Nothing was written, nothing was sent: the refusal happens
        // before the fan-out.
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.echos).isEmpty()
        assertThat(mirror.statuts).isEmpty()
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(sender.plainSends).isEmpty()
        assertThat(sender.fragments).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(gate.activatedFor).isEmpty()
    }

    @Test
    fun `encrypt failure writes a failed echo and never sends plain`() {
        val gate = FakeGroupGate(
            decision = E2eGroupSendGate.Decision.Encrypted,
            encryptError = AppError.Validation("no own number"),
        )
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gate.encryptedFor).containsExactly(listOf(ALICE, BOB))
        // Nothing went out to anyone: no radio, no system rows, no
        // per-recipient rows — there is nothing to track.
        assertThat(sender.plainSends).isEmpty()
        assertThat(sender.fragments).isEmpty()
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.statuts).isEmpty()
        // The group thread gets a FAILED echo, carrying the flag, so
        // the user sees the failure where they sent it.
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isTrue()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.FAILED)
        assertThat(mirror.echos.single().corps).isEqualTo(BODY)
        assertThat(gate.activatedFor).isEmpty()
    }

    // ──────────────────────────── Non-group sends skip the gate ────────────────────────────

    @Test
    fun `multi-recipient send without an echo never consults the group gate`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()

        // A Safety call / emergency broadcast: each recipient gets a
        // private SMS, no echo — it is not a group send.
        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            echoInGroup = false,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.echos).isEmpty()
    }

    @Test
    fun `group reaction never consults the group gate`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()

        // A group reaction (empty local mirror body, like
        // SendReactionUseCase passes for groups) stays plain:
        // group reactions encrypt in a follow-up.
        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            localMirrorBody = "",
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.echos).isEmpty()
    }

    @Test
    fun `one-to-one send never consults the group gate`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()

        val outcome = sendGroup(
            recipients = listOf(ALICE),
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            echoInGroup = false,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(sender.plainSends).containsExactly(BODY)
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
        assertThat(mirror.echos).isEmpty()
    }
}
