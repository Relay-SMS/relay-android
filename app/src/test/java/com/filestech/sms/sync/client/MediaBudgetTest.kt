package com.filestech.sms.sync.client

import com.filestech.sms.core.result.AppError
import com.filestech.sms.domain.usecase.SendMediaMmsUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Client mode C5 — the media budget: the share math, the
 * passthrough rules (small photos, GIFs, video, audio — never
 * transcoded, never recompressed when they fit), and the refusals.
 * The JPEG ladder needs real Bitmaps, so on the plain-JVM harness a
 * photo over its share reports `ClientPhotoPrepareFailed` (the
 * decode stubs throw) — the ladder itself is device-verified.
 */
class MediaBudgetTest {

    private val tempDir: File = Files.createTempDirectory("c5-budget").toFile()

    private fun file(name: String, sizeBytes: Int): File =
        File(tempDir, name).also { it.writeBytes(ByteArray(sizeBytes) { (it % 251).toByte() }) }

    private fun payload(
        file: File,
        mimeType: String,
        durationMs: Long? = null,
    ) = SendMediaMmsUseCase.AttachmentPayload(file, mimeType, durationMs = durationMs)

    @Test fun `partParImage splits the cap minus text minus files across the images`() {
        // (280 KB - 1000 - 2000) / 2.
        assertThat(MediaBudget.partParImage(1000, 2000, 2)).isEqualTo(141_860L)
        // Zero images share nothing.
        assertThat(MediaBudget.partParImage(1000, 2000, 0)).isEqualTo(0L)
        // Text + files already over the cap: a negative share.
        assertThat(MediaBudget.partParImage(300_000, 0, 1)).isEqualTo(-13_280L)
    }

    @Test fun `already-small photo passes through as-is`() = runTest {
        val photo = file("small.png", 1024)

        val result = MediaBudget.prepare(listOf(payload(photo, "image/png")), "hi")

        assertThat(result).isInstanceOf(MediaBudget.PrepareResult.Ready::class.java)
        val item = (result as MediaBudget.PrepareResult.Ready).items.single()
        assertThat(item.file).isEqualTo(photo)
        assertThat(item.mimeType).isEqualTo("image/png")
        // The wire name is the staged file's name — it survives the
        // send manager's durable copy (whose internal `out-…` name
        // must never reach the phone or the recipient).
        assertThat(item.fileName).isEqualTo("small.png")
    }

    @Test fun `GIF that fits passes through uncompressed`() = runTest {
        val gif = file("anim.gif", 1024)

        val result = MediaBudget.prepare(listOf(payload(gif, "image/gif")), "")

        assertThat(result).isInstanceOf(MediaBudget.PrepareResult.Ready::class.java)
        val item = (result as MediaBudget.PrepareResult.Ready).items.single()
        // GIFs are never JPEG-compressed (re-encoding kills the
        // animation): the original bytes go out.
        assertThat(item.file).isEqualTo(gif)
        assertThat(item.mimeType).isEqualTo("image/gif")
    }

    @Test fun `GIF over its share is refused, never recompressed`() = runTest {
        val gif = file("big.gif", 200 * 1024)
        // 100 KB of text leaves a 180 KB share for a 200 KB GIF.
        val body = "x".repeat(100 * 1024)

        val result = MediaBudget.prepare(listOf(payload(gif, "image/gif")), body)

        assertThat(result).isEqualTo(
            MediaBudget.PrepareResult.Refused(AppError.ClientMmsCapReached),
        )
    }

    @Test fun `video passes through untranscoded`() = runTest {
        val video = file("clip.mp4", 100 * 1024)

        val result = MediaBudget.prepare(listOf(payload(video, "video/mp4")), "")

        assertThat(result).isInstanceOf(MediaBudget.PrepareResult.Ready::class.java)
        val item = (result as MediaBudget.PrepareResult.Ready).items.single()
        assertThat(item.file).isEqualTo(video)
        assertThat(item.mimeType).isEqualTo("video/mp4")
    }

    @Test fun `video over the cap is refused`() = runTest {
        val video = file("huge.mp4", 300 * 1024)

        val result = MediaBudget.prepare(listOf(payload(video, "video/mp4")), "")

        assertThat(result).isEqualTo(
            MediaBudget.PrepareResult.Refused(AppError.ClientMmsCapReached),
        )
    }

    @Test fun `audio passes through with its duration`() = runTest {
        val audio = file("voice.3gp", 10 * 1024)

        val result = MediaBudget.prepare(
            listOf(payload(audio, "audio/3gpp", durationMs = 5_000L)),
            "",
        )

        assertThat(result).isInstanceOf(MediaBudget.PrepareResult.Ready::class.java)
        val item = (result as MediaBudget.PrepareResult.Ready).items.single()
        assertThat(item.file).isEqualTo(audio)
        assertThat(item.durationMs).isEqualTo(5_000L)
    }

    @Test fun `no images and total over cap is refused`() = runTest {
        val video = file("a.mp4", 150 * 1024)
        val audio = file("b.3gp", 150 * 1024)

        val result = MediaBudget.prepare(
            listOf(payload(video, "video/mp4"), payload(audio, "audio/3gpp")),
            "",
        )

        assertThat(result).isEqualTo(
            MediaBudget.PrepareResult.Refused(AppError.ClientMmsCapReached),
        )
    }

    @Test fun `share below 48 KB is refused`() = runTest {
        val photo = file("small.png", 1024)
        val video = file("clip.mp4", 250 * 1024)
        // 250 KB of video leaves a 30 KB share — below the 48 KB
        // floor, so the send is refused even though the photo itself
        // is tiny.
        val result = MediaBudget.prepare(
            listOf(payload(photo, "image/png"), payload(video, "video/mp4")),
            "",
        )

        assertThat(result).isEqualTo(
            MediaBudget.PrepareResult.Refused(AppError.ClientMmsCapReached),
        )
    }

    @Test fun `photo over its share fails to prepare on the JVM`() = runTest {
        val photo = file("big.png", 100 * 1024)
        // 200 KB of text leaves an 80 KB share for a 100 KB photo:
        // the ladder runs — and on the plain-JVM harness the decode
        // stubs throw, so the photo cannot be prepared. (On a device
        // the ladder recompresses it; the ladder is device-verified.)
        val body = "x".repeat(200 * 1024)

        val result = MediaBudget.prepare(listOf(payload(photo, "image/png")), body)

        assertThat(result).isEqualTo(
            MediaBudget.PrepareResult.Refused(AppError.ClientPhotoPrepareFailed),
        )
    }

    @Test fun `empty list is Ready with no items`() = runTest {
        val result = MediaBudget.prepare(emptyList(), "hello")

        assertThat(result).isEqualTo(MediaBudget.PrepareResult.Ready(emptyList()))
    }

    @Test fun `UTF-8 multibyte text is counted in bytes`() = runTest {
        val photo = file("small.png", 1024)
        // 70,000 emoji = 280,000 bytes (4 each): the share is 6 KB,
        // below the floor. Counted in chars (70,000) it would pass.
        val body = "😀".repeat(70_000)

        val result = MediaBudget.prepare(listOf(payload(photo, "image/png")), body)

        assertThat(result).isEqualTo(
            MediaBudget.PrepareResult.Refused(AppError.ClientMmsCapReached),
        )
    }
}
