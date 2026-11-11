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
import com.filestech.sms.ui.theme.scaledBy

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
            // The initials scale with the avatar: at the default 48dp they
            // keep the fixed 17sp, but in a 32dp mini they shrink to ~11sp
            // so the letters keep clear whitespace instead of touching the
            // circle's edge.
            Text(
                text = initials,
                color = avatarForeground,
                style = RelayText.ConvoName.scaledBy(size / 48.dp),
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
 * a third of the slot. Both minis carry the same 2dp ring in the surface
 * colour, drawn on top of them: the ring separates the front circle from
 * the one behind, and giving it to both keeps their tint interiors the
 * same apparent size — a ring on the front one alone made it read ~4dp
 * smaller than the back one. `Alignment.TopStart` / `BottomEnd` keep the
 * diagonal correct in right-to-left layouts.
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
            GroupMiniAvatar(
                member = first,
                size = mini,
                ringColor = cs.surface,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        members.getOrNull(1)?.let { second ->
            GroupMiniAvatar(
                member = second,
                size = mini,
                ringColor = cs.surface,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * One mini of the group avatar: the member's avatar with a 2dp ring in the
 * surface colour drawn on top of it. Both minis carry the same ring, so
 * their tint interiors read the same size.
 */
@Composable
private fun GroupMiniAvatar(
    member: GroupMemberAvatar,
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size)) {
        RelayAvatar(
            label = member.label,
            size = size,
            customUri = member.photoUri,
            isUnknownContact = member.isUnknownContact,
        )
        // Ring on top of the circle (a border on the wrapper would sit
        // underneath the avatar and be hidden). Without it two tint
        // circles would read as one blob.
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(2.dp, ringColor, CircleShape),
        )
    }
}
