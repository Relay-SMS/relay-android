package com.filestech.sms.ui.screens.linkeddevices

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The start-failure card rule ([shouldShowStartError]): the card
 * shows only while the server is enabled, not running, and a
 * failure was recorded — a stale error must not linger after the
 * user turns the server off.
 */
class LinkedDevicesStartErrorTest {

    @Test fun `error card hides when disabled even with a stored error`() {
        assertThat(shouldShowStartError(false, false, STORED_ERROR)).isFalse()
    }

    @Test fun `error card shows when enabled with an error and not running`() {
        assertThat(shouldShowStartError(true, false, STORED_ERROR)).isTrue()
    }

    @Test fun `error card hides while the server is running`() {
        assertThat(shouldShowStartError(true, true, STORED_ERROR)).isFalse()
    }

    @Test fun `error card hides with no stored error`() {
        assertThat(shouldShowStartError(true, false, null)).isFalse()
    }

    @Test fun `error card hides when disabled and running`() {
        assertThat(shouldShowStartError(false, true, STORED_ERROR)).isFalse()
    }

    @Test fun `initial state has the server off`() {
        assertThat(LinkedDevicesState().serverEnabled).isFalse()
    }

    companion object {
        private const val STORED_ERROR =
            "2026-11-17T00:00:00Z | engine | SSLException: failed to initialize"
    }
}
