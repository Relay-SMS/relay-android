package com.filestech.sms.data.linkpreview

import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.linkpreview.LinkPreview
import com.filestech.sms.domain.linkpreview.LinkPreviewRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
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
 * `<meta property="…" content="…">` elements every site emits.
 *
 * Round 18 — a preview must describe the SPECIFIC link, not the site or
 * the profile behind it (every YouTube link reading "YouTube", every post
 * link reading its author's name, is a pointless card). So [resolvePreview]
 * dispatches per platform before the generic Open Graph fetch:
 * - YouTube (watch, Shorts, youtu.be, …): the official oEmbed endpoint
 *   first — title + the author's name + the thumbnail that actually
 *   exists (`hqdefault`, where `og:image`'s `maxresdefault` 404s for
 *   older videos). Falls back to Open Graph when oEmbed fails (channels,
 *   playlists). Verified live 2026-11-12 (watch + Shorts).
 * - X post URLs (`/status/…`): the page's own Open Graph tags already
 *   carry the post — but `og:title` is the author's name and the post
 *   text is `og:description`, so [shapeXPost] swaps them, puts the
 *   author on the domain line, and drops the author's avatar
 *   (`/profile_images/`) so a text-only post shows no image instead of
 *   the profile picture. Verified live 2026-11-12.
 * - TikTok: the video page serves a script shell with no Open Graph tags
 *   and oEmbed returns no title or thumbnail, so a TikTok link yields
 *   no card rather than a generic one. Verified live 2026-11-12.
 * Instagram/Facebook post pages are login-walled with no anonymous
 * oEmbed, so they stay on the generic path.
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
        val preview = runCatching { resolvePreview(clean) }
            .onFailure { Timber.d(it, "Link preview fetch failed") }
            .getOrNull()
        synchronized(cacheLock) { cache[clean] = preview }
        preview
    }

    /**
     * Round 18 — the preview for [url], per platform (see the class KDoc):
     * YouTube goes to oEmbed first, X post URLs get their Open Graph tags
     * reshaped to the post, TikTok suppresses its generic shell, and
     * everything else takes the plain Open Graph fetch.
     */
    private fun resolvePreview(url: String): LinkPreview? {
        val host = hostOf(url)
        return when {
            isYouTubeHost(host) -> fetchOEmbed(url, YOUTUBE_OEMBED_URL) ?: fetchOpenGraph(url)
            isXHost(host) && url.contains("/status/") ->
                fetchOpenGraph(url)?.let(::shapeXPost)
            isTikTokHost(host) -> fetchOpenGraph(url)?.takeUnless { it.imageUrl == null }
            else -> fetchOpenGraph(url)
        }
    }

    /** The page's Open Graph preview, or `null` when the page yields none. */
    private fun fetchOpenGraph(url: String): LinkPreview? =
        fetchHtml(url)?.let { parseOpenGraph(it, url) }

    /** The oEmbed preview for [pageUrl] (`title` + author + thumbnail), or `null`. */
    private fun fetchOEmbed(pageUrl: String, endpoint: String): LinkPreview? {
        val json = fetchOEmbedJson(pageUrl, endpoint) ?: return null
        return parseOEmbed(json, pageUrl)
    }

    /**
     * The oEmbed endpoint's JSON body (capped at [MAX_OEMBED_CHARS] —
     * oEmbed answers are small), or `null` on any failure. Non-200 (a
     * channel or playlist URL, which has no video to embed) is a plain
     * miss, not an error: the caller falls back to Open Graph.
     */
    private fun fetchOEmbedJson(pageUrl: String, endpoint: String): String? {
        val encoded = runCatching { URLEncoder.encode(pageUrl, Charsets.UTF_8.name()) }
            .getOrNull() ?: return null
        val connection = openGetConnection("$endpoint?url=$encoded&format=json") ?: return null
        connection.setRequestProperty("Accept", "application/json")
        val body = runCatching {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                readUpTo(connection, MAX_OEMBED_CHARS)
            }
        }.getOrNull()
        connection.disconnect()
        return body
    }

    /** A GET connection with the fetcher's timeouts, UA and redirect following. */
    private fun openGetConnection(pageUrl: String): HttpURLConnection? {
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
        return connection
    }

    /**
     * The page's `<head>` as text, or `null` when the URL is not a
     * fetchable HTML page (see [isFetchableHtml]). Reading stops at
     * `</head>` (Open Graph tags live there) or at [MAX_HTML_CHARS] — a
     * page body is never needed.
     */
    private fun fetchHtml(pageUrl: String): String? {
        val connection = openGetConnection(pageUrl) ?: return null
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

    /** The response body as UTF-8 text, up to [maxChars]. */
    private fun readUpTo(connection: HttpURLConnection, maxChars: Int): String {
        val builder = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(READ_BUFFER_CHARS)
            while (builder.length < maxChars) {
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
private const val MAX_OEMBED_CHARS = 64 * 1024
private const val MAX_TEXT_CHARS = 500
private const val YOUTUBE_OEMBED_URL = "https://www.youtube.com/oembed"
private const val HIGH_SURROGATE_START = 0xD800
private const val HIGH_SURROGATE_END = 0xDBFF
private const val LOW_SURROGATE_START = 0xDC00
private const val LOW_SURROGATE_END = 0xDFFF

/** The URL's host, lowercased, or `null` when the URL does not parse. */
private fun hostOf(url: String): String? =
    runCatching { URI(url).host?.lowercase() }.getOrNull()

private fun isYouTubeHost(host: String?): Boolean =
    host == "youtube.com" || host?.endsWith(".youtube.com") == true ||
        host == "youtu.be" || host?.endsWith(".youtu.be") == true ||
        host == "youtube-nocookie.com" || host?.endsWith(".youtube-nocookie.com") == true

private fun isXHost(host: String?): Boolean =
    host == "x.com" || host?.endsWith(".x.com") == true ||
        host == "twitter.com" || host?.endsWith(".twitter.com") == true

private fun isTikTokHost(host: String?): Boolean =
    host == "tiktok.com" || host?.endsWith(".tiktok.com") == true

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

/**
 * Round 18 — reshapes an X post page's Open Graph preview to the post
 * itself: the page puts the author's name in `og:title`
 * ("Name (@handle) on X") and the post text in `og:description`, so the
 * card would otherwise show the profile header for every post. The post
 * text becomes the title, the author moves to the domain line, and the
 * author's avatar (`/profile_images/`) is dropped — a text-only post
 * shows its text with no image instead of the profile picture. A post
 * with media keeps its first image (`og:image` is the attached media).
 */
internal fun shapeXPost(preview: LinkPreview): LinkPreview {
    val postText = preview.description?.takeIf { it.isNotBlank() }
    val author = preview.title
        ?.removeSuffix(" on X")
        ?.takeIf { it.isNotBlank() && it != preview.title }
    val image = preview.imageUrl?.takeUnless { it.contains("/profile_images/") }
    return preview.copy(
        title = postText ?: preview.title,
        description = null,
        imageUrl = image,
        siteName = author ?: preview.siteName,
    )
}

/**
 * Round 18 — the preview for [pageUrl] from an oEmbed JSON body
 * (YouTube): `title` is the video title, `author_name` the channel
 * (shown on the card's domain line), `thumbnail_url` the thumbnail.
 * `null` when the body carries neither a title nor a thumbnail.
 */
internal fun parseOEmbed(json: String, pageUrl: String): LinkPreview? {
    val title = extractJsonStringField(json, "title")
        ?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_CHARS)
    val author = extractJsonStringField(json, "author_name")
        ?.trim()?.takeIf { it.isNotBlank() }
    val thumbnail = extractJsonStringField(json, "thumbnail_url")
        ?.trim()?.takeIf { it.isNotBlank() }
        ?.let { resolveImageUrl(pageUrl, it) }
    if (title == null && thumbnail == null) return null
    return LinkPreview(
        url = pageUrl,
        title = title,
        description = null,
        imageUrl = thumbnail,
        siteName = author,
    )
}

/** The string value of a top-level `"field": "…"` pair, JSON-unescaped, or `null`. */
internal fun extractJsonStringField(json: String, field: String): String? {
    val regex = Regex("\"" + Regex.escape(field) + """\"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    val raw = regex.find(json)?.groupValues?.getOrNull(1) ?: return null
    return raw.unescapeJsonString()
}

/**
 * Decodes a JSON string's escapes: `\"` `\\` `\/` `\b` `\f` `\n` `\r`
 * `\t` and `\uXXXX`, with surrogate pairs (`\uD83D\uDC40`) combined into
 * the one character. A lone or malformed escape is kept literally (or
 * becomes `?` for an unpaired surrogate), never a throw. A string without
 * a backslash is returned untouched.
 */
internal fun String.unescapeJsonString(): String {
    if (!contains('\\')) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '\\' || i + 1 >= length) {
            out.append(c)
            i++
            continue
        }
        when (val esc = this[i + 1]) {
            '"', '\\', '/' -> {
                out.append(esc)
                i += 2
            }
            'b' -> {
                out.append('\b')
                i += 2
            }
            'f' -> {
                out.append('\u000C')
                i += 2
            }
            'n' -> {
                out.append('\n')
                i += 2
            }
            'r' -> {
                out.append('\r')
                i += 2
            }
            't' -> {
                out.append('\t')
                i += 2
            }
            'u' -> i += appendJsonUnicodeEscape(out, i)
            else -> {
                out.append('\\').append(esc)
                i += 2
            }
        }
    }
    return out.toString()
}

/**
 * Appends the `\uXXXX` escape at [start] to [out] — a high surrogate
 * followed by a low surrogate combines into the one character — and
 * returns how many input characters the escape consumed.
 */
private fun String.appendJsonUnicodeEscape(out: StringBuilder, start: Int): Int {
    val hex = substring(start + 2, minOf(start + 6, length))
    val code = hex.toIntOrNull(16)
    if (code == null || hex.length < 4) {
        out.append("\\u").append(hex)
        return 2 + hex.length
    }
    val hasLowSurrogate = start + 12 <= length && this[start + 6] == '\\' && this[start + 7] == 'u'
    if (code in HIGH_SURROGATE_START..HIGH_SURROGATE_END && hasLowSurrogate) {
        val low = substring(start + 8, start + 12).toIntOrNull(16)
        if (low != null && low in LOW_SURROGATE_START..LOW_SURROGATE_END) {
            val combined = 0x10000 + ((code - HIGH_SURROGATE_START) shl 10) +
                (low - LOW_SURROGATE_START)
            out.append(String(Character.toChars(combined)))
            return 12
        }
    }
    // Note: Character.isValidCodePoint is true for surrogates (they are
    // inside 0..0x10FFFF), so the unpaired-surrogate range is excluded
    // explicitly — otherwise the lone surrogate would be copied through
    // verbatim and render as a replacement glyph downstream.
    if (code in HIGH_SURROGATE_START..LOW_SURROGATE_END) {
        out.append('?')
    } else {
        out.append(String(Character.toChars(code)))
    }
    return 6
}
