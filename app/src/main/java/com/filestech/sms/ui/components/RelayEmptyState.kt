package com.filestech.sms.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.RelayText

/**
 * Shared empty state of the Relay design system: a centred glyph, one headline, an optional
 * body line, and an optional primary button. Screens pass their own copy — the geometry stays
 * identical everywhere. The glyph defaults to 48dp; screens with their own artwork pass
 * [iconSize] (round 18: Kristin's archive/clock/vault icons render at their designed
 * sizes, 76/67/80dp, like her mockups).
 */
@Composable
fun RelayEmptyState(
    icon: ImageVector,
    headline: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    iconSize: Dp = 48.dp,
) {
    RelayEmptyStateContent(
        painter = rememberVectorPainter(icon),
        headline = headline,
        body = body,
        modifier = modifier,
        buttonText = buttonText,
        onButtonClick = onButtonClick,
        iconSize = iconSize,
    )
}

/**
 * Round 17 — the same empty state for Kristin's own icons, which ship as
 * vector drawables (archive/clock/vault), not Material ImageVectors.
 * The drawable's black fill takes the same onSurfaceVariant tint, so
 * the glyph follows light/dark mode like every other empty state.
 */
@Composable
fun RelayEmptyState(
    @DrawableRes iconRes: Int,
    headline: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    iconSize: Dp = 48.dp,
) {
    RelayEmptyStateContent(
        painter = painterResource(iconRes),
        headline = headline,
        body = body,
        modifier = modifier,
        buttonText = buttonText,
        onButtonClick = onButtonClick,
        iconSize = iconSize,
    )
}

@Composable
private fun RelayEmptyStateContent(
    painter: Painter,
    headline: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    iconSize: Dp = 48.dp,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        // Round 20 — the gap below the icon is 32dp, like Kristin's
        // mockups (her Scheduled/Archived/Vault references all measure
        // ~32px from glyph to headline); the old shared 12dp read as
        // cramped under the big icons. The headline-to-body gap stays 12dp.
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = headline,
            style = RelayText.ConvoName,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = body,
                style = RelayText.Preview,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (buttonText != null && onButtonClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            RelayPrimaryButton(
                text = buttonText,
                onClick = onButtonClick,
            )
        }
    }
}
