package com.filestech.sms.system.scheduler

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 36 — the stale-PENDING watchdog against a REAL in-memory
 * Room database, because the flip lives in the SQL: a stale
 * PENDING row flips to FAILED, a fresh PENDING row does not, and
 * neither does a stale SENT row or a stale incoming PENDING row.
 * A fake DAO would only test the fake.
 *
 * CI-ONLY, like `ConversationMirrorSyncTest`: the local harness
 * compiles with kotlinc and no KSP, so the generated
 * `AppDatabase_Impl` does not exist there and Room cannot open
 * the database — this file fails at runtime in the harness (and
 * the harness `robo` run already fails to compile on the
 * pre-existing `TextScalePersistenceTest` breakage). Gradle + KSP
 * generate the implementation, so CI runs it. The delegation half
 * (the 15-minute cutoff) is proven locally by
 * [StalePendingWatchdogTest].
 *
 * JUnit 4 under Robolectric (the vintage engine runs it).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class StalePendingWatchdogDbTest {

    private lateinit var db: AppDatabase
    private lateinit var watchdog: StalePendingWatchdog
    private var conversationId: Long = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        watchdog = StalePendingWatchdog(db.messageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test fun `a stale PENDING flips to FAILED, a fresh PENDING does not`() = runTest {
        conversationId = db.conversationDao().insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = "+33612345678",
                displayName = null,
                lastMessageAt = 0,
                lastMessagePreview = null,
            ),
        )
        val now = System.currentTimeMillis()
        val stalePending = insertMessage(
            date = now - 16 * 60 * 1000,
            status = MessageStatus.PENDING,
            direction = MessageDirection.OUTGOING,
        )
        val freshPending = insertMessage(
            date = now - 60 * 1000,
            status = MessageStatus.PENDING,
            direction = MessageDirection.OUTGOING,
        )
        val staleSent = insertMessage(
            date = now - 16 * 60 * 1000,
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING,
        )
        val staleIncoming = insertMessage(
            date = now - 16 * 60 * 1000,
            status = MessageStatus.PENDING,
            direction = MessageDirection.INCOMING,
        )

        val flipped = watchdog.runOnce()

        assertThat(flipped).isEqualTo(1)
        val staleRow = db.messageDao().findById(stalePending)
        assertThat(staleRow?.status).isEqualTo(MessageStatus.FAILED)
        assertThat(staleRow?.errorCode).isEqualTo(-2)
        assertThat(db.messageDao().findById(freshPending)?.status)
            .isEqualTo(MessageStatus.PENDING)
        assertThat(db.messageDao().findById(staleSent)?.status)
            .isEqualTo(MessageStatus.SENT)
        assertThat(db.messageDao().findById(staleIncoming)?.status)
            .isEqualTo(MessageStatus.PENDING)
        // Idempotent: a second run finds nothing left to flip.
        assertThat(watchdog.runOnce()).isEqualTo(0)
    }

    private suspend fun insertMessage(
        date: Long,
        status: MessageStatus,
        direction: MessageDirection,
    ): Long = db.messageDao().insert(
        MessageEntity(
            conversationId = conversationId,
            telephonyUri = null,
            address = "+33612345678",
            body = "hello",
            type = MessageType.SMS,
            direction = direction,
            date = date,
            dateSent = null,
            read = true,
            status = status,
        ),
    )
}
