package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.e2e.E2eMediaGate
import com.filestech.sms.domain.e2e.MediaFileInput
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Media gate tests (round 48, design §3): the decide + encrypt +
 * key-send path — the decision matrix, the ciphertext part format,
 * the key/part id match, the cap refusal, and the never-silent
 * fallback.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eMediaGateImplTest {

    private companion object {
        const val BOB = "+33612345678"
        const val NOW = 1_800_000_000_000L
        const val SUB_ID = 2
        const val CAP = 300L * 1024L
    }

    @TempDir
    lateinit var tempDir: File

    private class Harness(
        private val tempDir: File,
        val peerDao: FakeE2ePeerDao = FakeE2ePeerDao(),
        keySendResult: Outcome<Unit> = Outcome.Success(Unit),
    ) {
        val context = mockk<Context>()
        val sendManager = mockk<E2eSendManager>()
        val probeManager = mockk<E2eProbeManager>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)
        val keyJsons = mutableListOf<String>()

        val gate = E2eMediaGateImpl(
            peers = E2ePeerRepository(peerDao),
            sendManager = sendManager,
            probeManager = probeManager,
            cipher = AeadCipher(),
            failureRecorder = recorder,
            context = context,
        )

        init {
            every { context.cacheDir } returns tempDir
            every { probeManager.probeForSend(any(), any()) } just Runs
            val keyJsonSlot = slot<String>()
            coEvery {
                sendManager.sendMediaKey(any(), capture(keyJsonSlot), any(), any())
            } answers {
                keyJsons += keyJsonSlot.captured
                keySendResult
            }
        }

        fun seedPeer(
            state: E2ePeerState = E2ePeerState.ENCRYPTED,
            mediaCapable: Boolean = true,
        ) {
            peerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = state,
                mediaCapable = mediaCapable,
                updatedAt = NOW,
            )
        }

        fun cipherDir(): File = File(tempDir, "media_encrypted")
    }

    private fun mediaFile(name: String, bytes: ByteArray): File =
        File(tempDir, name).apply { writeBytes(bytes) }

    private fun plaintextBytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `capable plus ENCRYPTED encrypts with an octet-stream relay-media part`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer()
        val bytes = plaintextBytes(1000)
        val file = mediaFile("photo.jpg", bytes)

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        assertThat(result).isInstanceOf(E2eMediaGate.PrepareResult.Encrypted::class.java)
        val attachments = (result as E2eMediaGate.PrepareResult.Encrypted).attachments
        assertThat(attachments).hasSize(1)
        assertThat(attachments[0].mimeType).isEqualTo("application/octet-stream")
        assertThat(attachments[0].kind)
            .isEqualTo(com.filestech.sms.domain.mms.MmsAttachment.Kind.OTHER)
        assertThat(attachments[0].file.name).matches("relay-media-[0-9a-f]{16}\\.bin")
        // The ciphertext is the envelope (29 bytes) over the plaintext.
        assertThat(attachments[0].file.length()).isEqualTo(bytes.size + 29L)
    }

    @Test
    fun `the sent key decrypts the part and the id matches the filename`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer()
        val bytes = plaintextBytes(500)
        val file = mediaFile("photo.jpg", bytes)

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        val attachments = (result as E2eMediaGate.PrepareResult.Encrypted).attachments
        assertThat(harness.keyJsons).hasSize(1)
        val key = E2eMediaKeyMessage.decode(harness.keyJsons[0])!!
        assertThat(key.id).isEqualTo(E2eMediaKeyMessage.fileIdFromPartName(attachments[0].file.name))
        assertThat(key.mime).isEqualTo("image/jpeg")
        assertThat(key.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(key.fileName).isEqualTo("photo.jpg")
        val decrypted = AeadCipher().decryptRaw(key.key, attachments[0].file.readBytes())
        assertThat(decrypted).isInstanceOf(Outcome.Success::class.java)
        assertThat((decrypted as Outcome.Success<ByteArray>).value).isEqualTo(bytes)
    }

    @Test
    fun `a key message goes out per file with distinct ids`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer()
        val files = listOf(
            MediaFileInput(mediaFile("a.jpg", plaintextBytes(100)), "image/jpeg"),
            MediaFileInput(mediaFile("b.jpg", plaintextBytes(200)), "image/jpeg"),
        )

        val result = harness.gate.prepareMediaSend(BOB, files, CAP, NOW, SUB_ID)

        assertThat(result).isInstanceOf(E2eMediaGate.PrepareResult.Encrypted::class.java)
        assertThat(harness.keyJsons).hasSize(2)
        val ids = harness.keyJsons.map { E2eMediaKeyMessage.decode(it)!!.id }
        assertThat(ids).containsNoDuplicates()
        val partIds = (result as E2eMediaGate.PrepareResult.Encrypted).attachments
            .map { E2eMediaKeyMessage.fileIdFromPartName(it.file.name) }
        assertThat(partIds).containsExactlyElementsIn(ids)
    }

    @Test
    fun `not media-capable sends plain and never touches the key channel`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer(mediaCapable = false)
        val file = mediaFile("photo.jpg", plaintextBytes(100))

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        assertThat(result).isEqualTo(E2eMediaGate.PrepareResult.Plain)
        assertThat(harness.keyJsons).isEmpty()
        verify(exactly = 0) { harness.recorder.recordEvent(any(), any()) }
    }

    @Test
    fun `not ENCRYPTED sends plain and probes in the background`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer(state = E2ePeerState.PROBING)
        val file = mediaFile("photo.jpg", plaintextBytes(100))

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        assertThat(result).isEqualTo(E2eMediaGate.PrepareResult.Plain)
        assertThat(harness.keyJsons).isEmpty()
        verify { harness.probeManager.probeForSend(BOB, SUB_ID) }
    }

    @Test
    fun `an unknown peer sends plain and probes in the background`() = runTest {
        val harness = Harness(tempDir)
        // No peer row at all.
        val file = mediaFile("photo.jpg", plaintextBytes(100))

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        assertThat(result).isEqualTo(E2eMediaGate.PrepareResult.Plain)
        verify { harness.probeManager.probeForSend(BOB, SUB_ID) }
    }

    @Test
    fun `a key-send failure falls back to plain and records it`() = runTest {
        val harness = Harness(tempDir, keySendResult = Outcome.Failure(AppError.Telephony("refused")))
        harness.seedPeer()
        val file = mediaFile("photo.jpg", plaintextBytes(100))

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        assertThat(result).isEqualTo(E2eMediaGate.PrepareResult.FallbackToPlain)
        verify { harness.recorder.recordEvent("e2e-media-fallback", any()) }
        // No temp files leak: the staged ciphertext is deleted.
        assertThat(harness.cipherDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `ciphertext over the cap refuses loudly and sends no key`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer()
        // 100 bytes plaintext + 29 envelope = 129 > the 100-byte cap.
        val file = mediaFile("photo.jpg", plaintextBytes(100))

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(file, "image/jpeg")),
            100L,
            NOW,
            SUB_ID,
        )

        assertThat(result).isInstanceOf(E2eMediaGate.PrepareResult.Refuse::class.java)
        assertThat((result as E2eMediaGate.PrepareResult.Refuse).message).contains("size cap")
        assertThat(harness.keyJsons).isEmpty()
        assertThat(harness.cipherDir().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `an unreadable file falls back to plain and records it`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer()
        val missing = File(tempDir, "gone.jpg") // never created

        val result = harness.gate.prepareMediaSend(
            BOB,
            listOf(MediaFileInput(missing, "image/jpeg")),
            CAP,
            NOW,
            SUB_ID,
        )

        assertThat(result).isEqualTo(E2eMediaGate.PrepareResult.FallbackToPlain)
        verify { harness.recorder.recordEvent("e2e-media-fallback", any()) }
    }

    @Test
    fun `no files is plain`() = runTest {
        val harness = Harness(tempDir)
        harness.seedPeer()

        val result = harness.gate.prepareMediaSend(BOB, emptyList(), CAP, NOW, SUB_ID)

        assertThat(result).isEqualTo(E2eMediaGate.PrepareResult.Plain)
        assertThat(harness.keyJsons).isEmpty()
    }
}
