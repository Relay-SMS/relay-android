package com.filestech.sms.data.linkpreview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 17 — the Open Graph parser without a network: tag extraction,
 * fallbacks, entity decoding and URL resolution. The live fetch itself
 * ([LinkPreviewFetcher.preview] against a real page) needs a network and a
 * device — it is not covered here; YouTube and X responses were verified
 * live with curl 2026-11-12 instead.
 */
class LinkPreviewFetcherTest {

    private val youTubeLike = """
        <html><head>
        <meta property="og:url" content="https://www.youtube.com/watch?v=dQw4w9WgXcQ">
        <meta property="og:site_name" content="YouTube">
        <meta property="og:title" content="Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)">
        <meta property="og:image" content="https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg">
        <meta property="og:type" content="video.other">
        <title>Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster) - YouTube</title>
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun `og tags parse into a preview`() {
        val preview = parseOpenGraph(youTubeLike, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo(
            "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
        )
        assertThat(preview.imageUrl).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg")
        assertThat(preview.siteName).isEqualTo("YouTube")
        assertThat(preview.url).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    }

    @Test
    fun `reversed attribute order and single quotes parse the same`() {
        val html = """
            <html><head>
            <meta content='https://example.com/pic.jpg' property='og:image'>
            <meta content='Reversed Title' property='og:title'>
            </head></html>
        """.trimIndent()

        val preview = parseOpenGraph(html, "https://example.com/page")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo("Reversed Title")
        assertThat(preview.imageUrl).isEqualTo("https://example.com/pic.jpg")
    }

    @Test
    fun `twitter tags fill in when og tags are missing`() {
        val html = """
            <html><head>
            <meta name="twitter:title" content="Tweet title">
            <meta name="twitter:description" content="Tweet text here">
            <meta name="twitter:image" content="https://pbs.twimg.com/media/x.jpg">
            </head></html>
        """.trimIndent()

        val preview = parseOpenGraph(html, "https://x.com/someone/status/1")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo("Tweet title")
        assertThat(preview.description).isEqualTo("Tweet text here")
        assertThat(preview.imageUrl).isEqualTo("https://pbs.twimg.com/media/x.jpg")
    }

    @Test
    fun `the title element is the last-resort title`() {
        val html = "<html><head><title>Plain Article Title</title></head></html>"

        val preview = parseOpenGraph(html, "https://example.com/article")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo("Plain Article Title")
        assertThat(preview.imageUrl).isNull()
    }

    @Test
    fun `a page with nothing usable yields no preview`() {
        assertThat(parseOpenGraph("<html><head></head><body>hi</body></html>", "https://example.com/")).isNull()
        assertThat(parseOpenGraph("", "https://example.com/")).isNull()
    }

    @Test
    fun `relative and protocol-relative image urls resolve against the page`() {
        val html = """
            <html><head>
            <meta property="og:title" content="T">
            <meta property="og:image" content="/images/hero.jpg">
            </head></html>
        """.trimIndent()

        assertThat(parseOpenGraph(html, "https://example.com/news/item")!!.imageUrl)
            .isEqualTo("https://example.com/images/hero.jpg")

        val protocolRelative = html.replace("/images/hero.jpg", "//cdn.example.com/hero.jpg")
        assertThat(parseOpenGraph(protocolRelative, "https://example.com/news/item")!!.imageUrl)
            .isEqualTo("https://cdn.example.com/hero.jpg")
    }

    @Test
    fun `a data-uri image is refused but the title still previews`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Titled">
            <meta property="og:image" content="data:image/png;base64,iVBORw0KGgo=">
            </head></html>
        """.trimIndent()

        val preview = parseOpenGraph(html, "https://example.com/page")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo("Titled")
        assertThat(preview.imageUrl).isNull()
    }

    @Test
    fun `entities decode in titles and urls`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Fish &amp; Chips &#8212; caf&#233;">
            <meta property="og:image" content="https://example.com/pic.jpg?a=1&amp;b=2">
            </head></html>
        """.trimIndent()

        val preview = parseOpenGraph(html, "https://example.com/page")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo("Fish & Chips — café")
        assertThat(preview.imageUrl).isEqualTo("https://example.com/pic.jpg?a=1&b=2")
    }

    @Test
    fun `ampersand decodes exactly once`() {
        assertThat("Fish &amp;lt; Chips".decodeHtmlEntities()).isEqualTo("Fish &lt; Chips")
    }

    @Test
    fun `an overlong title is capped`() {
        val longTitle = "x".repeat(600)
        val html = "<html><head><meta property=\"og:title\" content=\"$longTitle\"></head></html>"

        val preview = parseOpenGraph(html, "https://example.com/page")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).hasLength(500)
    }

    @Test
    fun `the first og title wins when a page repeats it`() {
        val html = """
            <html><head>
            <meta property="og:title" content="First">
            <meta property="og:title" content="Second">
            </head></html>
        """.trimIndent()

        assertThat(parseOpenGraph(html, "https://example.com/")!!.title).isEqualTo("First")
    }

    @Test
    fun `charset comes from the content type when present`() {
        assertThat(charsetFrom("text/html; charset=UTF-8")).isEqualTo(Charsets.UTF_8)
        assertThat(charsetFrom("text/html; charset=ISO-8859-1")).isEqualTo(Charsets.ISO_8859_1)
        assertThat(charsetFrom("text/html")).isNull()
        assertThat(charsetFrom("text/html; charset=not-a-charset")).isNull()
    }
}
