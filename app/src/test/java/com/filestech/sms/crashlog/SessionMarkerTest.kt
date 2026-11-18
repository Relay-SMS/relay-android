package com.filestech.sms.crashlog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Local-only error log — the session marker: JSON round-trip,
 * tolerant decoding (a marker written by a newer build, or torn
 * by a death mid-write, must never crash the startup check),
 * and the route-pattern-to-screen-name mapping (no filled-in
 * argument may ever reach the marker).
 */
class SessionMarkerTest {

    private val marker = SessionMarker(
        sessionStartMillis = 1_000L,
        lastAliveMillis = 2_000L,
        pid = 1234,
        foreground = true,
        screen = "Thread",
        syncState = "syncing",
    )

    @Test
    fun `encode then decode round-trips`() {
        assertThat(SessionMarker.decode(SessionMarker.encode(marker))).isEqualTo(marker)
    }

    @Test
    fun `decode tolerates missing optional fields`() {
        val decoded = SessionMarker.decode(
            """{"sessionStartMillis":1000,"lastAliveMillis":2000,"pid":1234,"foreground":true}""",
        )
        assertThat(decoded).isEqualTo(marker.copy(screen = null, syncState = null))
    }

    @Test
    fun `decode ignores unknown fields`() {
        val decoded = SessionMarker.decode(
            """{"sessionStartMillis":1000,"lastAliveMillis":2000,"pid":1234,"foreground":true,"futureField":"x"}""",
        )
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.pid).isEqualTo(1234)
    }

    @Test
    fun `decode returns null for garbage`() {
        assertThat(SessionMarker.decode("not json")).isNull()
        assertThat(SessionMarker.decode("")).isNull()
        assertThat(SessionMarker.decode("{}")).isNull()
    }

    @Test
    fun `decode returns null for wrong types`() {
        assertThat(
            SessionMarker.decode(
                """{"sessionStartMillis":"soon","lastAliveMillis":2000,"pid":1234,"foreground":true}""",
            ),
        ).isNull()
    }

    @Test
    fun `screenName takes the simple name of a route`() {
        assertThat(SessionMarker.screenName("com.filestech.sms.ui.navigation.Settings"))
            .isEqualTo("Settings")
    }

    @Test
    fun `screenName strips route arguments`() {
        assertThat(SessionMarker.screenName("com.filestech.sms.ui.navigation.Thread/{conversationId}"))
            .isEqualTo("Thread")
        assertThat(
            SessionMarker.screenName("com.filestech.sms.ui.navigation.Conversations?archived={archived}"),
        ).isEqualTo("Conversations")
    }

    @Test
    fun `screenName returns null for null blank or unparseable routes`() {
        assertThat(SessionMarker.screenName(null)).isNull()
        assertThat(SessionMarker.screenName("")).isNull()
        assertThat(SessionMarker.screenName("   ")).isNull()
        assertThat(SessionMarker.screenName("{conversationId}")).isNull()
    }
}
