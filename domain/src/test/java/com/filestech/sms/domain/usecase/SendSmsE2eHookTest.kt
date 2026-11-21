package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The E2E send hook in [SendSmsUseCase] (design §3.4 + §12.2): the
 * encryption decision is taken before the multi-recipient fan-out, and
 * the plain path must behave byte-identically when E2E is present but
 * inactive.
 *
 * The gate is faked here: the real gate ([com.filestech.sms.e2e
 * .E2eSendManager]) is covered by its own tests in the app module.
 * What this file locks is the USE-CASE side of the contract:
 *
 * - Encrypted → the mirror row carries `encrypted = 1` with no system
 *   row (the plaintext never enters the system sent box), the plain
 *   sender is never called, and the gate's `sendEncrypted` does the
 *   dispatch.
 * - Plain → the gate is consulted, a probe is triggered, and the send
 *   goes out exactly as it did before E2E existed.
 * - RefuseOverCap → the send is refused BEFORE any row is written, and
 *   never silently falls back to plain.
 * - Groups never consult the gate. A reaction consults it only when it
 *   carries the REACTION payload type (a 1:1 reaction, per
 *   [SendReactionUseCase]); any other reaction stays plain and only
 *   probes.
 */
class SendSmsE2eHookTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
        const val BODY = "hello, encrypted world"
    }

    private class DefaultSettings : AppSettingsSource {
        private val value = AppSettings()
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    /** A recording [E2eSendGate]: the test chooses the decision and the send outcome. */
    private class FakeGate(
        var decision: E2eSendGate.Decision = E2eSendGate.Decision.Plain,
        var sendOutcome: Outcome<Unit> = Outcome.Success(Unit),
    ) : E2eSendGate {
        data class EncryptedCall(
            val localMessageId: Long,
            val address: String,
            val body: String,
            val subId: Int?,
            val requestDeliveryReport: Boolean,
            val attempt: Int,
            val payloadType: E2ePayloadType,
        )

        val decidedFor = mutableListOf<String>()
        val probed = mutableListOf<String>()
        val encryptedCalls = mutableListOf<EncryptedCall>()

        override suspend fun decideSend(address: String, body: String): E2eSendGate.Decision {
            decidedFor += address
            return decision
        }

        override suspend fun sendEncrypted(
            localMessageId: Long,
            address: String,
            body: String,
            date: Long,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
            payloadType: E2ePayloadType,
        ): Outcome<Unit> {
            encryptedCalls += EncryptedCall(
                localMessageId, address, body, subId, requestDeliveryReport, attempt, payloadType,
            )
            return sendOutcome
        }

        override fun probeForSend(address: String, subId: Int?) {
            probed += address
        }
    }

    private fun send(
        recipients: List<String>,
        body: String = BODY,
        gate: E2eSendGate,
        sender: RecordingSender = RecordingSender(),
        mirror: NoopMirror = NoopMirror(),
        recorder: RecordingRecorder = RecordingRecorder(),
        blocked: Set<String> = emptySet(),
        localMirrorBody: String? = null,
        e2ePayloadType: E2ePayloadType = E2ePayloadType.TEXT,
    ): Outcome<SendReport> = runBlocking {
        sendSmsUseCase(
            settings = DefaultSettings(),
            sender = sender,
            recorder = recorder,
            mirror = mirror,
            blocked = BlockedList(blocked),
            e2eGate = gate,
        ).invoke(
            recipients = recipients.map { PhoneAddress.of(it) },
            body = body,
            localMirrorBody = localMirrorBody,
            e2ePayloadType = e2ePayloadType,
        )
    }

    // ──────────────────────────── The encrypted path ────────────────────────────

    @Test
    fun `encrypted send mirrors encrypted and never touches the plain sender`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Encrypted)
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = send(listOf(ALICE), gate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // The mirror row carries the plaintext WITH the encrypted flag,
        // and no system row exists for it (the plaintext never enters
        // the system sent box — the ciphertext fragments do, later).
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isTrue()
        assertThat(mirror.lignes.single().telephonyUri).isNull()
        assertThat(recorder.adressesEcrites).isEmpty()
        // The plain sender never sees the message; the gate dispatches.
        assertThat(sender.callCount).isEqualTo(0)
        assertThat(gate.encryptedCalls).hasSize(1)
        assertThat(gate.encryptedCalls.single().address).isEqualTo(ALICE)
        assertThat(gate.encryptedCalls.single().body).isEqualTo(BODY)
        assertThat(gate.encryptedCalls.single().attempt).isEqualTo(0)
        assertThat(gate.encryptedCalls.single().payloadType).isEqualTo(E2ePayloadType.TEXT)
    }

    @Test
    fun `encrypted send failure marks the row failed`() {
        val gate = FakeGate(
            decision = E2eSendGate.Decision.Encrypted,
            sendOutcome = Outcome.Failure(AppError.Telephony("radio down")),
        )
        val mirror = NoopMirror()

        val outcome = send(listOf(ALICE), gate = gate, mirror = mirror)

        // Nothing dispatched, so the use case reports the failure (the
        // same "no message dispatched" outcome as a failed plain send).
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isTrue()
        assertThat(mirror.statuts).hasSize(1)
        assertThat(mirror.statuts.single().second).isEqualTo(MessageStatus.FAILED)
    }

    // ──────────────────────────── The plain path ────────────────────────────

    @Test
    fun `plain decision probes and sends exactly as before`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Plain)
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = send(listOf(ALICE), gate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(ALICE)
        assertThat(gate.probed).containsExactly(ALICE)
        assertThat(gate.encryptedCalls).isEmpty()
        // Byte-identical plain behavior: system row written, plain
        // sender called once, mirror row unencrypted.
        assertThat(recorder.adressesEcrites).containsExactly(ALICE)
        assertThat(sender.callCount).isEqualTo(1)
        assertThat(sender.lastText).isEqualTo(BODY)
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
        assertThat(mirror.lignes.single().telephonyUri).isNotNull()
    }

    @Test
    fun `noop gate behaves byte-identically to the pre-e2e send`() {
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = send(
            listOf(ALICE),
            gate = E2eSendGate.NoOp,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(recorder.adressesEcrites).containsExactly(ALICE)
        assertThat(sender.callCount).isEqualTo(1)
        assertThat(sender.lastText).isEqualTo(BODY)
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
    }

    // ──────────────────────────── Refusal, never silent fallback ────────────────────────────

    @Test
    fun `over-cap encrypted send is refused before any row is written`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.RefuseOverCap)
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = send(listOf(ALICE), gate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        // Nothing was written, nothing was sent, nothing was probed:
        // the refusal happens before the fan-out.
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.statuts).isEmpty()
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(sender.callCount).isEqualTo(0)
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(gate.probed).isEmpty()
    }

    // ──────────────────────────── Groups and reactions skip encryption ────────────────────────────

    @Test
    fun `group send never consults the gate`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Encrypted)
        val sender = RecordingSender()

        val outcome = send(listOf(ALICE, BOB), gate = gate, sender = sender)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.probed).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(sender.callCount).isEqualTo(2)
    }

    @Test
    fun `reaction send with TEXT payload never consults the gate but still probes`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Encrypted)
        val sender = RecordingSender()
        val mirror = NoopMirror()

        val outcome = send(
            listOf(ALICE),
            gate = gate,
            sender = sender,
            mirror = mirror,
            localMirrorBody = "",
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // A reaction without the REACTION payload type (group,
        // multi-target) is never encrypted (the gate is not consulted),
        // but sending to the thread is intent to talk: probe.
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(gate.probed).containsExactly(ALICE)
        assertThat(sender.callCount).isEqualTo(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
    }

    // ──────────────────────────── 1:1 reactions encrypt ────────────────────────────

    @Test
    fun `reaction with REACTION payload encrypts when the peer is encrypted`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Encrypted)
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = send(
            listOf(ALICE),
            body = "Liked \"salut\"",
            gate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            localMirrorBody = "",
            e2ePayloadType = E2ePayloadType.REACTION,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // The gate dispatches with the REACTION payload type and the
        // formatted tapback body, untouched.
        assertThat(gate.decidedFor).containsExactly(ALICE)
        assertThat(gate.encryptedCalls).hasSize(1)
        assertThat(gate.encryptedCalls.single().payloadType).isEqualTo(E2ePayloadType.REACTION)
        assertThat(gate.encryptedCalls.single().body).isEqualTo("Liked \"salut\"")
        // The plain sender never sees it; the mirror row is encrypted
        // with no system row (the plaintext never enters the sent box).
        assertThat(sender.callCount).isEqualTo(0)
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(mirror.lignes.single().encrypted).isTrue()
        assertThat(mirror.lignes.single().telephonyUri).isNull()
    }

    @Test
    fun `reaction with REACTION payload stays plain for a non-encrypted peer`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Plain)
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = send(
            listOf(ALICE),
            body = "Liked \"salut\"",
            gate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            localMirrorBody = "",
            e2ePayloadType = E2ePayloadType.REACTION,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // Plain, byte-identical to the pre-encryption reaction send:
        // system row written, plain sender called once with the
        // formatted body, mirror row unencrypted — plus a probe.
        assertThat(gate.decidedFor).containsExactly(ALICE)
        assertThat(gate.probed).containsExactly(ALICE)
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(recorder.adressesEcrites).containsExactly(ALICE)
        assertThat(sender.callCount).isEqualTo(1)
        assertThat(sender.lastText).isEqualTo("Liked \"salut\"")
        assertThat(mirror.lignes.single().encrypted).isFalse()
        assertThat(mirror.lignes.single().telephonyUri).isNotNull()
    }

    @Test
    fun `multi-recipient reaction never encrypts even with REACTION payload`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Encrypted)
        val sender = RecordingSender()

        val outcome = send(
            listOf(ALICE, BOB),
            body = "Liked \"salut\"",
            gate = gate,
            sender = sender,
            localMirrorBody = "",
            e2ePayloadType = E2ePayloadType.REACTION,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // The single-recipient guard still applies: no decision, no
        // encrypted dispatch, no probe — a plain broadcast.
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(gate.probed).isEmpty()
        assertThat(sender.callCount).isEqualTo(2)
    }

    // ──────────────────────────── Blocked recipients ────────────────────────────

    @Test
    fun `blocked recipient with encrypted decision leaves the plain blocked trace`() {
        val gate = FakeGate(decision = E2eSendGate.Decision.Encrypted)
        val sender = RecordingSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        send(
            listOf(ALICE),
            gate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            blocked = setOf(ALICE),
        )

        // The blocked trace is the plain one (unencrypted, no system
        // row): nothing went out, so there is nothing to encrypt.
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
        assertThat(mirror.lignes.single().telephonyUri).isNull()
        assertThat(mirror.statuts.single().second).isEqualTo(MessageStatus.FAILED)
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(sender.callCount).isEqualTo(0)
        assertThat(gate.encryptedCalls).isEmpty()
    }
}
