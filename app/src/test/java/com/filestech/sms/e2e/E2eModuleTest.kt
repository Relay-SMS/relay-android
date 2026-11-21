package com.filestech.sms.e2e

import com.filestech.sms.di.E2eModule
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The E2E provider's degraded path (round 38): a native-load failure
 * must never escape Hilt provisioning. Plain JVM has no
 * `libvodozemac.so`, so `createNew()` throws `UnsatisfiedLinkError`
 * here deterministically — the same failure class the round-37 release
 * build hit on device (R8 had stripped the uncalled JNI bindings, so
 * `JNI_OnLoad` returned `JNI_ERR` and the Settings Encryption screen
 * crashed on open). The provider must catch it and hand out the
 * unavailable backend instead.
 */
class E2eModuleTest {

    @Test
    fun `provideE2eCrypto degrades to the unavailable backend when the native library cannot load`() {
        val crypto = E2eModule.provideE2eCrypto()

        assertThat(crypto).isInstanceOf(UnavailableE2eCrypto::class.java)
    }
}
