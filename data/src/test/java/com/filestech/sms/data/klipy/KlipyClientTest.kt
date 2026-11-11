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

    private val sampleBody = """
        {
          "result": true,
          "data": {
            "data": [
              {
                "url": "https://klipy.com/gifs/happy-cat",
                "title": "Happy Cat",
                "slug": "happy-cat",
                "file": {
                  "mp4": "https://static.klipy.com/ii/abc/1/happy-cat.mp4",
                  "gif": "https://static.klipy.com/ii/abc/1/happy-cat.gif",
                  "webp": "https://static.klipy.com/ii/abc/1/happy-cat.webp"
                },
                "file_meta": {
                  "mp4": {"width": 320, "height": 320},
                  "gif": {"width": 320, "height": 320},
                  "webp": {"width": 320, "height": 320}
                },
                "tags": ["cat", "happy"],
                "type": "gif"
              },
              {
                "url": "https://klipy.com/gifs/only-gif",
                "title": "",
                "slug": "only-gif",
                "file": {"gif": "https://static.klipy.com/ii/def/2/only-gif.gif"},
                "type": "gif"
              },
              {"slug": "no-file", "title": "No file"},
              {"slug": "blank-url", "title": "", "file": {"gif": ""}},
              {
                "url": "https://klipy.com/ads/something",
                "title": "An ad",
                "slug": "an-ad",
                "file": {"gif": "https://static.klipy.com/ii/ad/3/ad.gif"},
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

        assertThat(gifs).hasSize(2)
        assertThat(gifs[0]).isEqualTo(
            KlipyGif(
                id = "happy-cat",
                title = "Happy Cat",
                thumbnailUrl = "https://static.klipy.com/ii/abc/1/happy-cat.gif",
                downloadUrl = "https://static.klipy.com/ii/abc/1/happy-cat.gif",
                width = 320,
                height = 320,
            ),
        )
        assertThat(gifs[1].id).isEqualTo("only-gif")
        assertThat(gifs[1].thumbnailUrl).isEqualTo("https://static.klipy.com/ii/def/2/only-gif.gif")
        assertThat(gifs[1].downloadUrl).isEqualTo("https://static.klipy.com/ii/def/2/only-gif.gif")
        assertThat(gifs[1].width).isEqualTo(0)
        assertThat(gifs[1].height).isEqualTo(0)
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
        val client = KlipyClient(mockk<Context>(), Dispatchers.Unconfined)

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
        assertThat((first as Outcome.Success).value).hasSize(2)
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
        assertThat(items).hasSize(2)
        assertThat(items[0].id).isEqualTo("happy-cat")
        assertThat(items[0].thumbnailUrl).isEqualTo("https://static.klipy.com/ii/abc/1/happy-cat.gif")
        assertThat(items[0].downloadUrl).isEqualTo("https://static.klipy.com/ii/abc/1/happy-cat.gif")
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
