package com.filestech.sms.data.notes

import android.content.Context
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.NotesKeyManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers [NoteAttachmentCrypto]'s file envelope with a fake notes key (a fixed 32-byte
 * key from a mocked [NotesKeyManager]) and a real [AeadCipher]: round-trip, overhead,
 * wrong-key and tamper failure modes, and the atomic file write.
 */
class NoteAttachmentCryptoTest {

    private val masterKey = ByteArray(32) { it.toByte() }

    private fun cryptoFor(dir: File, key: ByteArray = masterKey): NoteAttachmentCrypto {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        val keyManager = mockk<NotesKeyManager>()
        // answers, not returns: the crypto wipes the key copy after every use, so each
        // call must get a FRESH copy (a `returns` stub would hand back the same array,
        // zeroed by the first call, and every later decryption would fail).
        every { keyManager.getOrCreateMasterKey() } answers { key.copyOf() }
        return NoteAttachmentCrypto(context, keyManager, AeadCipher())
    }

    @Test
    fun `encrypt then decrypt round-trips the plaintext`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)
        val plaintext = "hello notes".toByteArray()

        val ciphertext = (crypto.encrypt(plaintext) as Outcome.Success).value
        val recovered = (crypto.decrypt(ciphertext) as Outcome.Success).value

        assertThat(recovered).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext is plaintext plus 29 bytes and starts with version 1`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)
        val plaintext = ByteArray(100) { 1 }

        val ciphertext = (crypto.encrypt(plaintext) as Outcome.Success).value

        assertThat(ciphertext.size).isEqualTo(100 + NoteAttachmentCrypto.CIPHER_OVERHEAD_BYTES)
        assertThat(NoteAttachmentCrypto.CIPHER_OVERHEAD_BYTES).isEqualTo(29)
        assertThat(ciphertext[0]).isEqualTo(0x01.toByte())
    }

    @Test
    fun `decrypt with the wrong key fails`(@TempDir dir: File) {
        val writer = cryptoFor(dir, ByteArray(32) { 1 })
        val reader = cryptoFor(dir, ByteArray(32) { 2 })
        val ciphertext = (writer.encrypt("secret".toByteArray()) as Outcome.Success).value

        val result = reader.decrypt(ciphertext)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Crypto::class.java)
    }

    @Test
    fun `decrypt of a tampered blob fails`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)
        val ciphertext = (crypto.encrypt("x".toByteArray()) as Outcome.Success).value
        ciphertext[ciphertext.lastIndex] = (ciphertext[ciphertext.lastIndex].toInt() xor 0x42).toByte()

        val result = crypto.decrypt(ciphertext)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `encryptToFile then decryptFile round-trips with no temp file left`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)
        val plaintext = "file bytes".toByteArray()
        val target = crypto.fileForUuid("uuid-1")

        val written = crypto.encryptToFile(plaintext, target)

        assertThat(written).isInstanceOf(Outcome.Success::class.java)
        assertThat(target.exists()).isTrue()
        assertThat(File(target.parentFile, target.name + NoteAttachmentCrypto.TMP_SUFFIX).exists()).isFalse()
        val recovered = (crypto.decryptFile(target) as Outcome.Success).value
        assertThat(recovered).isEqualTo(plaintext)
    }

    @Test
    fun `decryptFile on a missing file returns NotFound`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)

        val result = crypto.decryptFile(File(dir, "nope"))

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.NotFound::class.java)
    }

    @Test
    fun `a lost notes key maps to a Crypto failure, not a throw`(@TempDir dir: File) {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        val keyManager = mockk<NotesKeyManager>()
        every { keyManager.getOrCreateMasterKey() } throws NotesKeyManager.Failure.WrapCorrupted()
        val crypto = NoteAttachmentCrypto(context, keyManager, AeadCipher())

        val result = crypto.encrypt("x".toByteArray())

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Crypto::class.java)
    }

    @Test
    fun `fileForUuid places the file under filesDir notes attachments`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)

        val file = crypto.fileForUuid("uuid-abc")

        assertThat(file.absolutePath).isEqualTo(
            File(File(File(dir, "notes"), "attachments"), "uuid-abc").absolutePath,
        )
    }

    @Test
    fun `deleteFileQuietly removes the file and ignores a missing one`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)
        val target = crypto.fileForUuid("uuid-1")
        crypto.encryptToFile("x".toByteArray(), target)
        assertThat(target.exists()).isTrue()

        crypto.deleteFileQuietly(target.absolutePath)
        assertThat(target.exists()).isFalse()
        crypto.deleteFileQuietly(target.absolutePath)
    }

    @Test
    fun `two encryptions of the same bytes differ thanks to the random IV`(@TempDir dir: File) {
        val crypto = cryptoFor(dir)
        val plaintext = "same".toByteArray()

        val first = (crypto.encrypt(plaintext) as Outcome.Success).value
        val second = (crypto.encrypt(plaintext) as Outcome.Success).value

        assertThat(first).isNotEqualTo(second)
    }
}
