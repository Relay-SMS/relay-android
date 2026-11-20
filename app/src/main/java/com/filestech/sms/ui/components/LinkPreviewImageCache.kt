package com.filestech.sms.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Round 33 — the on-disk cache for link-preview (Open Graph)
 * images, keyed by image URL. The preview metadata was already
 * cached per process ([LinkPreviewFetcher]), but the IMAGE went
 * through Coil's HTTP cache every time a conversation opened — and
 * most sites serve their OG images with cache headers Coil must
 * revalidate, so every open re-fetched them: glitchy scrolling,
 * and a network fetch on every thread entry. This cache downloads
 * each image once, stores it under the SHA-256 of its URL, and
 * serves the file from then on — no revalidation, no expiry, just
 * a bounded directory the system may clear with the rest of
 * `cacheDir`.
 *
 * Local-only: the only network use is the same single GET Coil
 * used to make, with the same timeouts and mobile UA. A failed
 * download caches nothing, so the next open retries.
 *
 * [download] is injectable for tests; production passes the
 * default HTTP download.
 */
class LinkPreviewImageCache(
    private val dir: File,
    private val maxFiles: Int = MAX_FILES,
    private val maxBytes: Long = MAX_BYTES,
    private val download: suspend (String) -> ByteArray? = ::downloadImageBytes,
) {
    /** The cached file for [url], when it was already fetched. Touches it, for LRU eviction. */
    fun cachedFile(url: String): File? {
        val file = fileFor(url)
        if (!file.exists() || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * The cached file for [url], downloading it first when missing.
     * `null` on any failure (no network, non-200, oversized, I/O)
     * — the failure is not cached, so a later open retries. The
     * download lands via a temp file + rename, so a killed process
     * never leaves a truncated image under the cache key.
     */
    suspend fun fetch(url: String): File? {
        cachedFile(url)?.let { return it }
        val bytes = runCatching { download(url) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            dir.mkdirs()
            val file = fileFor(url)
            val tmp = File(dir, file.name + TMP_SUFFIX)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return null
            }
            prune()
            file
        }.getOrNull()
    }

    private fun fileFor(url: String): File = File(dir, sha256Hex(url))

    /** Evicts the least-recently-touched files while the cache is over its caps. */
    private fun prune() {
        val files = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= maxFiles && total <= maxBytes) break
            total -= file.length()
            count--
            file.delete()
        }
    }

    companion object {
        /** How many preview images the cache keeps. */
        const val MAX_FILES = 100

        /** How many bytes of preview images the cache keeps. */
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}

/** The SHA-256 of [value], lowercase hex — the cache key for an image URL. */
internal fun sha256Hex(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        out.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return out.toString()
}

/**
 * One HTTP GET of an image URL (same timeouts and mobile UA as the
 * preview fetcher), capped at [MAX_IMAGE_BYTES] — a hostile
 * `og:image` must not fill the disk. `null` on any failure.
 */
private suspend fun downloadImageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    // Overshoots by at most one 8 KiB buffer — harmless.
                    if (out.size() > MAX_IMAGE_BYTES) return@runCatching null
                }
            }
            out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }.onFailure { Timber.d(it, "Link preview image download failed") }.getOrNull()
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
private const val TMP_SUFFIX = ".tmp"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val READ_BUFFER_BYTES = 8 * 1024
private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

// A mobile browser UA: the same one the preview fetcher uses, so
// image hosts serve the same bytes they serve a browser visit.
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"
