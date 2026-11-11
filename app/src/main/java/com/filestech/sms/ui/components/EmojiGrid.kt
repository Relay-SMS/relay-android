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
 * Emoji tab of the new picker (round 13): a single scrollable grid of the
 * curated Twemoji set ([EMOJI_GRID_EMOJIS], most common first), rendered
 * from the bundled SVGs so every cell stays crisp. No categories — one
 * grid, most-used emojis first, is enough for the picker's fixed-height
 * panel; tapping an emoji calls [onEmojiSelected] with its Unicode string,
 * which is what gets sent in the text.
 *
 * This is NOT the reaction picker ([EmojiReactionPickerSheet]) — that sheet
 * reacts to a message, this grid inserts an emoji into the composer.
 */
@Composable
fun EmojiGrid(
    modifier: Modifier = Modifier,
    onEmojiSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = rememberTwemojiImageLoader()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        modifier = modifier,
    ) {
        items(EMOJI_GRID_EMOJIS, key = { it }) { emoji ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onEmojiSelected(emoji) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(twemojiSvgAssetUri(emoji))
                        .size(144)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = emoji,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
