package com.filestech.sms.di

import android.content.Context
import com.filestech.sms.crashlog.CrashReportStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Local-only error log — the Hilt binding for the crash-report
 * store. The store's constructor takes the reports directory,
 * which Hilt cannot invent, so it needs a provider. The handler
 * itself ([com.filestech.sms.crashlog.CrashLogHandler]) builds its
 * own store in `MainApplication.onCreate` — it runs before Hilt
 * is ready to provide anything — pointing at the same directory,
 * so both sides see the same reports.
 */
@Module
@InstallIn(SingletonComponent::class)
object CrashLogModule {

    @Provides
    @Singleton
    fun crashReportStore(@ApplicationContext context: Context): CrashReportStore =
        CrashReportStore(File(context.filesDir, CrashReportStore.DIR_NAME))
}
