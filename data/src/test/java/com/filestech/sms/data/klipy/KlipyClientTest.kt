package com.filestech.sms.data.klipy

import android.content.Context
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.gif.GifItem
import com.filestech.sms.domain.gif.GifProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 13 — the Klipy client without a network: response parsing, URL building,
 * and the fail-soft paths (no key yet, HTTP error, `result: false`). Every call
 * is fresh — there is no cache (Klipy does not permit a partner-operated media
 * cache).
 *
 * The live call itself ([KlipyClient.httpGet] against api.klipy.com) needs a real
 * key and a device — it is not covered here.
 */
class KlipyClientTest {

    // Round 14 — the REAL response shape, verified against the live API
    // 2026-11-11: `file` is tiers first (hd/md/sm/xs), then formats, each
    // rendition carrying its own URL and dimensions. The round-13 fixture had
    // this backwards (`file.gif` as a bare string), which is exactly why the
    // grid showed "No results" with a valid key — every item parsed to an
    // empty URL and was dropped.
    private val sampleBody = """
        {
          "result": true,
          "data": {
            "data": [
              {
                "id": 3480491069936577,
                "title": "Happy Cat",
                "slug": "happy-cat",
                "file": {
                  "hd": {"gif": {"url": "https://static.klipy.com/ii/abc/1/hd-happy-cat.gif", "width": 498, "height": 373, "size": 4286735}},
                  "md": {"gif": {"url": "https://static.klipy.com/ii/abc/1/md-happy-cat.gif", "width": 640, "height": 480, "size": 515511}},
                  "sm": {"gif": {"url": "https://static.klipy.com/ii/abc/1/sm-happy-cat.gif", "width": 220, "height": 165, "size": 51878}},
                  "xs": {"gif": {"url": "https://static.klipy.com/ii/abc/1/xs-happy-cat.gif", "width": 120, "height": 90, "size": 20705}}
                },
                "tags": ["cat", "happy"],
                "type": "gif"
              },
              {
                "title": "",
                "slug": "only-sm",
                "file": {
                  "sm": {"gif": {"url": "https://static.klipy.com/ii/def/2/sm-only.gif", "width": 220, "height": 165, "size": 51878}}
                },
                "type": "gif"
              },
              {
                "title": "HD only",
                "slug": "hd-only",
                "file": {
                  "hd": {"gif": {"url": "https://static.klipy.com/ii/ghi/3/hd-only.gif", "width": 498, "height": 373, "size": 4248588}}
                },
                "type": "gif"
              },
              {"slug": "no-file", "title": "No file"},
              {"slug": "blank-url", "title": "", "file": {"md": {"gif": {"url": ""}}}},
              {
                "title": "An ad",
                "slug": "an-ad",
                "file": {"md": {"gif": {"url": "https://static.klipy.com/ii/ad/3/ad.gif", "width": 640, "height": 480, "size": 100}}},
                "type": "ad"
              }
            ],
            "current_page": 1,
            "per_page": 24,
            "has_next": true
          }
        }
    """.trimIndent()

    @Test
    fun `parsing keeps usable gifs and skips the rest`() {
        val gifs = parseKlipyResponse(sampleBody)

        assertThat(gifs).hasSize(3)
        // Thumbnail = small gif, download = medium gif, dimensions from medium.
        // Round 17 — the extra-small URL rides along for the MMS cap fallback.
        assertThat(gifs[0]).isEqualTo(
            KlipyGif(
                id = "happy-cat",
                title = "Happy Cat",
                thumbnailUrl = "https://static.klipy.com/ii/abc/1/sm-happy-cat.gif",
                downloadUrl = "https://static.klipy.com/ii/abc/1/md-happy-cat.gif",
                width = 640,
                height = 480,
                extraSmallUrl = "https://static.klipy.com/ii/abc/1/xs-happy-cat.gif",
            ),
        )
        // One tier only: both URLs fall back to it.
        assertThat(gifs[1].id).isEqualTo("only-sm")
        assertThat(gifs[1].thumbnailUrl).isEqualTo("https://static.klipy.com/ii/def/2/sm-only.gif")
        assertThat(gifs[1].downloadUrl).isEqualTo("https://static.klipy.com/ii/def/2/sm-only.gif")
        assertThat(gifs[1].width).isEqualTo(220)
        assertThat(gifs[1].height).isEqualTo(165)
        // No `xs` tier on these two: the fallback URL is blank, not absent.
        assertThat(gifs[1].extraSmallUrl).isEmpty()
        assertThat(gifs[2].id).isEqualTo("hd-only")
        assertThat(gifs[2].thumbnailUrl).isEqualTo("https://static.klipy.com/ii/ghi/3/hd-only.gif")
        assertThat(gifs[2].downloadUrl).isEqualTo("https://static.klipy.com/ii/ghi/3/hd-only.gif")
        assertThat(gifs[2].width).isEqualTo(498)
        assertThat(gifs[2].height).isEqualTo(373)
    }

    @Test
    fun `ad objects are filtered out`() {
        val gifs = parseKlipyResponse(sampleBody)

        assertThat(gifs.map { it.id }).doesNotContain("an-ad")
    }

    @Test
    fun `parsing an empty data array gives an empty list`() {
        assertThat(parseKlipyResponse("""{"result": true, "data": {"data": []}}""")).isEmpty()
    }

    @Test
    fun `a result false body fails`() {
        runCatching { parseKlipyResponse("""{"result": false, "data": {"data": []}}""") }
            .onSuccess { throw AssertionError("expected a failure") }
            .onFailure { assertThat(it).isInstanceOf(IllegalStateException::class.java) }
    }

    @Test
    fun `search url encodes the query and names the single base url`() {
        val client = KlipyClient(mockk<Context>(), Dispatchers.Unconfined)

        val url = client.searchUrl("happy cat", limit = 25)

        assertThat(url).startsWith("https://api.klipy.com/api/v1/")
        assertThat(url).contains("/gifs/search")
        assertThat(url).contains("q=happy+cat")
        assertThat(url).contains("per_page=25")
        assertThat(url).contains("content_filter=high")
    }

    @Test
    fun `trending url hits the trending endpoint`() {
        val client = KlipyClient(mockk<Context>(), Dispatchers.Unconfined)

        val url = client.trendingUrl(limit = 25)

        assertThat(url).startsWith("https://api.klipy.com/api/v1/")
        assertThat(url).contains("/gifs/trending")
        assertThat(url).contains("per_page=25")
        assertThat(url).contains("content_filter=high")
    }

    @Test
    fun `placeholder key means no key yet`() {
        // Round 18 fix (2026-11-12) — this must NOT read the key baked into
        // the build: CI builds now ship a real key from the repository
        // secret, so a plain client reports hasApiKey == true there and
        // this test failed run #119. Pin the placeholder through the fake
        // instead, like the no-network test below does.
        val client = FakeKlipyClient(body = sampleBody, apiKey = "YOUR_KLIPY_API_KEY")

        assertThat(client.hasApiKey).isFalse()
    }

    @Test
    fun `no key means no network attempt, just an empty list`() = runTest {
        val client = FakeKlipyClient(body = sampleBody, apiKey = "YOUR_KLIPY_API_KEY")

        assertThat(client.search("cat")).isEqualTo(Outcome.Success(emptyList<GifItem>()))
        assertThat(client.trending()).isEqualTo(Outcome.Success(emptyList<GifItem>()))
        assertThat(client.calls).isEqualTo(0)
    }

    @Test
    fun `trending is fetched fresh every time, no cache`() = runTest {
        val client = FakeKlipyClient(body = sampleBody)

        val first = client.trending()
        val second = client.trending()

        assertThat(first).isEqualTo(second)
        assertThat((first as Outcome.Success).value).hasSize(3)
        assertThat(client.calls).isEqualTo(2)
    }

    @Test
    fun `a failed search fails`() = runTest {
        val client = FakeKlipyClient(body = null)

        assertThat(client.search("cat")).isEqualTo(Outcome.Failure(AppError.Network()))
    }

    @Test
    fun `a failed trending fetch fails`() = runTest {
        val client = FakeKlipyClient(body = null)

        assertThat(client.trending()).isEqualTo(Outcome.Failure(AppError.Network()))
    }

    @Test
    fun `a blank search never hits the network`() = runTest {
        val client = FakeKlipyClient(body = sampleBody)

        assertThat(client.search("  ")).isEqualTo(Outcome.Success(emptyList<GifItem>()))
        assertThat(client.calls).isEqualTo(0)
    }

    @Test
    fun `the grid talks to the provider interface, and every submit calls fresh`() = runTest {
        val provider: GifProvider = FakeKlipyClient(body = sampleBody)
        val fake = provider as FakeKlipyClient

        val first = provider.search("happy cat")
        val second = provider.search("happy cat")
        val other = provider.search("dog")
        val items = (first as Outcome.Success).value

        assertThat(first).isEqualTo(second)
        assertThat(items).hasSize(3)
        assertThat(items[0].id).isEqualTo("happy-cat")
        assertThat(items[0].thumbnailUrl).isEqualTo("https://static.klipy.com/ii/abc/1/sm-happy-cat.gif")
        assertThat(items[0].downloadUrl).isEqualTo("https://static.klipy.com/ii/abc/1/md-happy-cat.gif")
        assertThat(items[0].extraSmallUrl).isEqualTo("https://static.klipy.com/ii/abc/1/xs-happy-cat.gif")
        assertThat(other).isEqualTo(first)
        // No cache (Klipy does not permit one): a repeat search costs a call, like any other.
        assertThat(fake.calls).isEqualTo(3)
    }

    @Test
    fun `the sent query stays verbatim`() {
        val client = FakeKlipyClient(body = sampleBody)

        assertThat(client.searchUrl("Happy Cat", limit = 25)).contains("q=Happy+Cat")
    }

    /** A client with a stand-in key and a canned HTTP body, so no call ever leaves the JVM. */
    private class FakeKlipyClient(
        var body: String?,
        override val apiKey: String = "test-key",
    ) : KlipyClient(mockk<Context>(), Dispatchers.Unconfined) {
        var calls: Int = 0

        override fun httpGet(url: String): String? {
            calls++
            return body
        }
    }
}
