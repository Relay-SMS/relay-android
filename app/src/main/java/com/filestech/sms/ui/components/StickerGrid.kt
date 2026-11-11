package com.filestech.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Sticker tab of the new picker (round 13): the same Twemoji graphics as
 * the emoji grid, but the expressive subset ([STICKER_EMOJIS]) rendered
 * sticker-sized. Tapping one calls [onStickerSelected] with the sticker's
 * emoji Unicode string.
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
    onStickerSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = rememberTwemojiImageLoader()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier,
    ) {
        items(STICKER_EMOJIS, key = { it }) { emoji ->
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
