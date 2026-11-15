package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The start-failure diagnostic format (§6 of the release
 * investigation): `<ISO-8601 time> | <step> | <SimpleName>: <first line>`.
 * [formatStartError] is a pure function, so the format is pinned here
 * without a DataStore (which needs an Android Context).
 */
class LinkedDeviceStoreTest {

    @Test fun `formatStartError carries time step name and first line`() {
        val formatted = formatStartError(
            1_789_000_000_000L,
            "identity",
            IllegalStateException("no identity\nsecond line"),
        )
        assertThat(formatted).startsWith("2026-")
        assertThat(formatted).contains("| identity | IllegalStateException: no identity")
        assertThat(formatted).doesNotContain("second line")
    }

    @Test fun `formatStartError truncates a long message to 200 chars`() {
        val formatted = formatStartError(0L, "engine", IllegalStateException("x".repeat(300)))
        assertThat(formatted).contains("IllegalStateException: " + "x".repeat(200))
        assertThat(formatted).doesNotContain("x".repeat(201))
    }

    @Test fun `formatStartError omits the message when there is none`() {
        val formatted = formatStartError(0L, "mdns", IllegalStateException())
        assertThat(formatted).isEqualTo("1970-01-01T00:00:00Z | mdns | IllegalStateException")
    }
}
