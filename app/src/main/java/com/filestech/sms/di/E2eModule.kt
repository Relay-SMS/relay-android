package com.filestech.sms.di

import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.e2e.E2eCrypto
import com.filestech.sms.e2e.E2eDataSmsSender
import com.filestech.sms.e2e.E2eDataSmsSenderImpl
import com.filestech.sms.e2e.E2eSendManager
import com.filestech.sms.e2e.VodozemacCrypto
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for end-to-end encryption (design §4).
 *
 * The [E2eCrypto] provider returns a wrapper around a FRESH Account —
 * always [VodozemacCrypto.createNew], never `fromPickle`, even when a
 * stored pickle exists. The stored Account is loaded by
 * [com.filestech.sms.e2e.E2eAccountManager.ensureAccount] (the single
 * place that unpickles it), which every E2E entry point calls before
 * any session work. Loading in the provider instead would unpickle
 * twice (once here, once in the manager — whose unpickle also closes
 * every live session), and would do blocking DAO + Keystore I/O at
 * provision time. The fresh-instance contract the manager relies on is
 * exactly this: `pickleAccount()` works on a never-unpickled wrapper,
 * so first E2E use persists the fresh identity with no separate
 * "create" call.
 *
 * The provider itself does no I/O and constructs nothing expensive —
 * but [E2eCrypto] still must only be resolved off the main thread
 * (every manager takes it as a `dagger.Lazy`): the first native call
 * loads `libvodozemac.so`, and the Account load that follows does
 * Keystore + database I/O.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class E2eModule {

    @Binds
    @Singleton
    abstract fun bindE2eSendGate(impl: E2eSendManager): E2eSendGate

    @Binds
    @Singleton
    abstract fun bindE2eDataSmsSender(impl: E2eDataSmsSenderImpl): E2eDataSmsSender

    companion object {

        /**
         * The crypto backend: one wrapper around a fresh Account (see
         * the class KDoc for why it is never pre-unpickled here).
         * `@Singleton`: the wrapper owns the Account and the live
         * session cache, and must be shared by every E2E manager.
         */
        @Provides
        @Singleton
        fun provideE2eCrypto(): E2eCrypto = VodozemacCrypto.createNew()
    }
}
