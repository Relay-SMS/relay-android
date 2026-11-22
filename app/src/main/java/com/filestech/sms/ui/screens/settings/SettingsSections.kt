package com.filestech.sms.ui.screens.settings

import androidx.annotation.StringRes
import com.filestech.sms.R

/**
 * Round 24 — where a landing row takes the user.
 *
 * Five sections open a sub-screen inside Settings ([SUB_SCREEN]); the other
 * five open a screen that already exists (or, for [SYSTEM_LANGUAGE], the
 * Android per-app language page). The landing routes on this, so the
 * navigation and the [SettingsItem] mapping below can never disagree about
 * which rows are sub-screens.
 */
enum class SettingsDestination {
    /** Opens a sub-screen inside Settings (same Scaffold, same ViewModel). */
    SUB_SCREEN,

    /** Opens the existing Linked devices screen. */
    LINKED_DEVICES,

    /** Opens the Android per-app language page (Android 13+ only). */
    SYSTEM_LANGUAGE,

    /** Opens the existing Advanced screen. */
    ADVANCED,

    /** Opens the existing Help screen. */
    HELP,

    /** Opens the existing About screen. */
    ABOUT,
}

/**
 * Round 24 — the ten Settings sections, in landing order.
 *
 * The enum order IS the landing order: the landing renders
 * [SettingsSection.entries] top to bottom, and [SettingsSectionsTest]
 * pins that order so a reorder is a deliberate, reviewed change. Every
 * title reuses a string that already exists in all five locales — no new
 * strings were needed for the restructure.
 */
enum class SettingsSection(
    @StringRes val titleRes: Int,
    val destination: SettingsDestination,
) {
    LINKED_DEVICES(R.string.settings_section_linked_devices, SettingsDestination.LINKED_DEVICES),
    APPEARANCE(R.string.settings_section_appearance, SettingsDestination.SUB_SCREEN),
    SENDING(R.string.settings_section_sending, SettingsDestination.SUB_SCREEN),
    NOTIFICATIONS(R.string.settings_section_notifications, SettingsDestination.SUB_SCREEN),
    SECURITY_PRIVACY(R.string.settings_section_security, SettingsDestination.SUB_SCREEN),
    BACKUP(R.string.settings_section_backup, SettingsDestination.SUB_SCREEN),
    APP_LANGUAGE(R.string.settings_language, SettingsDestination.SYSTEM_LANGUAGE),
    ADVANCED(R.string.settings_section_advanced, SettingsDestination.ADVANCED),
    HELP(R.string.help_title, SettingsDestination.HELP),
    ABOUT(R.string.about_title, SettingsDestination.ABOUT),
}

/**
 * Round 24 — every row the old single-page Settings showed, as a stable
 * key.
 *
 * Each item names the setting it edits (the `AppSettings` field path) or
 * the action its row performs (`action.*`). The [section] is the ONE
 * sub-screen that shows it: [SettingsSectionsTest] asserts every item
 * lands on exactly one section and the total count, so a setting dropped
 * or duplicated in a later round fails the build instead of vanishing
 * silently.
 *
 * Deliberately NOT in this list (flagged, not forgotten):
 *  - the "notifications off" banners in Notifications and Security: they
 *    are conditional warnings, not rows — they stay with their sections
 *    as before;
 *  - `AppSettings` fields with no Settings row at all (phantom or
 *    owned elsewhere): `appearance.density`,
 *    `sending.convertToMmsAfterSegments`, `sending.mmsImageQuality`,
 *    `security.panicCodeEnabled` (never read anywhere),
 *    `security.emergencyCallBehavior` (orphaned since v1.14.1),
 *    `appearance.customAccentArgb` (read by the theme, settable nowhere),
 *    `conversations.previewLines` (read, settable nowhere),
 *    `conversations.sortMode` / `sending.defaultSubId` (set from the
 *    conversation list / thread, not from Settings),
 *    `sending.reactionEmojiOnly` (legacy sync-only, no row),
 *    `backup.encrypt` (lives on the Backup screen itself), and the
 *    `AdvancedSettings` migration flags plus `security.lastAutoPurgeAt` /
 *    `security.vaultPurgeFailedOnce` (internal state, no row by design).
 */
enum class SettingsItem(
    val key: String,
    val section: SettingsSection,
) {
    // Appearance — the theme swatches, the two colour toggles, text size,
    // and the Conversations rows that moved here in feedback round 8.
    APPEARANCE_THEME_MODE("appearance.themeMode", SettingsSection.APPEARANCE),
    APPEARANCE_DYNAMIC_COLORS("appearance.dynamicColors", SettingsSection.APPEARANCE),
    APPEARANCE_AMOLED_TRUE_BLACK("appearance.amoledTrueBlack", SettingsSection.APPEARANCE),
    APPEARANCE_TEXT_SCALE("appearance.textScale", SettingsSection.APPEARANCE),
    APPEARANCE_SHOW_AVATARS("conversations.showAvatars", SettingsSection.APPEARANCE),
    APPEARANCE_SHOW_DUPLICATE_NUMBER(
        "conversations.showDuplicateNameNumber",
        SettingsSection.APPEARANCE,
    ),
    APPEARANCE_SHOW_CATEGORIES("conversations.showCategories", SettingsSection.APPEARANCE),
    APPEARANCE_SIGNATURE("conversations.signature", SettingsSection.APPEARANCE),

    // Sending.
    SENDING_DELIVERY_REPORTS("sending.deliveryReports", SettingsSection.SENDING),
    SENDING_CONFIRM_BEFORE_BROADCAST(
        "sending.confirmBeforeBroadcast",
        SettingsSection.SENDING,
    ),
    SENDING_GROUP_MMS("sending.groupMms", SettingsSection.SENDING),
    SENDING_MY_NUMBER("sending.userMsisdn", SettingsSection.SENDING),
    SENDING_DEFAULT_REGION("sending.defaultRegionIso", SettingsSection.SENDING),
    SENDING_SEND_REACTIONS("sending.sendReactionsToRecipient", SettingsSection.SENDING),
    SENDING_REACTION_FORMAT("sending.reactionFormat", SettingsSection.SENDING),
    SENDING_SENDER_NAME("sending.senderDisplayName", SettingsSection.SENDING),

    // Notifications. MUTE_CATEGORIES is one key (`mutedCategories`) that
    // renders one row per category.
    NOTIFICATIONS_ENABLED("notifications.enabled", SettingsSection.NOTIFICATIONS),
    NOTIFICATIONS_INLINE_REPLY("notifications.inlineReply", SettingsSection.NOTIFICATIONS),
    NOTIFICATIONS_MUTE_CATEGORIES(
        "conversations.mutedCategories",
        SettingsSection.NOTIFICATIONS,
    ),
    NOTIFICATIONS_PREVIEW_MODE("notifications.previewMode", SettingsSection.NOTIFICATIONS),
    NOTIFICATIONS_STYLE("notifications.style", SettingsSection.NOTIFICATIONS),
    NOTIFICATIONS_OPEN_SYSTEM(
        "action.openSystemNotifications",
        SettingsSection.NOTIFICATIONS,
    ),

    // Security & privacy — the lock rows, the vault PIN rows, the purge
    // and retention rows, Safety call, Emergency mode and Blocking (the
    // three groups that moved into this card in feedback round 8).
    SECURITY_APP_LOCK("security.lockMode", SettingsSection.SECURITY_PRIVACY),
    SECURITY_AUTO_LOCK_DELAY("security.autoLockDelay", SettingsSection.SECURITY_PRIVACY),
    SECURITY_PANIC_CODE("security.panicCode", SettingsSection.SECURITY_PRIVACY),
    SECURITY_FLAG_SECURE("security.flagSecure", SettingsSection.SECURITY_PRIVACY),
    SECURITY_LOCK_VAULT_ON_LEAVE("security.lockVaultOnLeave", SettingsSection.SECURITY_PRIVACY),
    SECURITY_SMISHING_DETECTION(
        "security.smishingDetectionEnabled",
        SettingsSection.SECURITY_PRIVACY,
    ),
    SECURITY_VAULT_PIN("security.vaultPinEnabled", SettingsSection.SECURITY_PRIVACY),
    SECURITY_VAULT_PIN_CHANGE("action.changeVaultPin", SettingsSection.SECURITY_PRIVACY),
    SECURITY_VAULT_PIN_FORGOT("action.forgotVaultPin", SettingsSection.SECURITY_PRIVACY),
    SECURITY_PURGE_BLOCKED("action.purgeBlockedConversations", SettingsSection.SECURITY_PRIVACY),
    SECURITY_AUTO_DELETE("security.autoDeleteOlderThanDays", SettingsSection.SECURITY_PRIVACY),
    SECURITY_SAFETY_CALL("security.safetyCall", SettingsSection.SECURITY_PRIVACY),
    SECURITY_EMERGENCY_MODE("security.emergency", SettingsSection.SECURITY_PRIVACY),
    SECURITY_EMERGENCY_SHORTCUT(
        "security.emergencyShortcutEnabled",
        SettingsSection.SECURITY_PRIVACY,
    ),
    SECURITY_EMERGENCY_CALL_POLICE(
        "security.emergencyCallPoliceEnabled",
        SettingsSection.SECURITY_PRIVACY,
    ),
    SECURITY_SEND_I_AM_OK_SMS("security.sendIAmOkSmsOnReset", SettingsSection.SECURITY_PRIVACY),
    SECURITY_EMERGENCY_INCLUDE_LOCATION(
        "security.emergency.includeLocation",
        SettingsSection.SECURITY_PRIVACY,
    ),
    SECURITY_BLOCK_UNKNOWN("blocking.blockUnknown", SettingsSection.SECURITY_PRIVACY),
    SECURITY_MANAGE_BLOCKED("action.openBlockedNumbers", SettingsSection.SECURITY_PRIVACY),

    // E2E — the Encryption sub-screen row (its own group after the
    // security card), and the discovery toggle on that screen.
    SECURITY_ENCRYPTION("action.openEncryption", SettingsSection.SECURITY_PRIVACY),
    SECURITY_E2E_AUTO_DISCOVER(
        "encryption.autoDiscoverRelayContacts",
        SettingsSection.SECURITY_PRIVACY,
    ),

    // Backup — all three rows open screens that already exist.
    BACKUP_NOW("action.backupNow", SettingsSection.BACKUP),
    BACKUP_RESTORE("action.restore", SettingsSection.BACKUP),
    BACKUP_MIGRATION("action.openMigration", SettingsSection.BACKUP),

    // App language — a single row that opens the system language page.
    APP_LANGUAGE_OPEN_SYSTEM("action.openSystemLanguage", SettingsSection.APP_LANGUAGE),

    // The four rows that open existing screens straight from the landing.
    LINKED_DEVICES_OPEN("action.openLinkedDevices", SettingsSection.LINKED_DEVICES),

    // Client mode — the mode row on the Linked devices screen: "Link
    // this device to another phone" on a hub, the switch-back row on a
    // linked device. It edits `AppSettings.mode`, not a sub-screen of
    // its own — the Linked devices screen renders it.
    LINKED_DEVICES_MODE("app.mode", SettingsSection.LINKED_DEVICES),
    ADVANCED_OPEN("action.openAdvanced", SettingsSection.ADVANCED),
    HELP_OPEN("action.openHelp", SettingsSection.HELP),
    ABOUT_OPEN("action.openAbout", SettingsSection.ABOUT),
}

/**
 * Round 24 — the section → items mapping and the landing visibility rules,
 * kept pure (no Compose, no Context) so a plain JUnit test can pin them.
 */
object SettingsSectionMapping {

    /** Every item grouped by the section that shows it. */
    val itemsBySection: Map<SettingsSection, List<SettingsItem>> =
        SettingsItem.entries.groupBy { it.section }

    /** The items on one sub-screen, in display order. */
    fun itemsFor(section: SettingsSection): List<SettingsItem> =
        itemsBySection[section].orEmpty()

    /**
     * Whether a landing row shows. Linked devices and Backup stay hidden
     * in a panic-decoy session (identifying names / vault export — the
     * same guards the old page had); App language needs the Android 13+
     * per-app language page. Everything else always shows.
     */
    fun isVisible(section: SettingsSection, isPanicDecoy: Boolean, sdkInt: Int): Boolean =
        isVisible(section, isPanicDecoy, sdkInt, isClientMode = false)

    /**
     * Client mode — the same visibility rules, plus the client-mode
     * hiding: a linked device has no SIM and no message store of its
     * own, so Sending (delivery reports, group MMS, "my number") and
     * Backup (export/restore of a store that lives on the hub) are
     * meaningless there and their landing rows hide. Security &
     * privacy stays — the lock rows still apply — but its
     * Safety call / Emergency / Blocking rows hide inside the
     * sub-screen (see `SettingsScreen.SecuritySection`).
     */
    fun isVisible(
        section: SettingsSection,
        isPanicDecoy: Boolean,
        sdkInt: Int,
        isClientMode: Boolean,
    ): Boolean {
        if (isClientMode) {
            when (section) {
                SettingsSection.SENDING,
                SettingsSection.BACKUP,
                -> return false
                else -> Unit
            }
        }
        return when (section) {
            SettingsSection.LINKED_DEVICES,
            SettingsSection.BACKUP,
            -> !isPanicDecoy
            SettingsSection.APP_LANGUAGE -> sdkInt >= android.os.Build.VERSION_CODES.TIRAMISU
            else -> true
        }
    }
}
