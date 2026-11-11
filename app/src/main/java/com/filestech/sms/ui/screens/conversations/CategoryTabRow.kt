package com.filestech.sms.ui.screens.conversations

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.categories.MessageCategory

/**
 * Round 11 — the category tab row above the conversation list.
 *
 * One fully rounded pill per visible category (plus All), horizontally
 * scrollable. A pill is ALWAYS a full pill: the corner radius stays 24dp
 * when selected — it never morphs squarer. Only the colours animate
 * (selected = `primaryContainer`, unselected = `surfaceContainerHigh`).
 *
 * Tap selects the category (tapping the selected pill again, or All,
 * clears the filter). Long-press toggles mute for that category; All
 * cannot be muted, so long-press on All does nothing. A muted category
 * shows a small muted-bell icon so the state is visible.
 *
 * The unread count shows only when it is > 0. Tabs for empty categories
 * never reach this row — the ViewModel only exposes categories with at
 * least one conversation (All always shows).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryTabRow(
    tabs: List<CategoryTab>,
    selectedCategory: MessageCategory?,
    onSelect: (MessageCategory?) -> Unit,
    onToggleMute: (MessageCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs, key = { it.category?.name ?: "All" }) { tab ->
            CategoryPill(
                tab = tab,
                selected = tab.category == selectedCategory,
                onSelect = { onSelect(tab.category) },
                onToggleMute = { tab.category?.let(onToggleMute) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryPill(
    tab: CategoryTab,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        label = "categoryPillContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) cs.onPrimaryContainer else cs.onSurface,
        label = "categoryPillContent",
    )
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onToggleMute,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = categoryLabel(tab.category),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
            if (tab.unreadCount > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tab.unreadCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            if (tab.muted) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** The label for a tab: All for `null`, the category name otherwise. Shared with Settings. */
@Composable
fun categoryLabel(category: MessageCategory?): String = stringResource(
    when (category) {
        null -> R.string.category_all
        MessageCategory.PERSONAL -> R.string.category_personal
        MessageCategory.OTP -> R.string.category_otp
        MessageCategory.PROMOTIONAL -> R.string.category_promotional
        MessageCategory.TRANSACTIONS -> R.string.category_transactions
        MessageCategory.OTHER -> R.string.category_other
    },
)
