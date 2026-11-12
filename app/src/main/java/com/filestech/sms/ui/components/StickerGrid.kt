package com.filestech.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filestech.sms.R

/**
 * Sticker tab of the new picker (round 13): the same Twemoji graphics as
 * the emoji grid, but the expressive subset ([STICKER_EMOJIS]) rendered
 * sticker-sized. Tapping one calls [onStickerSelected] with the sticker's
 * emoji Unicode string.
 *
 * Live search: while [query] is non-blank the grid filters to matching
 * stickers ([emojiMatchesQuery]), the same as the emoji tab. An empty
 * result shows the shared "No results" line.
 *
 * Sending: a sticker goes out as an MMS image attachment. Use
 * [stickerAssetUri] for the bundled graphic's location and
 * [stickerSendUri] for a `content://` URI the MMS send path can read —
 * the `file:///android_asset/` URI alone is not readable by
 * `ContentResolver.openInputStream`.
 */
@Composable
fun StickerGrid(
    modifier: Modifier = Modifier,
    query: String = "",
    onStickerSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = rememberTwemojiImageLoader()
    val trimmedQuery = query.trim()
    val stickers = remember(trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            STICKER_EMOJIS
        } else {
            STICKER_EMOJIS.filter { emojiMatchesQuery(it, trimmedQuery) }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier,
    ) {
        if (stickers.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.padding(top = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.thread_search_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(stickers, key = { it }) { emoji ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onStickerSelected(emoji) }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(twemojiSvgAssetUri(emoji))
                        .size(288)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = emoji,
                    modifier = Modifier.size(80.dp),
                )
            }
        }
    }
}
