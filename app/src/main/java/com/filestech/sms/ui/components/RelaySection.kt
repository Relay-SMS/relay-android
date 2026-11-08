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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.RelayText
import java.util.Locale

/**
 * Grouped-list section of the Relay design system: a 13sp/600 uppercase header in the
 * secondary colour, followed by the section's rows in a rounded card (16dp corners,
 * 16dp side margins) on the page canvas. The header sits 24dp below the previous card
 * and 8dp above its own, so two cards are 48dp apart including the label — the
 * comfortable rhythm of the grouped-list reference.
 *
 * The card lifts off the canvas in both schemes: white on the `#F5F5F5` light canvas,
 * `#2A2A2A` on the `#1B1B1B` dark canvas (the dark `surface` equals the canvas, so it
 * cannot be the card fill). Picked from the surface luminance so it follows the active
 * scheme, dark variants included.
 */
@Composable
fun RelaySection(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * Feedback round 5 — current Material 3 section header: `titleSmall`
     * (14sp/500) in sentence case instead of the 13sp/600 all-caps header.
     * Opt-in per section: the shared kit keeps the all-caps header by
     * default, so the other screens using it (About, Backup, Emergency
     * Setup, Safety Call Setup) are untouched — only Settings and the new
     * Help screen ask for the Material header.
     */
    materialHeader: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text = if (materialHeader) title else title.uppercase(Locale.ROOT),
            style = if (materialHeader) {
                MaterialTheme.typography.titleSmall
            } else {
                RelayText.SectionHeader
            },
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        RelayCard {
            content()
        }
    }
}

/**
 * One rounded grouped-list card, on its own (no section label): the identity card at
 * the top of Settings and the standalone About row at the bottom use it. Same 16dp
 * corners and theme-aware fill as [RelaySection]; the caller owns the side margins.
 */
@Composable
fun RelayCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(relayCardColor()),
    ) {
        content()
    }
}

/** Card fill for [RelayCard]: white in light, `#2A2A2A` in dark (see [RelaySection]). */
@Composable
private fun relayCardColor(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.surface.luminance() < 0.5f) cs.surfaceVariant else cs.surface
}

/**
 * 1px divider between two rows of a grouped card, inset 16dp on both sides so it runs
 * from the row text to just before the trailing chevron/switch instead of bleeding
 * edge to edge. Colour is the scheme `outlineVariant` (`#E5E5E5` light, `#3A3A3A`
 * dark).
 */
@Composable
fun RelayDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One 56dp row of a grouped list: optional leading glyph (22dp, supplied by the
 * caller), a 16sp/400 title with an optional 13sp secondary subtitle, and an optional
 * trailing slot (chevron, switch, value). Pass [onClick] to make the whole row tappable;
 * without it the row is static. Pass [titleColor] to tint the title — the danger-zone rows
 * use the error red for it. Pass [dividerAbove] for every row but the first of a card:
 * it draws a [RelayDivider] above the row so rows read as separate entries without a
 * full-bleed line. The first row of a card must leave it off — including when it is
 * only first because the rows above it are hidden (e.g. in a decoy session), so call
 * sites with conditional rows above compute it instead of hardcoding it. Pass
 * [subtitleTopPadding] to open a gap between the title and the subtitle; it defaults
 * to 0 so the other screens using this row (About, Backup, Emergency Setup, Safety
 * Call Setup) are untouched — only the Settings rows ask for the gap. Pass
 * [leadingIconTopAligned] to centre the leading glyph on the title line instead of
 * on the whole row: the icon and the title share an inner row, so on a two-line row
 * the icon sits next to the title (as in the grouped-list reference, where the icon
 * is 9dp above the row centre on a two-line row) while on a one-line row it stays
 * exactly where the centred layout put it. Only the Settings rows ask for it — the
 * other screens keep the centred icon.
 */
@Composable
fun RelayRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTopPadding: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
    dividerAbove: Boolean = false,
    leadingIconTopAligned: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val rowModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Column(modifier = Modifier.fillMaxWidth()) {
        if (dividerAbove) {
            RelayDivider()
        }
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingIconTopAligned) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        leadingIcon?.invoke()
                        Text(
                            text = title,
                            style = RelayText.MessageBody,
                            color = titleColor ?: cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = RelayText.Timestamp,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(
                                // The subtitle starts under the title text, past
                                // the 22dp glyph + 12dp gap (no indent when there
                                // is no glyph).
                                start = if (leadingIcon != null) 34.dp else 0.dp,
                                top = subtitleTopPadding,
                            ),
                        )
                    }
                }
            } else {
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
                            modifier = Modifier.padding(top = subtitleTopPadding),
                        )
                    }
                }
            }
            trailing?.invoke()
        }
    }
}
