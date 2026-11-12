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

    // Round 18 — oEmbed (YouTube) and X post shaping. The JSON and the og
    // tags below are the real responses captured live with curl 2026-11-12.

    private val youTubeOEmbed = """
        {"title":"I need Junk Drawers: Onefinity ep 9","author_name":"jimmydiresta",
        "author_url":"https://www.youtube.com/@jimmydiresta","type":"video",
        "provider_name":"YouTube","provider_url":"https://www.youtube.com/",
        "thumbnail_height":360,"thumbnail_width":480,
        "thumbnail_url":"https://i.ytimg.com/vi/8di8glAj0S8/hqdefault.jpg"}
    """.trimIndent()

    @Test
    fun `oEmbed parses a YouTube video into title, channel and thumbnail`() {
        val preview = parseOEmbed(youTubeOEmbed, "https://www.youtube.com/watch?v=8di8glAj0S8")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo("I need Junk Drawers: Onefinity ep 9")
        assertThat(preview.siteName).isEqualTo("jimmydiresta")
        assertThat(preview.imageUrl).isEqualTo("https://i.ytimg.com/vi/8di8glAj0S8/hqdefault.jpg")
        assertThat(preview.description).isNull()
    }

    @Test
    fun `oEmbed unescapes surrogate-pair emoji in a Shorts title`() {
        val shortsJson = """
            {"title":"\uD83D\uDC40\uD83C\uDFDB\uFE0FLEAKED Church Plans\u2026\uD83D\uDC40\uD83C\uDFDB\uFE0F",
            "author_name":"RISE ZION","type":"video",
            "thumbnail_url":"https://i.ytimg.com/vi/zJNyt1GJVjg/hq2.jpg"}
        """.trimIndent()

        val preview = parseOEmbed(shortsJson, "https://youtube.com/shorts/zJNyt1GJVjg")

        assertThat(preview).isNotNull()
        assertThat(preview!!.title).isEqualTo(
            // 👀 🌻️ … — written as escapes so the VS16 is unambiguous.
            "\uD83D\uDC40\uD83C\uDFDB\uFE0FLEAKED Church Plans\u2026\uD83D\uDC40\uD83C\uDFDB\uFE0F",
        )
        assertThat(preview.siteName).isEqualTo("RISE ZION")
        assertThat(preview.imageUrl).isEqualTo("https://i.ytimg.com/vi/zJNyt1GJVjg/hq2.jpg")
    }

    @Test
    fun `oEmbed with no title and no thumbnail yields no preview`() {
        assertThat(
            parseOEmbed(
                """{"type":"video","provider_name":"YouTube"}""",
                "https://www.youtube.com/watch?v=x",
            ),
        ).isNull()
        assertThat(
            parseOEmbed("not json at all", "https://www.youtube.com/watch?v=x"),
        ).isNull()
    }

    private val xPostLike = """
        <html><head>
        <meta property="og:site_name" content="X (formerly Twitter)"/>
        <meta property="og:url" content="https://x.com/Interior/status/507185938620219395"/>
        <meta property="og:type" content="article"/>
        <meta property="og:title" content="U.S. Department of the Interior (@Interior) on X"/>
        <meta property="og:description" content="Happy 50th anniversary to the Wilderness Act! Here&#x27;s a great wilderness photo from @YosemiteNPS. #Wilderness50"/>
        <meta property="og:image" content="https://pbs.twimg.com/media/Bwni7DgCQAABVQh?format=webp&amp;name=large"/>
        </head></html>
    """.trimIndent()

    @Test
    fun `an X post preview shows the post text, not the profile name`() {
        val parsed = parseOpenGraph(xPostLike, "https://x.com/Interior/status/507185938620219395")!!
        val preview = shapeXPost(parsed)

        assertThat(preview.title).isEqualTo(
            "Happy 50th anniversary to the Wilderness Act! Here's a great wilderness photo " +
                "from @YosemiteNPS. #Wilderness50",
        )
        assertThat(preview.siteName).isEqualTo("U.S. Department of the Interior (@Interior)")
        assertThat(preview.imageUrl).isEqualTo(
            "https://pbs.twimg.com/media/Bwni7DgCQAABVQh?format=webp&name=large",
        )
        assertThat(preview.description).isNull()
    }

    @Test
    fun `an X text-only post drops the avatar image`() {
        val html = xPostLike.replace(
            "https://pbs.twimg.com/media/Bwni7DgCQAABVQh?format=webp&amp;name=large",
            "https://pbs.twimg.com/profile_images/1234567/avatar.jpg",
        )
        val parsed = parseOpenGraph(html, "https://x.com/Interior/status/507185938620219395")!!
        val preview = shapeXPost(parsed)

        assertThat(preview.title).startsWith("Happy 50th anniversary")
        assertThat(preview.imageUrl).isNull()
    }

    @Test
    fun `JSON string escapes decode, including surrogate pairs`() {
        assertThat("""A \uD83D\uDC40 B""".unescapeJsonString()).isEqualTo("A \uD83D\uDC40 B")
        assertThat("""wait\u2026""".unescapeJsonString()).isEqualTo("wait\u2026")
        assertThat("""say \"hi\"""".unescapeJsonString()).isEqualTo("say \"hi\"")
        assertThat("""back\\slash""".unescapeJsonString()).isEqualTo("back\\slash")
        assertThat("""lone \uD83D here""".unescapeJsonString()).isEqualTo("lone ? here")
        assertThat("no escapes here".unescapeJsonString()).isEqualTo("no escapes here")
    }
}
