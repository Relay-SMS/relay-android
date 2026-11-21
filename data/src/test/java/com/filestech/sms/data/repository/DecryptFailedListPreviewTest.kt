package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.AppLockManager.LockState
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E list preview — a conversation whose latest visible message is an
 * undecryptable placeholder carries `lastMessageDecryptFailed`, so the
 * row renders "This message couldn't be decrypted." instead of a blank
 * preview (or the attachment fallback). The flag is computed at read
 * time from the messages table, never persisted.
 *
 * The DAO query itself is proven against a real Room database in
 * `E2eDaoDbTest` (CI-only); what this file locks is the REPOSITORY
 * side: the flag lands on the right conversations in both list
 * streams, and flips when the decrypt-failed set changes.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class DecryptFailedListPreviewTest {

    private fun conversation(id: Long, inVault: Boolean = false) = ConversationEntity(
        id = id,
        threadId = id,
        addressesCsv = "+3360000000$id",
        displayName = null,
        lastMessageAt = NOW,
        lastMessagePreview = "",
        unreadCount = 0,
        inVault = inVault,
    )

    private fun repository(
        rows: List<ConversationEntity>,
        decryptFailedIds: Flow<List<Long>>,
        vaultOpen: Boolean = false,
    ): ConversationRepositoryImpl {
        val conversationDao = mockk<ConversationDao>()
        every { conversationDao.observe(any()) } returns flowOf(rows)
        every { conversationDao.observeVault() } returns flowOf(rows)
        val messageDao = mockk<MessageDao>()
        every { messageDao.observeDecryptFailedLatestConversationIds() } returns decryptFailedIds
        val blockedRepo = mockk<BlockedNumberRepository>()
        every { blockedRepo.observe() } returns flowOf(emptyList())
        val phoneIdentity = mockk<PhoneIdentity>()
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot(null)
        val appLock = mockk<AppLockManager>()
        every { appLock.state } returns MutableStateFlow<LockState>(LockState.Unlocked)
        val vaultSession = VaultSessionState()
        if (vaultOpen) vaultSession.markUnlocked()
        return ConversationRepositoryImpl(
            database = mockk(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            attachmentDao = mockk(),
            appLock = appLock,
            vaultSession = vaultSession,
            blockedRepo = blockedRepo,
            notifier = mockk(),
            contactRepo = mockk(),
            telephonyReader = mockk(),
            phoneIdentity = phoneIdentity,
            eraser = mockk(),
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `observeAll flags exactly the conversations with a decrypt-failed latest message`() = runTest {
        val repo = repository(
            rows = listOf(conversation(CONV_ONE), conversation(CONV_TWO)),
            decryptFailedIds = flowOf(listOf(CONV_TWO)),
        )

        val list = repo.observeAll(includeArchived = false).first()

        assertThat(list).hasSize(2)
        assertThat(list.single { it.id == CONV_ONE }.lastMessageDecryptFailed).isFalse()
        assertThat(list.single { it.id == CONV_TWO }.lastMessageDecryptFailed).isTrue()
        // The flag is additive: nothing else about the rows changes.
        assertThat(list.all { !it.blocked }).isTrue()
        assertThat(list.single { it.id == CONV_TWO }.lastMessagePreview).isEqualTo("")
    }

    @Test
    fun `observeAll flags nothing when no latest message failed to decrypt`() = runTest {
        val repo = repository(
            rows = listOf(conversation(CONV_ONE), conversation(CONV_TWO)),
            decryptFailedIds = flowOf(emptyList()),
        )

        val list = repo.observeAll(includeArchived = false).first()

        assertThat(list.all { !it.lastMessageDecryptFailed }).isTrue()
    }

    @Test
    fun `the flag flips when the decrypt-failed set changes`() = runTest {
        val ids = MutableStateFlow(listOf(CONV_TWO))
        val repo = repository(
            rows = listOf(conversation(CONV_ONE), conversation(CONV_TWO)),
            decryptFailedIds = ids,
        )

        val collected = async { repo.observeAll(includeArchived = false).take(2).toList() }
        // The first emission lands synchronously (Unconfined io +
        // flowOf sources); only then does the set change — e.g. a
        // retry decrypts the kept ciphertext.
        runCurrent()
        ids.value = emptyList()
        val emissions = collected.await()

        assertThat(emissions).hasSize(2)
        assertThat(emissions[0].single { it.id == CONV_TWO }.lastMessageDecryptFailed).isTrue()
        assertThat(emissions[1].single { it.id == CONV_TWO }.lastMessageDecryptFailed).isFalse()
    }

    @Test
    fun `observeVault flags the decrypt-failed conversation too`() = runTest {
        val repo = repository(
            rows = listOf(conversation(CONV_ONE, inVault = true), conversation(CONV_TWO, inVault = true)),
            decryptFailedIds = flowOf(listOf(CONV_ONE)),
            vaultOpen = true,
        )

        val list = repo.observeVault().first()

        assertThat(list).hasSize(2)
        assertThat(list.single { it.id == CONV_ONE }.lastMessageDecryptFailed).isTrue()
        assertThat(list.single { it.id == CONV_TWO }.lastMessageDecryptFailed).isFalse()
    }

    private companion object {
        const val CONV_ONE = 1L
        const val CONV_TWO = 2L
        const val NOW = 1_800_000_000_000L
    }
}
