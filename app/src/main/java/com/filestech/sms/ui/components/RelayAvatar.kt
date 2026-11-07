package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.core.ext.avatarInitials
import com.filestech.sms.ui.theme.RelayText

/**
 * Circular avatar of the Relay design system: 48dp by default (32dp in the thread header),
 * initials on the blue tint (`primaryContainer` — `#E4EBFD` on light) instead of the
 * per-contact gradient of [Avatar]. A caller-supplied photo keeps priority over the
 * initials, and groups show the group glyph on the same tint.
 */
@Composable
fun RelayAvatar(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    customUri: String? = null,
    isGroup: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val initials = remember(label) { label.avatarInitials() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(cs.primaryContainer),
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
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(size * 0.55f),
            )
        } else {
            Text(
                text = initials,
                color = cs.onPrimaryContainer,
                style = RelayText.ConvoName,
            )
        }
    }
}
