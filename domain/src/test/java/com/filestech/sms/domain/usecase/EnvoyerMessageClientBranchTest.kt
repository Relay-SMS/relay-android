package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.DeviceMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Client mode C3 — the router's wire branch: in client mode a text
 * send goes through the gateway (never telephony), above the hub
 * router AND above the own-number guard; hub mode is unchanged.
 */
class EnvoyerMessageClientBranchTest {

    private val sms = RecordingSender()
    private val mms = RecordingMmsDispatcher()
    private val miroir = NoopMirror()
    private val seul = listOf(PhoneAddress.of("+33600000001"))
    private val groupe = listOf(PhoneAddress.of("+33600000001"), PhoneAddress.of("+33600000002"))

    private fun routeur(
        mode: DeviceMode,
        groupMms: Boolean = false,
        gateway: FakeClientSendGateway = FakeClientSendGateway(clientMode = mode == DeviceMode.CLIENT),
        ensurer: FakeOwnNumberEnsurer = FakeOwnNumberEnsurer(),
    ): EnvoyerMessageUseCase {
        val reglages = FakeSettings(mode = mode, groupMms = groupMms)
        return EnvoyerMessageUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            sendMediaMms = SendMediaMmsUseCase(
                defaultAppManager = alwaysDefaultSmsApp(),
                blockedRepo = NeverBlocked(),
                mirror = miroir,
                sender = mms,
                settings = reglages,
                attachmentStore = PassthroughAttachmentStore(),
            ),
            settings = reglages,
            ownNumberEnsurer = ensurer,
            clientSend = gateway,
        )
    }

    @Test
    fun `client mode text send goes through the gateway, never telephony`() = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = true)

        val res = routeur(DeviceMode.CLIENT, gateway = gateway)
            .invoke(seul, "salut", conversationId = 7L, replyToMessageId = 3L)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gateway.sendTextCalls).hasSize(1)
        val call = gateway.sendTextCalls.single()
        assertThat(call.localConversationId).isEqualTo(7L)
        assertThat(call.recipients).isEqualTo(seul)
        assertThat(call.body).isEqualTo("salut")
        assertThat(call.replyToLocalMessageId).isEqualTo(3L)
        assertThat(sms.sentTexts).isEmpty()
        assertThat(mms.appels).isEqualTo(0)
    }

    @Test
    fun `client mode group text send uses the same wire branch, guard never consulted`() = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = true)
        // The ensurer would refuse (blank number) — the client branch
        // sits above the guard, so it must never be asked.
        val ensurer = FakeOwnNumberEnsurer(result = false)

        val res = routeur(DeviceMode.CLIENT, groupMms = true, gateway = gateway, ensurer = ensurer)
            .invoke(groupe, "salut le groupe")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gateway.sendTextCalls).hasSize(1)
        assertThat(ensurer.calls).isEqualTo(0)
        assertThat(sms.sentTexts).isEmpty()
        assertThat(mms.appels).isEqualTo(0)
    }

    @Test
    fun `client mode send with attachments routes to gateway sendMedia, no telephony`(
        @TempDir dir: File,
    ) = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = true)
        val photo = listOf(
            SendMediaMmsUseCase.AttachmentPayload(
                file = File(dir, "p.jpg").apply { writeBytes(ByteArray(64) { 1 }) },
                mimeType = "image/jpeg",
            ),
        )

        val res = routeur(DeviceMode.CLIENT, gateway = gateway)
            .invoke(seul, "look", attachments = photo, conversationId = 7L, replyToMessageId = 3L)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gateway.sendMediaCalls).hasSize(1)
        val call = gateway.sendMediaCalls.single()
        assertThat(call.localConversationId).isEqualTo(7L)
        assertThat(call.recipients).isEqualTo(seul)
        assertThat(call.body).isEqualTo("look")
        assertThat(call.attachments).isEqualTo(photo)
        assertThat(call.replyToLocalMessageId).isEqualTo(3L)
        assertThat(gateway.sendTextCalls).isEmpty()
        assertThat(sms.sentTexts).isEmpty()
        assertThat(mms.appels).isEqualTo(0)
    }

    @Test
    fun `client mode media gateway failure propagates (budget refused)`(
        @TempDir dir: File,
    ) = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = true).apply {
            sendMediaResult = Outcome.Failure(AppError.ClientMmsCapReached)
        }
        val photo = listOf(
            SendMediaMmsUseCase.AttachmentPayload(
                file = File(dir, "p.jpg").apply { writeBytes(ByteArray(64) { 1 }) },
                mimeType = "image/jpeg",
            ),
        )

        val res = routeur(DeviceMode.CLIENT, gateway = gateway).invoke(seul, "", attachments = photo)

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientMmsCapReached))
        assertThat(sms.sentTexts).isEmpty()
        assertThat(mms.appels).isEqualTo(0)
    }

    @Test
    fun `client mode passes a given client key through (a retry reuses it)`() = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = true)

        routeur(DeviceMode.CLIENT, gateway = gateway)
            .invoke(seul, "salut", clientMessageId = "key-123")

        assertThat(gateway.sendTextCalls.single().clientMessageId).isEqualTo("key-123")
    }

    @Test
    fun `client mode gateway failure propagates (offline)`() = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = true).apply {
            sendTextResult = Outcome.Failure(AppError.ClientOffline)
        }

        val res = routeur(DeviceMode.CLIENT, gateway = gateway).invoke(seul, "salut")

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        assertThat(sms.sentTexts).isEmpty()
    }

    /** Hub mode unchanged: the gateway is never consulted, the SMS path runs. */
    @Test
    fun `hub mode text send never touches the gateway`() = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = false)

        val res = routeur(DeviceMode.HUB, gateway = gateway).invoke(seul, "salut")

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gateway.sendTextCalls).isEmpty()
        assertThat(sms.sentTexts).hasSize(1)
    }

    /** Hub mode unchanged: the own-number guard still refuses a group MMS with a blank number. */
    @Test
    fun `hub mode group MMS with blank number still fails fast`() = runBlocking {
        val gateway = FakeClientSendGateway(clientMode = false)
        val ensurer = FakeOwnNumberEnsurer(result = false)

        val res = routeur(DeviceMode.HUB, groupMms = true, gateway = gateway, ensurer = ensurer)
            .invoke(groupe, "salut le groupe")

        assertThat(res).isEqualTo(Outcome.Failure(AppError.OwnNumberMissing))
        assertThat(gateway.sendTextCalls).isEmpty()
        assertThat(mms.appels).isEqualTo(0)
    }
}
