package com.filestech.sms.e2e

import com.filestech.sms.crashlog.CrashLogSettings
import com.filestech.sms.crashlog.CrashReportStore
import com.filestech.sms.di.E2eModule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The E2E provider's degraded path (round 38): a native-load failure
 * must never escape Hilt provisioning. Plain JVM has no
 * `libvodozemac.so`, so `createNew()` throws `UnsatisfiedLinkError`
 * here deterministically — the same failure class the round-37 release
 * build hit on device (R8 had stripped the uncalled JNI bindings, so
 * `JNI_OnLoad` returned `JNI_ERR` and the Settings Encryption screen
 * crashed on open). The provider must catch it and hand out the
 * unavailable backend instead — and record the failure in the local
 * error log, so a silent degradation leaves a trace.
 */
class E2eModuleTest {

    private lateinit var tempDir: File
    private lateinit var store: CrashReportStore
    private lateinit var settings: CrashLogSettings

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("e2e-module-test").toFile()
        store = CrashReportStore(File(tempDir, "crash-reports"))
        settings = mockk { every { enabled } returns true }
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun recorder() = E2eFailureRecorder(store, settings)

    @Test
    fun `provideE2eCrypto degrades to the unavailable backend when the native library cannot load`() {
        val crypto = E2eModule.provideE2eCrypto(recorder())

        assertThat(crypto).isInstanceOf(UnavailableE2eCrypto::class.java)
    }

    @Test
    fun `provideE2eCrypto records the native-load failure in the error log`() {
        E2eModule.provideE2eCrypto(recorder())

        val reports = store.listReports()
        assertThat(reports).hasSize(1)
        val text = store.readReport(reports.single().id)
        assertThat(text).contains("Relay diagnostic — e2e-native-load")
        assertThat(text).contains("UnsatisfiedLinkError")
    }

    @Test
    fun `provideE2eCrypto records nothing when the error log is off`() {
        every { settings.enabled } returns false

        val crypto = E2eModule.provideE2eCrypto(recorder())

        assertThat(crypto).isInstanceOf(UnavailableE2eCrypto::class.java)
        assertThat(store.listReports()).isEmpty()
    }
}
