package com.filestech.sms.data.linkpreview

import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.linkpreview.LinkPreview
import com.filestech.sms.domain.linkpreview.LinkPreviewRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 17 — the [LinkPreviewRepository] behind the link preview cards in
 * message bubbles: a plain HTTP GET of the page, then [parseOpenGraph]
 * reads its Open Graph tags (`og:title`, `og:description`, `og:image`,
 * `og:site_name`, with `twitter:*` and `<title>` fallbacks).
 *
 * No HTML parser dependency: the tags are found with two small regexes
 * ([META_TAG_REGEX] + [META_ATTR_REGEX]), which is enough for the flat
 * `<meta property="…" content="…">` elements every site emits. Verified
 * live 2026-11-12 against YouTube (watch page: `og:title` + `og:image`
 * `maxresdefault.jpg`) and X (profile page: `og:title` + `og:description`
 * + `og:site_name`).
 *
 * Fail-soft everywhere: any failure — no network, non-200, non-HTML,
 * timeout, no usable tags — is a `null` preview, never a throw. The fetch
 * is display-only: the image is loaded into the thread card by Coil, never
 * attached to an outgoing message.
 *
 * Privacy: fetching a preview contacts the linked site (it sees the
 * device's IP, like any browser visit). Fetches happen only for a URL in a
 * message the user is looking at, only once per URL per process — results
 * (including "no preview") are cached in [cache], shared across instances
 * because bubbles construct their own fetcher the way the GIF grid builds
 * its own `KlipyClient`.
 */
@Singleton
open class LinkPreviewFetcher @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : LinkPreviewRepository {

    /** Manual construction (Compose bubbles, tests): the same fetcher on [Dispatchers.IO]. */
    constructor() : this(Dispatchers.IO)

    override suspend fun preview(url: String): LinkPreview? = withContext(io) {
        val clean = url.trim()
        val scheme = clean.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return@withContext null
        synchronized(cacheLock) {
            if (cache.containsKey(clean)) return@withContext cache[clean]
        }
        val preview = runCatching { fetchHtml(clean)?.let { parseOpenGraph(it, clean) } }
            .onFailure { Timber.d(it, "Link preview fetch failed") }
            .getOrNull()
        synchronized(cacheLock) { cache[clean] = preview }
        preview
    }

    /**
     * The page's `<head>` as text, or `null` when the URL is not a
     * fetchable HTML page (see [isFetchableHtml]). Reading stops at
     * `</head>` (Open Graph tags live there) or at [MAX_HTML_CHARS] — a
     * page body is never needed.
     */
    private fun fetchHtml(pageUrl: String): String? {
        val connection = runCatching { URL(pageUrl).openConnection() as HttpURLConnection }
            .getOrNull() ?: return null
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            instanceFollowRedirects = true
        }
        try {
            return if (isFetchableHtml(connection)) readHead(connection) else null
        } catch (_: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A 200 HTML response whose final URL is still HTTP(S): the scheme is
     * re-checked after redirects, so a redirect to a non-HTTP(S) URL is
     * refused.
     */
    private fun isFetchableHtml(connection: HttpURLConnection): Boolean =
        connection.responseCode == HttpURLConnection.HTTP_OK &&
            (connection.url.protocol == "http" || connection.url.protocol == "https") &&
            (connection.contentType ?: "").contains("text/html", ignoreCase = true)

    /** The response head as text, up to `</head>` or [MAX_HTML_CHARS]. */
    private fun readHead(connection: HttpURLConnection): String {
        val charset = charsetFrom(connection.contentType ?: "") ?: Charsets.UTF_8
        val builder = StringBuilder()
        connection.inputStream.bufferedReader(charset).use { reader ->
            val buffer = CharArray(READ_BUFFER_CHARS)
            while (builder.length < MAX_HTML_CHARS &&
                !builder.contains("</head>", ignoreCase = true)
            ) {
                val read = reader.read(buffer)
                if (read < 0) break
                builder.append(buffer, 0, read)
            }
        }
        return builder.toString()
    }

    companion object {
        // In-memory preview cache, shared across fetcher instances (see the
        // class KDoc): `null` values are cached too, so a URL with no
        // preview is not re-fetched on every scroll. Bounded (256 entries,
        // eldest evicted) like `LinkHitCache`.
        private const val MAX_CACHE_ENTRIES: Int = 256
        private val cacheLock = Any()
        private val cache = object : LinkedHashMap<String, LinkPreview?>(MAX_CACHE_ENTRIES) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, LinkPreview?>,
            ): Boolean = size > MAX_CACHE_ENTRIES
        }
    }
}

// A mobile browser UA: sites serve their real page (with Open Graph tags)
// to it. Verified live 2026-11-12 for YouTube and X.
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val READ_BUFFER_CHARS = 8 * 1024
private const val MAX_HTML_CHARS = 512 * 1024
private const val MAX_TEXT_CHARS = 500

/** `text/html; charset=UTF-8` → UTF-8; absent or unknown → `null` (caller falls back). */
internal fun charsetFrom(contentType: String): Charset? {
    val name = contentType.substringAfter("charset=", missingDelimiterValue = "")
        .substringBefore(";").trim()
    if (name.isEmpty()) return null
    return runCatching { Charset.forName(name) }.getOrNull()
}

/** `<meta …>` elements, in document order. */
private val META_TAG_REGEX = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)

/** One `name="value"` (or `name='value'`, or bare `name=value`) attribute. */
private val META_ATTR_REGEX = Regex("""([\w-]+)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""")

/** `<title>` element content, across line breaks. */
private val TITLE_REGEX = Regex(
    """<title[^>]*>(.*?)</title>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** Numeric character references: `&#123;` and `&#x1F;`. */
private val NUMERIC_ENTITY_REGEX = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

/**
 * The `property`/`name` → `content` pairs of every `<meta>` element, first
 * occurrence wins (a page that repeats `og:title` keeps its first one).
 * Attribute order does not matter: `content` before `property` parses the
 * same. Keys are lowercased; values keep their quotes stripped.
 */
internal fun extractMetaProperties(html: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (tag in META_TAG_REGEX.findAll(html)) {
        val attrs = mutableMapOf<String, String>()
        for (attr in META_ATTR_REGEX.findAll(tag.value)) {
            val name = attr.groupValues[1].lowercase()
            val raw = attr.groupValues[2]
            val value = when {
                raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ->
                    raw.substring(1, raw.length - 1)
                raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'') ->
                    raw.substring(1, raw.length - 1)
                else -> raw
            }
            attrs.putIfAbsent(name, value)
        }
        val key = (attrs["property"] ?: attrs["name"])?.lowercase()
        val content = attrs["content"]
        if (key != null && content != null) {
            out.putIfAbsent(key, content)
        }
    }
    return out
}

/**
 * Decodes the entities that appear in titles, descriptions and URLs:
 * `&lt;` `&gt;` `&quot;` `&apos;` `&#39;` `&#x27;`, numeric references
 * (`&#233;`, `&#xE9;` — full code points, emoji included), and `&amp;`
 * LAST, so `&amp;lt;` decodes once to `&lt;` instead of twice to `<`.
 * A string without `&` is returned untouched.
 */
internal fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this
    var out = replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
    out = NUMERIC_ENTITY_REGEX.replace(out) { match ->
        val raw = match.groupValues[1]
        val code = if (raw.startsWith("x")) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
        if (code != null && Character.isValidCodePoint(code)) {
            String(Character.toChars(code))
        } else {
            match.value
        }
    }
    return out.replace("&amp;", "&")
}

/**
 * Resolves [imageUrl] against [pageUrl] (relative and protocol-relative
 * `//host/path` URLs included) and refuses anything that does not come
 * back `http`/`https` — a `data:` image is never handed to Coil.
 */
internal fun resolveImageUrl(pageUrl: String, imageUrl: String): String? {
    val resolved = runCatching { URL(URL(pageUrl), imageUrl).toString() }.getOrNull()
        ?: return null
    val scheme = resolved.substringBefore("://", missingDelimiterValue = "").lowercase()
    return if (scheme == "http" || scheme == "https") resolved else null
}

/**
 * The preview for [pageUrl] from its HTML, or `null` when the page carries
 * nothing usable (no title, no description, no image). Title falls back
 * `og:title` → `twitter:title` → `<title>`; image `og:image` →
 * `og:image:url` → `twitter:image`; description `og:description` →
 * `twitter:description`. Titles and descriptions are entity-decoded and
 * capped at [MAX_TEXT_CHARS] so a hostile page cannot stuff a megabyte of
 * title into the thread.
 */
internal fun parseOpenGraph(html: String, pageUrl: String): LinkPreview? {
    val meta = extractMetaProperties(html)
    val title = (meta["og:title"] ?: meta["twitter:title"] ?: extractTitle(html))
        ?.decodeHtmlEntities()?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_CHARS)
    val description = (meta["og:description"] ?: meta["twitter:description"])
        ?.decodeHtmlEntities()?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_CHARS)
    val imageUrl = (meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"])
        ?.decodeHtmlEntities()?.trim()?.takeIf { it.isNotBlank() }
        ?.let { resolveImageUrl(pageUrl, it) }
    val siteName = meta["og:site_name"]
        ?.decodeHtmlEntities()?.trim()?.takeIf { it.isNotBlank() }
    if (title == null && description == null && imageUrl == null) return null
    return LinkPreview(
        url = pageUrl,
        title = title,
        description = description,
        imageUrl = imageUrl,
        siteName = siteName,
    )
}

/** The `<title>` element's text, or `null` when the page has none. */
internal fun extractTitle(html: String): String? =
    TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
