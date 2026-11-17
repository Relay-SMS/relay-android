package com.filestech.sms.data.repository

import android.content.Context
import com.filestech.sms.data.local.db.dao.SyncedContactDao
import com.filestech.sms.data.local.db.entity.SYNCED_AVATARS_DIR_NAME
import com.filestech.sms.data.local.db.entity.SyncedContactEntity
import com.filestech.sms.domain.clientmode.ClientAvatarGateway
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.google.common.truth.Truth.assertThat
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Client mode C4 — the mode-aware contact repository: hub mode
 * delegates to the local provider (and never touches the cache);
 * client mode reads the synced cache (and never touches the
 * provider). Photos are lazy: a contact with a photo but no cached
 * file asks the hub for it while the lookup returns initials.
 */
class ModeAwareContactRepositoryTest {

    private class FakeSyncedContactDao : SyncedContactDao {
        val rows = LinkedHashMap<String, SyncedContactEntity>()
        val gets = mutableListOf<String>()

        override suspend fun upsert(contact: SyncedContactEntity) {
            rows[contact.number] = contact
        }

        override suspend fun upsertAll(contacts: List<SyncedContactEntity>) {
            contacts.forEach { rows[it.number] = it }
        }

        override suspend fun get(number: String): SyncedContactEntity? {
            gets += number
            return rows[number]
        }

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

    private class FakeAvatarGateway : ClientAvatarGateway {
        val requested = mutableListOf<String>()

        override suspend fun requestAvatar(number: String) {
            requested += number
        }
    }

    private class Fixture(mode: DeviceMode) {
        val local = mockk<ContactRepositoryImpl>(relaxed = true)
        val dao = FakeSyncedContactDao()
        val settings = mockk<AppSettingsSource>(relaxed = true)
        val gateway = FakeAvatarGateway()
        val filesDir: File = Files.createTempDirectory("c4-modeaware").toFile()
        val avatarsDir: File = File(filesDir, SYNCED_AVATARS_DIR_NAME)
        val context = mockk<Context>()

        init {
            coEvery { settings.hydratedOrNull() } returns AppSettings(mode = mode)
            every { context.filesDir } returns filesDir
        }

        val repo = ModeAwareContactRepository(
            local,
            dao,
            settings,
            context,
            Dispatchers.Unconfined,
            gateway,
        )

        fun seedAvatarFile(number: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File {
            avatarsDir.mkdirs()
            return File(avatarsDir, number).also { it.writeBytes(bytes) }
        }
    }

    // -- Hub mode ------------------------------------------------------

    @Test fun `hub mode lookup delegates to the local provider`() = runTest {
        val f = Fixture(DeviceMode.HUB)
        val contact = Contact(
            id = 5L,
            displayName = "Alice",
            phones = listOf(PhoneAddress.of("+33611111111")),
        )
        coEvery { f.local.lookupByPhone("+33611111111") } returns contact
        assertThat(f.repo.lookupByPhone("+33611111111")).isEqualTo(contact)
        assertThat(f.dao.gets).isEmpty()
    }

    @Test fun `hub mode listAll delegates to the local provider`() = runTest {
        val f = Fixture(DeviceMode.HUB)
        val contacts = listOf(
            Contact(id = 5L, displayName = "Alice", phones = listOf(PhoneAddress.of("+33611111111"))),
        )
        coEvery { f.local.listAll() } returns contacts
        assertThat(f.repo.listAll()).isEqualTo(contacts)
    }

    @Test fun `hub mode setContactPhoto delegates to the local provider`() = runTest {
        val f = Fixture(DeviceMode.HUB)
        coEvery { f.local.setContactPhoto(any(), any()) } returns ContactPhotoWriteResult.Ok
        assertThat(f.repo.setContactPhoto("+33611111111", byteArrayOf(1)))
            .isEqualTo(ContactPhotoWriteResult.Ok)
    }

    // -- Client mode ------------------------------------------------------

    @Test fun `client mode lookup reads the cache and never touches the provider`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.dao.upsert(row("+33611111111", "Alice"))
        val contact = f.repo.lookupByPhone("+336 11 11 11 11")
        assertThat(contact?.displayName).isEqualTo("Alice")
        assertThat(contact?.phones?.map { it.raw }).containsExactly("+33611111111").inOrder()
        assertThat(contact?.photoUri).isNull()
        verify { f.local wasNot Called }
    }

    @Test fun `client mode lookup miss returns null`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        assertThat(f.repo.lookupByPhone("+33611111111")).isNull()
    }

    @Test fun `client mode lookup returns the cached photo as a file URI`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        val file = f.seedAvatarFile("+33611111111")
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 7L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        val contact = f.repo.lookupByPhone("+33611111111")
        assertThat(contact?.photoUri).isEqualTo("file://${file.absolutePath}")
        assertThat(contact?.photoUpdatedAt).isEqualTo(7L)
        assertThat(f.gateway.requested).isEmpty()
    }

    @Test fun `client mode lookup with a photo but no file asks the hub for it`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 7L))
        val contact = f.repo.lookupByPhone("+33611111111")
        assertThat(contact?.photoUri).isNull()
        assertThat(f.gateway.requested).containsExactly("+33611111111").inOrder()
    }

    @Test fun `client mode listAll returns one contact per row`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.dao.upsert(row("+33611111111", "Alice"))
        f.dao.upsert(row("+33622222222", "Alice"))
        f.dao.upsert(row("+33633333333", "Bob"))
        val contacts = f.repo.listAll()
        assertThat(contacts.map { it.firstPhone?.raw }).containsExactly(
            "+33611111111",
            "+33622222222",
            "+33633333333",
        ).inOrder()
        assertThat(contacts.map { it.displayName }).containsExactly(
            "Alice",
            "Alice",
            "Bob",
        ).inOrder()
        verify { f.local wasNot Called }
    }

    @Test fun `client mode listAll asks for missing photos`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        f.dao.upsert(row("+33633333333", "Bob", hasPhoto = false))
        f.repo.listAll()
        assertThat(f.gateway.requested).containsExactly("+33611111111").inOrder()
    }

    @Test fun `client mode setContactPhoto fails without touching the provider`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        assertThat(f.repo.setContactPhoto("+33611111111", byteArrayOf(1)))
            .isEqualTo(ContactPhotoWriteResult.Failed)
        verify { f.local wasNot Called }
    }

    @Test fun `client mode openPhotoStream serves the cached file`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.seedAvatarFile("+33611111111", byteArrayOf(9, 8, 7))
        f.dao.upsert(
            row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L)
                .copy(avatarLocalPath = "+33611111111"),
        )
        val bytes = f.repo.openPhotoStream("+33611111111")?.use { it.readBytes() }
        assertThat(bytes).isEqualTo(byteArrayOf(9, 8, 7))
    }

    @Test fun `client mode openPhotoStream is null without a cached file`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 1L))
        assertThat(f.repo.openPhotoStream("+33611111111")).isNull()
    }

    @Test fun `client mode listAllDetailed carries the photo version`() = runTest {
        val f = Fixture(DeviceMode.CLIENT)
        f.dao.upsert(row("+33611111111", "Alice", hasPhoto = true, photoUpdatedAt = 7L))
        val contacts = f.repo.listAllDetailed()
        assertThat(contacts.single().photoUpdatedAt).isEqualTo(7L)
        coVerify(exactly = 0) { f.local.listAllDetailed() }
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
    }
}
