package com.filestech.sms.ui.screens.details

import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 32 — the Photos tab is real photos only: no GIFs, no
 * stickers (the desktop detail pane's rule). The sticker heuristic
 * is the `sticker-` filename, or a 512×512 PNG sent with no
 * caption (blank or emoji-only body). Round 33 follow-up — the
 * dimensions arm gains 72×72, the bundled-PNG fallback size
 * `stickerSendUri` produces when the SVG render fails. Round 36 —
 * the dimensions arm also covers WebP (imported stickers).
 */
class DetailsMediaFiltersTest {

    @Test
    fun `a plain photo shows in the Photos tab`() {
        assertThat(photo().isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a video never shows in the Photos tab`() {
        val video = photo(mimeType = "video/mp4")
        assertThat(video.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a GIF never shows in the Photos tab`() {
        val gif = photo(mimeType = "image/gif")
        assertThat(gif.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a sticker- filename is a sticker whatever the bytes`() {
        val sticker = photo(fileName = "sticker-7f3a.png", mimeType = "image/jpeg")
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a filename containing sticker anywhere is a sticker`() {
        val sticker = photo(fileName = "my-sticker-pack.png", mimeType = "image/png")
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `the sticker filename match is case-insensitive`() {
        val sticker = photo(fileName = "STICKER_1.PNG", mimeType = "image/png")
        assertThat(sticker.isStickerAttachment()).isTrue()
    }

    @Test
    fun `a 512x512 PNG with no caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/png",
            width = 512,
            height = 512,
            body = "",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a 512x512 PNG with an emoji-only caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/png",
            width = 512,
            height = 512,
            body = "😀😀",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
    }

    @Test
    fun `a 512x512 PNG with a real caption is a photo`() {
        val captioned = photo(
            mimeType = "image/png",
            width = 512,
            height = 512,
            body = "look at this",
        )
        assertThat(captioned.isStickerAttachment()).isFalse()
        assertThat(captioned.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a non-square PNG with no caption is a photo`() {
        val wide = photo(mimeType = "image/png", width = 800, height = 600, body = "")
        assertThat(wide.isStickerAttachment()).isFalse()
        assertThat(wide.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a PNG without dimensions only hides on the filename arm`() {
        val noDims = photo(mimeType = "image/png", width = null, height = null, body = "")
        assertThat(noDims.isStickerAttachment()).isFalse()
        assertThat(noDims.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a PNG without a message body is never hidden on the dimensions arm`() {
        val noBody = photo(mimeType = "image/png", width = 512, height = 512, body = null)
        assertThat(noBody.isStickerAttachment()).isFalse()
        assertThat(noBody.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a 72x72 PNG with no caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/png",
            width = 72,
            height = 72,
            body = "",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a 72x72 PNG with an emoji-only caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/png",
            width = 72,
            height = 72,
            body = "😀",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
    }

    @Test
    fun `a 72x72 PNG with a real caption is a photo`() {
        val captioned = photo(
            mimeType = "image/png",
            width = 72,
            height = 72,
            body = "look at this",
        )
        assertThat(captioned.isStickerAttachment()).isFalse()
        assertThat(captioned.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a 72x72 JPEG with no caption is a photo`() {
        val jpeg = photo(
            mimeType = "image/jpeg",
            width = 72,
            height = 72,
            body = "",
        )
        assertThat(jpeg.isStickerAttachment()).isFalse()
        assertThat(jpeg.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a desktop-sent sticker filename is caught whatever the caption`() {
        val sticker = photo(
            fileName = "sticker-0.png",
            mimeType = "image/png",
            width = 512,
            height = 512,
            body = "hello",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a 512x512 WebP with no caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/webp",
            width = 512,
            height = 512,
            body = "",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a 512x512 WebP with an emoji-only caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/webp",
            width = 512,
            height = 512,
            body = "😀😀",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
    }

    @Test
    fun `a 512x512 WebP with a real caption is a photo`() {
        val captioned = photo(
            mimeType = "image/webp",
            width = 512,
            height = 512,
            body = "look at this",
        )
        assertThat(captioned.isStickerAttachment()).isFalse()
        assertThat(captioned.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a 72x72 WebP with no caption is a sticker`() {
        val sticker = photo(
            mimeType = "image/webp",
            width = 72,
            height = 72,
            body = "",
        )
        assertThat(sticker.isStickerAttachment()).isTrue()
        assertThat(sticker.isPhotosTabItem()).isFalse()
    }

    @Test
    fun `a non-square WebP with no caption is a photo`() {
        val wide = photo(mimeType = "image/webp", width = 800, height = 600, body = "")
        assertThat(wide.isStickerAttachment()).isFalse()
        assertThat(wide.isPhotosTabItem()).isTrue()
    }

    @Test
    fun `a WebP without dimensions only hides on the filename arm`() {
        val noDims = photo(mimeType = "image/webp", width = null, height = null, body = "")
        assertThat(noDims.isStickerAttachment()).isFalse()
        assertThat(noDims.isPhotosTabItem()).isTrue()
    }

    private fun photo(
        mimeType: String = "image/jpeg",
        fileName: String? = "a.jpg",
        width: Int? = 800,
        height: Int? = 600,
        body: String? = "hello",
    ): Attachment = Attachment(
        id = 1,
        messageId = 10,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = 3,
        localUri = "/files/a.jpg",
        width = width,
        height = height,
        durationMs = null,
        messageBody = body,
    )
}
