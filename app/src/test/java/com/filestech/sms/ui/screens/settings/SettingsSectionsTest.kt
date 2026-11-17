package com.filestech.sms.ui.screens.settings

import com.filestech.sms.R
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 24 — the Settings restructure, pinned as a pure data model: the
 * landing order, the destination of each row, and the proof that every
 * setting from the old single page lands on exactly one sub-screen.
 *
 * Compose UI tests cannot run in the local harness, so the mapping is the
 * contract: the landing renders [SettingsSection.entries] and routes on
 * [SettingsSection.destination], and each sub-screen renders the rows of
 * its section. If a later round drops or duplicates a row, the count and
 * per-section tests below fail instead of the setting vanishing silently.
 */
class SettingsSectionsTest {

    @Test
    fun `landing order is exactly the ten sections in Kristin's order`() {
        assertThat(SettingsSection.entries).containsExactly(
            SettingsSection.LINKED_DEVICES,
            SettingsSection.APPEARANCE,
            SettingsSection.SENDING,
            SettingsSection.NOTIFICATIONS,
            SettingsSection.SECURITY_PRIVACY,
            SettingsSection.BACKUP,
            SettingsSection.APP_LANGUAGE,
            SettingsSection.ADVANCED,
            SettingsSection.HELP,
            SettingsSection.ABOUT,
        ).inOrder()
    }

    @Test
    fun `every settings item belongs to exactly one section`() {
        val mapped = SettingsSectionMapping.itemsBySection.values.flatten()
        assertThat(mapped).containsExactlyElementsIn(SettingsItem.entries)
        assertThat(SettingsItem.entries.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `total item count is 49 - a dropped row fails here, not on device`() {
        assertThat(SettingsItem.entries).hasSize(49)
    }

    @Test
    fun `no section is empty`() {
        val empty = SettingsSection.entries.filter {
            SettingsSectionMapping.itemsFor(it).isEmpty()
        }
        assertThat(empty).isEmpty()
    }

    @Test
    fun `appearance holds exactly its seven rows`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.APPEARANCE))
            .containsExactly(
                SettingsItem.APPEARANCE_THEME_MODE,
                SettingsItem.APPEARANCE_DYNAMIC_COLORS,
                SettingsItem.APPEARANCE_AMOLED_TRUE_BLACK,
                SettingsItem.APPEARANCE_TEXT_SCALE,
                SettingsItem.APPEARANCE_SHOW_AVATARS,
                SettingsItem.APPEARANCE_SHOW_CATEGORIES,
                SettingsItem.APPEARANCE_SIGNATURE,
            ).inOrder()
    }

    @Test
    fun `sending holds exactly its eight rows`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.SENDING))
            .containsExactly(
                SettingsItem.SENDING_DELIVERY_REPORTS,
                SettingsItem.SENDING_CONFIRM_BEFORE_BROADCAST,
                SettingsItem.SENDING_GROUP_MMS,
                SettingsItem.SENDING_MY_NUMBER,
                SettingsItem.SENDING_DEFAULT_REGION,
                SettingsItem.SENDING_SEND_REACTIONS,
                SettingsItem.SENDING_REACTION_FORMAT,
                SettingsItem.SENDING_SENDER_NAME,
            ).inOrder()
    }

    @Test
    fun `notifications holds exactly its six rows`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.NOTIFICATIONS))
            .containsExactly(
                SettingsItem.NOTIFICATIONS_ENABLED,
                SettingsItem.NOTIFICATIONS_INLINE_REPLY,
                SettingsItem.NOTIFICATIONS_MUTE_CATEGORIES,
                SettingsItem.NOTIFICATIONS_PREVIEW_MODE,
                SettingsItem.NOTIFICATIONS_STYLE,
                SettingsItem.NOTIFICATIONS_OPEN_SYSTEM,
            ).inOrder()
    }

    @Test
    fun `security and privacy holds exactly its nineteen rows`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.SECURITY_PRIVACY))
            .containsExactly(
                SettingsItem.SECURITY_APP_LOCK,
                SettingsItem.SECURITY_AUTO_LOCK_DELAY,
                SettingsItem.SECURITY_PANIC_CODE,
                SettingsItem.SECURITY_FLAG_SECURE,
                SettingsItem.SECURITY_LOCK_VAULT_ON_LEAVE,
                SettingsItem.SECURITY_SMISHING_DETECTION,
                SettingsItem.SECURITY_VAULT_PIN,
                SettingsItem.SECURITY_VAULT_PIN_CHANGE,
                SettingsItem.SECURITY_VAULT_PIN_FORGOT,
                SettingsItem.SECURITY_PURGE_BLOCKED,
                SettingsItem.SECURITY_AUTO_DELETE,
                SettingsItem.SECURITY_SAFETY_CALL,
                SettingsItem.SECURITY_EMERGENCY_MODE,
                SettingsItem.SECURITY_EMERGENCY_SHORTCUT,
                SettingsItem.SECURITY_EMERGENCY_CALL_POLICE,
                SettingsItem.SECURITY_SEND_I_AM_OK_SMS,
                SettingsItem.SECURITY_EMERGENCY_INCLUDE_LOCATION,
                SettingsItem.SECURITY_BLOCK_UNKNOWN,
                SettingsItem.SECURITY_MANAGE_BLOCKED,
            ).inOrder()
    }

    @Test
    fun `backup holds exactly its three rows`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.BACKUP))
            .containsExactly(
                SettingsItem.BACKUP_NOW,
                SettingsItem.BACKUP_RESTORE,
                SettingsItem.BACKUP_MIGRATION,
            ).inOrder()
    }

    @Test
    fun `linked devices holds the open row and the client-mode mode row`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.LINKED_DEVICES))
            .containsExactly(
                SettingsItem.LINKED_DEVICES_OPEN,
                SettingsItem.LINKED_DEVICES_MODE,
            ).inOrder()
    }

    @Test
    fun `single-row sections hold exactly their one row`() {
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.APP_LANGUAGE))
            .containsExactly(SettingsItem.APP_LANGUAGE_OPEN_SYSTEM)
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.ADVANCED))
            .containsExactly(SettingsItem.ADVANCED_OPEN)
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.HELP))
            .containsExactly(SettingsItem.HELP_OPEN)
        assertThat(SettingsSectionMapping.itemsFor(SettingsSection.ABOUT))
            .containsExactly(SettingsItem.ABOUT_OPEN)
    }

    @Test
    fun `destinations route each landing row to the right screen`() {
        assertThat(SettingsSection.LINKED_DEVICES.destination)
            .isEqualTo(SettingsDestination.LINKED_DEVICES)
        assertThat(SettingsSection.APPEARANCE.destination)
            .isEqualTo(SettingsDestination.SUB_SCREEN)
        assertThat(SettingsSection.SENDING.destination)
            .isEqualTo(SettingsDestination.SUB_SCREEN)
        assertThat(SettingsSection.NOTIFICATIONS.destination)
            .isEqualTo(SettingsDestination.SUB_SCREEN)
        assertThat(SettingsSection.SECURITY_PRIVACY.destination)
            .isEqualTo(SettingsDestination.SUB_SCREEN)
        assertThat(SettingsSection.BACKUP.destination)
            .isEqualTo(SettingsDestination.SUB_SCREEN)
        assertThat(SettingsSection.APP_LANGUAGE.destination)
            .isEqualTo(SettingsDestination.SYSTEM_LANGUAGE)
        assertThat(SettingsSection.ADVANCED.destination)
            .isEqualTo(SettingsDestination.ADVANCED)
        assertThat(SettingsSection.HELP.destination)
            .isEqualTo(SettingsDestination.HELP)
        assertThat(SettingsSection.ABOUT.destination)
            .isEqualTo(SettingsDestination.ABOUT)
    }

    @Test
    fun `titles reuse the existing section strings in all locales`() {
        assertThat(SettingsSection.LINKED_DEVICES.titleRes)
            .isEqualTo(R.string.settings_section_linked_devices)
        assertThat(SettingsSection.APPEARANCE.titleRes)
            .isEqualTo(R.string.settings_section_appearance)
        assertThat(SettingsSection.SENDING.titleRes)
            .isEqualTo(R.string.settings_section_sending)
        assertThat(SettingsSection.NOTIFICATIONS.titleRes)
            .isEqualTo(R.string.settings_section_notifications)
        assertThat(SettingsSection.SECURITY_PRIVACY.titleRes)
            .isEqualTo(R.string.settings_section_security)
        assertThat(SettingsSection.BACKUP.titleRes)
            .isEqualTo(R.string.settings_section_backup)
        assertThat(SettingsSection.APP_LANGUAGE.titleRes)
            .isEqualTo(R.string.settings_language)
        assertThat(SettingsSection.ADVANCED.titleRes)
            .isEqualTo(R.string.settings_section_advanced)
        assertThat(SettingsSection.HELP.titleRes).isEqualTo(R.string.help_title)
        assertThat(SettingsSection.ABOUT.titleRes).isEqualTo(R.string.about_title)
    }

    @Test
    fun `panic decoy hides linked devices and backup only`() {
        for (section in SettingsSection.entries) {
            val expected = section != SettingsSection.LINKED_DEVICES &&
                section != SettingsSection.BACKUP
            assertThat(SettingsSectionMapping.isVisible(section, true, 34))
                .isEqualTo(expected)
        }
        assertThat(SettingsSection.entries).isNotEmpty()
    }

    @Test
    fun `client mode hides sending and backup, keeps the rest`() {
        for (section in SettingsSection.entries) {
            val expected = section != SettingsSection.SENDING &&
                section != SettingsSection.BACKUP
            assertThat(SettingsSectionMapping.isVisible(section, false, 34, true))
                .isEqualTo(expected)
        }
        assertThat(SettingsSection.entries).isNotEmpty()
    }

    @Test
    fun `client mode still hides linked devices in a panic decoy`() {
        assertThat(
            SettingsSectionMapping.isVisible(SettingsSection.LINKED_DEVICES, true, 34, true),
        ).isFalse()
        assertThat(
            SettingsSectionMapping.isVisible(SettingsSection.APPEARANCE, true, 34, true),
        ).isTrue()
    }

    @Test
    fun `hub mode keeps the old visibility exactly`() {
        for (section in SettingsSection.entries) {
            assertThat(SettingsSectionMapping.isVisible(section, false, 34, false))
                .isEqualTo(SettingsSectionMapping.isVisible(section, false, 34))
        }
        assertThat(SettingsSection.entries).isNotEmpty()
    }

    @Test
    fun `app language needs Android 13, everything else shows on older releases`() {
        assertThat(SettingsSectionMapping.isVisible(SettingsSection.APP_LANGUAGE, false, 32))
            .isFalse()
        assertThat(SettingsSectionMapping.isVisible(SettingsSection.APP_LANGUAGE, false, 33))
            .isTrue()
        for (section in SettingsSection.entries) {
            if (section == SettingsSection.APP_LANGUAGE) continue
            assertThat(SettingsSectionMapping.isVisible(section, false, 32)).isTrue()
        }
        assertThat(SettingsSection.entries).isNotEmpty()
    }
}
