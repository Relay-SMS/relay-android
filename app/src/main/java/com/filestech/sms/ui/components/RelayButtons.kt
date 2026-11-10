package com.filestech.sms.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText

/**
 * Primary action button of the Relay design system: full-width, brand blue with white
 * 16sp regular text, fully rounded (pill), 14dp vertical padding. Pressed state darkens
 * to [RelayColors.BluePressed] and scales to 0.99.
 *
 * Feedback round 7 — large full-width buttons are pill-shaped with a non-bold
 * font, app-wide: the shape went from 10dp corners to a pill and the label
 * from [RelayText.Button] (SemiBold) to [RelayText.ButtonText] (regular).
 */
@Composable
fun RelayPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        label = "relayPrimaryPressScale",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .scale(pressScale),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) RelayColors.BluePressed else RelayColors.Blue,
            contentColor = Color.White,
            disabledContainerColor = RelayColors.Blue.copy(alpha = 0.38f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(text = text, style = RelayText.ButtonText)
    }
}

/**
 * Text action button: blue 16sp text with no background and a 44dp minimum hit area.
 * Pass [confirm] = true for the confirming action of a dialog, which renders 600 weight
 * instead of 400.
 *
 * Phase 8 — the blue comes from the colour scheme, not the fixed brand blue: the fixed
 * `#1E4FD8` was 2.60:1 on the dark canvas. The light scheme keeps `#1E4FD8`.
 */
@Composable
fun RelayTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    confirm: Boolean = false,
) {
    RelayTextButtonContent(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        color = MaterialTheme.colorScheme.primary,
        textStyle = if (confirm) RelayText.Button else RelayText.ButtonText,
    )
}

/**
 * Destructive action button: same geometry as [RelayTextButton] but in the error red
 * — for delete / block / leave actions.
 *
 * Phase 8 — the red comes from the colour scheme: the fixed `#D7263D` was 3.47:1 on
 * the dark canvas. The light scheme keeps `#D7263D`.
 */
@Composable
fun RelayDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RelayTextButtonContent(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        color = MaterialTheme.colorScheme.error,
        textStyle = RelayText.ButtonText,
    )
}

@Composable
private fun RelayTextButtonContent(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    color: Color,
    textStyle: TextStyle,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = color,
            disabledContentColor = color.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(text = text, style = textStyle)
    }
}
