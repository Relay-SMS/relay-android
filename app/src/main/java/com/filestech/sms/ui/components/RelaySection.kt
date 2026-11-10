package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Grouped-list section of the Relay design system: a section header followed
 * by the section's rows in a rounded card (16dp corners, 16dp side margins)
 * on the page canvas. The header sits 24dp below the previous card and 8dp
 * above its own, so two cards are 48dp apart including the label — the
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = RelayText.SectionHeader,
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
 * 1px divider between two rows of a grouped card. Feedback round 8 — the
 * system Settings divider runs (nearly) edge to edge inside the card, not
 * inset 16dp: measured on the reference screenshot the line spans the card
 * minus ~2dp, so it reads as a full-bleed hairline between items.
 *
 * Feedback round 9 — the system dividers read softer than a solid line,
 * with rounded ends (see the Lock-display screenshot): the line is the
 * official M3 divider token — `outlineVariant`, 1dp, full-width
 * (m3.material.io/components/divider/specs) — drawn at 50% alpha with
 * fully rounded ends, so it sits quieter against the card. The rounded
 * ends and reduced alpha are the system look, not the spec: the spec
 * itself has no corner radius or alpha for dividers.
 *
 * Feedback round 9 fix — the divider was full-width (`fillMaxWidth`
 * with no inset), so the rounded ends sat flush against the card edge
 * and were invisible: it read as a plain straight line. It is now
 * inset 16dp on each side (matching the row content padding), so the
 * rounded caps are visible against the card. Feedback round 9 — the
 * alpha drops 0.5 → 0.3 so the line blends with the card like the
 * system dividers, which read as barely-there rather than a drawn line.
 */
@Composable
fun RelayDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    )
}

/**
 * One row of a grouped list: optional leading glyph (24dp, supplied by the
 * caller), a 16sp/400 title with an optional 14sp secondary subtitle, and an optional
 * trailing slot (chevron, switch, value). Height follows the M3 list-item spec:
 * 56dp for a one-line row, 72dp for a two-line row. Pass [onClick] to make the whole row tappable;
 * without it the row is static. Pass [titleColor] to tint the title — the danger-zone rows
 * use the error red for it. Pass [dividerAbove] for every row but the first of a card:
 * it draws a [RelayDivider] above the row so rows read as separate entries without a
 * full-bleed line. The first row of a card must leave it off — including when it is
 * only first because the rows above it are hidden (e.g. in a decoy session), so call
 * sites with conditional rows above compute it instead of hardcoding it. Pass
 * [subtitleTopPadding] to open a gap between the title and the subtitle; it defaults
 * to 0 so the other screens using this row (Backup, Emergency Setup, Safety Call
 * Setup) are untouched — only the Settings and About rows ask for the gap. Pass
 * [leadingIconTopAligned] to centre the leading glyph on the title line instead of
 * on the whole row: the icon and the title share an inner row, so on a two-line row
 * the icon sits next to the title (as in the grouped-list reference, where the icon
 * is 9dp above the row centre on a two-line row) while on a one-line row it stays
 * exactly where the centred layout put it. Only the Settings and About rows ask for
 * it — the other screens keep the centred icon.
 */
@Composable
fun RelayRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTopPadding: Dp = 0.dp,
    // Feedback round 9 — vertical breathing room inside the row. The
    // default 8dp fits the dense Settings rows; the About page (long
    // multi-line descriptions) passes 12dp so its rows don't read as
    // scrunched.
    verticalPadding: Dp = 8.dp,
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
                // Feedback round 6 — M3 list-item heights: 56dp one-line, 72dp
                // two-line (`ListTokens.OneLineContainerHeight` /
                // `TwoLineContainerHeight`). The single 56dp minimum made two-line
                // rows too tight — title + subtitle + 2dp gap filled the row with
                // only the 8dp vertical padding to spare.
                .heightIn(min = if (subtitle != null) 72.dp else 56.dp)
                .padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (leadingIconTopAligned) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                            // Feedback round 6 — M3 supporting text is bodyMedium
                            // (14sp/20), not the 13sp/16 timestamp style: the
                            // subtitle was cramped under a 16sp title.
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(
                                // The subtitle starts under the title text, past
                                // the 24dp glyph + 16dp gap (no indent when there
                                // is no glyph).
                                start = if (leadingIcon != null) 40.dp else 0.dp,
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
                            // Feedback round 6 — M3 supporting text is bodyMedium
                            // (14sp/20), not the 13sp/16 timestamp style.
                            style = MaterialTheme.typography.bodyMedium,
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
