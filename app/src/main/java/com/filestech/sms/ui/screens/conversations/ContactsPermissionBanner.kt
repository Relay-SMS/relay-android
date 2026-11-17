package com.filestech.sms.ui.screens.conversations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.settings.DeviceMode

/**
 * Contacts-permission fix — whether READ_CONTACTS is granted, via
 * the direct framework call, not `ContextCompat.checkSelfPermission`
 * (core 1.19's version calls `TextUtils.equals` on its pre-33 path,
 * a stub that throws on plain-JVM unit tests — the same trap
 * `RepositorySyncMessageSource.contactsReadiness` already avoids).
 * The framework call is API 23+ and minSdk is 26, so on device this
 * is exactly what ContextCompat does for READ_CONTACTS.
 */
internal fun Context.contactsPermissionGranted(): Boolean =
    checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/**
 * Contacts-permission fix — the "names and photos need the contact
 * permission" banner above the conversation list, shown only when it
 * can actually help:
 *
 * - hub mode only: a linked device never reads the local provider
 *   (its contacts arrive from the hub), so the prompt would be a
 *   dead end there — the same mode guard as the default-app banner;
 * - only once this device is the default SMS app: the permission is
 *   requested as part of becoming default, so before that the user
 *   has not declined anything yet and the default-app banner is the
 *   setup step that matters;
 * - never on the archived list, and never after the user dismissed
 *   it (`conv.contactsBannerDismissed` persists the dismissal, so
 *   the banner does not nag on every launch).
 *
 * The enable action ([onEnable]) opens the app settings page, not
 * the system dialog: after two refusals Android never shows the
 * dialog again, and a request-only button would then do nothing —
 * the same reason the compose screen's refused-contacts row goes to
 * the app settings page. The screen re-checks the permission on
 * resume, so the banner disappears right after the grant.
 *
 * Extracted into its own file (not inlined in [ConversationsScreen],
 * which is at the Detekt complexity ceiling) so the screen keeps
 * only the one conditional call.
 */
@Composable
fun ContactsPermissionBannerIfNeeded(
    isDefaultSmsApp: Boolean,
    contactsPermissionGranted: Boolean,
    bannerDismissed: Boolean,
    archived: Boolean,
    mode: DeviceMode,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val setupDone = isDefaultSmsApp && !archived && mode != DeviceMode.CLIENT
    val needsPrompt = !contactsPermissionGranted && !bannerDismissed
    if (!setupDone || !needsPrompt) {
        return
    }
    ContactsPermissionBanner(onEnable = onEnable, onDismiss = onDismiss)
    // Feedback round 5 — breathing room between the prompt and the
    // 1px divider below it, mirroring DefaultAppBannerIfNeeded.
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
}

/**
 * Contacts-permission fix — the banner itself. Same surface as the
 * default-app banner (tinted rounded card, `primaryContainer` at
 * 0.55 alpha), but two rows instead of one: the sentence plus a
 * filled button plus a dismiss action cannot share one row without
 * crushing the text to a sliver on narrow screens (the trap the
 * default-app banner's own KDoc documents), so the text gets the
 * full width and the actions sit right-aligned underneath.
 */
@Composable
private fun ContactsPermissionBanner(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.PersonOff,
                    contentDescription = null,
                    tint = cs.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.conversations_contacts_banner_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onPrimaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.conversations_contacts_banner_dismiss))
                }
                Spacer(Modifier.size(8.dp))
                // Same dark/light split as the default-app banner:
                // the light `#E5EAFD` background with dark-navy text
                // in dark mode, so the button reads as a light chip
                // on the dark banner. The split comes from the
                // surface luminance, like the bubble colours.
                val surface = cs.surface
                val surfaceLuma = 0.2126f * surface.red + 0.7152f * surface.green +
                    0.0722f * surface.blue
                val isDark = surfaceLuma < 0.5f
                FilledTonalButton(
                    onClick = onEnable,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isDark) Color(0xFFE5EAFD) else cs.primary,
                        contentColor = if (isDark) Color(0xFF001A40) else cs.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 0.dp),
                ) {
                    Text(
                        stringResource(R.string.conversations_contacts_banner_action),
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
    }
}
