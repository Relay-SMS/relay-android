package com.filestech.sms.sync.client

import android.content.Context
import com.filestech.sms.data.local.db.dao.SyncedContactDao
import com.filestech.sms.data.local.db.entity.SYNCED_AVATARS_DIR_NAME
import com.filestech.sms.data.local.db.entity.SyncedContactEntity
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedContact
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Client mode C4 — contacts sync: snapshot application + prune,
 * delta upsert/delete, stale-photo handling, and lazy avatar
 * fetch/store (chunk reassembly + SHA-256/size verification).
 * The DAO is an in-memory fake (the Room SQL itself is standard
 * `@Insert(REPLACE)` / `@Query` — CI-only to exercise against a
 * real database, see the report).
 */
class ClientContactSyncTest {

    private class FakeChannel : ClientSyncChannel {
        override val state: StateFlow<ClientConnectionState> =
            MutableStateFlow(ClientConnectionState.Disconnected)
        override val frames: SharedFlow<ServerFrame> =
            MutableSharedFlow(extraBufferCapacity = 64)
        val sent = mutableListOf<String>()
        var sendResult = true

        override suspend fun send(text: String): Boolean {
            sent += text
            return sendResult
        }
    }

    private class FakeSyncedContactDao : SyncedContactDao {
        val rows = LinkedHashMap<String, SyncedContactEntity>()

        override suspend fun upsert(contact: SyncedContactEntity) {
            rows[contact.number] = contact
        }

        override suspend fun upsertAll(contacts: List<SyncedContactEntity>) {
            contacts.forEach { rows[it.number] = it }
        }

        override suspend fun get(number: String): SyncedContactEntity? = rows[number]

        override suspend fun listAll(): List<SyncedContactEntity> =
            rows.values.sortedBy { it.displayName }

        override suspend fun numbers(): List<String> = rows.keys.toList()

        override suspend fun deleteNumbers(numbers: List<String>) {
            numbers.forEach { rows.remove(it) }
        }

        override suspend fun setAvatarPath(number: String, path: String?) {
            rows[number]?.let { rows[number] = it.copy(avatarLocalPath = path) }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private class Fixture {
        val channel = FakeChannel()
        val dao = FakeSyncedContactDao()
        val filesDir: File = Files.createTempDirectory("c4-avatars").toFile()
        val avatarsDir: File = File(filesDir, SYNCED_AVATARS_DIR_NAME)
        val context = mockk<Context>()

        init {
            every { context.filesDir } returns filesDir
        }

        val contactSync = ClientContactSync(channel, dao, context)

        fun avatarFile(number: String): File = File(avatarsDir, number)

        fun seedAvatarFile(number: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File {
            avatarsDir.mkdirs()
            return avatarFile(number).also { it.writeBytes(bytes) }
        }

        /** The `type` + `address` of the avatar_request frames sent so far. */
        fun avatarRequests(): List<String> = channel.sent.mapNotNull { text ->
            val obj = Json.parseToJsonElement(text).jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "avatar_request") {
                obj["address"]?.jsonPrimitive?.content
            } else {
                null
            }
        }
    }

    // -- Snapshot ------------------------------------------------------

    @Test fun `snapshot batch upserts one row per phone`() = runTest {
        val f = Fixture()
        val complete = f.contactSync.onContacts(
            SyncProtocol.Contacts(
                contacts = listOf(
                    contact("Alice", listOf("+33611111111", "+33622222222")),
                    contact("Bob", listOf("+33633333333")),
                ),
                hasMore = true,
            ),
        )
        assertThat(complete).isFalse()
        assertThat(f.dao.numbers()).containsExactly(
            "+33611111111",
            "+33622222222",
            "+33633333333",
        ).inOrder()
        assertThat(f.dao.get("+33622222222")?.displayName).isEqualTo("Alice")
    }

    @Test fun `final snapshot batch prunes absent numbers`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice"))
        f.dao.upsert(row("+33699999999", "Carol").copy(avatarLocalPath = "+33699999999"))
        f.seedAvatarFile("+33699999999")
        val complete = f.contactSync.onContacts(
            SyncProtocol.Contacts(
                contacts = listOf(contact("Alice", listOf("+33611111111"))),
                hasMore = false,
            ),
        )
        assertThat(complete).isTrue()
        assertThat(f.dao.numbers()).containsExactly("+33611111111").inOrder()
        assertThat(f.avatarFile("+33699999999").exists()).isFalse()
    }

    @Test fun `non-final snapshot batch does not prune`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33699999999", "Carol"))
        val complete = f.contactSync.onContacts(
            SyncProtocol.Contacts(
                contacts = listOf(contact("Alice", listOf("+33611111111"))),
                hasMore = true,
            ),
        )
        assertThat(complete).isFalse()
        assertThat(f.dao.get("+33699999999")).isNotNull()
    }

    @Test fun `a new snapshot resets the seen set`() = runTest {
        val f = Fixture()
        f.contactSync.onContacts(
            SyncProtocol.Contacts(
                contacts = listOf(contact("Alice", listOf("+33611111111"))),
                hasMore = false,
            ),
        )
        f.contactSync.resetSnapshot()
        f.contactSync.onContacts(
            SyncProtocol.Contacts(
                contacts = listOf(contact("Bob", listOf("+33633333333"))),
                hasMore = false,
            ),
        )
        assertThat(f.dao.numbers()).containsExactly("+33633333333").inOrder()
    }

    // -- Deltas ----------------------------------------------------------

    @Test fun `contact_updated upserts and deletes removed phones`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice"))
        f.dao.upsert(row("+33622222222", "Alice"))
        f.contactSync.onContactUpdated(
            SyncProtocol.ContactUpdated(
                contact = contact("Alice Smith", listOf("+33611111111")),
                removedPhones = listOf("+33622222222"),
            ),
        )
        assertThat(f.dao.numbers()).containsExactly("+33611111111").inOrder()
        assertThat(f.dao.get("+33611111111")?.displayName).isEqualTo("Alice Smith")
    }

    @Test fun `contact_updated with a new photo version deletes the stale avatar`() = runTest {
        val f = Fixture()
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        f.seedAvatarFile("+33611111111")
        f.contactSync.onContactUpdated(
            SyncProtocol.ContactUpdated(
                contact = contact(
                    "Alice",
                    listOf("+33611111111"),
                    hasPhoto = true,
                    photoUpdatedAt = 2L,
                ),
            ),
        )
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
        assertThat(f.dao.get("+33611111111")?.avatarLocalPath).isNull()
    }

    @Test fun `contact_updated with the same photo version keeps the avatar`() = runTest {
        val f = Fixture()
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        f.seedAvatarFile("+33611111111")
        f.contactSync.onContactUpdated(
            SyncProtocol.ContactUpdated(
                contact = contact(
                    "Alice",
                    listOf("+33611111111"),
                    hasPhoto = true,
                    photoUpdatedAt = 1L,
                ),
            ),
        )
        assertThat(f.avatarFile("+33611111111").exists()).isTrue()
        assertThat(f.dao.get("+33611111111")?.avatarLocalPath).isEqualTo("+33611111111")
    }

    @Test fun `contact_updated with a photo asks for it directly`() = runTest {
        val f = Fixture()
        f.contactSync.onContactUpdated(
            SyncProtocol.ContactUpdated(
                contact = contact(
                    "Alice",
                    listOf("+33611111111"),
                    hasPhoto = true,
                    photoUpdatedAt = 1L,
                ),
            ),
        )
        assertThat(f.avatarRequests()).containsExactly("+33611111111").inOrder()
    }

    @Test fun `contact_deleted deletes rows and avatar files`() = runTest {
        val f = Fixture()
        f.dao.upsert(
            row("+33611111111", "Alice").copy(avatarLocalPath = "+33611111111"),
        )
        f.dao.upsert(row("+33622222222", "Bob"))
        f.seedAvatarFile("+33611111111")
        f.contactSync.onContactDeleted(
            SyncProtocol.ContactDeleted(phones = listOf("+33611111111")),
        )
        assertThat(f.dao.numbers()).containsExactly("+33622222222").inOrder()
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
    }

    // -- Lazy avatars ------------------------------------------------------

    @Test fun `requestAvatar sends an avatar_request frame`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.avatarRequests()).containsExactly("+33611111111").inOrder()
    }

    @Test fun `requestAvatar is a no-op for an unknown number`() = runTest {
        val f = Fixture()
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.channel.sent).isEmpty()
    }

    @Test fun `requestAvatar is a no-op for a photo-less contact`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = false))
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.channel.sent).isEmpty()
    }

    @Test fun `requestAvatar is a no-op when the photo is already cached`() = runTest {
        val f = Fixture()
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        f.seedAvatarFile("+33611111111")
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.channel.sent).isEmpty()
    }

    @Test fun `requestAvatar clears a stale path and re-downloads`() = runTest {
        val f = Fixture()
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        // The path points at a file that is gone.
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.avatarRequests()).containsExactly("+33611111111").inOrder()
        assertThat(f.dao.get("+33611111111")?.avatarLocalPath).isNull()
    }

    @Test fun `requestAvatar dedupes while a download is in flight`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.avatarRequests()).containsExactly("+33611111111").inOrder()
    }

    @Test fun `single-chunk avatar stores the file and sets the path`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        val bytes = byteArrayOf(10, 20, 30, 40)
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33611111111", offset = 0, bytes, isFinal = true),
        )
        assertThat(f.avatarFile("+33611111111").readBytes()).isEqualTo(bytes)
        assertThat(f.dao.get("+33611111111")?.avatarLocalPath).isEqualTo("+33611111111")
    }

    @Test fun `multi-chunk avatar reassembles in order`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33611111111", offset = 0, byteArrayOf(1, 2), isFinal = false),
        )
        f.contactSync.onAvatarChunk(
            chunk(
                "avatar:+33611111111",
                offset = 2,
                byteArrayOf(3, 4),
                isFinal = true,
                totalBytes = byteArrayOf(1, 2, 3, 4),
            ),
        )
        assertThat(f.avatarFile("+33611111111").readBytes())
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test fun `avatar chunk with a gap fails the download`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33611111111", offset = 5, byteArrayOf(1, 2), isFinal = true),
        )
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
        assertThat(f.dao.get("+33611111111")?.avatarLocalPath).isNull()
        // Refused: a re-request does not go out again.
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.avatarRequests()).containsExactly("+33611111111").inOrder()
    }

    @Test fun `avatar with a bad checksum fails the download`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.onAvatarChunk(
            chunk(
                "avatar:+33611111111",
                offset = 0,
                byteArrayOf(1, 2, 3),
                isFinal = true,
                sha256 = "deadbeef",
            ),
        )
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
        assertThat(f.dao.get("+33611111111")?.avatarLocalPath).isNull()
    }

    @Test fun `avatar error refuses the download until the session resumes`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.onAvatarError(
            SyncProtocol.AttachmentError(
                attachmentId = "avatar:+33611111111",
                code = SyncProtocol.AttachmentError.UNKNOWN_ATTACHMENT,
                message = "no photo",
            ),
        )
        f.contactSync.requestAvatar("+33611111111")
        assertThat(f.avatarRequests()).containsExactly("+33611111111").inOrder()
        // The reconnect is a fresh start for a refused avatar.
        f.contactSync.onSessionResumed()
        assertThat(f.avatarRequests()).containsExactly("+33611111111", "+33611111111").inOrder()
    }

    @Test fun `avatar completion never creates a row`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.dao.deleteNumbers(listOf("+33611111111"))
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33611111111", offset = 0, byteArrayOf(1, 2), isFinal = true),
        )
        assertThat(f.dao.get("+33611111111")).isNull()
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
    }

    @Test fun `chunks for an unknown download are ignored`() = runTest {
        val f = Fixture()
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33611111111", offset = 0, byteArrayOf(1, 2), isFinal = true),
        )
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
    }

    @Test fun `session end re-queues in-flight downloads`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.onSessionEnded()
        f.contactSync.onSessionResumed()
        assertThat(f.avatarRequests()).containsExactly("+33611111111", "+33611111111").inOrder()
    }

    @Test fun `cache wipe deletes avatar files and drops downloads`() = runTest {
        val f = Fixture()
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        f.seedAvatarFile("+33611111111")
        f.dao.upsert(row("+33622222222", "Bob", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33622222222")
        f.contactSync.onCacheWiped()
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
        // A late chunk for the dropped download is ignored.
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33622222222", offset = 0, byteArrayOf(1, 2), isFinal = true),
        )
        assertThat(f.avatarFile("+33622222222").exists()).isFalse()
    }

    @Test fun `hub change drops in-flight downloads`() = runTest {
        val f = Fixture()
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.contactSync.requestAvatar("+33611111111")
        f.contactSync.onHubChanged()
        f.contactSync.onAvatarChunk(
            chunk("avatar:+33611111111", offset = 0, byteArrayOf(1, 2), isFinal = true),
        )
        assertThat(f.avatarFile("+33611111111").exists()).isFalse()
    }

    private companion object {
        fun row(
            number: String,
            displayName: String,
            hasPhoto: Boolean = false,
            photoUpdatedAt: Long? = null,
        ) = SyncedContactEntity(
            number = number,
            displayName = displayName,
            hasPhoto = hasPhoto,
            photoUpdatedAt = photoUpdatedAt,
        )

        fun contact(
            displayName: String,
            phones: List<String>,
            hasPhoto: Boolean = false,
            photoUpdatedAt: Long? = null,
        ) = SyncedContact(
            displayName = displayName,
            phones = phones,
            hasPhoto = hasPhoto,
            photoUpdatedAt = photoUpdatedAt,
        )

        /**
         * One chunk frame. The final chunk's SHA-256 + size cover the
         * WHOLE download ([totalBytes], the chunk's own bytes when
         * the download is a single chunk) — that is what the hub
         * sends, and what the verifier checks.
         */
        fun chunk(
            attachmentId: String,
            offset: Long,
            bytes: ByteArray,
            isFinal: Boolean,
            sha256: String? = null,
            totalBytes: ByteArray = bytes,
        ) = SyncProtocol.AttachmentChunk(
            attachmentId = attachmentId,
            offset = offset,
            dataBase64Url = Base64Url.encode(bytes),
            isFinal = isFinal,
            sha256 = if (isFinal) sha256 ?: totalBytes.toSha256Hex() else null,
            sizeBytes = if (isFinal) totalBytes.size.toLong() else null,
        )

        fun ByteArray.toSha256Hex(): String =
            MessageDigest.getInstance("SHA-256").digest(this)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
