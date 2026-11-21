package com.filestech.sms.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.E2eAccountEntity
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.local.db.entity.E2eSessionEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * E2E (schema v22) — the five E2E DAOs plus the `messages` flag columns, against a
 * REAL in-memory Room database. The SQL is the thing under test here: the unique
 * (address, session_id) index, the composite fragment key dedupe, the fallback
 * prune, the probe bookkeeping columns, and the message flag defaults.
 *
 * CI-ONLY, like `StalePendingWatchdogDbTest`: the local harness compiles with
 * kotlinc and no KSP, so the generated `AppDatabase_Impl` does not exist there and
 * Room cannot open the database — this file fails at runtime in the harness (and
 * the harness `robo` run already fails to compile on the pre-existing
 * `TextScalePersistenceTest` breakage). Gradle + KSP generate the implementation,
 * so CI runs it. The store logic above these DAOs is proven locally by the
 * MockK-based `E2e*Test` files in this package.
 *
 * JUnit 4 under Robolectric (the vintage engine runs it).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class E2eDaoDbTest {

    private lateinit var db: AppDatabase

    private val address = "+33612345678"
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test fun `account upsert replaces the single row and delete clears it`() = runTest {
        val dao = db.e2eAccountDao()
        assertThat(dao.get()).isNull()

        dao.upsert(E2eAccountEntity(pickle = "pickle-1"))
        assertThat(dao.get()?.pickle).isEqualTo("pickle-1")

        dao.upsert(E2eAccountEntity(pickle = "pickle-2"))
        assertThat(dao.get()?.pickle).isEqualTo("pickle-2")

        dao.delete()
        assertThat(dao.get()).isNull()
    }

    @Test fun `peer insert ignores duplicates and targeted updates keep the other columns`() = runTest {
        val dao = db.e2ePeerDao()
        val peer = E2ePeerEntity(address = address, updatedAt = now)
        assertThat(dao.insert(peer)).isNotEqualTo(-1L)
        assertThat(dao.insert(peer)).isEqualTo(-1L)

        dao.updateKeys(address, "Y3VydmU=", "ZWQ=", now + 1)
        dao.setVerified(address, true, now + 2)
        dao.updateState(address, E2ePeerState.ENCRYPTED, now + 3)
        dao.recordProbe(address, 1, now, now, now + 4)
        dao.recordBundleReply(address, now + 5, now + 5)

        val found = dao.findByAddress(address)
        assertThat(found?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(found?.theirCurveKey).isEqualTo("Y3VydmU=")
        assertThat(found?.theirEdKey).isEqualTo("ZWQ=")
        assertThat(found?.verified).isTrue()
        assertThat(found?.probeAttempts).isEqualTo(1)
        assertThat(found?.windowStartedAt).isEqualTo(now)
        assertThat(found?.lastProbeAt).isEqualTo(now)
        assertThat(found?.lastBundleReplyAt).isEqualTo(now + 5)
        assertThat(dao.verifiedPeers().map { it.address }).containsExactly(address)
    }

    @Test fun `sessions order current first then newest used, and prune drops old fallbacks`() = runTest {
        val dao = db.e2eSessionDao()
        dao.insert(session("old", lastUsedAt = now - 3000))
        dao.insert(session("new", lastUsedAt = now - 1000))
        dao.insert(session("current", isCurrent = true, lastUsedAt = now - 2000))

        assertThat(dao.sessionsForPeer(address).map { it.sessionId })
            .containsExactly("current", "new", "old").inOrder()
        assertThat(dao.currentSession(address)?.sessionId).isEqualTo("current")
        assertThat(dao.countForPeer(address)).isEqualTo(3)

        dao.insert(session("extra-1", lastUsedAt = now - 500))
        dao.insert(session("extra-2", lastUsedAt = now - 400))
        assertThat(dao.pruneFallbacks(address, 2)).isEqualTo(2)
        assertThat(dao.sessionsForPeer(address).map { it.sessionId })
            .containsExactly("current", "extra-1", "extra-2")
    }

    @Test fun `the unique address plus session id index rejects a duplicate session`() = runTest {
        val dao = db.e2eSessionDao()
        dao.insert(session("dup"))

        val failure = runCatching { dao.insert(session("dup")) }

        assertThat(failure.isFailure).isTrue()
        assertThat(failure.exceptionOrNull()?.message.orEmpty()).contains("UNIQUE")
    }

    @Test fun `fragments dedupe on the composite key and prune by age`() = runTest {
        val dao = db.e2eFragmentDao()
        val fragment = E2eFragmentEntity(
            sender = address,
            msgId = "ab12cd",
            idx = 0,
            total = 2,
            chunk = "QUJD",
            receivedAt = now,
        )
        assertThat(dao.insert(fragment)).isNotEqualTo(-1L)
        assertThat(dao.insert(fragment)).isEqualTo(-1L)
        dao.insert(fragment.copy(idx = 1, chunk = "REVG"))

        assertThat(dao.fragmentsFor(address, "ab12cd").map { it.idx })
            .containsExactly(0, 1).inOrder()
        assertThat(dao.countFor(address, "ab12cd")).isEqualTo(2)

        // A stale fragment from another set prunes; this set survives.
        dao.insert(
            E2eFragmentEntity(
                sender = address,
                msgId = "ff00ff",
                idx = 0,
                total = 1,
                chunk = "eA==",
                receivedAt = now - 25 * 60 * 60 * 1000L,
            ),
        )
        assertThat(dao.deleteOlderThan(now - E2eFragmentStore.FRAGMENT_TTL_MS)).isEqualTo(1)
        assertThat(dao.countFor(address, "ab12cd")).isEqualTo(2)

        assertThat(dao.deleteSet(address, "ab12cd")).isEqualTo(2)
        assertThat(dao.fragmentsFor(address, "ab12cd")).isEmpty()
    }

    @Test fun `notices list oldest first, count by kind, and delete per conversation`() = runTest {
        val dao = db.e2eNoticeDao()
        dao.insert(E2eNoticeEntity(conversationId = 7L, kind = E2eNoticeKind.ACTIVATED, date = now))
        dao.insert(E2eNoticeEntity(conversationId = 7L, kind = E2eNoticeKind.KEY_CHANGED, date = now + 1000))
        dao.insert(E2eNoticeEntity(conversationId = 9L, kind = E2eNoticeKind.ACTIVATED, date = now))

        assertThat(dao.noticesForConversation(7L).map { it.kind })
            .containsExactly(E2eNoticeKind.ACTIVATED, E2eNoticeKind.KEY_CHANGED).inOrder()
        assertThat(dao.countByKind(7L, E2eNoticeKind.ACTIVATED)).isEqualTo(1)
        assertThat(dao.countByKind(7L, E2eNoticeKind.KEY_CHANGED_VERIFIED)).isEqualTo(0)

        assertThat(dao.deleteForConversation(7L)).isEqualTo(2)
        assertThat(dao.noticesForConversation(7L)).isEmpty()
        assertThat(dao.noticesForConversation(9L)).hasSize(1)
    }

    @Test fun `message flag columns default to plain and flip through the DAO`() = runTest {
        val conversationId = db.conversationDao().insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = address,
                displayName = null,
                lastMessageAt = 0,
                lastMessagePreview = null,
            ),
        )
        val messageDao = db.messageDao()
        val id = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                telephonyUri = null,
                address = address,
                body = "hello",
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = now,
                dateSent = null,
                status = MessageStatus.RECEIVED,
            ),
        )
        val stored = messageDao.findById(id)
        assertThat(stored?.encrypted).isFalse()
        assertThat(stored?.decryptFailed).isFalse()
        assertThat(stored?.ciphertext).isNull()

        messageDao.markEncrypted(id)
        assertThat(messageDao.findById(id)?.encrypted).isTrue()

        messageDao.markDecryptFailed(id, "UkxZMQ==")
        val failed = messageDao.findById(id)
        assertThat(failed?.decryptFailed).isTrue()
        assertThat(failed?.ciphertext).isEqualTo("UkxZMQ==")
        assertThat(messageDao.findDecryptFailed(address).map { it.id }).containsExactly(id)

        messageDao.markDecrypted(id)
        val decrypted = messageDao.findById(id)
        assertThat(decrypted?.decryptFailed).isFalse()
        assertThat(decrypted?.ciphertext).isNull()
        assertThat(decrypted?.encrypted).isTrue()
        assertThat(messageDao.findDecryptFailed(address)).isEmpty()
    }

    @Test fun `observeDecryptFailedLatestConversationIds tracks the latest visible message`() = runTest {
        val conversationDao = db.conversationDao()
        val messageDao = db.messageDao()
        suspend fun conversation(csv: String) = conversationDao.insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = csv,
                displayName = null,
                lastMessageAt = 0,
                lastMessagePreview = null,
            ),
        )
        suspend fun message(conversationId: Long, date: Long, decryptFailed: Boolean = false, hidden: Boolean = false) =
            messageDao.insert(
                MessageEntity(
                    conversationId = conversationId,
                    telephonyUri = null,
                    address = address,
                    body = if (decryptFailed) "" else "hello",
                    type = MessageType.SMS,
                    direction = MessageDirection.INCOMING,
                    date = date,
                    dateSent = null,
                    status = MessageStatus.RECEIVED,
                    decryptFailed = decryptFailed,
                    hidden = hidden,
                ),
            )

        // Latest visible message failed to decrypt → listed.
        val failedLatest = conversation("+33600000001")
        message(failedLatest, now)
        val failedId = message(failedLatest, now + 1, decryptFailed = true)
        // A decrypt failure superseded by a newer plain message → not listed.
        val superseded = conversation("+33600000002")
        message(superseded, now, decryptFailed = true)
        message(superseded, now + 1)
        // A decrypt failure that is still the latest visible message, with a
        // NEWER hidden reaction sentinel above it → listed (sentinels never
        // touch the conversation, so they can never be the preview's message).
        val sentinelAbove = conversation("+33600000003")
        message(sentinelAbove, now, decryptFailed = true)
        message(sentinelAbove, now + 1, hidden = true)
        // No failure at all → not listed.
        val plain = conversation("+33600000004")
        message(plain, now)

        val ids = messageDao.observeDecryptFailedLatestConversationIds().first()
        assertThat(ids).containsExactly(failedLatest, sentinelAbove).inOrder()

        // Clearing the failure (a retry decrypted the kept ciphertext)
        // drops the conversation from the set.
        messageDao.markDecrypted(failedId)
        val afterClear = messageDao.observeDecryptFailedLatestConversationIds().first()
        assertThat(afterClear).containsExactly(sentinelAbove).inOrder()
    }

    private fun session(
        sessionId: String,
        isCurrent: Boolean = false,
        lastUsedAt: Long = now,
    ) = E2eSessionEntity(
        address = address,
        sessionId = sessionId,
        pickle = "session:$sessionId:false",
        isCurrent = isCurrent,
        createdAt = now,
        lastUsedAt = lastUsedAt,
    )
}
