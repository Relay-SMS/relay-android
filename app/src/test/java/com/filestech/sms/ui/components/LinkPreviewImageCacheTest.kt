package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 33 — the preview-image disk cache: one download per URL,
 * served from disk afterwards, keyed by URL, bounded, and a failed
 * download caches nothing.
 */
class LinkPreviewImageCacheTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fetch downloads once and serves the file afterwards`() = runTest {
        val downloads = AtomicInteger(0)
        val cache = cache { downloads.incrementAndGet().let { IMAGE_BYTES } }
        val first = cache.fetch(IMAGE_URL)
        val second = cache.fetch(IMAGE_URL)
        assertThat(first).isNotNull()
        assertThat(second).isEqualTo(first)
        assertThat(downloads.get()).isEqualTo(1)
        assertThat(first!!.readBytes()).isEqualTo(IMAGE_BYTES)
    }

    @Test
    fun `cachedFile is null before fetch and the file after`() = runTest {
        val cache = cache { IMAGE_BYTES }
        assertThat(cache.cachedFile(IMAGE_URL)).isNull()
        cache.fetch(IMAGE_URL)
        assertThat(cache.cachedFile(IMAGE_URL)).isNotNull()
    }

    @Test
    fun `different URLs cache to different files`() = runTest {
        val cache = cache { IMAGE_BYTES }
        val a = cache.fetch("https://example.com/a.png")
        val b = cache.fetch("https://example.com/b.png")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `a failed download caches nothing and the next fetch retries`() = runTest {
        val downloads = AtomicInteger(0)
        val cache = cache {
            if (downloads.incrementAndGet() == 1) null else IMAGE_BYTES
        }
        assertThat(cache.fetch(IMAGE_URL)).isNull()
        assertThat(cache.cachedFile(IMAGE_URL)).isNull()
        assertThat(cache.fetch(IMAGE_URL)).isNotNull()
        assertThat(downloads.get()).isEqualTo(2)
    }

    @Test
    fun `a second cache instance over the same dir finds the file`() = runTest {
        val first = cache { IMAGE_BYTES }
        first.fetch(IMAGE_URL)
        val second = cache { error("must not download") }
        assertThat(second.cachedFile(IMAGE_URL)).isNotNull()
    }

    @Test
    fun `prune evicts the oldest file past the cap`() = runTest {
        val cache = LinkPreviewImageCache(
            dir = tempDir,
            maxFiles = 2,
            download = { IMAGE_BYTES },
        )
        cache.fetch("https://example.com/old.png")
        // The touch order decides eviction: make the first file
        // older than the rest by backdating it.
        cache.cachedFile("https://example.com/old.png")!!
            .setLastModified(System.currentTimeMillis() - 60_000)
        cache.fetch("https://example.com/mid.png")
        cache.fetch("https://example.com/new.png")
        assertThat(cache.cachedFile("https://example.com/old.png")).isNull()
        assertThat(cache.cachedFile("https://example.com/mid.png")).isNotNull()
        assertThat(cache.cachedFile("https://example.com/new.png")).isNotNull()
    }

    @Test
    fun `sha256Hex matches the known vector and is stable`() {
        assertThat(sha256Hex("abc")).isEqualTo(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
        assertThat(sha256Hex(IMAGE_URL)).isEqualTo(sha256Hex(IMAGE_URL))
        assertThat(sha256Hex("https://example.com/a.png"))
            .isNotEqualTo(sha256Hex("https://example.com/b.png"))
    }

    private fun cache(download: suspend (String) -> ByteArray?): LinkPreviewImageCache =
        LinkPreviewImageCache(dir = tempDir, download = download)

    private companion object {
        const val IMAGE_URL = "https://example.com/og.png"
        val IMAGE_BYTES = "fake-png-bytes".toByteArray(Charsets.UTF_8)
    }
}
