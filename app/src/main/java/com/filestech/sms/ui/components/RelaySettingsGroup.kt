package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Grouped settings primitives in the M3 Expressive grouped-list format:
 * a section's rows share one tonal surface, split by thin gaps, and only
 * the group's outer corners are fully rounded. Each row carries its icon
 * on a round tonal badge, a title, an optional subtitle, and an optional
 * trailing control. Section labels use the primary colour; hints sit under
 * a group in the same inset.
 *
 * Screens keep their normal top bar with the title in it — there is no
 * large in-content headline.
 */

/**
 * One segment of a grouped list. [index] and [count] place it in its
 * group: the outer corners (top of the first, bottom of the last) are
 * fully rounded and the inner corners between segments are small.
 * Content that is itself clickable gets its ripple clipped to the
 * segment's shape.
 */
@Composable
fun RelayGroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = if (index < count - 1) 2.dp else 0.dp)
            .clip(relaySegmentShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                when {
                    onClick == null -> Modifier
                    onLongClick == null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                    )
                },
            ),
    ) {
        content()
    }
}

/** Large corners at the ends of a group, small ones between its items. */
fun relaySegmentShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

internal data class RelayGroupEntry(
    val onClick: (() -> Unit)?,
    val content: @Composable () -> Unit,
)

/**
 * Collects the items of one group. Each [item] call adds one segment;
 * items guarded by an `if` only register when the condition holds, so
 * the corner rounding always follows the rows actually shown.
 */
class RelayGroupScope {
    internal val items = mutableListOf<RelayGroupEntry>()

    fun item(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
        items.add(RelayGroupEntry(onClick, content))
    }
}

/**
 * One group of settings rows. The [content] registers each row with
 * [RelayGroupScope.item]; the group renders them as [RelayGroupItem]
 * segments with the right outer and inner corners. Use an `if` guard
 * around an `item` call for a row that only shows sometimes — it is
 * left out of the count entirely, so the visible rows keep the right
 * shape.
 */
@Composable
fun RelaySettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable RelayGroupScope.() -> Unit,
) {
    val scope = RelayGroupScope()
    scope.content()
    Column(modifier = modifier) {
        val count = scope.items.size
        scope.items.forEachIndexed { index, entry ->
            RelayGroupItem(index = index, count = count, onClick = entry.onClick) {
                entry.content()
            }
        }
    }
}

/**
 * One settings entry: the icon on a round tonal badge, a title, an
 * optional line under it, and an optional control at the end. Pass
 * [chevron] for a navigation row (the trailing chevron); pass a custom
 * [trailing] for anything else. Pass [onClick] to make the whole row
 * tappable on its own (the dialog rows use it — their dialog state
 * lives here, so the group item carries no click); without it the row
 * is plain layout and the caller (usually a [RelayGroupItem]) owns the
 * tap. Pass [textOnClick] to make only the badge-and-text area
 * tappable (the info rows use it to open their popup while the switch
 * keeps its own tap).
 */
@Composable
fun RelaySettingRow(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    textOnClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .then(if (textOnClick != null) Modifier.clickable(onClick = textOnClick) else Modifier),
        ) {
            if (icon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(cs.secondaryContainer),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = cs.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(
                        start = if (icon != null) 16.dp else 0.dp,
                        end = if (trailing != null || chevron) 16.dp else 0.dp,
                    ),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor ?: cs.onSurface)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        if (chevron) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        trailing?.invoke()
    }
}

/**
 * A [RelaySettingRow] that carries a switch, the whole row being the
 * control: the switch itself takes no click, so a tap anywhere on the
 * line flips it and a screen reader announces one thing rather than
 * two.
 *
 * When [infoText] is given, the row is split the way the system
 * Settings does it: the badge-and-text area opens an info popup (with
 * a chevron before the switch), and only the switch itself toggles.
 * The switch keeps the expressive thumb check icon when on.
 */
@Composable
fun RelaySwitchRow(
    icon: ImageVector?,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    infoText: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    var infoOpen by remember { mutableStateOf(false) }
    if (infoText == null) {
        RelaySettingRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    thumbContent = if (checked) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            },
        )
    } else {
        RelaySettingRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            modifier = modifier,
            textOnClick = { infoOpen = true },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { infoOpen = true }) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = stringResource(R.string.settings_row_info),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 24.dp)
                            .background(cs.outlineVariant),
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                        thumbContent = if (checked) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }
    if (infoOpen && infoText != null) {
        RelayDialog(
            onDismissRequest = { infoOpen = false },
            title = title,
            content = {
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_close),
                    onClick = { infoOpen = false },
                )
            },
        )
    }
}

/** A group's title, lined up with the content of the segments under it. */
@Composable
fun RelaySectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** A line of explanation under a group, lined up the same way. */
@Composable
fun RelayHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}
