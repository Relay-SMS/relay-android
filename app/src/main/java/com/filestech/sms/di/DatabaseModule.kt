package com.filestech.sms.di

import android.content.Context
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.DatabaseFactory
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.BlockedNumberDao
import com.filestech.sms.data.local.db.dao.ClientConversationStateDao
import com.filestech.sms.data.local.db.dao.ClientSyncStateDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.E2eAccountDao
import com.filestech.sms.data.local.db.dao.E2eFragmentDao
import com.filestech.sms.data.local.db.dao.E2eGroupDistributionDao
import com.filestech.sms.data.local.db.dao.E2eGroupKeyDao
import com.filestech.sms.data.local.db.dao.E2eMediaKeyDao
import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.dao.E2ePeerDao
import com.filestech.sms.data.local.db.dao.E2eSessionDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.NoteAttachmentDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.dao.NoteMessageDao
import com.filestech.sms.data.local.db.dao.QuickReplyDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.dao.SyncedContactDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room database and one DAO per table.
 *
 * TooManyFunctions is suppressed: the module is one provider per DAO
 * by design — every table needs its provider here, so the count grows
 * with the schema (21 at v24); splitting it would scatter the DAO
 * providers across modules for no gain.
 */
@Suppress("TooManyFunctions")
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context, factory: DatabaseFactory): AppDatabase =
        factory.build(context)

    @Provides fun conversationDao(db: AppDatabase): ConversationDao = db.conversationDao()
    @Provides fun messageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun attachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()
    @Provides fun blockedNumberDao(db: AppDatabase): BlockedNumberDao = db.blockedNumberDao()
    @Provides fun scheduledMessageDao(db: AppDatabase): ScheduledMessageDao = db.scheduledMessageDao()
    @Provides fun quickReplyDao(db: AppDatabase): QuickReplyDao = db.quickReplyDao()

    // Client mode (C2) — the sync-state tables.
    @Provides fun clientSyncStateDao(db: AppDatabase): ClientSyncStateDao = db.clientSyncStateDao()

    @Provides fun clientConversationStateDao(db: AppDatabase): ClientConversationStateDao =
        db.clientConversationStateDao()

    @Provides fun syncedContactDao(db: AppDatabase): SyncedContactDao = db.syncedContactDao()

    // Notes phase 1 — the local-only notes tables.
    @Provides fun noteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides fun noteMessageDao(db: AppDatabase): NoteMessageDao = db.noteMessageDao()

    @Provides fun noteAttachmentDao(db: AppDatabase): NoteAttachmentDao = db.noteAttachmentDao()

    // E2E encryption (schema v22) — the Account, peer, session, fragment, notice tables.
    @Provides fun e2eAccountDao(db: AppDatabase): E2eAccountDao = db.e2eAccountDao()

    @Provides fun e2ePeerDao(db: AppDatabase): E2ePeerDao = db.e2ePeerDao()

    @Provides fun e2eSessionDao(db: AppDatabase): E2eSessionDao = db.e2eSessionDao()

    @Provides fun e2eFragmentDao(db: AppDatabase): E2eFragmentDao = db.e2eFragmentDao()

    @Provides fun e2eNoticeDao(db: AppDatabase): E2eNoticeDao = db.e2eNoticeDao()

    // E2E media encryption (schema v23, round 48) — the received media keys.
    @Provides fun e2eMediaKeyDao(db: AppDatabase): E2eMediaKeyDao = db.e2eMediaKeyDao()

    // E2E encrypted groups (schema v24, round 49) — the group sender keys and distributions.
    @Provides fun e2eGroupKeyDao(db: AppDatabase): E2eGroupKeyDao = db.e2eGroupKeyDao()

    @Provides fun e2eGroupDistributionDao(db: AppDatabase): E2eGroupDistributionDao =
        db.e2eGroupDistributionDao()
}
