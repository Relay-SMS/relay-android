package com.filestech.sms.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.R

/**
 * Round 12 — the quick-reaction bar on a bubble tap.
 *
 * One horizontal row of the user's most recently used reaction emojis
 * (falling back to [DEFAULT_QUICK_REACTIONS] when there are none yet),
 * plus a "+" at the right end that opens the full emoji picker. Tapping
 * an emoji sends the reaction immediately — no confirmation. Same overlay
 * pattern as [BubbleMenu] (a [DropdownMenu] anchored to the bubble), so it
 * floats near the bubble and dismisses on an outside tap. It animates in
 * and out with a quick fade + slight scale (150ms).
 */
val DEFAULT_QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

/**
 * The emojis the bar shows: recents first, topped up with the defaults
 * (skipping any already present), capped at [RecentReactionsStore.MAX_RECENT_REACTIONS].
 */
fun quickReactionEmojis(recentEmojis: List<String>): List<String> =
    (recentEmojis + DEFAULT_QUICK_REACTIONS)
        .distinct()
        .take(com.filestech.sms.data.local.datastore.RecentReactionsStore.MAX_RECENT_REACTIONS)

@Composable
fun QuickReactionBar(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    recentEmojis: List<String>,
    onEmojiSelected: (String) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Round 12 — quick fade + slight scale on appear and dismiss (150ms,
    // same AnimatedVisibility idiom as the picker sheet). The DropdownMenu
    // stays `expanded` while composed — AnimatedVisibility keeps it alive
    // through the exit animation, then removes it.
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(QUICK_BAR_ANIM_MS)) +
            scaleIn(
                initialScale = QUICK_BAR_START_SCALE,
                animationSpec = tween(QUICK_BAR_ANIM_MS),
            ),
        exit = fadeOut(animationSpec = tween(QUICK_BAR_ANIM_MS)) +
            scaleOut(
                targetScale = QUICK_BAR_START_SCALE,
                animationSpec = tween(QUICK_BAR_ANIM_MS),
            ),
        label = "quickReactionBar",
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismissRequest,
            // Round 12 — full pill like the bubbles (24dp), not the 18dp menu.
            shape = RoundedCornerShape(24.dp),
            modifier = modifier,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickReactionEmojis(recentEmojis).forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                onEmojiSelected(emoji)
                                onDismissRequest()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
                // "+" — opens the full emoji picker for this message.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable {
                            onOpenPicker()
                            onDismissRequest()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.reaction_picker_other),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** Enter/exit animation length: quick and subtle, so the bar feels snappy. */
private const val QUICK_BAR_ANIM_MS = 150

/** Scale the bar starts (and ends) at — a slight grow/shrink, not a pop. */
private const val QUICK_BAR_START_SCALE = 0.92f
