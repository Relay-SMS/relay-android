package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayText
import kotlin.math.roundToInt

/**
 * Emoji tab of the new picker (round 13): a scrollable grid of the curated
 * Twemoji set ([EMOJI_SECTIONS]), rendered from the bundled SVGs so every
 * cell stays crisp. Emojis are grouped under titled sections (Smileys,
 * Hands, Hearts, …) in the main screen's section-header style
 * ([RelayText.SectionHeader]); tapping an emoji calls [onEmojiSelected] with
 * its Unicode string, which is what gets sent in the text.
 *
 * Live search: while [query] is non-blank the sections collapse into a flat
 * list of matches ([emojiMatchesQuery]) — typing filters on every keystroke,
 * no submit needed (the data is local, so there is no API cost). Long-press
 * on a hand or people emoji opens the skin-tone picker ([TonePickerPopup])
 * above the cell; tapping a variant inserts that variant.
 *
 * This is NOT the reaction picker ([EmojiReactionPickerSheet]) — that sheet
 * reacts to a message, this grid inserts an emoji into the composer.
 */
@Composable
fun EmojiGrid(
    modifier: Modifier = Modifier,
    query: String = "",
    onEmojiSelected: (String) -> Unit,
) {
    val imageLoader = rememberTwemojiImageLoader()
    var tonePickerFor by remember { mutableStateOf<String?>(null) }
    val trimmedQuery = query.trim()
    val searchResults = remember(trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            null
        } else {
            EMOJI_GRID_EMOJIS.filter { emojiMatchesQuery(it, trimmedQuery) }
        }
    }
    BoxWithConstraints(modifier = modifier) {
        // Round 14 — the section titles sit on the same vertical line as
        // the first emoji of each row (Kristin, 2026-11-11). The cells are
        // adaptive (48dp minimum) with a centred 36dp image, so the first
        // emoji's left edge is half the leftover cell width in from the
        // grid edge — computed here from the real width, not guessed, so
        // it holds on any screen width.
        val columns = maxOf(1, (maxWidth / EMOJI_CELL_MIN_DP.dp).toInt())
        val cellWidth = maxWidth / columns
        val headerStart = ((cellWidth - EMOJI_IMAGE_SIZE_DP.dp) / 2).coerceAtLeast(0.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = EMOJI_CELL_MIN_DP.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                searchResults == null -> {
                    EMOJI_SECTIONS.forEachIndexed { index, section ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "header-${section.titleRes}") {
                            Text(
                                text = stringResource(section.titleRes),
                                style = RelayText.SectionHeader,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = headerStart,
                                    end = 16.dp,
                                    top = if (index == 0) 8.dp else 16.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(section.emojis, key = { it }) { emoji ->
                            EmojiCell(
                                emoji = emoji,
                                imageLoader = imageLoader,
                                showTonePicker = tonePickerFor == emoji,
                                onLongPress = { tonePickerFor = emoji },
                                onTonePickerDismiss = { tonePickerFor = null },
                                onEmojiSelected = onEmojiSelected,
                            )
                        }
                    }
                }
                searchResults.isEmpty() -> {
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
                else -> {
                    items(searchResults, key = { it }) { emoji ->
                        EmojiCell(
                            emoji = emoji,
                            imageLoader = imageLoader,
                            showTonePicker = tonePickerFor == emoji,
                            onLongPress = { tonePickerFor = emoji },
                            onTonePickerDismiss = { tonePickerFor = null },
                            onEmojiSelected = onEmojiSelected,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One emoji cell. Tap inserts the emoji; long-press on a skin-tone-capable
 * emoji (hands, people) opens the tone picker above the cell. Long-press
 * elsewhere does nothing — no popup, no haptic.
 */
@Composable
private fun EmojiCell(
    emoji: String,
    imageLoader: coil.ImageLoader,
    showTonePicker: Boolean,
    onLongPress: () -> Unit,
    onTonePickerDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Last-known window position of this cell, for the tone popup's
    // horizontal clamp. A plain holder, not state: writing it on every
    // layout (scroll) must not recompose the cell.
    val position = remember { CellPosition() }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coords ->
                val windowX = coords.positionInWindow().x
                position.centerX = windowX + coords.size.width / 2f
            }
            // Round 15 — the clip sits BEFORE the clickable, so the tap
            // ripple follows the rounded shape instead of the square cell.
            .clip(RoundedCornerShape(EMOJI_CELL_CORNER_DP.dp))
            .combinedClickable(
                onClick = { onEmojiSelected(emoji) },
                onLongClick = {
                    if (supportsSkinTones(emoji)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                },
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TwemojiImage(emoji = emoji, imageLoader = imageLoader)
        if (showTonePicker) {
            TonePickerPopup(
                base = emoji,
                imageLoader = imageLoader,
                anchorCenterX = position.centerX,
                onToneSelected = { variant ->
                    onTonePickerDismiss()
                    onEmojiSelected(variant)
                },
                onDismissRequest = onTonePickerDismiss,
            )
        }
    }
}

/** Last-known horizontal centre of a cell, in window pixels. Not state. */
private class CellPosition {
    var centerX: Float = 0f
}

/**
 * The skin-tone picker: a floating rounded row of the base emoji plus its
 * five tone variants ([skinToneVariants]), anchored above the long-pressed
 * cell. The row has a FIXED size ([TONE_POPUP_WIDTH_DP] x
 * [TONE_POPUP_HEIGHT_DP]) so the offsets are exact: it floats
 * [TONE_POPUP_GAP_DP] above the cell, and its centre is clamped to the
 * screen so a cell at the grid's edge never pushes the row off-screen.
 */
@Composable
private fun TonePickerPopup(
    base: String,
    imageLoader: coil.ImageLoader,
    anchorCenterX: Float,
    onToneSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val popupOffset = with(density) {
        val halfWidthPx = (TONE_POPUP_WIDTH_DP / 2).dp.toPx()
        val marginPx = TONE_POPUP_SCREEN_MARGIN_DP.dp.toPx()
        val clampedCenter = anchorCenterX.coerceIn(
            halfWidthPx + marginPx,
            screenWidthPx - halfWidthPx - marginPx,
        )
        IntOffset(
            x = (clampedCenter - anchorCenterX).roundToInt(),
            y = -(TONE_POPUP_HEIGHT_DP + TONE_POPUP_GAP_DP).dp.roundToPx(),
        )
    }
    Popup(
        alignment = Alignment.TopCenter,
        offset = popupOffset,
        onDismissRequest = onDismissRequest,
    ) {
        Row(
            modifier = Modifier
                .width(TONE_POPUP_WIDTH_DP.dp)
                .height(TONE_POPUP_HEIGHT_DP.dp)
                .background(cs.surfaceContainerHigh, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            skinToneVariants(base).forEach { variant ->
                Box(
                    modifier = Modifier
                        .size(TONE_OPTION_SIZE_DP.dp)
                        // Round 15 — same rounded tap highlight as the grid cells.
                        .clip(RoundedCornerShape(EMOJI_CELL_CORNER_DP.dp))
                        .clickable { onToneSelected(variant) },
                    contentAlignment = Alignment.Center,
                ) {
                    TwemojiImage(emoji = variant, imageLoader = imageLoader)
                }
            }
        }
    }
}

/**
 * One Twemoji SVG at grid size. If it fails to load (e.g. the SVG decoder
 * is stripped in a release build), the emoji falls back to the system font
 * so the grid is never an empty wall of invisible cells.
 */
@Composable
private fun TwemojiImage(
    emoji: String,
    imageLoader: coil.ImageLoader,
) {
    val context = LocalContext.current
    var loadFailed by remember(emoji) { mutableStateOf(false) }
    if (loadFailed) {
        Text(text = emoji, fontSize = 28.sp)
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(twemojiSvgAssetUri(emoji))
                .size(144)
                .build(),
            imageLoader = imageLoader,
            contentDescription = emoji,
            onError = { loadFailed = true },
            modifier = Modifier.size(EMOJI_IMAGE_SIZE_DP.dp),
        )
    }
}

/** Grid cell minimum size — the adaptive column count derives from this. */
private const val EMOJI_CELL_MIN_DP = 48

/** The Twemoji image size inside each cell (centred, so the header inset is half the leftover cell width). */
private const val EMOJI_IMAGE_SIZE_DP = 36

/**
 * Tap-highlight corner radius for emoji cells and tone options (round 15) —
 * the ripple follows the clip, so the highlight is rounded, not square.
 * Matches the GIF tiles' 12dp.
 */
private const val EMOJI_CELL_CORNER_DP = 12

/** Tone popup: 6 options x 40dp + 16dp side padding. Fixed, for the clamp. */
private const val TONE_POPUP_WIDTH_DP = 256

/** Tone popup height: 40dp options + 16dp vertical padding. */
private const val TONE_POPUP_HEIGHT_DP = 56

/** Gap between the popup's bottom edge and the pressed cell's top edge. */
private const val TONE_POPUP_GAP_DP = 8

/** Minimum gap between the popup and the screen's left/right edges. */
private const val TONE_POPUP_SCREEN_MARGIN_DP = 8

/** One tone option cell, square. */
private const val TONE_OPTION_SIZE_DP = 40
