package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Round 48 — decrypt-on-view ([E2eMediaResolver]) against a real
 * [AeadCipher] and a real [E2eMediaKeyStore] over
 * [FakeE2eMediaKeyDao]. Ciphertext lives in temp files (the live
 * MMS path); the `content://` import branch is covered by code
 * review only — it needs a ContentResolver.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose body returns a value.
 */
class E2eMediaResolverTest {

    @TempDir
    lateinit var tempDir: File

    private fun harness(): Harness {
        val context = mockk<Context>()
        every { context.cacheDir } returns tempDir
        val messageDao = mockk<MessageDao>()
        coEvery { messageDao.markMediaDecryptFailed(any()) } just Runs
        val store = E2eMediaKeyStore(FakeE2eMediaKeyDao())
        return Harness(
            store = store,
            resolver = E2eMediaResolver(
                context = context,
                mediaKeys = store,
                cipher = AeadCipher(),
                messageDao = messageDao,
                io = Dispatchers.Unconfined,
            ),
            messageDao = messageDao,
        )
    }

    private class Harness(
        val store: E2eMediaKeyStore,
        val resolver: E2eMediaResolver,
        val messageDao: MessageDao,
    )

    @Test
    fun `decryptToMemory round-trips through the real cipher`() = runTest {
        val h = harness()
        val plaintext = "a photo, encrypted on the wire".toByteArray(Charsets.UTF_8)
        val attachment = h.cipherAttachment(plaintext)

        val outcome = h.resolver.decryptToMemory(attachment)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat((outcome as Outcome.Success).value).isEqualTo(plaintext)
        coVerify(exactly = 0) { h.messageDao.markMediaDecryptFailed(any()) }
    }

    @Test
    fun `a flipped ciphertext byte fails the tag and marks the message failed`() = runTest {
        val h = harness()
        val plaintext = "a photo, encrypted on the wire".toByteArray(Charsets.UTF_8)
        val attachment = h.cipherAttachment(plaintext)
        // Corrupt the stored ciphertext after the key was stored.
        val bytes = File(attachment.localUri).readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        File(attachment.localUri).writeBytes(bytes)

        val outcome = h.resolver.decryptToMemory(attachment)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat((outcome as Outcome.Failure).error).isInstanceOf(AppError.Crypto::class.java)
        coVerify(exactly = 1) { h.messageDao.markMediaDecryptFailed(MESSAGE_ID) }
    }

    @Test
    fun `a sha mismatch fails verification even when the tag passes`() = runTest {
        val h = harness()
        val plaintext = "a photo, encrypted on the wire".toByteArray(Charsets.UTF_8)
        // Same size, different content: the GCM tag passes, the
        // hash check is the one that fires.
        val other = "a phpto, encrypted on the wire".toByteArray(Charsets.UTF_8)
        assertThat(other.size).isEqualTo(plaintext.size)
        val attachment = h.cipherAttachment(plaintext, shaOf = other)

        val outcome = h.resolver.decryptToMemory(attachment)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 1) { h.messageDao.markMediaDecryptFailed(MESSAGE_ID) }
    }

    @Test
    fun `the wrong key fails and marks the message failed`() = runTest {
        val h = harness()
        val plaintext = "a photo, encrypted on the wire".toByteArray(Charsets.UTF_8)
        val attachment = h.cipherAttachment(plaintext, storeKey = ByteArray(32) { 0x5A.toByte() })

        val outcome = h.resolver.decryptToMemory(attachment)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 1) { h.messageDao.markMediaDecryptFailed(MESSAGE_ID) }
    }

    @Test
    fun `no key row fails without marking failed`() = runTest {
        val h = harness()
        val plaintext = "a photo, encrypted on the wire".toByteArray(Charsets.UTF_8)
        val attachment = h.cipherAttachment(plaintext, storeKey = null)

        val outcome = h.resolver.decryptToMemory(attachment)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat((outcome as Outcome.Failure).error).isInstanceOf(AppError.Crypto::class.java)
        // Pending, not failed — the key may still arrive.
        coVerify(exactly = 0) { h.messageDao.markMediaDecryptFailed(any()) }
    }

    @Test
    fun `part before key is pending, then decrypts once the key lands`() = runTest {
        val h = harness()
        val plaintext = "a photo, encrypted on the wire".toByteArray(Charsets.UTF_8)
        val attachment = h.cipherAttachment(plaintext, storeKey = null)

        assertThat(h.resolver.keyFor(attachment)).isNull()

        h.store.store(keyFor(plaintext))
        assertThat(h.resolver.keyFor(attachment)).isNotNull()
        val outcome = h.resolver.decryptToMemory(attachment)
        assertThat((outcome as Outcome.Success).value).isEqualTo(plaintext)
    }

    @Test
    fun `decryptToPlaybackFile writes plaintext and release wipes it`() = runTest {
        val h = harness()
        val plaintext = "audio bytes".toByteArray(Charsets.UTF_8)
        val attachment = h.cipherAttachment(plaintext, mime = "audio/ogg", name = "voice.ogg")

        val outcome = h.resolver.decryptToPlaybackFile(attachment)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        val file = (outcome as Outcome.Success).value
        assertThat(file.exists()).isTrue()
        assertThat(file.readBytes()).isEqualTo(plaintext)
        assertThat(file.parentFile?.name).isEqualTo(E2eMediaResolver.PLAYBACK_DIR)

        h.resolver.releasePlaybackFile(file)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `releasePlaybackFile refuses a file outside the playback dir`() = runTest {
        val h = harness()
        val outside = File(tempDir, "not-a-playback-file.txt").apply { writeText("keep me") }

        h.resolver.releasePlaybackFile(outside)

        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `keyFor returns null for a plain attachment`() = runTest {
        val h = harness()
        val plain = Attachment(
            id = 1L,
            messageId = MESSAGE_ID,
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
            sizeBytes = 10L,
            localUri = File(tempDir, "photo.jpg").absolutePath,
            width = null,
            height = null,
            durationMs = null,
        )

        assertThat(h.resolver.keyFor(plain)).isNull()
    }

    @Test
    fun `withMediaKey substitutes the original metadata and keeps the ciphertext uri`() {
        val attachment = Attachment(
            id = 1L,
            messageId = MESSAGE_ID,
            mimeType = "application/octet-stream",
            fileName = "relay-media-$FILE_ID.bin",
            sizeBytes = 999L,
            localUri = File(tempDir, "cipher.bin").absolutePath,
            width = null,
            height = null,
            durationMs = null,
        )
        val key = keyFor("x".toByteArray(Charsets.UTF_8))

        val effective = attachment.withMediaKey(key)

        assertThat(effective.mimeType).isEqualTo("image/jpeg")
        assertThat(effective.fileName).isEqualTo("photo.jpg")
        assertThat(effective.sizeBytes).isEqualTo(1L)
        assertThat(effective.localUri).isEqualTo(attachment.localUri)
    }

    /**
     * Encrypts [plaintext] under the test key, writes the
     * ciphertext to a temp file, and (unless [storeKey] is null)
     * stores a key row for it. [storeKey] overrides the stored
     * key (a wrong key); [shaOf] overrides the hashed bytes (a
     * metadata mismatch). Returns the ciphertext attachment.
     */
    private suspend fun Harness.cipherAttachment(
        plaintext: ByteArray,
        storeKey: ByteArray? = KEY_BYTES,
        shaOf: ByteArray = plaintext,
        mime: String = "image/jpeg",
        name: String = "photo.jpg",
    ): Attachment {
        val cipher = AeadCipher()
        val ciphertext = when (val encrypted = cipher.encryptRaw(KEY_BYTES, plaintext)) {
            is Outcome.Success -> encrypted.value
            is Outcome.Failure -> error("test setup: encrypt failed: ${encrypted.error}")
        }
        val file = File(tempDir, "relay-media-$FILE_ID.bin").apply { writeBytes(ciphertext) }
        if (storeKey != null) {
            store.store(
                E2eMediaKeyMessage.MediaKey(
                    id = FILE_ID,
                    key = storeKey,
                    mime = mime,
                    sizeBytes = plaintext.size.toLong(),
                    sha256Hex = sha256Hex(shaOf),
                    fileName = name,
                ),
            )
        }
        return Attachment(
            id = 1L,
            messageId = MESSAGE_ID,
            mimeType = "application/octet-stream",
            fileName = "relay-media-$FILE_ID.bin",
            sizeBytes = ciphertext.size.toLong(),
            localUri = file.absolutePath,
            width = null,
            height = null,
            durationMs = null,
        )
    }

    private fun keyFor(plaintext: ByteArray): E2eMediaKeyMessage.MediaKey =
        E2eMediaKeyMessage.MediaKey(
            id = FILE_ID,
            key = KEY_BYTES,
            mime = "image/jpeg",
            sizeBytes = plaintext.size.toLong(),
            sha256Hex = sha256Hex(plaintext),
            fileName = "photo.jpg",
        )

    private companion object {
        const val FILE_ID = "0123456789abcdef"
        const val MESSAGE_ID = 77L
        val KEY_BYTES: ByteArray = ByteArray(32) { it.toByte() }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
