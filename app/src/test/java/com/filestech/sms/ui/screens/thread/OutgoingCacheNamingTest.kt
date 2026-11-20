package com.filestech.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 33 follow-up — the sticker marker must survive the staged
 * cache copy: a sticker picked from the sticker tab arrives named
 * `sticker-<codepoint>-512.png`, and the stored row's filename has
 * to keep "sticker" in it for the Photos tab's filename arm. The
 * copy used to rename everything to a generated
 * `<timestamp>-<uuid>.<ext>`, stripping the marker before storage.
 */
class OutgoingCacheNamingTest {

    @Test
    fun `a sticker source name keeps the sticker- prefix`() {
        val name = outgoingCacheFileName("sticker-1f602-512.png", "png")

        assertThat(name).startsWith("sticker-")
        assertThat(name).endsWith(".png")
        assertThat(name).contains("sticker")
    }

    @Test
    fun `the sticker prefix match is case-insensitive`() {
        val name = outgoingCacheFileName("Sticker-1f602-512.png", "png")

        assertThat(name).startsWith("sticker-")
    }

    @Test
    fun `a GIF keeps a plain generated name`() {
        val name = outgoingCacheFileName("klipy-abc123.gif", "gif")

        assertThat(name).doesNotContain("sticker")
        assertThat(name).endsWith(".gif")
    }

    @Test
    fun `a photo keeps a plain generated name`() {
        val name = outgoingCacheFileName("IMG_20261120_134501.jpg", "jpg")

        assertThat(name).doesNotContain("sticker")
        assertThat(name).endsWith(".jpg")
    }

    @Test
    fun `a null source name gets a generated name`() {
        val name = outgoingCacheFileName(null, "png")

        assertThat(name).doesNotContain("sticker")
        assertThat(name).endsWith(".png")
    }

    @Test
    fun `sticker without the dash is not a sticker name`() {
        val name = outgoingCacheFileName("sticker.png", "png")

        assertThat(name).doesNotContain("sticker")
    }

    @Test
    fun `a blank extension falls back to bin`() {
        val name = outgoingCacheFileName("sticker-1f602-512.png", "")

        assertThat(name).startsWith("sticker-")
        assertThat(name).endsWith(".bin")
    }

    @Test
    fun `two stickers sent together get different cache names`() {
        val first = outgoingCacheFileName("sticker-1f602-512.png", "png")
        val second = outgoingCacheFileName("sticker-1f602-512.png", "png")

        assertThat(first).isNotEqualTo(second)
    }
}
