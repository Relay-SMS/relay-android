package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.RelayText
import java.util.Locale

/**
 * Grouped-list section of the Relay design system: a 13sp/600 uppercase header in the
 * secondary colour, followed by the section's rows. The screen owns the page background;
 * rows themselves sit on the canvas.
 */
@Composable
fun RelaySection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = RelayText.SectionHeader,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surface),
        ) {
            content()
        }
    }
}

/**
 * One 50dp row of a grouped list: optional leading glyph (22dp, blue, supplied by the
 * caller), a 16sp/400 title with an optional 13sp secondary subtitle, and an optional
 * trailing slot (chevron, switch, value). Pass [onClick] to make the whole row tappable;
 * without it the row is static. Pass [titleColor] to tint the title — the danger-zone rows
 * use the error red for it.
 */
@Composable
fun RelayRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    val rowModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingIcon?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = RelayText.MessageBody,
                color = titleColor ?: cs.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = RelayText.Timestamp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
