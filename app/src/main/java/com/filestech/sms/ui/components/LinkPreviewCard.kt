package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
    val preview by produceState<LinkPreview?>(initialValue = null, url) {
        value = fetcher.preview(url)
    }
    val loaded = preview ?: return
    LinkPreviewCardContent(preview = loaded, modifier = modifier)
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
        if (preview.imageUrl != null) {
            AsyncImage(
                model = preview.imageUrl,
                // Decorative: the title below carries the meaning.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(LINK_PREVIEW_IMAGE_RATIO),
            )
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

private const val LINK_PREVIEW_CORNER_DP = 12
private const val LINK_PREVIEW_BELOW_GAP_DP = 8
private const val LINK_PREVIEW_TEXT_PADDING_DP = 12
private const val LINK_PREVIEW_HEADLINE_LINES = 2
private const val LINK_PREVIEW_IMAGE_RATIO = 16f / 9f
