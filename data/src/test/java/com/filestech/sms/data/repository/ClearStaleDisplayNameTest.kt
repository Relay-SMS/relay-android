package com.filestech.sms.data.repository

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.security.VaultSessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 44 — the stale-display-name heal: `clearStaleDisplayName`
 * clears the cached name only when a live lookup definitively says
 * "no contact" (hub mode, READ_CONTACTS granted, provider answered
 * empty). Every other shape leaves the name untouched — a denied
 * permission also reads as "no contact", and clearing on that
 * would erase correct names.
 */
class ClearStaleDisplayNameTest {

    /** Minimal gateway: only the mode flag matters here. */
    private class FakeGateway(private val clientMode: Boolean) : ClientActionGateway {
        override suspend fun isClientMode(): Boolean = clientMode

        override suspend fun sendAction(
            action: ClientAction,
            revert: (suspend () -> Unit)?,
        ): Boolean = true

        override suspend fun createConversation(
            addresses: List<String>,
            customName: String?,
        ): Outcome<Long> = Outcome.Success(42L)

        override suspend fun createNote(title: String): Outcome<Long> =
            Outcome.Success(42L)

        override suspend fun deleteNote(localNoteId: Long): Outcome<Unit> =
            Outcome.Success(Unit)

        override suspend fun deleteNoteMessage(
            localNoteId: Long,
            localMessageId: Long,
        ): Outcome<Unit> = Outcome.Success(Unit)
    }

    private class Harness(
        val conversationDao: ConversationDao,
        val contactRepo: ContactRepository,
        val repo: ConversationRepositoryImpl,
    )

    private fun harness(clientMode: Boolean): Harness {
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val contactRepo = mockk<ContactRepository>(relaxed = true)
        val repo = ConversationRepositoryImpl(
            database = mockk<AppDatabase>(relaxed = true),
            conversationDao = conversationDao,
            messageDao = mockk(relaxed = true),
            attachmentDao = mockk(relaxed = true),
            appLock = mockk(relaxed = true),
            vaultSession = VaultSessionState(),
            blockedRepo = mockk(relaxed = true),
            notifier = mockk(relaxed = true),
            contactRepo = contactRepo,
            telephonyReader = mockk(relaxed = true),
            phoneIdentity = mockk(relaxed = true),
            eraser = mockk(relaxed = true),
            io = Dispatchers.Unconfined,
            clientActions = FakeGateway(clientMode = clientMode),
        )
        return Harness(conversationDao, contactRepo, repo)
    }

    @Test
    fun `hub mode, permission granted, lookup misses clears the display name`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.contactRepo.hasContactsPermission() } returns true
        coEvery { h.contactRepo.lookupByPhone(NUMBER) } returns null

        h.repo.clearStaleDisplayName(CONVERSATION_ID, NUMBER)

        coVerify { h.conversationDao.setDisplayName(CONVERSATION_ID, null) }
    }

    @Test
    fun `lookup finds a contact leaves the display name alone`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.contactRepo.hasContactsPermission() } returns true
        coEvery { h.contactRepo.lookupByPhone(NUMBER) } returns Contact(
            id = 1L,
            displayName = "Maria",
            phones = listOf(PhoneAddress.of(NUMBER)),
        )

        h.repo.clearStaleDisplayName(CONVERSATION_ID, NUMBER)

        coVerify(exactly = 0) { h.conversationDao.setDisplayName(CONVERSATION_ID, null) }
    }

    @Test
    fun `permission denied leaves the display name alone`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.contactRepo.hasContactsPermission() } returns false
        coEvery { h.contactRepo.lookupByPhone(NUMBER) } returns null

        h.repo.clearStaleDisplayName(CONVERSATION_ID, NUMBER)

        coVerify(exactly = 0) { h.conversationDao.setDisplayName(CONVERSATION_ID, null) }
    }

    @Test
    fun `client mode leaves the display name alone`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.contactRepo.hasContactsPermission() } returns true
        coEvery { h.contactRepo.lookupByPhone(NUMBER) } returns null

        h.repo.clearStaleDisplayName(CONVERSATION_ID, NUMBER)

        coVerify(exactly = 0) { h.conversationDao.setDisplayName(CONVERSATION_ID, null) }
    }

    @Test
    fun `lookup throwing SecurityException leaves the display name alone`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.contactRepo.hasContactsPermission() } returns true
        coEvery { h.contactRepo.lookupByPhone(NUMBER) } throws SecurityException("revoked")

        h.repo.clearStaleDisplayName(CONVERSATION_ID, NUMBER)

        coVerify(exactly = 0) { h.conversationDao.setDisplayName(CONVERSATION_ID, null) }
    }

    private companion object {
        private const val CONVERSATION_ID = 7L
        private const val NUMBER = "+16108423232"
    }
}
