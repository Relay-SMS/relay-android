package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.NotesKeyManager
import com.filestech.sms.data.notes.NoteAttachmentCrypto
import com.filestech.sms.data.notes.NoteAttachmentResolverImpl
import com.filestech.sms.data.notes.NoteRepositoryImpl
import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Covers [NoteAttachmentResolverImpl] against a real in-memory Room database plus the real
 * Keystore-backed notes key (instrumented: the Keystore exists here).
 *
 * The tests pin the three read shapes (in-memory / playback file / stream), the
 * transient-plaintext discipline (playback files are wiped on release, stale ones are
 * purged), and the failure mode (missing or tampered files fail as [Outcome.Failure],
 * never as a crash).
 *
 * ⚠️ Block bodies, not `= runBlocking { … }`: JUnit4 requires `void` test methods.
 */
@RunWith(AndroidJUnit4::class)
class NoteAttachmentResolverTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepositoryImpl
    private lateinit var resolver: NoteAttachmentResolverImpl

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyManager = NotesKeyManager(context, KeystoreManager(), AeadCipher())
        val crypto = NoteAttachmentCrypto(context, keyManager, AeadCipher())
        repository = NoteRepositoryImpl(
            db,
            db.noteDao(),
            db.noteMessageDao(),
            db.noteAttachmentDao(),
            crypto,
            Dispatchers.Unconfined,
        )
        resolver = NoteAttachmentResolverImpl(context, crypto, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "notes").deleteRecursively()
        File(context.cacheDir, "notes_playback").deleteRecursively()
    }

    @Test
    fun decryptToMemory_roundTripsPlaintext() {
        runBlocking {
            val attachment = addVideo("clip.mp4", PLAINTEXT)

            val result = resolver.decryptToMemory(attachment)

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            assertThat((result as Outcome.Success).value).isEqualTo(PLAINTEXT)
        }
    }

    @Test
    fun decryptToMemory_missingFile_returnsNotFound() {
        runBlocking {
            val attachment = addVideo("clip.mp4", PLAINTEXT)
            assertThat(File(attachment.localUri).delete()).isTrue()

            val result = resolver.decryptToMemory(attachment)

            assertThat(result).isInstanceOf(Outcome.Failure::class.java)
            assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.NotFound::class.java)
        }
    }

    @Test
    fun decryptToPlaybackFile_createsPlaintextFile_releaseWipesIt() {
        runBlocking {
            val attachment = addVideo("clip.mp4", PLAINTEXT)

            val result = resolver.decryptToPlaybackFile(attachment)

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            val playback = (result as Outcome.Success).value
            assertThat(playback.exists()).isTrue()
            assertThat(playback.readBytes()).isEqualTo(PLAINTEXT)
            assertThat(playback.parentFile?.name).isEqualTo("notes_playback")

            resolver.releasePlaybackFile(playback)
            assertThat(playback.exists()).isFalse()
        }
    }

    @Test
    fun releasePlaybackFile_ignoresPathsOutsidePlaybackDir() {
        runBlocking {
            val outsider = File(context.cacheDir, "not-a-playback-file.bin")
            outsider.writeBytes("keep me".toByteArray())

            resolver.releasePlaybackFile(outsider)

            assertThat(outsider.exists()).isTrue()
            outsider.delete()
        }
    }

    @Test
    fun decryptToStream_writesPlaintextToStream() {
        runBlocking {
            val attachment = addVideo("clip.mp4", PLAINTEXT)
            val out = ByteArrayOutputStream()

            val result = resolver.decryptToStream(attachment, out)

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            assertThat(out.toByteArray()).isEqualTo(PLAINTEXT)
        }
    }

    @Test
    fun decryptToMemory_tamperedCiphertext_returnsFailure() {
        runBlocking {
            val attachment = addVideo("clip.mp4", PLAINTEXT)
            val file = File(attachment.localUri)
            val tampered = file.readBytes()
            tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x42).toByte()
            file.writeBytes(tampered)

            val result = resolver.decryptToMemory(attachment)

            // GCM authentication fails: no plaintext is ever released.
            assertThat(result).isInstanceOf(Outcome.Failure::class.java)
            assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Crypto::class.java)
        }
    }

    @Test
    fun decryptToPlaybackFile_purgesStaleFiles() {
        runBlocking {
            val dir = File(context.cacheDir, "notes_playback").apply { mkdirs() }
            val stale = File(dir, "note-playback-1-deadbeef")
            stale.writeBytes("old plaintext".toByteArray())
            assertThat(stale.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)).isTrue()
            val attachment = addVideo("clip.mp4", PLAINTEXT)

            val result = resolver.decryptToPlaybackFile(attachment)

            assertThat(result).isInstanceOf(Outcome.Success::class.java)
            assertThat(stale.exists()).isFalse()
            resolver.releasePlaybackFile((result as Outcome.Success).value)
        }
    }

    private suspend fun addVideo(fileName: String, plaintext: ByteArray): Attachment {
        val note = repository.createNote("Media")
        val message = repository.insertMessage(note.id, "video")
        val stored = repository.addAttachment(
            message.id,
            mimeType = "video/mp4",
            fileName = fileName,
            plaintext = plaintext,
            width = 640,
            height = 480,
            durationMs = 5_000L,
        )
        assertThat(stored).isInstanceOf(Outcome.Success::class.java)
        return (stored as Outcome.Success).value
    }

    private companion object {
        val PLAINTEXT = "fake-video-bytes".toByteArray()
    }
}
