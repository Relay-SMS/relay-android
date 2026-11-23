package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.e2e.E2eMediaGate
import com.filestech.sms.domain.e2e.MediaFileInput
import com.filestech.sms.domain.mms.MmsAttachment
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Round 48 — the MMS use cases' media-encryption wiring: the
 * pre-pass decides per recipient BEFORE any mirror row exists, an
 * Encrypted preparation dispatches the ciphertext parts and
 * mirrors the row encrypted, a Refuse fails the whole send, and
 * groups + blocked recipients never reach the gate.
 *
 * The gate itself is faked (its decision matrix lives in
 * `E2eMediaGateImplTest`, `:app`); what is measured here is what
 * the USE CASES do with each preparation outcome.
 */
class SendMediaMmsE2eTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
    }

    /** A gate whose answer is scripted per address; records every call. */
    private class FakeMediaGate(
        private val results: Map<String, E2eMediaGate.PrepareResult> = emptyMap(),
    ) : E2eMediaGate {
        val calls = mutableListOf<String>()

        override suspend fun prepareMediaSend(
            address: String,
            files: List<MediaFileInput>,
            maxTotalBytes: Long,
            date: Long,
            subId: Int?,
        ): E2eMediaGate.PrepareResult {
            calls += address
            return results[address] ?: E2eMediaGate.PrepareResult.Plain
        }
    }

    private fun fichier(dir: File, nom: String): File =
        File(dir, nom).apply { writeBytes(ByteArray(64) { 1 }) }

    /** A ciphertext part exactly as the real gate builds one. */
    private fun chiffre(dir: File, id: String): MmsAttachment {
        val file = File(dir, "relay-media-$id.bin").apply { writeBytes(ByteArray(93) { 2 }) }
        return MmsAttachment(file, "application/octet-stream", MmsAttachment.Kind.OTHER)
    }

    private fun mediaUseCase(
        mirror: NoopMirror,
        dispatcher: RecordingMmsDispatcher,
        gate: E2eMediaGate,
        blocked: Set<String> = emptySet(),
    ) = SendMediaMmsUseCase(
        defaultAppManager = alwaysDefaultSmsApp(),
        blockedRepo = BlockedList(blocked),
        mirror = mirror,
        sender = dispatcher,
        settings = DefaultSettingsSource(),
        attachmentStore = PassthroughAttachmentStore(),
        mediaGate = gate,
    )

    private fun voiceUseCase(
        mirror: NoopMirror,
        dispatcher: RecordingMmsDispatcher,
        gate: E2eMediaGate,
        blocked: Set<String> = emptySet(),
    ) = SendVoiceMmsUseCase(
        defaultAppManager = alwaysDefaultSmsApp(),
        blockedRepo = BlockedList(blocked),
        mirror = mirror,
        sender = dispatcher,
        settings = DefaultSettingsSource(),
        attachmentStore = PassthroughAttachmentStore(),
        ownNumberEnsurer = FakeOwnNumberEnsurer(),
        mediaGate = gate,
    )

    // ───────────────────────────── Voie MÉDIA ─────────────────────────────

    @Test
    fun `encrypted preparation dispatches the ciphertext parts and mirrors encrypted`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                textBody = "regarde",
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.dernierPiecesJointes).containsExactly(part)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("application/octet-stream")
        assertThat(dispatcher.dernierPiecesJointes!![0].file.name).isEqualTo("relay-media-0123456789abcdef.bin")
        // The mirror row keeps the PLAINTEXT sender copy, flagged encrypted.
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes[0].encrypted).isTrue()
        assertThat(mirror.lignesMms[0].encrypted).isTrue()
    }

    @Test
    fun `the ciphertext temp file is deleted after dispatch`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(part.file.exists()).isFalse()
    }

    @Test
    fun `plain preparation dispatches the originals and mirrors plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.dernierPiecesJointes).hasSize(1)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
        assertThat(dispatcher.dernierPiecesJointes!![0].file.name).isEqualTo("photo.jpg")
        assertThat(mirror.lignes[0].encrypted).isFalse()
    }

    @Test
    fun `a fallback preparation dispatches the originals plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.FallbackToPlain),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
        assertThat(mirror.lignes[0].encrypted).isFalse()
    }

    @Test
    fun `a refusal fails the whole send before anything is mirrored or dispatched`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Refuse("encrypted attachments exceed the MMS size cap")),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(dispatcher.appels).isEqualTo(0)
        assertThat(mirror.lignes).isEmpty()
    }

    @Test
    fun `a group MMS never reaches the gate and goes plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(
                ALICE to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "0123456789abcdef")),
                ),
                BOB to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "fedcba9876543210")),
                ),
            ),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.calls).isEmpty()
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
    }

    @Test
    fun `a blocked recipient never reaches the gate`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val uc = mediaUseCase(mirror, dispatcher, gate, blocked = setOf(BOB))

        runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(gate.calls).containsExactly(ALICE)
        // The blocked recipient's row exists, in failure — and plain.
        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE, BOB)
        assertThat(mirror.lignes.first { it.adresse == BOB }.encrypted).isFalse()
    }

    @Test
    fun `the decision is per recipient in a broadcast`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(mirror.lignes.first { it.adresse == ALICE }.encrypted).isTrue()
        assertThat(mirror.lignes.first { it.adresse == BOB }.encrypted).isFalse()
    }

    // ───────────────────────────── Voie VOCALE ─────────────────────────────

    @Test
    fun `encrypted voice dispatches through sendMediaMms with the ciphertext part`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = voiceUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        // Encrypted voice goes through sendMediaMms (sendVoiceMms
        // forces the AUDIO kind in the SMIL; a ciphertext part is
        // an octet-stream OTHER).
        assertThat(dispatcher.appelsMedia).isEqualTo(1)
        assertThat(dispatcher.appelsVoix).isEqualTo(0)
        assertThat(dispatcher.dernierPiecesJointes).containsExactly(part)
        assertThat(mirror.lignesMms).hasSize(1)
        assertThat(mirror.lignesMms[0].encrypted).isTrue()
        assertThat(part.file.exists()).isFalse()
    }

    @Test
    fun `plain voice dispatches through sendVoiceMms and mirrors plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val uc = voiceUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.appelsVoix).isEqualTo(1)
        assertThat(dispatcher.appelsMedia).isEqualTo(0)
        assertThat(mirror.lignesMms[0].encrypted).isFalse()
    }

    @Test
    fun `a group voice MMS never reaches the gate`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(
                ALICE to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "0123456789abcdef")),
                ),
            ),
        )
        val uc = voiceUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.calls).isEmpty()
        assertThat(dispatcher.appelsVoix).isEqualTo(1)
    }
}
