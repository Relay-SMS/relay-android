package com.filestech.sms.data.local.db

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.KeystoreManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.spec.SecretKeySpec

/**
 * Covers [NotesKeyManager]'s wrap/unwrap round-trip with a fake Keystore (a fixed
 * software AES key standing in for the Keystore alias) and a real [AeadCipher].
 *
 * The Keystore itself is not exercised here — the instrumented
 * `NoteRepositoryTest`/`NoteAttachmentResolverTest` run against the real one.
 */
class NotesKeyManagerTest {

    private val wrappingKey = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "AES")

    private fun managerFor(dir: File, keystore: KeystoreManager = fakeKeystore()): NotesKeyManager {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        return NotesKeyManager(context, keystore, AeadCipher())
    }

    private fun fakeKeystore(): KeystoreManager {
        val keystore = mockk<KeystoreManager>()
        every {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_NOTES_AEAD, any(), any())
        } returns wrappingKey
        return keystore
    }

    private fun keyFile(dir: File) = File(File(dir, "notes"), "master.key")

    @Test
    fun `first call generates a 32-byte key and persists a wrapped blob`(@TempDir dir: File) {
        val manager = managerFor(dir)

        val key = manager.getOrCreateMasterKey()

        assertThat(key).hasLength(32)
        // Wrapped blob: 1 version + 12 nonce + 32 key bytes + 16 tag.
        assertThat(keyFile(dir).length()).isEqualTo(61L)
    }

    @Test
    fun `second manager instance unwraps the same key`(@TempDir dir: File) {
        val first = managerFor(dir).getOrCreateMasterKey()

        val second = managerFor(dir).getOrCreateMasterKey()

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `hasWrappedKey is false before and true after the first call`(@TempDir dir: File) {
        val manager = managerFor(dir)

        assertThat(manager.hasWrappedKey()).isFalse()
        manager.getOrCreateMasterKey()
        assertThat(manager.hasWrappedKey()).isTrue()
    }

    @Test
    fun `corrupted wrapped blob throws WrapCorrupted and keeps the file`(@TempDir dir: File) {
        val manager = managerFor(dir)
        manager.getOrCreateMasterKey()
        val blob = keyFile(dir).readBytes()
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 0x42).toByte()
        keyFile(dir).writeBytes(blob)

        // A fresh manager (empty cache) must re-read the corrupted blob.
        val fresh = managerFor(dir)
        assertThrows<NotesKeyManager.Failure.WrapCorrupted> { fresh.getOrCreateMasterKey() }
        // Audit F18: no silent wipe — the file stays for a recovery flow.
        assertThat(keyFile(dir).exists()).isTrue()
    }

    @Test
    fun `invalidated keystore alias throws KeystoreInvalidated`(@TempDir dir: File) {
        val keystore = mockk<KeystoreManager>()
        every {
            keystore.getOrCreateKey(KeystoreManager.ALIAS_NOTES_AEAD, any(), any())
        } throws mockk<KeyPermanentlyInvalidatedException>()
        val manager = managerFor(dir, keystore)

        assertThrows<NotesKeyManager.Failure.KeystoreInvalidated> { manager.getOrCreateMasterKey() }
    }

    @Test
    fun `destroyKeyFile removes the file and the cache, so the next key is new`(@TempDir dir: File) {
        val manager = managerFor(dir)
        val first = manager.getOrCreateMasterKey()

        manager.destroyKeyFile()

        assertThat(keyFile(dir).exists()).isFalse()
        assertThat(manager.hasWrappedKey()).isFalse()
        val second = manager.getOrCreateMasterKey()
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `returned keys are copies wiping one does not wipe the cache`(@TempDir dir: File) {
        val manager = managerFor(dir)
        val first = manager.getOrCreateMasterKey()
        val snapshot = first.copyOf()
        first.fill(0)

        val second = manager.getOrCreateMasterKey()

        assertThat(second).isEqualTo(snapshot)
    }
}
