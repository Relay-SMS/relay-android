package com.filestech.sms.di

import com.filestech.sms.sync.calls.AndroidCallDialer
import com.filestech.sms.sync.calls.CallDialer
import com.filestech.sms.sync.calls.CallStateObserver
import com.filestech.sms.sync.calls.TelephonyCallStateObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Live calls — the Hilt bindings for the hub phone's call
 * signaling ([CallStateObserver]) and dial execution
 * ([CallDialer]). Same shape as [SyncWakelockModule]: one
 * instance for the app. The observer is a single system
 * listener (one registration, shared by every session), and
 * the dialer is stateless.
 *
 * NOTE: the Hilt graph is CI-only — the local harness has no
 * kapt, so these bindings are verified by CI's Build & verify,
 * not by a local build.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncCallsModule {

    /** Call-state observation comes from the telephony listener. */
    @Provides
    @Singleton
    fun provideCallStateObserver(impl: TelephonyCallStateObserver): CallStateObserver = impl

    /** Dialing goes through the platform ACTION_CALL dialer. */
    @Provides
    @Singleton
    fun provideCallDialer(impl: AndroidCallDialer): CallDialer = impl
}
