package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 17 — the link preview card's pure helpers. The URL extraction
 * ([firstPreviewUrl]) runs `android.util.Patterns` and needs Robolectric,
 * like the linkify pass itself — it is not covered here.
 */
class LinkPreviewCardTest {

    @Test
    fun `host strips www`() {
        assertThat("https://www.youtube.com/watch?v=x".hostOrNull()).isEqualTo("youtube.com")
    }

    @Test
    fun `host without www passes through`() {
        assertThat("https://x.com/NASA".hostOrNull()).isEqualTo("x.com")
    }

    @Test
    fun `host keeps subdomains other than www`() {
        assertThat("https://m.example.co.uk/p".hostOrNull()).isEqualTo("m.example.co.uk")
    }

    @Test
    fun `unparseable url has no host`() {
        assertThat("not a url".hostOrNull()).isNull()
        assertThat("".hostOrNull()).isNull()
    }
}
