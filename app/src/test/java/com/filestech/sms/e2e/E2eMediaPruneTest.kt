package com.filestech.sms.e2e

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.PendingMediaAttachment
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 48 — the 24-hour media-key expiry ([E2eMediaPrune]).
 * The DAO query itself (`findPendingMediaMessages`) is Room SQL
 * and is covered by the query's shape (see its KDoc); these
 * tests pin the prune's ORDER (keys first, then messages) and
 * its key-presence guard. No re-probe exists anywhere in the
 * prune — by construction it holds no probe dependency.
 */
class E2eMediaPruneTest {

    private class Harness {
        val mediaKeyDao = FakeE2eMediaKeyDao()
        val store = E2eMediaKeyStore(mediaKeyDao)
        val attachmentDao = mockk<AttachmentDao>()
        val messageDao = mockk<MessageDao>()
        val prune = E2eMediaPrune(store, attachmentDao, messageDao)

        init {
            coEvery { attachmentDao.findPendingMediaMessages(any()) } returns emptyList()
            coEvery { attachmentDao.findByFileName(any()) } returns null
            coEvery { messageDao.markMediaDecryptFailed(any()) } just Runs
        }
    }

    @Test
    fun `orphaned keys older than 24h are deleted, fresh keys survive`() = runTest {
        val h = Harness()
        h.store.store(keyFor(OLD_ID), now = NOW - 25 * HOUR_MS)
        h.store.store(keyFor(FRESH_ID), now = NOW - HOUR_MS)

        val result = h.prune.prune(NOW)

        assertThat(result.keysDeleted).isEqualTo(1)
        assertThat(h.mediaKeyDao.keys).containsKey(FRESH_ID)
        assertThat(h.mediaKeyDao.keys).doesNotContainKey(OLD_ID)
    }

    @Test
    fun `a keyless pending message older than 24h is marked failed`() = runTest {
        val h = Harness()
        coEvery { h.attachmentDao.findPendingMediaMessages(any()) } returns listOf(
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$OLD_ID.bin"),
        )

        val result = h.prune.prune(NOW)

        assertThat(result.messagesFailed).isEqualTo(1)
        coVerify(exactly = 1) { h.messageDao.markMediaDecryptFailed(77L) }
    }

    @Test
    fun `a pending message with a surviving key is untouched`() = runTest {
        val h = Harness()
        // The key arrived late (fresh) for an old message: the
        // part waited, the key came, the user can still open it.
        h.store.store(keyFor(FRESH_ID), now = NOW - HOUR_MS)
        coEvery { h.attachmentDao.findPendingMediaMessages(any()) } returns listOf(
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$FRESH_ID.bin"),
        )

        val result = h.prune.prune(NOW)

        assertThat(result.messagesFailed).isEqualTo(0)
        coVerify(exactly = 0) { h.messageDao.markMediaDecryptFailed(any()) }
    }

    @Test
    fun `a key for an existing message survives past 24h and its message is untouched`() = runTest {
        val h = Harness()
        // Orphan-only deletion: the key is old, but its part
        // arrived (the attachment exists), so the key is kept
        // for decrypt-on-view and the message is untouched.
        h.store.store(keyFor(OLD_ID), now = NOW - 25 * HOUR_MS)
        coEvery { h.attachmentDao.findByFileName("relay-media-$OLD_ID.bin") } returns mockk()
        coEvery { h.attachmentDao.findPendingMediaMessages(any()) } returns listOf(
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$OLD_ID.bin"),
        )

        val result = h.prune.prune(NOW)

        assertThat(result.keysDeleted).isEqualTo(0)
        assertThat(h.mediaKeyDao.keys).containsKey(OLD_ID)
        assertThat(result.messagesFailed).isEqualTo(0)
        coVerify(exactly = 0) { h.messageDao.markMediaDecryptFailed(any()) }
    }

    @Test
    fun `a non-relay filename in the candidate list is skipped`() = runTest {
        val h = Harness()
        coEvery { h.attachmentDao.findPendingMediaMessages(any()) } returns listOf(
            PendingMediaAttachment(messageId = 77L, fileName = "photo.jpg"),
        )

        val result = h.prune.prune(NOW)

        assertThat(result.messagesFailed).isEqualTo(0)
        coVerify(exactly = 0) { h.messageDao.markMediaDecryptFailed(any()) }
    }

    @Test
    fun `a message with two keyless parts is failed once`() = runTest {
        val h = Harness()
        coEvery { h.attachmentDao.findPendingMediaMessages(any()) } returns listOf(
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$OLD_ID.bin"),
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$FRESH_ID.bin"),
        )

        val result = h.prune.prune(NOW)

        assertThat(result.messagesFailed).isEqualTo(1)
        coVerify(exactly = 1) { h.messageDao.markMediaDecryptFailed(77L) }
    }

    @Test
    fun `a message with one keyed and one keyless part still fails`() = runTest {
        val h = Harness()
        h.store.store(keyFor(FRESH_ID), now = NOW - HOUR_MS)
        // The keyed row comes first: the message must still fail
        // on the keyless row — the join order must not matter.
        coEvery { h.attachmentDao.findPendingMediaMessages(any()) } returns listOf(
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$FRESH_ID.bin"),
            PendingMediaAttachment(messageId = 77L, fileName = "relay-media-$OLD_ID.bin"),
        )

        val result = h.prune.prune(NOW)

        assertThat(result.messagesFailed).isEqualTo(1)
        coVerify(exactly = 1) { h.messageDao.markMediaDecryptFailed(77L) }
    }

    @Test
    fun `a key exactly at the cutoff survives`() = runTest {
        val h = Harness()
        // `findFileIdsOlderThan` is strictly older-than: a key
        // stamped exactly at now - 24h is not expired yet.
        h.store.store(keyFor(OLD_ID), now = NOW - E2eMediaKeyStore.MEDIA_KEY_TTL_MS)

        val result = h.prune.prune(NOW)

        assertThat(result.keysDeleted).isEqualTo(0)
        assertThat(h.mediaKeyDao.keys).containsKey(OLD_ID)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val OLD_ID = "aaaaaaaaaaaaaaaa"
        const val FRESH_ID = "bbbbbbbbbbbbbbbb"

        fun keyFor(id: String): E2eMediaKeyMessage.MediaKey =
            E2eMediaKeyMessage.MediaKey(
                id = id,
                key = ByteArray(32) { it.toByte() },
                mime = "image/jpeg",
                sizeBytes = 123L,
                sha256Hex = "ab".repeat(32),
                fileName = "photo.jpg",
            )
    }
}
