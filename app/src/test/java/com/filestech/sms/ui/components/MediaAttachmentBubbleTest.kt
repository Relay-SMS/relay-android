package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 36 — the image tile paints the bubble colour behind the
 * image ONLY when no image will render (a note whose decrypt is
 * pending or failed). A loaded image shows the thread behind its
 * transparent pixels, so a sticker renders bare — round 34
 * painted the colour unconditionally, which put a blue tile
 * behind every sticker in threads AND notes (both render through
 * the same `ImagePreview`).
 */
class MediaAttachmentBubbleTest {

    @Test
    fun `plain path never paints the placeholder, with or without a model`() {
        // The plain path always has an image to try (or the error
        // painter's tile on failure) — it never needs the
        // placeholder, so a transparent sticker stays bare.
        assertThat(imagePreviewShowsPlaceholderBackground(false, null)).isFalse()
        assertThat(imagePreviewShowsPlaceholderBackground(false, ByteArray(0))).isFalse()
    }

    @Test
    fun `note path paints the placeholder while the decrypt is pending`() {
        assertThat(imagePreviewShowsPlaceholderBackground(true, null)).isTrue()
    }

    @Test
    fun `note path with decrypted bytes paints no placeholder - the sticker stays bare`() {
        assertThat(imagePreviewShowsPlaceholderBackground(true, ByteArray(0))).isFalse()
    }
}
