package com.filestech.sms.sync.client

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — LICENSE-GATE-STUB: the license check always passes
 * while there is no license server, so linking is never blocked.
 * (The real gate — key entry, expiry, enforcement — is a separate
 * future workstream.)
 */
class LicenseGateTest {

    @Test fun `the license check always returns Valid`() {
        assertThat(LicenseGate.checkLicense()).isEqualTo(LicenseStatus.Valid)
    }

    @Test fun `only Valid is valid`() {
        assertThat(LicenseStatus.Valid.isValid()).isTrue()
        assertThat(LicenseStatus.Expired.isValid()).isFalse()
        assertThat(LicenseStatus.Missing.isValid()).isFalse()
    }
}
