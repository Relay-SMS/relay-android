package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
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
    /**
     * Feedback round 10 — true for a 1-to-1 conversation with someone who
     * is NOT a contact (no display name): instead of digit initials on the
     * blue tint, the avatar is the solid person glyph on a neutral grey —
     * `#E6E6E6` in light mode, a dark grey in dark mode (picked from the
     * surface luminance, like the bubble colours, so it follows the
     * System/Light/Dark setting). The glyph itself is `onSurfaceVariant`
     * in both themes.
     */
    isUnknownContact: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val initials = remember(label) { label.avatarInitials() }
    // Fixed light-blue tint + dark-navy foreground in both themes (see KDoc).
    val avatarBackground = RelayColors.BlueTint
    val avatarForeground = Color(0xFF001A40)
    // Feedback round 10 — unknown-contact grey, light `#E6E6E6` / dark
    // `#424242`, from the surface luminance.
    val surface = cs.surface
    val surfaceLuma = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue
    val unknownGrey = if (surfaceLuma < 0.5f) Color(0xFF424242) else Color(0xFFE6E6E6)
    val avatarBg = if (isUnknownContact && customUri == null && !isGroup) {
        unknownGrey
    } else {
        avatarBackground
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBg),
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
        } else if (isUnknownContact) {
            // Feedback round 10 — solid person glyph (Kristin's person.svg)
            // on the neutral grey, instead of digit initials on blue.
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_person_solid),
                contentDescription = null,
                tint = cs.onSurfaceVariant,
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

/**
 * One member of a group, as the main list sees them: the label to take
 * initials from (the contact name when known, else the raw number) and
 * whether the member is a known contact. A photo URI, when the row can
 * supply one, wins over the initials — the same priority as [RelayAvatar].
 * The list resolves contact photos for 1-to-1 conversations only, so group
 * members arrive without one and render initials or the grey glyph.
 */
data class GroupMemberAvatar(
    val label: String,
    val isUnknownContact: Boolean,
    val photoUri: String? = null,
)

/**
 * Two overlapping avatars for a group conversation on the main list: the
 * first two members, each a plain [RelayAvatar], so a contact gets initials
 * on the tint and an unknown number gets the grey person glyph — the same
 * rendering as a 1-to-1 row, just smaller.
 *
 * Geometry: two circles of two-thirds the slot (32dp in the 48dp slot),
 * the first at the top-start, the second at the bottom-end, overlapping by
 * a third of the slot. The front circle carries a ring in the surface
 * colour, drawn on top of it, so it reads as separate from the one behind
 * instead of blending into it when both share the same tint.
 * `Alignment.TopStart` / `BottomEnd` keep the diagonal correct in
 * right-to-left layouts.
 */
@Composable
fun RelayGroupAvatar(
    members: List<GroupMemberAvatar>,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val cs = MaterialTheme.colorScheme
    val mini = size * 2f / 3f
    Box(modifier = modifier.size(size)) {
        members.getOrNull(0)?.let { first ->
            RelayAvatar(
                label = first.label,
                size = mini,
                customUri = first.photoUri,
                isUnknownContact = first.isUnknownContact,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        members.getOrNull(1)?.let { second ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(mini),
            ) {
                RelayAvatar(
                    label = second.label,
                    size = mini,
                    customUri = second.photoUri,
                    isUnknownContact = second.isUnknownContact,
                )
                // Ring on top of the front circle (a border on the wrapper
                // would sit underneath the avatar and be hidden). Without
                // it two tint circles would read as one blob.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(2.dp, cs.surface, CircleShape),
                )
            }
        }
    }
}
