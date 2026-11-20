package com.filestech.sms.data.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 33 follow-up — the sticker marker must survive the durable
 * promotion too: `promoteToDurable` used to rename EVERYTHING to
 * `out-<timestamp>-<uuid>.<ext>`, stripping the `sticker-` prefix
 * the staged copy had just preserved, so the mirror still stored a
 * marker-less filename and the Photos tab's filename arm never
 * fired. A staged sticker now promotes to
 * `sticker-out-<timestamp>-<uuid>.<ext>`.
 */
class DurableAttachmentFileNameTest {

    @Test
    fun `a staged sticker keeps the sticker- prefix in the durable name`() {
        val name = durableAttachmentFileName("sticker-1793123456789-a1b2c3d4.png", "png")

        assertThat(name).startsWith("sticker-out-")
        assertThat(name).endsWith(".png")
        assertThat(name).contains("sticker")
    }

    @Test
    fun `a desktop-sent sticker name keeps the prefix`() {
        val name = durableAttachmentFileName("sticker-0.png", "png")

        assertThat(name).startsWith("sticker-out-")
        assertThat(name).endsWith(".png")
    }

    @Test
    fun `the sticker prefix match is case-insensitive`() {
        val name = durableAttachmentFileName("STICKER-1.png", "png")

        assertThat(name).startsWith("sticker-")
    }

    @Test
    fun `a staged photo gets the plain out- name`() {
        val name = durableAttachmentFileName("1793123456789-a1b2c3d4.jpg", "jpg")

        assertThat(name).startsWith("out-")
        assertThat(name).doesNotContain("sticker")
        assertThat(name).endsWith(".jpg")
    }

    @Test
    fun `a blank extension falls back to bin`() {
        val name = durableAttachmentFileName("sticker-1793123456789-a1b2c3d4.png", "")

        assertThat(name).startsWith("sticker-out-")
        assertThat(name).endsWith(".bin")
    }

    @Test
    fun `two promotions get different durable names`() {
        val first = durableAttachmentFileName("sticker-0.png", "png")
        val second = durableAttachmentFileName("sticker-0.png", "png")

        assertThat(first).isNotEqualTo(second)
    }
}
