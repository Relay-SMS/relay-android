package com.filestech.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The build marker format ("Build <sha> · <debug|release> · <date>").
 *
 * Pure Kotlin, no coroutines — block bodies, so every assertion
 * ends the test with a void statement (JUnit silently skips a
 * `@Test` whose body returns a value). No test asserts the real
 * SHA or date: both are build-time values.
 */
class BuildMarkerTest {

    @Test
    fun `release marker shows the sha release and date`() {
        assertThat(BuildMarker.format("a451cfcd", false, "21 Nov 2026"))
            .isEqualTo("Build a451cfcd · release · 21 Nov 2026")
    }

    @Test
    fun `debug marker says debug`() {
        assertThat(BuildMarker.format("a451cfcd", true, "21 Nov 2026"))
            .isEqualTo("Build a451cfcd · debug · 21 Nov 2026")
    }

    @Test
    fun `unknown commit renders as unknown`() {
        assertThat(BuildMarker.format("unknown", false, "21 Nov 2026"))
            .isEqualTo("Build unknown · release · 21 Nov 2026")
    }
}
