package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.filestech.sms.data.linkpreview.LinkPreviewFetcher
import com.filestech.sms.domain.linkpreview.LinkPreview

/**
 * Round 17 — a link preview card for the first URL in a message body: the
 * page's Open Graph image, title and site, fetched on-device for display
 * only (see [LinkPreviewFetcher]). Renders nothing when the body has no
 * URL or the page yields no preview.
 *
 * The fetcher is built here, per bubble, the way the GIF grid builds its
 * own `KlipyClient` — the preview cache itself is shared inside the
 * fetcher, so a URL is fetched once per process no matter how many bubbles
 * show it. The card is display-only: tapping it opens the URL in the
 * browser; nothing is ever attached to a message.
 *
 * The caller ([MessageBubble]) skips this on FAILED messages: the card tap
 * would fight the tap-to-retry, the same reason linkification is off
 * there.
 */
@Composable
fun LinkPreviewCard(
    body: String,
    messageId: Long,
    modifier: Modifier = Modifier,
) {
    val url = remember(body, messageId) { firstPreviewUrl(body, messageId) }
    if (url == null) return
    val fetcher = remember { LinkPreviewFetcher() }
    // Cached results render on first composition — a re-entered thread
    // shows its cards immediately instead of popping them in after the
    // fetch returns (the pop-in grows the thread and shifts the view up
    // onto the card, and the pin effect's snap back is visible). While
    // uncached, a fixed-height placeholder holds the card's space so
    // the thread barely grows when it lands.
    val isCached = remember(url) { fetcher.isCached(url) }
    var loading by remember(url) { mutableStateOf(!isCached) }
    val preview by produceState<LinkPreview?>(
        initialValue = remember(url) { fetcher.cachedPreview(url) },
        url,
    ) {
        if (!isCached) {
            value = fetcher.preview(url)
            loading = false
        }
    }
    if (loading) {
        LinkPreviewPlaceholder(modifier = modifier)
        return
    }
    val loaded = preview ?: return
    LinkPreviewCardContent(preview = loaded, modifier = modifier)
}

/**
 * Loading skeleton for the card: same box, text-card height, so the
 * thread doesn't grow when the preview lands. Image cards are taller —
 * the pin effect covers the rest.
 */
@Composable
private fun LinkPreviewPlaceholder(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LINK_PREVIEW_CORNER_DP.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = LINK_PREVIEW_BELOW_GAP_DP.dp)
            .height(LINK_PREVIEW_PLACEHOLDER_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface),
    )
}

/**
 * The card itself, once a preview has loaded: the Open Graph image on top
 * (16:9 crop), then the title (or the description when there is no title)
 * and the site name (or the URL host). Its own surface background plus a
 * hairline outline, so it reads on both the blue outgoing bubble and the
 * grey incoming one, in either theme.
 */
@Composable
private fun LinkPreviewCardContent(
    preview: LinkPreview,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val host = remember(preview.url) { preview.url.hostOrNull() }
    val headline = preview.title ?: preview.description
    val shape = RoundedCornerShape(LINK_PREVIEW_CORNER_DP.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = LINK_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable { uriHandler.openUri(preview.url) },
    ) {
        val imageUrl = preview.imageUrl
        if (imageUrl != null) {
            LinkPreviewCachedImage(imageUrl = imageUrl)
        }
        Column(modifier = Modifier.padding(LINK_PREVIEW_TEXT_PADDING_DP.dp)) {
            if (headline != null) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = LINK_PREVIEW_HEADLINE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val domain = preview.siteName ?: host
            if (domain != null) {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The URL host for the card's domain line (`www.` stripped), or `null` when unparseable. */
internal fun String.hostOrNull(): String? =
    runCatching { java.net.URI(this).host?.removePrefix("www.") }
        .getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Round 33 — the preview image, from the on-disk cache
 * ([LinkPreviewImageCache]): a cached file renders on first
 * composition, so a re-entered thread shows its preview images
 * immediately instead of re-fetching them on every open (the
 * reload that made scrolling glitchy). The 16:9 box is always
 * reserved, so a first-time download never grows the card when it
 * lands.
 */
@Composable
private fun LinkPreviewCachedImage(imageUrl: String) {
    val context = LocalContext.current
    val cache = remember(context) {
        LinkPreviewImageCache(java.io.File(context.cacheDir, LINK_PREVIEW_CACHE_DIR))
    }
    val file by produceState<java.io.File?>(
        initialValue = remember(imageUrl) { cache.cachedFile(imageUrl) },
        imageUrl,
    ) {
        if (value == null) value = cache.fetch(imageUrl)
    }
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(LINK_PREVIEW_IMAGE_RATIO),
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                // Decorative: the title below carries the meaning.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val LINK_PREVIEW_CORNER_DP = 12
private const val LINK_PREVIEW_BELOW_GAP_DP = 8
private const val LINK_PREVIEW_TEXT_PADDING_DP = 12
private const val LINK_PREVIEW_HEADLINE_LINES = 2
private const val LINK_PREVIEW_IMAGE_RATIO = 16f / 9f
private const val LINK_PREVIEW_PLACEHOLDER_DP = 96

/** The preview-image cache directory, under `cacheDir` (the system may clear it). */
private const val LINK_PREVIEW_CACHE_DIR = "link_previews"
