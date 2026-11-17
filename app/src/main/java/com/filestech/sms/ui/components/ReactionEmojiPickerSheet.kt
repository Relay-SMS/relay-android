package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Round 24 — the "+" reaction picker: the FULL emoji grid — all 487 emojis
 * in titled sections with a search at the top, like the desktop picker —
 * replacing the old 24-emoji quick-pick sheet (deleted) with its "Other
 * emoji" button and validate step.
 *
 * Tap an emoji to react IMMEDIATELY (no validate step); tapping the emoji
 * already applied to the message removes the reaction instead
 * ([toggleReaction] — the same toggle as the quick-reaction row and the
 * desktop picker). The grid is the shared [EmojiGrid], so search
 * ([emojiMatchesQuery]) and the long-press skin-tone picker behave exactly
 * like the composer's emoji tab, and the set stays the curated
 * [EMOJI_SECTIONS] (487 emojis — the round-15 exclusions stay out).
 *
 * [onPicked] receives the reaction to store, or `null` when the tap
 * removed the reaction. Back / drag down / outside tap → [onDismiss]
 * with no reaction change.
 */
@Composable
fun ReactionEmojiPickerSheet(
    currentReaction: String?,
    onPicked: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    // Round 24 — fully-rounded (48dp) top corners, like the old picker:
    // the emoji popup family reads as a pill. The search field manages
    // the IME itself (`imePadding` below), so the sheet only takes the
    // navigation-bars insets — the same setup as [ForwardMessageSheet].
    RelaySheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            RelaySearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.emoji_picker_search_emoji),
                onClear = { searchQuery = "" },
                clearContentDescription = stringResource(R.string.action_close),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            EmojiGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = REACTION_PICKER_GRID_MAX_HEIGHT_DP.dp),
                query = searchQuery,
                onEmojiSelected = { emoji ->
                    onPicked(toggleReaction(currentReaction, emoji))
                    onDismiss()
                },
            )
        }
    }
}

/** Round 24 — the grid's max height inside the sheet: ten 48dp rows. */
private const val REACTION_PICKER_GRID_MAX_HEIGHT_DP = 480
