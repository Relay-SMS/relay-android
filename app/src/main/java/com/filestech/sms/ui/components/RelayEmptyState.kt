package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.RelayText

/**
 * Shared empty state of the Relay design system: a centred glyph, one headline, an optional
 * body line, and an optional primary button. Screens pass their own copy — the geometry stays
 * identical everywhere.
 */
@Composable
fun RelayEmptyState(
    icon: ImageVector,
    headline: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = headline,
            style = RelayText.ConvoName,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                text = body,
                style = RelayText.Preview,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (buttonText != null && onButtonClick != null) {
            Spacer(modifier = Modifier.height(4.dp))
            RelayPrimaryButton(
                text = buttonText,
                onClick = onButtonClick,
            )
        }
    }
}
