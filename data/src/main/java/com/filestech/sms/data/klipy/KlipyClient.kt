package com.filestech.sms.data.klipy

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.BuildConfig
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.gif.GifItem
import com.filestech.sms.domain.gif.GifProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 13 — Klipy GIF search (Kristin's choice, 2026-11-11 — Giphy dropped
 * the same day: its 100/hour beta limit was too small), the only [GifProvider]
 * implementation. The picker talks to [GifProvider], never to this class
 * directly.
 *
 * Endpoints (both GET, both under [BASE_URL], `https://api.klipy.com/api/v1` —
 * the key is a **path segment**, `…/api/v1/{app_key}/gifs/…`, not a query
 * parameter):
 * - search:   `GET $BASE_URL/{key}/gifs/search?q=…&per_page=…&content_filter=high`
 * - trending: `GET $BASE_URL/{key}/gifs/trending?per_page=…&content_filter=high`
 *   (the empty-query state of the GIF grid)
 *
 * Direct client-side calls only, always to api.klipy.com. The API key lives in
 * exactly one place — the `KLIPY_API_KEY` `buildConfigField` in
 * `data/build.gradle.kts` (read here as [apiKey], from `local.properties`,
 * gitignored). There is no configurable base URL and no proxy hook: Klipy's
 * own rules require requests to originate from the end-user client, so the
 * host is a plain constant and the key is meant to live in the client app.
 * **The key is a path segment, so the full request URL is a secret — it is
 * never logged anywhere in this file.**
 *
 * Klipy's rules (checked 2026-11-11, docs.klipy.com, "Integration
 * Requirements" and "API Usage & Attribution Guidelines") — read before
 * touching this:
 * - "Load KLIPY media directly — Media must be loaded directly from the URLs
 *   included in the API response. Do not store, mirror, re-host, rewrite, or
 *   retain copies of KLIPY media unless KLIPY has approved a different
 *   delivery method in writing." So this client caches NOTHING: every time the
 *   GIF tab opens it calls Trending fresh, and every submitted search calls
 *   Search fresh. No in-memory cache, no TTL, no stored media URLs. The
 *   thumbnails load live from Klipy's URLs, with Coil's disk cache disabled
 *   for them (see `GifGrid`).
 * - "Send requests from the end-user client — API requests and media loads
 *   must originate from the user's mobile app, desktop app, or web browser.
 *   Do not route requests through partner-operated servers, proxies, CDNs, or
 *   other intermediaries without prior written approval." So there is no
 *   server proxy, and none is planned.
 * - "Preserve Search and Trending results — Display content returned by
 *   KLIPY's Search and Trending endpoints in the sequence and overall
 *   composition provided." Results are shown in the order returned, never
 *   reordered or filtered (except ad objects, below).
 * - "Keep content sources separate — KLIPY content must appear in a dedicated
 *   grid, feed, tab, or result section." The GIF grid shows Klipy content
 *   only; Klipy is the only GIF provider.
 * The one file that does land on disk is the single GIF the user taps to send:
 * a transient staging copy in `cacheDir` for the outgoing MMS, never re-served
 * as a thumbnail (thumbnails always load live from Klipy's URLs).
 *
 * Attribution (docs.klipy.com, "API Usage & Attribution Guidelines"):
 * - "Search KLIPY" as the search field placeholder — **REQUIRED**, and it
 *   gates production-key approval. The GIF tab's placeholder is exactly that
 *   (see `emoji_picker_search_gif`).
 * - A visible "Powered by KLIPY" mark wherever KLIPY content is shown —
 *   optional, but shown in the grid footer anyway (it also gates production
 *   approval). Hardcoded literal, never translated.
 * - A KLIPY watermark on the shared content message card — optional, not
 *   done (the GIF is sent as a plain MMS attachment).
 *
 * Ads: Klipy's Ads API is optional and OFF for this app — ads are not
 * integrated. If an API response ever carries an advertisement object
 * (`"type": "ad"`), it is filtered out here and never shown (see
 * [KlipyGifDto.toKlipyGif]).
 *
 * Rate limit: the **test** key allows ~100 calls/hour, like Giphy's beta key.
 * The **production** key is free and unlimited — requested via a simple form
 * in the Partner Panel at partner.klipy.com. What keeps the test key alive
 * until then: the picker searches submit-only (never per keystroke), so a
 * search costs exactly one API call.
 *
 * Network stack, deliberately light: the app had no HTTP client dependency, so
 * this uses [HttpURLConnection] (built into Android, no new dependency) and
 * parses the JSON with kotlinx-serialization, which `:data` already depends on
 * (same pattern as `BackupService`). No Retrofit/OkHttp was added — OkHttp is
 * already present transitively via Coil, but the client does not need it. Note
 * Coil also fetches the GIF thumbnails over HTTPS; the GIF grid is the only
 * feature that makes network calls, besides MMS transport by Android's own
 * service.
 *
 * Fail-soft by design: no key yet → [search]/[trending] return an empty list
 * (the grid shows its empty state, never a crash); HTTP 429 (rate-limited) or
 * any other failure → [Outcome.Failure] with [AppError.Network], and the grid
 * shows the "unavailable" message (there is no cached fallback — caching is
 * not permitted).
 *
 * The class, [httpGet] and [apiKey] are `open` so tests can substitute a canned
 * response and a stand-in key without a network (same pattern as
 * `SystemThreadResolver`).
 */
@Singleton
open class KlipyClient @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GifProvider {

    /** Manual construction (Compose grids, tests): the same client on [Dispatchers.IO]. */
    constructor(context: Context) : this(context, Dispatchers.IO)

    /**
     * The API key, from the single `KLIPY_API_KEY` `buildConfigField` in
     * `data/build.gradle.kts` (read from `local.properties`, gitignored).
     * `open` so tests can substitute a key without a real one (the checked-in
     * value is the placeholder, so [hasApiKey] is `false` in tests and in the
     * app until Kristin pastes her key).
     */
    internal open val apiKey: String
        get() = BuildConfig.KLIPY_API_KEY

    /** `true` once a real key has been pasted into `local.properties`. */
    val hasApiKey: Boolean
        get() = apiKey.isNotBlank() && apiKey != API_KEY_PLACEHOLDER

    /**
     * Trending GIFs, fetched fresh every time — no cache (Klipy does not permit
     * a partner-operated media cache). No key yet → an empty list, no network
     * attempt.
     */
    override suspend fun trending(): Outcome<List<GifItem>> = withContext(io) {
        if (!hasApiKey) return@withContext Outcome.Success(emptyList())
        when (val result = fetchGifs(trendingUrl(DEFAULT_LIMIT))) {
            is Outcome.Success -> Outcome.Success(result.value.map { it.toGifItem() })
            is Outcome.Failure -> result
        }
    }

    /**
     * GIFs matching [query] — the exact terms the user submitted, sent verbatim
     * as `q`. Fetched fresh every time — no cache. A blank query returns an
     * empty list (callers show trending instead); no key yet → an empty list,
     * no network attempt.
     */
    override suspend fun search(query: String): Outcome<List<GifItem>> = withContext(io) {
        if (!hasApiKey || query.isBlank()) return@withContext Outcome.Success(emptyList())
        when (val result = fetchGifs(searchUrl(query, DEFAULT_LIMIT))) {
            is Outcome.Success -> Outcome.Success(result.value.map { it.toGifItem() })
            is Outcome.Failure -> result
        }
    }

    /**
     * Downloads [item] to `cacheDir/mms_outgoing/klipy-<id>.gif` (a FileProvider
     * cache-path, like the outgoing camera photos) and returns its `content://`
     * URI, ready to attach as an MMS attachment through the existing attachment
     * path. The file is capped at [MAX_GIF_BYTES]; anything larger, a non-200
     * status, or a transport failure deletes the partial file and fails. The
     * file is transient staging for the outgoing MMS, not a media cache: it is
     * never re-served as a thumbnail, and it lives in `cacheDir`, so the OS may
     * purge it.
     */
    override suspend fun download(item: GifItem): Outcome<Uri> = withContext(io) {
        val safeId = item.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safeId.isEmpty()) return@withContext Outcome.Failure(AppError.Validation("empty gif id"))
        // Same scheme guard as MmsDownloader: the URL comes from an API response,
        // never let it chase a `file://` / `content://` URI.
        val scheme = item.downloadUrl.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return@withContext Outcome.Failure(AppError.Validation("gif url scheme not allowed: $scheme"))
        }
        val file = File(File(context.cacheDir, "mms_outgoing").apply { mkdirs() }, "klipy-$safeId.gif")
        val downloaded = runCatching { downloadToFile(item.downloadUrl, file) }
            .onFailure { Timber.w(it, "GIF download failed") }
            .getOrDefault(false)
        if (!downloaded) {
            file.delete()
            return@withContext Outcome.Failure(AppError.Network())
        }
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            Timber.w(it, "GIF FileProvider URI failed")
            file.delete()
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(AppError.Storage(it)) },
        )
    }

    internal fun trendingUrl(limit: Int): String =
        "${baseUrl()}/gifs/trending?per_page=$limit&content_filter=$CONTENT_FILTER"

    internal fun searchUrl(query: String, limit: Int): String =
        "${baseUrl()}/gifs/search" +
            "?q=${URLEncoder.encode(query, "UTF-8")}&per_page=$limit&content_filter=$CONTENT_FILTER"

    /**
     * The API root with the key as a path segment. The key is a secret and the
     * full URL therefore is too — callers must never log a URL built from this.
     */
    private fun baseUrl(): String =
        "$BASE_URL/${URLEncoder.encode(apiKey, "UTF-8")}"

    private fun fetchGifs(url: String): Outcome<List<KlipyGif>> {
        val body = runCatching { httpGet(url) }
            .onFailure { Timber.w(it, "Klipy request failed") }
            .getOrNull() ?: return Outcome.Failure(AppError.Network())
        return runCatching { parseKlipyResponse(body) }
            .onFailure { Timber.w(it, "Klipy response parse failed") }
            .fold(
                onSuccess = { Outcome.Success(it) },
                onFailure = { Outcome.Failure(AppError.Network(it)) },
            )
    }

    /**
     * Raw GET. Returns the body on HTTP 200, `null` on any other status — 429
     * (rate-limited) included, logged and never thrown — and throws [IOException]
     * on a transport failure. The URL is never logged: it carries the API key
     * as a path segment.
     */
    @Throws(IOException::class)
    internal open fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                if (connection.responseCode == HTTP_TOO_MANY_REQUESTS) {
                    Timber.w("Klipy rate-limited (HTTP 429): the key's hourly quota is used up")
                } else {
                    Timber.w("Klipy HTTP %d", connection.responseCode)
                }
                return null
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Streams the GIF at [url] into [file]; `false` on a non-200 status or over [MAX_GIF_BYTES]. */
    private fun downloadToFile(url: String, file: File): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_GIF_BYTES) return false
                        output.write(buffer, 0, read)
                    }
                }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }
}

/** One GIF from the Klipy API: a grid thumbnail and the file to download on tap. */
data class KlipyGif(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val width: Int,
    val height: Int,
)

internal fun KlipyGif.toGifItem(): GifItem = GifItem(
    id = id,
    title = title,
    thumbnailUrl = thumbnailUrl,
    downloadUrl = downloadUrl,
    width = width,
    height = height,
)

@Serializable
internal data class KlipySearchResponse(
    val result: Boolean = false,
    val data: KlipyDataDto? = null,
)

@Serializable
internal data class KlipyDataDto(
    val data: List<KlipyGifDto> = emptyList(),
)

@Serializable
internal data class KlipyGifDto(
    val url: String = "",
    val title: String = "",
    val slug: String = "",
    val file: KlipyFileDto? = null,
    @SerialName("file_meta") val fileMeta: KlipyFileMetaDto? = null,
    val tags: List<String> = emptyList(),
    val type: String = "",
)

@Serializable
internal data class KlipyFileDto(
    val mp4: String = "",
    val gif: String = "",
    val webp: String = "",
)

@Serializable
internal data class KlipyFileMetaDto(
    val mp4: KlipySizeDto? = null,
    val gif: KlipySizeDto? = null,
    val webp: KlipySizeDto? = null,
)

@Serializable
internal data class KlipySizeDto(
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Lenient parser: Klipy returns far more fields than these; unknown keys are
 * ignored. A `result: false` body is an API-level failure, not an empty
 * result. Ad objects (`"type": "ad"`) are filtered out — the Ads API is off
 * for this app and ads are never shown.
 */
private val klipyJson = Json { ignoreUnknownKeys = true }

internal fun parseKlipyResponse(body: String): List<KlipyGif> {
    val response = klipyJson.decodeFromString<KlipySearchResponse>(body)
    if (!response.result) error("Klipy result=false")
    return response.data?.data?.mapNotNull { it.toKlipyGif() } ?: emptyList()
}

/**
 * The `gif` file URL serves as both the grid thumbnail and the download
 * (Klipy returns one GIF rendition per item, ~320 px wide — small enough for
 * MMS). Entries with no usable GIF URL, no slug, or an ad type are skipped.
 * `file_meta.gif` carries the dimensions; missing meta means 0×0.
 */
private fun KlipyGifDto.toKlipyGif(): KlipyGif? {
    if (type == "ad") return null
    val gifUrl = file?.gif?.takeIf { it.isNotBlank() } ?: return null
    val id = slug.takeIf { it.isNotBlank() } ?: return null
    val meta = fileMeta?.gif
    return KlipyGif(
        id = id,
        title = title,
        thumbnailUrl = gifUrl,
        downloadUrl = gifUrl,
        width = meta?.width ?: 0,
        height = meta?.height ?: 0,
    )
}

// The host, fixed: Klipy requires requests from the end-user client ("Do not
// route requests through partner-operated servers, proxies, CDNs, or other
// intermediaries"), so there is no configurable base URL and no proxy hook.
private const val BASE_URL = "https://api.klipy.com/api/v1"

// `java.net.HttpURLConnection` defines no 429 constant, so the status code is
// named here. (HTTP_TOO_MANY_REQUESTS does not exist on that class.)
private const val HTTP_TOO_MANY_REQUESTS = 429

// Must match the placeholder in the `KLIPY_API_KEY` buildConfigField in
// `data/build.gradle.kts`: the client treats that value as "no key yet".
private const val API_KEY_PLACEHOLDER = "YOUR_KLIPY_API_KEY"
private const val DEFAULT_LIMIT = 25

// Strictest content filter (Klipy: off/low/medium/high) — the equivalent of
// Giphy's rating=g: general audiences only.
private const val CONTENT_FILTER = "high"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
private const val MAX_GIF_BYTES = 5L * 1024L * 1024L
