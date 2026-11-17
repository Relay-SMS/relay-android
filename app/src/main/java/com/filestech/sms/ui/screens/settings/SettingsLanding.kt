package com.filestech.sms.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.components.RelaySettingRow
import com.filestech.sms.ui.components.RelaySettingsGroup

// ============================================================================
// Round 24 — the Settings landing. It shows ONLY the ten [SettingsSection]
// titles, in order; tapping one routes on its [SettingsDestination]. The
// sub-screen contents live in SettingsScreen.kt next to the dialogs that
// serve them; the landing is split out so that file stays under the
// TooManyFunctions threshold.
// ============================================================================

/**
 * Round 24 — one scrollable page inside the Settings Scaffold: the landing
 * or a sub-screen. Each branch gets its own scroll state, so opening a
 * sub-screen always starts at the top.
 */
@Composable
internal fun SettingsPageColumn(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        content()
        Spacer(Modifier.size(32.dp))
    }
}

/**
 * Round 24 — the Settings landing: ONLY the ten section titles, in
 * [SettingsSection] order. Tapping one routes on its [SettingsDestination]:
 * five open a sub-screen in place, four open a screen that already exists,
 * and App language opens the system per-app language page.
 *
 * Scheduled messages is deliberately NOT a row here: it moved out of
 * Settings to the main screen's 3-dot menu in feedback round 8, and it
 * stays there.
 */
@Composable
internal fun SettingsLandingContent(
    isPanicDecoy: Boolean,
    // Client mode — a linked device hides the Sending and Backup rows.
    isClientMode: Boolean = false,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenLinkedDevices: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val onTap: (SettingsSection) -> Unit = { section ->
        when (section.destination) {
            SettingsDestination.SUB_SCREEN -> onOpenSection(section)
            SettingsDestination.LINKED_DEVICES -> onOpenLinkedDevices()
            SettingsDestination.SYSTEM_LANGUAGE -> onOpenLanguage()
            SettingsDestination.ADVANCED -> onOpenAdvanced()
            SettingsDestination.HELP -> onOpenHelp()
            SettingsDestination.ABOUT -> onOpenAbout()
        }
    }
    RelaySettingsGroup {
        for (section in SettingsSection.entries) {
            if (SettingsSectionMapping.isVisible(section, isPanicDecoy, sdkInt, isClientMode)) {
                item(onClick = { onTap(section) }) {
                    RelaySettingRow(
                        icon = landingIcon(section),
                        title = stringResource(section.titleRes),
                        chevron = true,
                    )
                }
            }
        }
    }
}

/**
 * Round 24 — the landing icon for a section: the icon its old card used,
 * where it had one (Devices, Palette, Notifications, Security, Backup,
 * Language, Tune, Help, Info); Sending takes the Send icon, since its
 * card had no single icon.
 */
private fun landingIcon(section: SettingsSection): ImageVector = when (section) {
    SettingsSection.LINKED_DEVICES -> Icons.Outlined.Devices
    SettingsSection.APPEARANCE -> Icons.Outlined.Palette
    SettingsSection.SENDING -> Icons.Outlined.Send
    SettingsSection.NOTIFICATIONS -> Icons.Outlined.Notifications
    SettingsSection.SECURITY_PRIVACY -> Icons.Outlined.Security
    SettingsSection.BACKUP -> Icons.Outlined.Backup
    SettingsSection.APP_LANGUAGE -> Icons.Outlined.Language
    SettingsSection.ADVANCED -> Icons.Outlined.Tune
    SettingsSection.HELP -> Icons.Outlined.HelpOutline
    SettingsSection.ABOUT -> Icons.Outlined.Info
}
