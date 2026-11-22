package com.filestech.sms.di

import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.e2e.E2eCrypto
import com.filestech.sms.e2e.E2eDataSmsSender
import com.filestech.sms.e2e.E2eDataSmsSenderImpl
import com.filestech.sms.e2e.E2eFailureRecorder
import com.filestech.sms.e2e.E2eSendManager
import com.filestech.sms.e2e.UnavailableE2eCrypto
import com.filestech.sms.e2e.VodozemacCrypto
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
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
         *
         * A native-load failure must never escape provisioning: the
         * first `Account()` loads `libvodozemac.so`, and a load
         * failure is an [UnsatisfiedLinkError] — an `Error`, not an
         * `Exception` — which sailed through every `runCatching` on
         * the E2E paths and crashed the app when the Settings
         * Encryption screen resolved this provider on the main thread
         * (round 37: R8 had stripped the uncalled JNI bindings, so
         * `JNI_OnLoad` returned `JNI_ERR`). Catch `Throwable` and
         * degrade to [UnavailableE2eCrypto]: every crypto operation
         * then throws `E2eCryptoException`, which every E2E entry
         * point already handles, so encryption is simply unavailable
         * instead of a crash.
         *
         * The failure is also recorded in the local error log
         * ([E2eFailureRecorder]): a silent degradation left the
         * round-38 device test with a missing fingerprint and no
         * trace of why, so the next failure must be visible in
         * Settings → Advanced. The recorder dedupes against the
         * settings screen's record of the same cause.
         */
        @Provides
        @Singleton
        fun provideE2eCrypto(recorder: E2eFailureRecorder): E2eCrypto {
            return try {
                VodozemacCrypto.createNew()
            } catch (t: Throwable) {
                Timber.w(t, "E2E: native crypto library failed to load; encryption unavailable")
                recorder.record("e2e-native-load", t)
                UnavailableE2eCrypto(t)
            }
        }
    }
}
