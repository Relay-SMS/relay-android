package com.filestech.sms.domain.linkpreview

/**
 * Round 17 — a link preview for a URL in a message body: the page's Open
 * Graph title, description and image, fetched on-device for display only.
 * The image is never sent as an attachment — it is loaded into the thread
 * card locally, the way a browser shows a link preview.
 *
 * Every field but [url] is nullable: pages without Open Graph tags (or
 * without a reachable image) still get a card when at least a title or a
 * description was found. A page with nothing usable yields no preview at
 * all ([LinkPreviewRepository.preview] returns `null`).
 */
data class LinkPreview(
    /** The page URL the preview was fetched for. */
    val url: String,
    /** `og:title` (or the `<title>` element, or `twitter:title`). */
    val title: String?,
    /** `og:description` (or `twitter:description`). */
    val description: String?,
    /** `og:image`, resolved to an absolute http(s) URL. */
    val imageUrl: String?,
    /** `og:site_name` — the card falls back to the URL host when absent. */
    val siteName: String?,
)

/**
 * Round 17 — fetches [LinkPreview]s for URLs found in message bodies. The
 * only implementation is
 * [com.filestech.sms.data.linkpreview.LinkPreviewFetcher], which does a
 * plain HTTP GET and reads the page's Open Graph tags.
 *
 * Fail-soft: any failure (no network, non-HTML page, no Open Graph tags,
 * timeout) is a `null` preview, never a throw — the message simply shows
 * no card.
 */
interface LinkPreviewRepository {

    /**
     * The preview for [url], or `null` when none can be built. Only
     * `http`/`https` URLs are fetched; anything else returns `null`
     * without a network attempt.
     */
    suspend fun preview(url: String): LinkPreview?
}
