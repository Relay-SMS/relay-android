package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.e2e.E2eGroupSendGate
import com.filestech.sms.domain.e2e.E2ePayloadType
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Round 49 — the encrypted-group routing in [EnvoyerMessageUseCase]
 * (design §13): an encrypted group TEXT always travels as
 * per-member SMS fragments — the group MMS path has no
 * encrypted-text channel, so the group-MMS setting never applies
 * to it. The router consults the group gate for group texts and
 * routes Encrypted to the SMS fan-out; the decision is re-taken
 * in [SendSmsUseCase] with the final (signed) body, so the
 * router's pre-check only routes — except an over-cap body,
 * which it refuses before any row is written.
 *
 * The same gate instance rides the router AND the inner SMS use
 * case (as in production, where Hilt binds one gate): the router
 * routes, the use case encrypts and fans out.
 */
class EnvoyerMessageGroupE2eTest {

    private companion object {
        const val ALICE = "+33600000001"
        const val BOB = "+33600000002"
        val FRAGMENTS = listOf("FRAG-1", "FRAG-2")
        const val GROUP_ID = "0123456789abcdef0123456789abcdef"
    }

    private class Reglages(groupMms: Boolean) : AppSettingsSource {
        private val value = AppSettings().let { it.copy(sending = it.sending.copy(groupMms = groupMms)) }
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    /** A recording [E2eGroupSendGate]: the test chooses the decision. */
    private class FakeGroupGate(
        var decision: E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain,
    ) : E2eGroupSendGate {
        val decidedFor = mutableListOf<List<String>>()

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
        ): Outcome<E2eGroupSendGate.EncryptedGroupMessage> =
            Outcome.Success(E2eGroupSendGate.EncryptedGroupMessage(FRAGMENTS, GROUP_ID))

        override suspend fun recordGroupActivated(memberAddresses: List<String>, now: Long) = Unit
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

    private val sms = RecordingSender()
    private val mms = RecordingMmsDispatcher()
    private val miroir = NoopMirror()

    private fun routeur(
        groupMms: Boolean,
        gate: E2eGroupSendGate,
        probeGate: E2eSendGate,
    ): EnvoyerMessageUseCase {
        val reglages = Reglages(groupMms)
        return EnvoyerMessageUseCase(
            sendSms = sendSmsUseCase(
                settings = reglages,
                sender = sms,
                mirror = miroir,
                groupGate = gate,
                e2eGate = probeGate,
            ),
            sendMediaMms = SendMediaMmsUseCase(
                defaultAppManager = alwaysDefaultSmsApp(),
                blockedRepo = NeverBlocked(),
                mirror = miroir,
                sender = mms,
                settings = reglages,
                attachmentStore = PassthroughAttachmentStore(),
            ),
            settings = reglages,
            // Group-send own-number fix — number on file, so the
            // just-in-time guard never blocks these routing tests.
            ownNumberEnsurer = FakeOwnNumberEnsurer(),
            groupGate = gate,
            e2eGate = probeGate,
        )
    }

    private val groupe = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB))

    private fun photo(dir: File) = listOf(
        SendMediaMmsUseCase.AttachmentPayload(
            file = File(dir, "p.jpg").apply { writeBytes(ByteArray(64) { 1 }) },
            mimeType = "image/jpeg",
        ),
    )

    @Test
    fun `encrypted group text routes to the SMS fan-out, group-MMS setting on`() = runBlocking {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)

        val res = routeur(groupMms = true, gate = gate, probeGate = ProbingGate())
            .invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // Both layers consult the gate (the router pre-check and
        // the sender's send plan) — decideGroupSend is a pure
        // state read, so the duplicate call is harmless.
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB), listOf(ALICE, BOB))
        // No group MMS — the same fragment set went out as SMS to
        // each member (the fragment sender's default delegates to
        // the plain send, so the fragments land in sentTexts).
        assertThat(mms.appels).isEqualTo(0)
        assertThat(sms.sentTexts).containsExactly("FRAG-1", "FRAG-2", "FRAG-1", "FRAG-2").inOrder()
        assertThat(miroir.echos).hasSize(1)
        assertThat(miroir.echos.single().encrypted).isTrue()
    }

    @Test
    fun `encrypted group text routes to the SMS fan-out, group-MMS setting off`() = runBlocking {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)

        val res = routeur(groupMms = false, gate = gate, probeGate = ProbingGate())
            .invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(mms.appels).isEqualTo(0)
        assertThat(sms.sentTexts).containsExactly("FRAG-1", "FRAG-2", "FRAG-1", "FRAG-2").inOrder()
    }

    @Test
    fun `plain group text with the setting on still goes out as one group MMS`() = runBlocking {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Plain)
        val probeGate = ProbingGate()

        val res = routeur(groupMms = true, gate = gate, probeGate = probeGate)
            .invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(mms.appels).isEqualTo(1)
        assertThat(mms.destinatairesTentes).containsExactly(ALICE, BOB)
        assertThat(sms.sentTexts).isEmpty()
        // The router probes the members — the group-MMS transport
        // never reaches the send hook, so the exchange starts here.
        // Ends with a void assertion (.inOrder()): JUnit silently
        // skips a @Test whose runBlocking body returns a value.
        assertThat(probeGate.probed).containsExactly(ALICE, BOB).inOrder()
    }

    @Test
    fun `plain group text with the setting off goes out as SMS per recipient`() = runBlocking {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Plain)
        val probeGate = ProbingGate()

        val res = routeur(groupMms = false, gate = gate, probeGate = probeGate)
            .invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(mms.appels).isEqualTo(0)
        assertThat(sms.sentTexts).containsExactly("salut", "salut")
        assertThat(miroir.echos).hasSize(1)
        assertThat(miroir.echos.single().encrypted).isFalse()
        // Both layers probe (the router and the send hook) — the
        // probe manager gates the duplicate (24-hour spacing), so
        // no member is probed twice for one send in production.
        // Ends with a void assertion (.inOrder()): JUnit silently
        // skips a @Test whose runBlocking body returns a value.
        assertThat(probeGate.probed).containsAtLeast(ALICE, BOB).inOrder()
    }

    @Test
    fun `over-cap group text is refused before any send`() = runBlocking {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.RefuseOverCap)

        val res = routeur(groupMms = true, gate = gate, probeGate = ProbingGate())
            .invoke(groupe, "salut")

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        // Refused, not rerouted to group MMS: routing it there
        // would silently send an encrypted group's message in the
        // clear.
        assertThat(mms.appels).isEqualTo(0)
        assertThat(sms.sentTexts).isEmpty()
        assertThat(miroir.lignes).isEmpty()
        assertThat(miroir.echos).isEmpty()
    }

    @Test
    fun `group media never consults the group gate`(@TempDir dir: File) = runBlocking {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)

        val res = routeur(groupMms = true, gate = gate, probeGate = ProbingGate())
            .invoke(groupe, "salut", attachments = photo(dir))

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // Group media stays plain (design §13.7) — the gate is
        // never consulted when there are attachments.
        assertThat(gate.decidedFor).isEmpty()
        assertThat(mms.appels).isEqualTo(1)
        assertThat(sms.sentTexts).isEmpty()
    }
}
