package com.filestech.sms.domain.gif

import android.net.Uri
import com.filestech.sms.core.result.Outcome

/**
 * Round 13 — the GIF provider behind the picker.
 *
 * The picker ([GifGrid]) talks only to this interface, never to Klipy directly:
 * Klipy is the only implementation ([com.filestech.sms.data.klipy.KlipyClient]),
 * and swapping it for another provider later means writing a new implementation,
 * not rewriting the picker. Kept small on purpose — one interface, one result
 * type — not a framework.
 *
 * Search is submit-only by contract: the picker calls [search] when the user
 * submits a search (IME search action / enter key), never on every keystroke.
 * If live search is ever added, it must be debounced (~500 ms after the user
 * stops typing) before calling [search] — the test-key rate limit is shared by
 * every user of the app.
 *
 * Every method is fail-soft: a missing key, a rate limit, or a network failure
 * is an [Outcome.Failure] (or an empty list), never a throw — the grid shows
 * the "unavailable" message. There is no cached fallback: Klipy does not permit
 * a partner-operated media cache, so a failed request shows the message and
 * nothing else. One provider per grid, too — Klipy content is never mixed with
 * another provider's content in the same grid (Klipy rule).
 */
interface GifProvider {

    /**
     * GIFs matching [query] — the exact terms the user submitted, verbatim.
     * A blank query returns an empty list; callers show [trending] instead.
     */
    suspend fun search(query: String): Outcome<List<GifItem>>

    /** Trending GIFs. */
    suspend fun trending(): Outcome<List<GifItem>>

    /**
     * Downloads [item] to a local file and returns its `content://` URI, ready
     * to attach as an MMS attachment through the existing attachment path.
     */
    suspend fun download(item: GifItem): Outcome<Uri>
}

/**
 * One GIF, provider-neutral: a grid thumbnail and the file to download on tap.
 * The provider maps its own API model onto this; the picker never sees the
 * provider's types or its media URLs beyond these fields.
 *
 * Round 17 — [extraSmallUrl] is the smallest rendition the provider offers
 * (Klipy's `xs` tier, ~90 px tall): the download falls back to it when the
 * MEDIUM and SMALL renditions both exceed the carrier MMS cap. Blank when
 * the provider has no smaller rendition.
 */
data class GifItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val width: Int,
    val height: Int,
    val extraSmallUrl: String = "",
)
