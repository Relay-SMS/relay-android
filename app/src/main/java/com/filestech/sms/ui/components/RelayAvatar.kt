package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.core.ext.avatarInitials
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText

/**
 * Circular avatar of the Relay design system: 48dp by default (32dp in the thread header),
 * initials on the fixed light-blue tint (`RelayColors.BlueTint` — `#E4EBFD`) in both themes
 * instead of the theme `primaryContainer` (dark navy `#004788` in dark mode). The initials
 * and the group glyph are the fixed dark navy `#001A40` on that tint (14.41:1) in both
 * themes — the dark theme's pale `onPrimaryContainer` would be unreadable (1.08:1) on it.
 * A caller-supplied photo keeps priority over the initials, and groups show the group
 * glyph on the same tint.
 */
@Composable
fun RelayAvatar(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    customUri: String? = null,
    isGroup: Boolean = false,
) {
    val initials = remember(label) { label.avatarInitials() }
    // Fixed light-blue tint + dark-navy foreground in both themes (see KDoc).
    val avatarBackground = RelayColors.BlueTint
    val avatarForeground = Color(0xFF001A40)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (customUri != null) {
            // The custom image covers the tint in CircleShape + crop. The tint stays as a
            // backdrop while Coil loads and if the URI fails (revoked, file deleted) — the
            // user never sees an empty bubble.
            coil.compose.AsyncImage(
                model = customUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
        } else if (isGroup) {
            Icon(
                imageVector = Icons.Outlined.Group,
                contentDescription = null,
                tint = avatarForeground,
                modifier = Modifier.size(size * 0.55f),
            )
        } else {
            Text(
                text = initials,
                color = avatarForeground,
                style = RelayText.ConvoName,
            )
        }
    }
}
