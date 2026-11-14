package com.filestech.sms.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.data.sms.readSimMsisdn
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.settings.AutoLockDelay
import com.filestech.sms.system.locale.ouvrirLaLangueDeLApplication
import com.filestech.sms.system.notifications.CANAL_DU_RACCOURCI_D_URGENCE
import com.filestech.sms.system.notifications.canalDesMessages
import com.filestech.sms.system.notifications.canauxVisibles
import com.filestech.sms.system.settings.ouvrirLesNotificationsDeLApplication
import com.filestech.sms.ui.components.BanniereNotificationsCoupees
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayPickerDialog
import com.filestech.sms.ui.components.RelayPickerOption
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.RelaySectionLabel
import com.filestech.sms.ui.components.RelaySettingRow
import com.filestech.sms.ui.components.RelaySettingsGroup
import com.filestech.sms.ui.components.RelaySwitchRow
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.components.rememberPermissionAccordee
import com.filestech.sms.ui.components.rememberPermissionLocalisation
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.screens.conversations.categoryLabel
import com.filestech.sms.ui.security.ProtectSecretInput
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.util.daySeparatorLabel
import com.filestech.sms.ui.util.libelleDeDuree
import com.filestech.sms.ui.util.rememberChatFormatters
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    // Feedback round 5 — Help lives on its own screen now (was the
    // "Quick help" section of About).
    onOpenHelp: () -> Unit,
    // Feedback round 6 — Advanced lives on its own screen now (was the
    // "Advanced" section at the bottom of this list).
    onOpenAdvanced: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenMigration: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenSafetyCall: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenEmergencySetup: () -> Unit,
    // Slice 1 — Linked devices (LAN server toggle, linked devices, QR pairing).
    onOpenLinkedDevices: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.10.0 perf P2 — recomputé toutes les 60s (ou à chaque changement de
    // state), évite l'appel à System.currentTimeMillis() à chaque recomposition.
    val safetyCallRemainingMs by viewModel.safetyCallRemainingMs.collectAsStateWithLifecycle()
    // v1.10.0 audit SEC-1 — en session PanicDecoy, masquer toutes les sections
    // qui révèlent que l'app dispose de fonctions de sécurité personnelle.
    val isPanicDecoy by viewModel.isPanicDecoy.collectAsStateWithLifecycle()
    var lockModePickerOpen by remember { mutableStateOf(false) }
    // v1.26.1 (audit F4) — sélecteur du délai de verrouillage automatique.
    var autoLockDelayPickerOpen by remember { mutableStateOf(false) }
    var textScalePickerOpen by remember { mutableStateOf(false) }

    /** v1.25.3 (audit M23) — confirmation avant de supprimer le verrou de l'app. */
    var lockClearConfirmOpen by remember { mutableStateOf(false) }
    var autoDeletePickerOpen by remember { mutableStateOf(false) }
    var autoDeletePurgeConfirmOpen by remember { mutableStateOf(false) }
    var pinSetupOpen by remember { mutableStateOf(false) }
    // v1.26.0 — code panique.
    var panicCodeSetupOpen by remember { mutableStateOf(false) }
    var panicCodeClearConfirmOpen by remember { mutableStateOf(false) }
    val panicCodeSet by viewModel.panicCodeSet.collectAsStateWithLifecycle()
    // L'etat du code panique ne transite pas par le flux des reglages (le secret vit hache dans
    // le magasin securise) : on le relit a l'entree de l'ecran.
    LaunchedEffect(Unit) { viewModel.refreshPanicCodeSet() }
    // v1.8.0 (bug 3 fix) — pickers PreviewMode + NotificationStyle.
    var previewModePickerOpen by remember { mutableStateOf(false) }
    var notifStylePickerOpen by remember { mutableStateOf(false) }
    // v1.8.0 (bug 5 fix) — picker ReactionFormat (3 options).
    var reactionFormatPickerOpen by remember { mutableStateOf(false) }
    // v1.21.0 — picker indicatif pays par défaut (conversion E.164 à l'envoi).
    var defaultRegionPickerOpen by remember { mutableStateOf(false) }
    // v1.8.0 — dialog de confirmation pour l'action destructive restante ici :
    // purge des conversations bloquées. (Resync et reset-all vivaient ici
    // aussi ; feedback round 6, ils ont déménagé sur l'écran Advanced avec
    // leurs dialogs — voir [AdvancedScreen].)
    var showPurgeBlockedConfirm by remember { mutableStateOf(false) }
    // v1.13.0 — PIN/pass distinct coffre.
    var vaultPinSetupOpen by remember { mutableStateOf(false) }
    // v1.27.10 (revue externe GitLab !38458) — trois parcours distincts la ou il n'y en avait
    // qu'un : poser/remplacer le PIN, le retirer (exige le PIN en place), et la porte de sortie
    // destructive « PIN oublie ». L'ancien `vaultPinClearConfirmOpen` ne demandait rien.
    var vaultPinDisableOpen by remember { mutableStateOf(false) }
    var vaultPinForgetConfirmOpen by remember { mutableStateOf(false) }
    // v1.28.2 — porte de sortie de la porte de sortie. `null` = fermee ; sinon, le nombre de
    // conversations dont la copie systeme a resiste, qu'il faut annoncer avant de detruire.
    var vaultPinForceConfirm by remember { mutableStateOf<Int?>(null) }
    // v1.14.0 — Comportement boutons 112/17 (DIALER_ONLY vs HOLD_3S_DIRECT_CALL).
    // Retiré v1.14.1 : la page Mode urgence v1.14.1 utilise direct call avec
    // fallback automatique → picker Settings devenait orphelin (dead setting).
    // Clé DataStore préservée pour compat ascendante mais non lue par UI.

    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // v1.26.0 — libelles du code panique resolus dans la composition, pas via
    // `Context.getString` depuis le collecteur d'evenements : lint le refuse a juste titre
    // (LocalContextGetResourceValueCall), un changement de langue ne serait pas repercute.
    val panicSavedMsg = stringResource(R.string.settings_panic_code_saved)
    val panicRemovedMsg = stringResource(R.string.settings_panic_code_removed)
    val panicSameAsPinMsg = stringResource(R.string.settings_panic_code_same_as_pin)
    val panicNoPinMsg = stringResource(R.string.settings_panic_code_no_pin)
    // v1.26.1 (audit C3) — hissé dans la composition comme ses voisins : un
    // `ctx.getString` dans le collecteur d'événements déclencherait
    // `LocalContextGetResourceValueCall`.
    val pinSameAsPanicMsg = stringResource(R.string.settings_pin_same_as_panic_code)
    // v1.28.3 (F07) — resolue au niveau COMPOSABLE, comme sa voisine ci-dessus : un `Context`
    // capture dans une lambda non composable ne suit pas les changements de configuration.
    val lockDowngradeRefusedMsg = stringResource(R.string.settings_lock_downgrade_vault_refused)
    val lockDowngradeUnverifiableMsg = stringResource(R.string.settings_lock_downgrade_vault_unverifiable)
    // v1.28.3 — idem : gabarit resolu au niveau composable, formate a l'emission.
    val vaultPurgeLocalFailureFmt = stringResource(R.string.settings_vault_pin_forgot_local_failure)
    // v1.9.0 — scope partagé pour les actions instantanées qui doivent émettre
    // un snack depuis un onClick callback (ex. bouton "Je vais bien" Safety call).
    val rootScope = rememberCoroutineScope()

    // v1.14.0 callPhonePermLauncher retiré v1.14.1 : la permission est
    // désormais demandée directement depuis EmergencyScreen au 1er tap d'un
    // bouton d'appel (pas dans Settings). Le setting `emergencyCallBehavior`
    // n'est plus lu par UI → permission peut être demandée hors picker.
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                // v1.28.3 (F07) — refus d'abaisser le verrouillage : la biometrie est le seul
                // second facteur du Coffre, qui n'est pas vide. En rouge, parce que c'est un
                // refus et non une confirmation, et nomme, parce qu'un geste sans effet ni
                // explication se repete.
                is SettingsViewModel.Event.LockDowngradeRefusedVault ->
                    snackbarHost.showError(lockDowngradeRefusedMsg)
                is SettingsViewModel.Event.LockDowngradeUnverifiable ->
                    snackbarHost.showError(lockDowngradeUnverifiableMsg)
                is SettingsViewModel.Event.BlockedPurged -> {
                    val msg = if (e.count > 0) {
                        ctx.getString(R.string.settings_purge_blocked_result, e.count)
                    } else ctx.getString(R.string.settings_purge_blocked_none)
                    snackbarHost.showSnackbar(msg)
                }
                is SettingsViewModel.Event.HistoryPurged -> {
                    snackbarHost.showSnackbar(
                        ctx.getString(R.string.settings_auto_delete_purge_done, e.count),
                    )
                }
                SettingsViewModel.Event.PanicCodeSet -> {
                    snackbarHost.showSnackbar(panicSavedMsg)
                }
                SettingsViewModel.Event.PanicCodeCleared -> {
                    snackbarHost.showSnackbar(panicRemovedMsg)
                }
                // v1.26.0 — les deux refus enfermeraient l'utilisateur en mode leurre sans issue,
                // d'ou une erreur explicite plutot qu'un echec muet. Voir
                // [com.filestech.sms.security.AppLockManager.setPanicCode].
                is SettingsViewModel.Event.PanicCodeRejected -> {
                    val msg = when (e.outcome) {
                        com.filestech.sms.security.AppLockManager.PanicCodeOutcome.SameAsPin ->
                            panicSameAsPinMsg
                        com.filestech.sms.security.AppLockManager.PanicCodeOutcome.NoPrimaryPin ->
                            panicNoPinMsg
                        com.filestech.sms.security.AppLockManager.PanicCodeOutcome.Ok ->
                            panicSavedMsg
                    }
                    snackbarHost.showError(msg)
                }
                // v1.26.1 (audit C3) — refus symetrique du precedent : le PIN propose est le
                // code panique. Voir [com.filestech.sms.security.AppLockManager.setPin].
                SettingsViewModel.Event.PinRejectedSameAsPanicCode -> {
                    snackbarHost.showError(pinSameAsPanicMsg)
                }
                // v1.27.10 — porte de sortie « PIN du coffre oublie » : le compte rend le
                // caractere destructif de l'operation constatable, pas seulement annonce.
                is SettingsViewModel.Event.VaultPurged -> {
                    snackbarHost.showSnackbar(
                        ctx.getString(R.string.settings_vault_pin_forgot_done, e.count),
                    )
                }
                // v1.27.11 (revue externe GitLab !38458, constat 2) — la purge a échoué, le PIN
                // reste donc en place. `showError` et non `showSnackbar` : l'utilisateur croit
                // avoir ouvert son coffre, il faut qu'il apprenne le contraire ici et pas
                // devant un dialogue de PIN qu'il n'attendait plus.
                is SettingsViewModel.Event.VaultPurgeIncomplete -> {
                    snackbarHost.showError(
                        ctx.getString(
                            R.string.settings_vault_pin_forgot_incomplete,
                            e.deleted,
                            e.left,
                        ),
                    )
                }
                // v1.28.2 — second echec : celui-la ne se levera pas tout seul. On ouvre la
                // sortie assumee plutot que de laisser l'utilisateur enferme dehors de son
                // propre coffre. Un dialogue, et non un message : il doit choisir, pas subir.
                is SettingsViewModel.Event.VaultPurgeStuck -> {
                    vaultPinForceConfirm = e.left
                }
                // v1.28.3 (audit du 2026-09-09) — echec LOCAL : pas de sortie forcee, et on le
                // dit au lieu de rouvrir un dialogue qui ne mene nulle part.
                is SettingsViewModel.Event.VaultPurgeStuckLocal -> {
                    snackbarHost.showError(vaultPurgeLocalFailureFmt.format(e.deleted, e.left))
                }
                // Le coffre est ouvert, mais des copies systeme restent sur le telephone. C'est
                // la contrepartie acceptee, et elle se dit — `showError` pour qu'elle se voie.
                is SettingsViewModel.Event.VaultPurgedWithResidue -> {
                    snackbarHost.showError(
                        ctx.getString(
                            R.string.settings_vault_pin_forgot_residue,
                            e.deleted,
                            e.left,
                        ),
                    )
                }
                // Feedback round 6 — `ResyncRequested` and `DataWiped` are
                // not handled here anymore: they are emitted by the Advanced
                // screen's actions and collected there (see [AdvancedScreen]).
                else -> Unit
            }
        }
    }

    // v1.14.5 — Launcher permission ACCESS_FINE_LOCATION pour le ToggleRow
    // "Inclure position GPS" du Mode urgence. Audit SEC-1 fix : si l'user
    // refuse la permission, on REVERT `includeLocation = false` en DataStore
    // pour éviter un état sale (toggle ON mais SMS sans coords). Pattern
    // miroir de `revertCallBehaviorIfPermissionRevoked` (v1.10.0/v1.14.1).
    // v1.28.13 — exacte ET approximative demandées ensemble ; l'une OU l'autre suffit, cf.
    // [rememberPermissionLocalisation]. Un « Approximative » ne remet plus l'option à OFF.
    val localisation = rememberPermissionLocalisation { granted ->
        if (!granted) {
            viewModel.update {
                it.copy(security = it.security.copy(
                    emergency = it.security.emergency.copy(includeLocation = false),
                ))
            }
            rootScope.launch {
                snackbarHost.showError(ctx.getString(R.string.settings_emergency_include_location_denied))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { com.filestech.sms.ui.components.SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Feedback round 4 (2026-11-07) — the identity card that used to sit here
            // is removed: the page starts directly with the first section, whose
            // header already carries the normal 24dp top spacing. `settings_app_subtitle`
            // stays in strings.xml (unused now) — strings are never deleted.
            // Slice 1 — Linked devices: the LAN server toggle, the linked
            // devices and QR pairing live on their own screen. Hidden in
            // a panic-decoy session like Backup: the linked device names
            // are identifying, and the decoy must read as an ordinary
            // SMS app. Round 22 — first on the page, above Appearance,
            // like the desktop Settings.
            if (!isPanicDecoy) {
                RelaySectionLabel(stringResource(R.string.settings_section_linked_devices))
                RelaySettingsGroup {
                    item(onClick = onOpenLinkedDevices) {
                        RelaySettingRow(
                            icon = Icons.Outlined.Devices,
                            title = stringResource(R.string.linked_devices_title),
                            chevron = true,
                        )
                    }
                }
            }
            RelaySectionLabel(stringResource(R.string.settings_section_appearance))
            RelaySettingsGroup {
                item {
                    ThemeSwatchPicker(
                        current = state.appearance.themeMode,
                        onSelect = { mode -> viewModel.update { it.copy(appearance = it.appearance.copy(themeMode = mode)) } },
                    )
                }
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.settings_dynamic_colors),
                        checked = state.appearance.dynamicColors,
                        onCheckedChange = { v -> viewModel.update { it.copy(appearance = it.appearance.copy(dynamicColors = v)) } },
                    )
                }
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.DarkMode,
                        title = stringResource(R.string.settings_amoled),
                        checked = state.appearance.amoledTrueBlack,
                        onCheckedChange = { v -> viewModel.update { it.copy(appearance = it.appearance.copy(amoledTrueBlack = v)) } },
                    )
                }
                // 2026-10-29 — taille du texte des messages, cf. [com.filestech.sms.ui.theme.MessageTextSize].
                item(onClick = { textScalePickerOpen = true }) {
                    RelaySettingRow(
                        icon = Icons.Outlined.FormatSize,
                        title = stringResource(R.string.settings_text_scale),
                        chevron = true,
                    )
                }
                // Feedback round 8 — the Conversations section is gone: its
                // two rows (Show avatars, Automatic signature) live here in
                // Appearance now, so the page has one fewer card.
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.PersonOutline,
                        title = stringResource(R.string.settings_show_avatars),
                        checked = state.conversations.showAvatars,
                        onCheckedChange = { v -> viewModel.update { it.copy(conversations = it.conversations.copy(showAvatars = v)) } },
                    )
                }
                // Round 12 — hide the category tab row on the main screen.
                // Round 17 — the subtitle says the quiet part: hiding the
                // tabs also turns the filtering off (the ViewModel returns
                // the full list unfiltered), so the toggle never reads as
                // display-only.
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.Category,
                        title = stringResource(R.string.settings_show_categories),
                        subtitle = stringResource(R.string.settings_show_categories_subtitle),
                        checked = state.conversations.showCategories,
                        onCheckedChange = { v ->
                            viewModel.update { it.copy(conversations = it.conversations.copy(showCategories = v)) }
                        },
                    )
                }
                // v1.26.1 (audit F3) — la signature était consommée à chaque SMS sortant sans
                // qu'aucun écran ne permette de la définir. Voir [SignatureRow].
                item {
                    SignatureRow(
                        current = state.conversations.signature,
                        onChange = { v ->
                            viewModel.update { it.copy(conversations = it.conversations.copy(signature = v)) }
                        },
                    )
                }
            }

            RelaySectionLabel(stringResource(R.string.settings_section_sending))
            // Round 12 — the group's conditional rows (Reaction format,
            // Sender name) are `if`-guarded inside the group's content
            // lambda. That lambda is hoisted/reused because everything it
            // captures is stable, so the group is skipped on recomposition
            // and the rows never hide when the toggle flips. Keying the
            // group on the two gating values forces a fresh composition —
            // fresh item collection, correct corner rounding — when either
            // changes.
            key(state.sending.sendReactionsToRecipient, state.sending.reactionFormat) {
                RelaySettingsGroup {
                    item {
                        RelaySwitchRow(
                            iconPainter = painterResource(R.drawable.ic_checkmark),
                            title = stringResource(R.string.settings_delivery_reports),
                            checked = state.sending.deliveryReports,
                            onCheckedChange = { v ->
                                viewModel.update { it.copy(sending = it.sending.copy(deliveryReports = v)) }
                            },
                        )
                    }
                    item {
                        RelaySwitchRow(
                            icon = Icons.Outlined.WarningAmber,
                            title = stringResource(R.string.settings_confirm_broadcast),
                            checked = state.sending.confirmBeforeBroadcast,
                            onCheckedChange = { v ->
                                viewModel.update { it.copy(sending = it.sending.copy(confirmBeforeBroadcast = v)) }
                            },
                        )
                    }
                    // v1.28.4 — MMS de groupe, désactivé par défaut (décision produit du 2026-09-10).
                    // Feedback round 8 — the long hint lives in the info popup now
                    // (chevron before the switch); the row stays one-line.
                    item {
                        RelaySwitchRow(
                            iconPainter = painterResource(R.drawable.ic_new_group),
                            title = stringResource(R.string.settings_group_mms),
                            checked = state.sending.groupMms,
                            onCheckedChange = { v ->
                                viewModel.update { it.copy(sending = it.sending.copy(groupMms = v)) }
                            },
                            infoText = stringResource(R.string.settings_group_mms_hint),
                        )
                    }
                    // v1.2.6 audit F4 — saisie facultative du MSISDN. Sans cette valeur, les MMS
                    // envoyés peuvent afficher "insert-address-token" comme expéditeur dans
                    // d'autres apps SMS sur certaines ROM Samsung One UI.
                    item {
                        MyNumberRow(
                            current = state.sending.userMsisdn,
                            onChange = { v ->
                                viewModel.update { it.copy(sending = it.sending.copy(userMsisdn = v?.takeIf { s -> s.isNotBlank() })) }
                            },
                        )
                    }
                    // v1.21.0 — indicatif pays par défaut pour convertir les numéros nationaux
                    // au format international (E.164) avant l'envoi. "Auto" = pays de la SIM.
                    // Indispensable pour envoyer des numéros nationaux d'un pays depuis une SIM
                    // étrangère (ex. 06… français depuis une SIM luxembourgeoise).
                    item(onClick = { defaultRegionPickerOpen = true }) {
                        RelaySettingRow(
                            icon = Icons.Outlined.Public,
                            title = stringResource(R.string.settings_default_region_title),
                            chevron = true,
                        )
                    }
                    // v1.3.1 — envoi des réactions emoji au correspondant. ON par défaut. Quand
                    // OFF, les réactions restent strictement locales (badge visible uniquement
                    // dans Relay). La description prévient explicitement du coût SMS.
                    // Feedback round 8 — renamed to "Send my reactions"; the long
                    // cost warning lives in the info popup now (chevron before the
                    // switch); the row stays one-line.
                    item {
                        RelaySwitchRow(
                            icon = Icons.Outlined.EmojiEmotions,
                            title = stringResource(R.string.settings_send_reactions_title),
                            checked = state.sending.sendReactionsToRecipient,
                            onCheckedChange = { v ->
                                viewModel.update { it.copy(sending = it.sending.copy(sendReactionsToRecipient = v)) }
                            },
                            infoText = stringResource(R.string.settings_send_reactions_desc),
                        )
                    }
                    // v1.8.0 (bug 5 fix) — format du SMS de réaction. 3 options
                    // (anciennement boolean reactionEmojiOnly à 2 valeurs) :
                    //  - READABLE_FR (nouveau défaut) : "J'ai réagi par ❤️ à : «…»"
                    //    — naturel et lisible pour les destinataires francophones Android
                    //  - TAPBACK_EN (ancien défaut v1.7.x) : "Reacted ❤️ to «…»"
                    //    — compat iMessage iPhone + Google Messages récent
                    //  - EMOJI_ONLY : "❤️" seul — minimal, perd le contexte côté destinataire
                    //
                    // Affiché uniquement si l'envoi des réactions est activé. The
                    // old AnimatedVisibility is a plain `if` now: the group
                    // shapes are computed from the rows actually shown, and an
                    // animated segment would leave a square corner behind when
                    // it collapses.
                    if (state.sending.sendReactionsToRecipient) {
                        item(onClick = { reactionFormatPickerOpen = true }) {
                            RelaySettingRow(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                title = stringResource(R.string.settings_reaction_format_title),
                                chevron = true,
                            )
                        }
                    }
                    // v1.8.1 — nom inclus dans les SMS de réaction sortants au
                    // format READABLE_FR. Affiché uniquement si READABLE_FR est
                    // sélectionné ET envoi des réactions activé (cohérence UX).
                    if (state.sending.sendReactionsToRecipient &&
                        state.sending.reactionFormat ==
                            com.filestech.sms.domain.model.ReactionFormat.READABLE_FR
                    ) {
                        item {
                            SenderNameRow(
                                current = state.sending.senderDisplayName,
                                onChange = { name ->
                                    viewModel.update {
                                        it.copy(
                                            sending = it.sending.copy(
                                                senderDisplayName = name?.takeIf { s -> s.isNotBlank() },
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // v1.15.0 — Section extraite vers [NotificationsSection] private @Composable.
            // Comportement identique, lecture facilitée (avant : 68 lignes inline ici).
            NotificationsSection(
                notifications = state.notifications,
                mutedCategories = state.conversations.mutedCategories,
                onUpdate = viewModel::update,
                onOpenPreviewPicker = { previewModePickerOpen = true },
                onOpenStylePicker = { notifStylePickerOpen = true },
            )

            // Feedback round 8 — the Safety call recap's "I'm OK" action,
            // built here (it needs the ViewModel, the snackbar host and the
            // two messages) and passed into SecuritySection, where the
            // Safety call rows now live.
            // Hissés au niveau de la composition : les appeler dans le lambda déclencherait
            // `LocalContextGetResourceValueCall` (lint), et un `ctx.getString` dans un
            // rappel ne suit pas les changements de langue.
            val imOkResetMessage = stringResource(R.string.settings_safety_call_im_ok_snack)
            val imOkStopsRelancesMessage =
                stringResource(R.string.settings_safety_call_im_ok_stops_relances)
            val onSafetyCallImOk: () -> Unit = {
                // v1.27.2 — deux sens, selon qu'une séquence de relances est ouverte
                // ou non. Décision de Patrice, 2026-08-05.
                //
                //  - AVANT déclenchement : « Je vais bien » remet le minuteur à zéro,
                //    comportement d'origine.
                //  - APRÈS déclenchement : il **désactive** le Safety Call et arrête
                //    les relances. Le cycle est clos ; la personne a confirmé aller
                //    bien, elle réarmera quand elle le voudra. Se contenter de
                //    remettre le minuteur à zéro laisserait `triggeredAt` posé et les
                //    relances continueraient de partir.
                val safetyCallNow = state.security.safetyCall
                val wasTriggered = safetyCallNow.isTriggered
                viewModel.update { s ->
                    val cfg = s.security.safetyCall
                    s.copy(
                        security = s.security.copy(
                            // v1.27.2 (audit Codex, SC-03) — un seul appel, partagé avec
                            // le tap sur la notification et l'ouverture de
                            // l'application. La branche `isTriggered` oubliait de
                            // remettre les horloges à zéro, et la branche normale
                            // oubliait de fermer la séquence : chacune faisait la
                            // moitié du travail de l'autre.
                            safetyCall = cfg.withActivityReset(
                                disarmIfTriggered = true,
                            ),
                        ),
                    )
                }
                rootScope.launch {
                    snackbarHost.showSnackbar(
                        if (wasTriggered) {
                            imOkStopsRelancesMessage
                        } else {
                            imOkResetMessage
                        },
                    )
                }
            }
            // v1.15.0 — Section extraite vers [SecuritySection] private @Composable.
            SecuritySection(
                security = state.security,
                isPanicDecoy = isPanicDecoy,
                onUpdate = viewModel::update,
                onOpenLockModePicker = { lockModePickerOpen = true },
                onOpenAutoLockDelayPicker = { autoLockDelayPickerOpen = true },
                panicCodeSet = panicCodeSet,
                onOpenPanicCodeSetup = { panicCodeSetupOpen = true },
                onOpenPanicCodeClearConfirm = { panicCodeClearConfirmOpen = true },
                onOpenVaultPinSetup = { vaultPinSetupOpen = true },
                onOpenVaultPinDisable = { vaultPinDisableOpen = true },
                onOpenVaultPinForgetConfirm = { vaultPinForgetConfirmOpen = true },
                onOpenPurgeBlockedConfirm = { showPurgeBlockedConfirm = true },
                onOpenAutoDeletePicker = { autoDeletePickerOpen = true },
                // Feedback round 8 — Safety call, Emergency mode and
                // Blocking now live inside this card.
                blocking = state.blocking,
                onOpenBlocked = onOpenBlocked,
                safetyCallRemainingMs = safetyCallRemainingMs,
                onOpenSafetyCall = onOpenSafetyCall,
                onSafetyCallImOk = onSafetyCallImOk,
                onOpenEmergencySetup = onOpenEmergencySetup,
                onOpenEmergency = onOpenEmergency,
                positionAccordee = localisation.accordee,
                onRequestLocationPermission = localisation.demander,
            )

            // Feedback round 8 — Safety call, Emergency mode and
            // Blocking moved into [SecuritySection] above (one
            // "Security & privacy" card now holds them all). Their
            // decoy guards moved with them.

            // Feedback round 8 — Scheduled messages moved out of Settings
            // to the main screen's 3-dot menu (under "New group"); the
            // section and its navigation are gone from here. The screen
            // itself (ScheduledMessages) is unchanged, and neither is the
            // visibility rule: `ScheduledMessageRepositoryImpl` still hides
            // vault scheduled messages in a decoy session or a closed vault.
            //
            // v1.26.1 (audit C2) — masquée en leurre, comme les sections Safety call et Mode
            // urgence. La sauvegarde exporte les conversations du coffre.
            //
            // ⚠️ v1.28.3 — ce commentaire disait « c'était la SEULE voie de lecture du coffre qui
            // n'était pas gardée ». C'était faux, et la relecture externe (F02) l'a montré :
            // l'écran « Messages programmés » en était une autre, et il rendait le corps et les
            // destinataires en clair. Une affirmation d'exhaustivité vieillit mal — celle-ci a
            // masqué le trou voisin pendant deux versions.
            //
            // Le vrai verrou est côté accès
            // (`BackupService.writeSmsbk` refuse en `PanicDecoy`) ; ceci évite en plus de
            // proposer une action vouée au refus.
            if (!isPanicDecoy) {
                RelaySectionLabel(stringResource(R.string.settings_section_backup))
                RelaySettingsGroup {
                    item(onClick = onOpenBackup) {
                        RelaySettingRow(
                            icon = Icons.Outlined.Backup,
                            title = stringResource(R.string.settings_backup_now),
                            chevron = true,
                        )
                    }
                    item(onClick = onOpenBackup) {
                        RelaySettingRow(
                            icon = Icons.Outlined.Refresh,
                            title = stringResource(R.string.settings_restore),
                            chevron = true,
                        )
                    }
                    item(onClick = onOpenMigration) {
                        RelaySettingRow(
                            icon = Icons.Outlined.SystemUpdate,
                            title = stringResource(R.string.migration_title),
                            chevron = true,
                        )
                    }
                }
            }

            // Feedback round 8 — the bottom card holds the meta rows, in
            // order: App Language (Android 13+ only), Advanced, Help, About.
            // The language row moved here from its own section up the page,
            // Help moved here from the About screen, and Advanced moved here
            // from its own section (feedback round 6 — it lives on its own
            // screen now). The card carries a "General" header like every
            // other section, so the page ends on a titled card instead of a
            // bare one.
            RelaySectionLabel(stringResource(R.string.settings_section_general))
            RelaySettingsGroup {
                // Réservé à Android 13+ : en dessous, la page de langue
                // n'existe pas et l'intent n'ouvrirait rien.
                val languageShown =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                if (languageShown) {
                    item(onClick = { ouvrirLaLangueDeLApplication(ctx) }) {
                        RelaySettingRow(
                            icon = Icons.Outlined.Language,
                            title = stringResource(R.string.settings_language),
                            chevron = true,
                        )
                    }
                }
                item(onClick = onOpenAdvanced) {
                    RelaySettingRow(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.settings_section_advanced),
                        chevron = true,
                    )
                }
                item(onClick = onOpenHelp) {
                    RelaySettingRow(
                        icon = Icons.Outlined.HelpOutline,
                        title = stringResource(R.string.help_title),
                        chevron = true,
                    )
                }
                item(onClick = onOpenAbout) {
                    RelaySettingRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.about_title),
                        chevron = true,
                    )
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    if (textScalePickerOpen) {
        TextScalePickerDialog(
            current = state.appearance.textScale,
            onPick = { scale ->
                textScalePickerOpen = false
                viewModel.update { it.copy(appearance = it.appearance.copy(textScale = scale)) }
            },
            onDismiss = { textScalePickerOpen = false },
        )
    }

    // v1.26.1 (audit F4) — sélecteur du délai de verrouillage automatique.
    if (autoLockDelayPickerOpen) {
        AutoLockDelayPickerDialog(
            current = state.security.autoLockDelay,
            onPick = { delay ->
                autoLockDelayPickerOpen = false
                viewModel.update { it.copy(security = it.security.copy(autoLockDelay = delay)) }
            },
            onDismiss = { autoLockDelayPickerOpen = false },
        )
    }
    if (lockModePickerOpen) {
        val biometricCtx = androidx.compose.ui.platform.LocalContext.current
        // v1.26.0 — libelle resolu ICI, dans la composition, et non via `Context.getString`
        // au fond d'une coroutine : lint le refuse (LocalContextGetResourceValueCall), et il
        // a raison — un changement de langue ne serait pas repercute sur un texte lu hors
        // composition.
        val biometricUnavailableMsg = stringResource(R.string.lock_biometric_unavailable)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        LockModePickerDialog(
            currentMode = state.security.lockMode,
            onPick = { mode ->
                lockModePickerOpen = false
                when (mode) {
                    // v1.25.3 (audit M23) — un simple tap sur « Aucun » supprimait le PIN et
                    // désarmait le verrouillage, sans retour possible. Le geste est aussi
                    // destructif que le retrait du PIN du coffre : même confirmation. No-op
                    // silencieux si le verrou est déjà désactivé — rien à confirmer.
                    com.filestech.sms.domain.settings.LockMode.OFF ->
                        if (state.security.lockMode != com.filestech.sms.domain.settings.LockMode.OFF) {
                            lockClearConfirmOpen = true
                        }
                    com.filestech.sms.domain.settings.LockMode.PIN -> {
                        // PIN-only: opens the 2-field setup. If already on BIOMETRIC, this also
                        // strips the biometric flag back down.
                        if (state.security.lockMode == com.filestech.sms.domain.settings.LockMode.BIOMETRIC) {
                            viewModel.disableBiometric()
                        } else {
                            pinSetupOpen = true
                        }
                    }
                    com.filestech.sms.domain.settings.LockMode.BIOMETRIC -> {
                        // Biometric requires a PIN as fallback. If none yet → run PIN setup first;
                        // the post-PIN flow will flip to BIOMETRIC. Otherwise just promote in place.
                        if (state.security.lockMode == com.filestech.sms.domain.settings.LockMode.OFF) {
                            pinSetupOpen = true
                            // We rely on the user re-picking BIOMETRIC after setting the PIN —
                            // safer than auto-switching behind a successful PIN setup.
                        } else {
                            scope.launch {
                                // v1.25.3 (audit H2) — vérifier la Classe 3 AVANT d'armer. Sans
                                // ce garde, on laissait activer un déverrouillage biométrique que
                                // LockScreen refuserait ensuite de déclencher : le réglage
                                // affichait « Biométrie » pour quelque chose qui ne se produisait
                                // jamais.
                                val ok = com.filestech.sms.ui.security.StrongBiometrics
                                    .isAvailable(biometricCtx) && viewModel.enableBiometricOverPin()
                                if (!ok) {
                                    android.widget.Toast.makeText(
                                        biometricCtx,
                                        biometricUnavailableMsg,
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            },
            onDismiss = { lockModePickerOpen = false },
        )
    }

    if (autoDeletePickerOpen) {
        AutoDeleteDialog(
            current = state.security.autoDeleteOlderThanDays,
            onSelect = { days ->
                // Persist mais on NE ferme PAS le dialog : l'utilisateur peut vouloir
                // enchaîner sur "Effacer maintenant" avec la nouvelle profondeur.
                viewModel.update { it.copy(security = it.security.copy(autoDeleteOlderThanDays = days)) }
            },
            onPurgeNow = { /* delegated to confirm dialog */ },
            onRequestConfirm = { autoDeletePurgeConfirmOpen = true },
            onDismiss = { autoDeletePickerOpen = false },
        )
    }

    if (autoDeletePurgeConfirmOpen) {
        val days = state.security.autoDeleteOlderThanDays ?: 0
        if (days > 0) {
            PurgeNowConfirmDialog(
                days = days,
                countLoader = { viewModel.countHistoryToPurge(days) },
                onConfirm = {
                    viewModel.purgeHistoryNow(days)
                    autoDeletePurgeConfirmOpen = false
                    autoDeletePickerOpen = false
                },
                onDismiss = { autoDeletePurgeConfirmOpen = false },
            )
        } else {
            // Cas sentinelle : `days = 0` ne devrait pas arriver car le bouton est
            // disabled, mais on ferme proprement si on y est tombé via une race.
            autoDeletePurgeConfirmOpen = false
        }
    }

    if (pinSetupOpen) {
        PinSetupDialog(
            onConfirm = { pin ->
                viewModel.setPin(pin)
                pinSetupOpen = false
            },
            onDismiss = { pinSetupOpen = false },
        )
    }

    // v1.26.0 — meme dialogue que le PIN principal, titre different. Le refus d'un code
    // identique au PIN est traite dans [AppLockManager.setPanicCode] et remonte par un evenement.
    if (panicCodeSetupOpen) {
        PinSetupDialog(
            titleRes = R.string.settings_panic_code,
            onConfirm = { code ->
                viewModel.setPanicCode(code)
                panicCodeSetupOpen = false
            },
            onDismiss = { panicCodeSetupOpen = false },
        )
    }

    if (panicCodeClearConfirmOpen) {
        RelayDialog(
            onDismissRequest = { panicCodeClearConfirmOpen = false },
            title = stringResource(R.string.settings_panic_code_clear_title),
            content = { Text(stringResource(R.string.settings_panic_code_clear_body)) },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.settings_panic_code_clear_action),
                    onClick = {
                        viewModel.clearPanicCode()
                        panicCodeClearConfirmOpen = false
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { panicCodeClearConfirmOpen = false },
                )
            },
        )
    }

    if (previewModePickerOpen) {
        PreviewModePickerDialog(
            current = state.notifications.previewMode,
            onSelect = { mode ->
                viewModel.update {
                    it.copy(notifications = it.notifications.copy(previewMode = mode))
                }
                previewModePickerOpen = false
            },
            onDismiss = { previewModePickerOpen = false },
        )
    }

    if (notifStylePickerOpen) {
        NotificationStylePickerDialog(
            current = state.notifications.style,
            onSelect = { style ->
                viewModel.update {
                    it.copy(notifications = it.notifications.copy(style = style))
                }
                notifStylePickerOpen = false
            },
            onDismiss = { notifStylePickerOpen = false },
        )
    }

    // v1.8.0 — Dialog de confirmation : Purger les conversations bloquées.
    // Action IRRÉVERSIBLE → autofocus Cancel critique + couleur error pour
    // éviter qu'un user clique "OK" par réflexe et perde ses conv.
    if (showPurgeBlockedConfirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        RelayDialog(
            onDismissRequest = { showPurgeBlockedConfirm = false },
            title = stringResource(R.string.settings_purge_blocked_confirm_title),
            content = { Text(stringResource(R.string.settings_purge_blocked_confirm_body)) },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        viewModel.purgeBlockedConversations()
                        showPurgeBlockedConfirm = false
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showPurgeBlockedConfirm = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                )
            },
        )
    }

    if (reactionFormatPickerOpen) {
        ReactionFormatPickerDialog(
            current = state.sending.reactionFormat,
            onSelect = { fmt ->
                viewModel.update {
                    // v1.8.0 (bug 5 fix) — synchronise le legacy `reactionEmojiOnly`
                    // au passage pour qu'un downgrade éventuel vers v1.7.x retrouve
                    // un état cohérent (EMOJI_ONLY → true, autres → false).
                    val legacyEmojiOnly = fmt == com.filestech.sms.domain.model.ReactionFormat.EMOJI_ONLY
                    it.copy(
                        sending = it.sending.copy(
                            reactionFormat = fmt,
                            reactionEmojiOnly = legacyEmojiOnly,
                        ),
                    )
                }
                reactionFormatPickerOpen = false
            },
            onDismiss = { reactionFormatPickerOpen = false },
        )
    }

    if (defaultRegionPickerOpen) {
        DefaultRegionPickerDialog(
            current = state.sending.defaultRegionIso,
            onSelect = { iso ->
                viewModel.update { it.copy(sending = it.sending.copy(defaultRegionIso = iso)) }
                defaultRegionPickerOpen = false
            },
            onDismiss = { defaultRegionPickerOpen = false },
        )
    }

    // v1.13.0 — Dialog setup PIN/pass coffre : saisie + confirmation.
    // v1.27.10 — le MEME dialogue sert au remplacement, avec un champ « PIN actuel » en tete
    // des lors qu'un PIN est deja en place. Un seul aller-retour, donc aucune autorisation a
    // faire vivre entre deux dialogues — c'est ce report d'etat qui aurait recree la faille.
    if (vaultPinSetupOpen) {
        VaultPinSetupDialog(
            appPinHint = state.security.lockMode == com.filestech.sms.domain.settings.LockMode.PIN ||
                state.security.lockMode == com.filestech.sms.domain.settings.LockMode.BIOMETRIC,
            requireCurrent = state.security.vaultPinEnabled,
            onSubmit = { current, newPin -> viewModel.submitVaultPin(current, newPin) },
            probeLockout = { viewModel.vaultLockoutRemainingMs() },
            onSaved = {
                vaultPinSetupOpen = false
                rootScope.launch {
                    snackbarHost.showSnackbar(ctx.getString(R.string.settings_vault_pin_saved))
                }
            },
            onDismiss = { vaultPinSetupOpen = false },
        )
    }

    // v1.25.3 (audit M23) — Dialog confirmation désactivation du verrou de l'app. Même patron
    // que le retrait du PIN du coffre juste en dessous : confirm rouge destructif, Annuler autofocus.
    if (lockClearConfirmOpen) {
        val cancelFocus = remember { FocusRequester() }
        // `runCatching` comme dans `DestructiveConfirmDialog` : `requestFocus()` jette si le nœud
        // n'est pas encore attaché, et perdre l'autofocus vaut mieux que planter les Réglages.
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        RelayDialog(
            onDismissRequest = { lockClearConfirmOpen = false },
            title = stringResource(R.string.settings_lock_clear_confirm_title),
            content = { Text(stringResource(R.string.settings_lock_clear_confirm_body)) },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.settings_lock_clear_confirm_action),
                    onClick = {
                        viewModel.clearLock()
                        lockClearConfirmOpen = false
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { lockClearConfirmOpen = false },
                    modifier = Modifier.focusRequester(cancelFocus).focusable(),
                )
            },
        )
    }

    // v1.13.0 — retrait du PIN/pass coffre (l'utilisateur desactive le toggle).
    //
    // v1.27.10 (revue externe GitLab !38458) — c'etait une SIMPLE confirmation. Retirer le
    // second facteur ne demandait donc que le premier, et quiconque connaissait le PIN
    // d'application ouvrait le coffre en deux tapes. Le PIN en place est desormais exige, sous
    // la meme temporisation que l'entree dans le coffre.
    if (vaultPinDisableOpen) {
        com.filestech.sms.ui.components.PinEntryDialog(
            title = stringResource(R.string.settings_vault_pin_clear_confirm_title),
            description = stringResource(R.string.settings_vault_pin_clear_confirm_body),
            confirmLabel = stringResource(R.string.action_disable),
            onVerify = { candidate -> viewModel.disableVaultPin(candidate) },
            probeLockout = { viewModel.vaultLockoutRemainingMs() },
            onVerified = {
                vaultPinDisableOpen = false
                rootScope.launch {
                    snackbarHost.showSnackbar(ctx.getString(R.string.settings_vault_pin_cleared))
                }
            },
            onCancel = { vaultPinDisableOpen = false },
        )
    }

    // v1.27.10 — porte de sortie « PIN du coffre oublie ». Elle EXISTE, parce qu'un secret
    // irrecuperable sans issue est un piege ; elle est DESTRUCTIVE, parce qu'une issue gratuite
    // serait le contournement qu'on vient de fermer. Le contenu du coffre part avec le PIN.
    if (vaultPinForgetConfirmOpen) {
        val cancelFocus = remember { FocusRequester() }
        // Même garde que les autres confirmations destructives de l'écran.
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        RelayDialog(
            onDismissRequest = { vaultPinForgetConfirmOpen = false },
            title = stringResource(R.string.settings_vault_pin_forgot_confirm_title),
            content = { Text(stringResource(R.string.settings_vault_pin_forgot_confirm_body)) },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.settings_vault_pin_forgot_confirm_action),
                    onClick = {
                        viewModel.forgetVaultPinAndPurge()
                        vaultPinForgetConfirmOpen = false
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { vaultPinForgetConfirmOpen = false },
                    modifier = Modifier.focusRequester(cancelFocus).focusable(),
                )
            },
        )
    }

    // v1.28.2 — la sortie de secours de la sortie de secours.
    //
    // Elle n'existe que parce que le refus prudent de la v1.28.1 pouvait devenir une IMPASSE :
    // une liaison durablement fausse — un `telephony_uri` restaure d'un autre telephone — fait
    // echouer le garde d'identite a chaque essai, a l'identique, et l'utilisateur qui a oublie
    // son PIN n'avait plus d'issue. Une porte de sortie qui ne s'ouvre jamais n'en est pas une.
    //
    // Elle ne s'affiche qu'au SECOND echec, et elle dit ce qu'elle coute avant d'agir : le
    // consentement doit etre eclaire, sinon ce n'est pas un consentement.
    val resteAuTelephone = vaultPinForceConfirm
    if (resteAuTelephone != null) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        RelayDialog(
            onDismissRequest = { vaultPinForceConfirm = null },
            title = stringResource(R.string.settings_vault_pin_force_title),
            content = {
                Text(
                    stringResource(R.string.settings_vault_pin_force_body, resteAuTelephone),
                )
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.settings_vault_pin_force_action),
                    onClick = {
                        viewModel.forgetVaultPinAndPurge(force = true)
                        vaultPinForceConfirm = null
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { vaultPinForceConfirm = null },
                    modifier = Modifier.focusRequester(cancelFocus).focusable(),
                )
            },
        )
    }

    // v1.14.1 — `EmergencyCallBehaviorPickerDialog` retiré (setting orphelin
    // post-redesign EmergencyScreen full-page direct-call).
}

/**
 * v1.8.0 (bug 3 fix HIGH 3a) — picker pour [com.filestech.sms.data.local.datastore
 * .PreviewMode]. Pattern strictement aligné sur [LockModePickerDialog] (radio + label +
 * hint sous-titre pour les options dont l'effet est non-évident).
 *
 * Le hint sous l'option `NEVER` est crucial : c'est exactement le scénario du bug 3
 * remonté par l'utilisateur (son joué, notif invisible). Documenter explicitement
 * "le son est joué mais rien ne s'affiche sur l'écran de verrouillage" élimine la
 * confusion.
 */
@Composable
private fun PreviewModePickerDialog(
    current: com.filestech.sms.domain.settings.PreviewMode,
    onSelect: (com.filestech.sms.domain.settings.PreviewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    RelayPickerDialog(
        title = stringResource(R.string.settings_notif_preview),
        options = listOf(
            RelayPickerOption(
                label = stringResource(R.string.settings_notif_preview_always),
                selected = current == com.filestech.sms.domain.settings.PreviewMode.ALWAYS,
                onClick = { onSelect(com.filestech.sms.domain.settings.PreviewMode.ALWAYS) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_notif_preview_unlocked),
                hint = stringResource(R.string.settings_notif_preview_unlocked_hint),
                selected = current == com.filestech.sms.domain.settings.PreviewMode.WHEN_UNLOCKED,
                onClick = { onSelect(com.filestech.sms.domain.settings.PreviewMode.WHEN_UNLOCKED) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_notif_preview_never),
                hint = stringResource(R.string.settings_notif_preview_never_hint),
                selected = current == com.filestech.sms.domain.settings.PreviewMode.NEVER,
                onClick = { onSelect(com.filestech.sms.domain.settings.PreviewMode.NEVER) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

/**
 * v1.8.0 (bug 3 fix MEDIUM 3b) — picker pour [com.filestech.sms.data.local.datastore
 * .NotificationStyle] (anciennement dead field). Wire l'enum à l'effet réel côté
 * [com.filestech.sms.system.notifications.IncomingMessageNotifier] :
 *  - HEADS_UP → canal IMPORTANCE_HIGH (default Android, heads-up + son)
 *  - BANNER → canal IMPORTANCE_HIGH mais son désactivé côté builder (badge seul)
 *  - SILENT → canal IMPORTANCE_LOW dédié (heads-up et son masqués par l'OS)
 */
/**
 * v1.8.0 (bug 5 fix) — picker pour [com.filestech.sms.domain.model.ReactionFormat].
 * 3 options avec hints expliquant clairement le trade-off (lisibilité francophone vs
 * compat iMessage vs minimalisme).
 */
@Composable
private fun ReactionFormatPickerDialog(
    current: com.filestech.sms.domain.model.ReactionFormat,
    onSelect: (com.filestech.sms.domain.model.ReactionFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    RelayPickerDialog(
        title = stringResource(R.string.settings_reaction_format_title),
        options = listOf(
            RelayPickerOption(
                label = stringResource(R.string.settings_reaction_format_ios),
                hint = stringResource(R.string.settings_reaction_format_ios_hint),
                selected = current == com.filestech.sms.domain.model.ReactionFormat.IOS_TAPBACK,
                onClick = { onSelect(com.filestech.sms.domain.model.ReactionFormat.IOS_TAPBACK) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_reaction_format_fr),
                hint = stringResource(R.string.settings_reaction_format_fr_hint),
                selected = current == com.filestech.sms.domain.model.ReactionFormat.READABLE_FR,
                onClick = { onSelect(com.filestech.sms.domain.model.ReactionFormat.READABLE_FR) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_reaction_format_en),
                hint = stringResource(R.string.settings_reaction_format_en_hint),
                selected = current == com.filestech.sms.domain.model.ReactionFormat.TAPBACK_EN,
                onClick = { onSelect(com.filestech.sms.domain.model.ReactionFormat.TAPBACK_EN) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_reaction_format_emoji_quote),
                hint = stringResource(R.string.settings_reaction_format_emoji_quote_hint),
                selected = current == com.filestech.sms.domain.model.ReactionFormat.EMOJI_WITH_QUOTE,
                onClick = { onSelect(com.filestech.sms.domain.model.ReactionFormat.EMOJI_WITH_QUOTE) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_reaction_format_emoji),
                hint = stringResource(R.string.settings_reaction_format_emoji_hint),
                selected = current == com.filestech.sms.domain.model.ReactionFormat.EMOJI_ONLY,
                onClick = { onSelect(com.filestech.sms.domain.model.ReactionFormat.EMOJI_ONLY) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

/**
 * v1.21.0 — picker de l'indicatif pays par défaut utilisé pour convertir les numéros nationaux
 * en E.164 avant l'envoi. Première option = "Auto" (pays de la SIM, `null`) ; suivent quelques
 * pays européens courants. Les noms de pays sont localisés automatiquement par [regionDisplayName]
 * (pas de chaîne à traduire), le drapeau est dérivé de l'ISO via [isoToFlagEmoji].
 */
@Composable
private fun DefaultRegionPickerDialog(
    current: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val autoOption = RelayPickerOption(
        label = stringResource(R.string.settings_default_region_auto),
        hint = stringResource(R.string.settings_default_region_auto_hint),
        selected = current == null,
        onClick = { onSelect(null) },
    )
    val regionOptions = DEFAULT_REGION_OPTIONS.map { opt ->
        RelayPickerOption(
            label = "${isoToFlagEmoji(opt.iso)} ${regionDisplayName(opt.iso)}",
            hint = opt.dialCode,
            selected = current.equals(opt.iso, ignoreCase = true),
            onClick = { onSelect(opt.iso) },
        )
    }
    RelayPickerDialog(
        title = stringResource(R.string.settings_default_region_title),
        options = listOf(autoOption) + regionOptions,
        onDismiss = onDismiss,
    )
}

/** ISO 3166-1 alpha-2 + dial code for the default-region picker. */
private data class RegionOption(val iso: String, val dialCode: String)

/** Curated shortlist — France + the countries her circle most often writes to. "Auto" (SIM) is
 *  offered separately as the first, default option. For any other country, storing contacts in
 *  `+CC…` international form already works regardless of this setting. */
private val DEFAULT_REGION_OPTIONS = listOf(
    RegionOption("FR", "+33"),
    RegionOption("BE", "+32"),
    RegionOption("LU", "+352"),
    RegionOption("CH", "+41"),
    RegionOption("DE", "+49"),
    RegionOption("ES", "+34"),
    RegionOption("IT", "+39"),
    RegionOption("PT", "+351"),
    RegionOption("NL", "+31"),
    RegionOption("GB", "+44"),
)

/** Localised country name for an ISO code (e.g. "FR" → "France" / "France"), falling back to the
 *  raw ISO if the platform has no display name. Uses the JVM `Locale` country DB — zero strings. */
private fun regionDisplayName(iso: String): String =
    java.util.Locale("", iso).displayCountry.ifBlank { iso }

/** Flag emoji from an ISO country code by mapping each letter to its Regional Indicator Symbol. */
private fun isoToFlagEmoji(iso: String): String {
    if (iso.length != 2) return ""
    val cc = iso.uppercase()
    if (cc[0] !in 'A'..'Z' || cc[1] !in 'A'..'Z') return ""
    val base = 0x1F1E6 // Regional Indicator Symbol Letter A
    return buildString {
        appendCodePoint(base + (cc[0] - 'A'))
        appendCodePoint(base + (cc[1] - 'A'))
    }
}

@Composable
private fun NotificationStylePickerDialog(
    current: com.filestech.sms.domain.settings.NotificationStyle,
    onSelect: (com.filestech.sms.domain.settings.NotificationStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    RelayPickerDialog(
        title = stringResource(R.string.settings_notif_style),
        options = listOf(
            RelayPickerOption(
                label = stringResource(R.string.settings_notif_style_heads_up),
                hint = stringResource(R.string.settings_notif_style_heads_up_hint),
                selected = current == com.filestech.sms.domain.settings.NotificationStyle.HEADS_UP,
                onClick = { onSelect(com.filestech.sms.domain.settings.NotificationStyle.HEADS_UP) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_notif_style_banner),
                hint = stringResource(R.string.settings_notif_style_banner_hint),
                selected = current == com.filestech.sms.domain.settings.NotificationStyle.BANNER,
                onClick = { onSelect(com.filestech.sms.domain.settings.NotificationStyle.BANNER) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.settings_notif_style_silent),
                hint = stringResource(R.string.settings_notif_style_silent_hint),
                selected = current == com.filestech.sms.domain.settings.NotificationStyle.SILENT,
                onClick = { onSelect(com.filestech.sms.domain.settings.NotificationStyle.SILENT) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

/**
 * v1.26.1 (audit F4) — sélecteur du délai de verrouillage automatique.
 *
 * `SecuritySettings.autoLockDelay` était LU par la production ([AutoLockObserver] s'en sert pour
 * décider quand appeler `forceLock()` et purger les caches en clair) mais AUCUN écran ne
 * permettait de le changer : le délai était donc figé sur son défaut d'une minute pour tout le
 * monde, et les cinq valeurs de l'enum étaient inatteignables. C'est un réglage de sécurité que
 * l'utilisateur ne pouvait ni durcir, ni assouplir — alors que `lockMode`, sa ligne voisine,
 * avait bien son sélecteur.
 */
@Composable
private fun AutoLockDelayPickerDialog(
    current: AutoLockDelay,
    onPick: (AutoLockDelay) -> Unit,
    onDismiss: () -> Unit,
) {
    RelayPickerDialog(
        title = stringResource(R.string.settings_auto_lock_delay_dialog_title),
        options = autoLockDelayOptions().map { (delay, label) ->
            RelayPickerOption(
                label = label,
                selected = current == delay,
                onClick = { onPick(delay) },
            )
        },
        onDismiss = onDismiss,
    )
}

/**
 * Libellés des cinq délais, résolus dans la composition (un `getString` dans le corps du dialogue
 * déclencherait `LocalContextGetResourceValueCall`). L'ordre est celui du plus strict au plus
 * permissif, pour que la lecture de haut en bas soit celle d'un curseur de sécurité.
 */
@Composable
private fun autoLockDelayOptions(): List<Pair<AutoLockDelay, String>> = listOf(
    AutoLockDelay.IMMEDIATE to stringResource(R.string.auto_lock_immediate),
    AutoLockDelay.FIFTEEN_SECONDS to stringResource(R.string.auto_lock_15s),
    AutoLockDelay.ONE_MINUTE to stringResource(R.string.auto_lock_1m),
    AutoLockDelay.FIVE_MINUTES to stringResource(R.string.auto_lock_5m),
    AutoLockDelay.NEXT_LAUNCH to stringResource(R.string.auto_lock_next_launch),
)

/**
 * Lock-mode picker — v1.1.x exposes only **None** and **PIN**. Pattern / Biometric ride on the
 * same `LockMode` enum but their UX (system biometric prompt, pattern grid) is reserved for a
 * later iteration.
 *
 * The dialog is purely a selector; actual lock setup happens in [PinSetupDialog] when the user
 * picks "PIN".
 */
@Composable
private fun LockModePickerDialog(
    currentMode: com.filestech.sms.domain.settings.LockMode,
    onPick: (com.filestech.sms.domain.settings.LockMode) -> Unit,
    onDismiss: () -> Unit,
) {
    RelayPickerDialog(
        title = stringResource(R.string.lock_mode_dialog_title),
        options = listOf(
            RelayPickerOption(
                label = stringResource(R.string.lock_mode_off),
                selected = currentMode == com.filestech.sms.domain.settings.LockMode.OFF,
                onClick = { onPick(com.filestech.sms.domain.settings.LockMode.OFF) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.lock_mode_pin),
                selected = currentMode == com.filestech.sms.domain.settings.LockMode.PIN,
                onClick = { onPick(com.filestech.sms.domain.settings.LockMode.PIN) },
            ),
            RelayPickerOption(
                label = stringResource(R.string.lock_mode_biometric),
                selected = currentMode == com.filestech.sms.domain.settings.LockMode.BIOMETRIC,
                onClick = { onPick(com.filestech.sms.domain.settings.LockMode.BIOMETRIC) },
            ),
        ),
        onDismiss = onDismiss,
    )
}

/**
 * Small secondary label above a filled field inside a dialog — the filled
 * [RelayTextField] carries no floating label of its own.
 */
@Composable
private fun DialogFieldLabel(text: String) {
    Text(
        text = text,
        style = RelayText.Timestamp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Two-field PIN-setup dialog. Both fields hold the secret in a `CharArray` derived from the
 * `TextFieldValue.text` snapshot so we can wipe it deterministically when the dialog goes
 * away or after the manager has consumed it.
 *
 * Validation:
 *  - digits only ([Char.isDigit]),
 *  - length 4–12,
 *  - both fields must match.
 *
 * On confirm, the new PIN is handed to the caller as a fresh `CharArray`. The dialog's own
 * working buffers are wiped synchronously before [onConfirm] returns control to recomposition.
 */
@Composable
private fun PinSetupDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    // v1.26.0 — le titre devient paramétrable : le même dialogue sert au PIN principal et au
    // code panique. Deux dialogues jumeaux auraient divergé à la première correction — c'est
    // l'asymétrie qui a produit la moitié des défauts de cette semaine.
    @androidx.annotation.StringRes titleRes: Int = R.string.pin_setup_title,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val digitsOnly = remember(pin, confirm) {
        pin.all { it.isDigit() } && confirm.all { it.isDigit() }
    }
    val tooShort = pin.length < 4
    val mismatch = pin.isNotEmpty() && confirm.isNotEmpty() && pin != confirm
    val canSubmit = digitsOnly && !tooShort && pin == confirm

    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(titleRes),
        content = {
            // v1.27.1 (N3) — DANS le contenu du dialogue (PIN principal ET code panique).
            ProtectSecretInput()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogFieldLabel(stringResource(R.string.pin_setup_field))
                    RelayTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 12) pin = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogFieldLabel(stringResource(R.string.pin_setup_confirm))
                    RelayTextField(
                        value = confirm,
                        onValueChange = { if (it.length <= 12) confirm = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                    )
                }
                val errorMessage = when {
                    !digitsOnly -> stringResource(R.string.pin_setup_digits_only)
                    pin.isNotEmpty() && tooShort -> stringResource(R.string.pin_setup_too_short)
                    mismatch -> stringResource(R.string.pin_setup_mismatch)
                    else -> null
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = RelayText.Timestamp,
                    )
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_confirm),
                onClick = {
                    val chars = pin.toCharArray()
                    pin = ""
                    confirm = ""
                    onConfirm(chars)
                },
                enabled = canSubmit,
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = {
                    pin = ""
                    confirm = ""
                    onDismiss()
                },
            )
        },
    )
}

/**
 * Visual theme picker: a horizontal row of three phone-shaped swatches, each rendering a tiny
 * mock of the conversation list under the corresponding theme (background, accent strip, two
 * message-bubble bars). The active swatch is wrapped in a primary-colored ring; tapping any
 * swatch commits the selection immediately.
 *
 * Beats both the previous radio list and the dropdown attempt: the user sees the actual look
 * of the theme before committing, and the three-up layout makes the choice scannable at a glance
 * (no menu to open, no scrolling).
 *
 * Feedback round 4 (2026-11-07) — the "Dark Tech" swatch is removed: Settings offers
 * System, Light and Dark only. A stored `DARK_TECH` value falls back to System at read
 * time (`SettingsRepository.enumOr`), so no migration is needed.
 */
@Composable
private fun ThemeSwatchPicker(
    current: com.filestech.sms.domain.settings.ThemeMode,
    onSelect: (com.filestech.sms.domain.settings.ThemeMode) -> Unit,
) {
    val items = listOf(
        com.filestech.sms.domain.settings.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        com.filestech.sms.domain.settings.ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        com.filestech.sms.domain.settings.ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { (mode, label) ->
                ThemeSwatch(
                    mode = mode,
                    label = label,
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    mode: com.filestech.sms.domain.settings.ThemeMode,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = swatchPalette(mode)
    // Round 11 — the selected swatch's accent (ring, check badge, label)
    // matches the "Set as default" button: light in dark mode, brand blue
    // in light mode. Same surface-luminance split as the button, the
    // recipient pills and the settings toggles.
    val cs = MaterialTheme.colorScheme
    val surfaceLuma = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val accent = if (surfaceLuma < 0.5f) Color(0xFFE5EAFD) else cs.primary
    val onAccent = if (surfaceLuma < 0.5f) Color(0xFF001A40) else cs.onPrimary
    val ringColor = if (selected) accent else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .border(width = 2.dp, color = ringColor, shape = RoundedCornerShape(14.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.background),
        ) {
            // Tiny mock-UI: header strip (accent), two bars (incoming bubble + outgoing bubble).
            Column(modifier = Modifier.fillMaxSize().padding(7.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.incomingBubble),
                )
                Spacer(Modifier.size(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.incomingBubble.copy(alpha = 0.75f)),
                )
                Spacer(Modifier.size(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.outgoingBubble),
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = onAccent,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Hand-picked palette used to render each [ThemeSwatch] preview. */
private data class SwatchPalette(
    val background: Color,
    val accent: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
)

@Composable
private fun swatchPalette(mode: com.filestech.sms.domain.settings.ThemeMode): SwatchPalette =
    when (mode) {
        com.filestech.sms.domain.settings.ThemeMode.SYSTEM -> SwatchPalette(
            // Split-style: half light, half dark — we use the light side and a contrast accent.
            background = Color(0xFFF2F4F8),
            accent = Color(0xFF1F2937),
            incomingBubble = Color(0xFFD8DEE9),
            outgoingBubble = Color(0xFF1F2937),
        )
        com.filestech.sms.domain.settings.ThemeMode.LIGHT -> SwatchPalette(
            background = Color(0xFFFFFFFF),
            accent = RelayColors.Blue,
            incomingBubble = Color(0xFFE7ECF3),
            outgoingBubble = RelayColors.Blue,
        )
        com.filestech.sms.domain.settings.ThemeMode.DARK -> SwatchPalette(
            background = Color(0xFF15171C),
            accent = Color(0xFF82B1FF),
            incomingBubble = Color(0xFF2A2E36),
            outgoingBubble = Color(0xFF3D6FE0),
        )
    }

// ─────────────────────────────────────────────────────────────────────────────
//  v1.2.6 audit F4 — "Mon numéro" row + dialog + auto-detection helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result d'une tentative de détection automatique du MSISDN du SIM par défaut.
 *  - [Success] : un numéro a été trouvé.
 *  - [PermissionDenied] : `READ_PHONE_NUMBERS` n'est pas accordée — message dédié à l'UI.
 *  - [Unavailable] : permission OK mais l'OS ne connaît pas le numéro (Free Mobile FR, MVNO).
 */
private sealed interface MsisdnDetection {
    data class Success(val msisdn: String) : MsisdnDetection
    data object PermissionDenied : MsisdnDetection
    data object Unavailable : MsisdnDetection
}

/**
 * Lit le MSISDN via `SubscriptionManager`. Discrimine "permission révoquée" vs "OS ne sait pas"
 * pour pouvoir afficher un message d'aide ciblé. Pas de magie : si la perm n'est pas accordée,
 * on ne fait pas l'appel système (évite `SecurityException` silencieusement attrapée v1.2.6).
 *
 * v1.2.7 audits S4 (checkSelfPermission strict) + Q6 (l'appel doit être fait sur Dispatchers.IO
 * par le caller — `getSystemService` + `activeSubscriptionInfoList` font un binder IPC qui peut
 * geler le main thread sur Samsung).
 *
 * Round 14 — la lecture elle-même vit désormais dans [readSimMsisdn], partagée avec le
 * chemin de réception des MMS : une seule définition des trois sources, au lieu de deux
 * copies qui dériveraient. Ce qui reste ici, c'est la distinction UI « permission » /
 * « indisponible », que le chemin de réception n'a pas à faire.
 */
@android.annotation.SuppressLint("MissingPermission")
private fun detectMsisdn(context: android.content.Context): MsisdnDetection {
    // v1.2.7 audit S4 : check runtime permission AVANT l'appel binder. On rate l'appel propre
    // au lieu de le faire crasher silencieusement dans le catch.
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_PHONE_NUMBERS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return MsisdnDetection.PermissionDenied

    val number = readSimMsisdn(context, null)
    return if (number.isNullOrBlank()) {
        MsisdnDetection.Unavailable
    } else {
        MsisdnDetection.Success(number)
    }
}

/** v1.2.7 audit Q7 : regex de validation MSISDN — autorise +, digits, espaces, tirets, parens. */
private val MSISDN_PATTERN = Regex("^\\+?[0-9 ()\\-]{4,20}$")

/**
 * v1.26.1 (audit F3) — réglage de la signature automatique.
 *
 * C'était le motif du code panique à l'identique : le mécanisme était **complet et branché en
 * production** — [com.filestech.sms.domain.usecase.SendSmsUseCase] ajoute `"\n--\n$signature"` à
 * CHAQUE SMS sortant, et le champ est câblé aux trois points DataStore — mais aucun écran ne
 * permettait de le renseigner. Le champ restait donc `null` à jamais et la fonctionnalité était
 * inerte, tandis que la chaîne `settings_signature` traînait, orpheline, dans les ressources.
 *
 * Champ multi-ligne volontairement borné : une signature longue bascule le SMS en multi-part,
 * donc en facturation multiple. Le compteur le rappelle.
 */
@Composable
private fun SignatureRow(current: String?, onChange: (String?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var dialogOpen by remember { mutableStateOf(false) }
    RelaySettingRow(
        icon = Icons.Outlined.Edit,
        title = stringResource(R.string.settings_signature),
        onClick = { dialogOpen = true },
        chevron = true,
    )
    if (dialogOpen) {
        var value by remember { mutableStateOf(current.orEmpty()) }
        RelayDialog(
            onDismissRequest = { dialogOpen = false },
            title = stringResource(R.string.settings_signature),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.settings_signature_dialog_body),
                        style = RelayText.Timestamp,
                        color = cs.onSurfaceVariant,
                    )
                    RelayTextField(
                        value = value,
                        onValueChange = { if (it.length <= SIGNATURE_MAX_CHARS) value = it },
                        placeholder = stringResource(R.string.settings_signature_placeholder),
                    )
                    Text(
                        text = "${value.length} / $SIGNATURE_MAX_CHARS",
                        style = RelayText.Timestamp,
                        color = cs.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        dialogOpen = false
                        // Une signature vide vaut « pas de signature » : on repose `null` pour
                        // que `SendSmsUseCase` retombe sur sa branche sans suffixe.
                        onChange(value.trim().takeIf { it.isNotBlank() })
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { dialogOpen = false },
                )
            },
        )
    }
}

/** v1.26.1 — plafond de la signature : au-delà, le SMS bascule en multi-part (facturation ×N). */
private const val SIGNATURE_MAX_CHARS = 80

@Composable
private fun MyNumberRow(current: String?, onChange: (String?) -> Unit) {
    var dialogOpen by remember { mutableStateOf(false) }
    RelaySettingRow(
        icon = Icons.Outlined.PhoneAndroid,
        title = stringResource(R.string.settings_my_number),
        onClick = { dialogOpen = true },
        chevron = true,
    )
    if (dialogOpen) {
        MyNumberDialog(
            initial = current.orEmpty(),
            onDismiss = { dialogOpen = false },
            onConfirm = { value ->
                dialogOpen = false
                onChange(value)
            },
        )
    }
}

@Composable
private fun MyNumberDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initial) }
    // v1.2.7 audit Q16 : le précédent SnackbarHost imbriqué dans le contenu du dialogue ne
    // s'affichait jamais (le host n'a pas de surface dans le content slot du dialog). On
    // remplace par un message inline placé sous le bouton "Détecter".
    var detectFeedback by remember { mutableStateOf<MsisdnDetection?>(null) }
    val cs = MaterialTheme.colorScheme

    // v1.2.8 : si la permission READ_PHONE_NUMBERS n'est pas accordée au moment de tap
    // "Détecter", on la demande automatiquement au lieu d'afficher juste un message d'erreur.
    // Si l'utilisateur accepte, on relance la détection ; sinon on affiche le feedback.
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                val result = withContext(kotlinx.coroutines.Dispatchers.IO) { detectMsisdn(context) }
                when (result) {
                    is MsisdnDetection.Success -> {
                        value = result.msisdn
                        detectFeedback = null
                    }
                    else -> detectFeedback = result
                }
            }
        } else {
            detectFeedback = MsisdnDetection.PermissionDenied
        }
    }

    // v1.2.7 audit Q7 : la valeur est valide si vide (= clear) OU matche le pattern MSISDN.
    val canSubmit = value.isBlank() || MSISDN_PATTERN.matches(value)

    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_my_number_dialog_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_my_number_dialog_body),
                    style = RelayText.Timestamp,
                    color = cs.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RelayTextField(
                        value = value,
                        onValueChange = { value = it.trim() },
                        placeholder = stringResource(R.string.settings_my_number_placeholder),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                        ),
                    )
                    if (value.isNotBlank() && !MSISDN_PATTERN.matches(value)) {
                        Text(
                            text = stringResource(R.string.settings_my_number_invalid),
                            style = RelayText.Timestamp,
                            color = cs.error,
                        )
                    }
                }
                RelayTextButton(
                    text = stringResource(R.string.settings_my_number_detect),
                    onClick = {
                        // v1.2.7 audit Q6 : detectMsisdn fait un binder IPC ; on l'exécute
                        // sur Dispatchers.IO pour ne pas geler le main thread.
                        // v1.2.8 : si la permission n'est pas encore accordée, on la demande
                        // d'abord — le callback du launcher relance la détection.
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.READ_PHONE_NUMBERS,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permissionLauncher.launch(android.Manifest.permission.READ_PHONE_NUMBERS)
                            return@RelayTextButton
                        }
                        scope.launch {
                            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                detectMsisdn(context)
                            }
                            when (result) {
                                is MsisdnDetection.Success -> {
                                    value = result.msisdn
                                    detectFeedback = null
                                }
                                else -> detectFeedback = result
                            }
                        }
                    },
                )
                detectFeedback?.let { fb ->
                    val msgRes = when (fb) {
                        MsisdnDetection.PermissionDenied -> R.string.settings_my_number_detect_no_perm
                        MsisdnDetection.Unavailable -> R.string.settings_my_number_detect_failed
                        is MsisdnDetection.Success -> null // jamais ici
                    }
                    if (msgRes != null) {
                        Text(
                            text = stringResource(msgRes),
                            style = RelayText.Timestamp,
                            color = cs.error,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = { onConfirm(value) },
                enabled = canSubmit,
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * v1.8.1 — row Settings pour saisir/effacer le nom personnel inclus dans les
 * SMS de réaction sortants au format `READABLE_FR`. `null` = utiliser l'auto-
 * détection via `ContactsContract.Profile` (le "moi" Android).
 */
@Composable
private fun SenderNameRow(current: String?, onChange: (String?) -> Unit) {
    var dialogOpen by remember { mutableStateOf(false) }
    RelaySettingRow(
        icon = Icons.Outlined.PersonOutline,
        title = stringResource(R.string.settings_sender_name_title),
        subtitle = current?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.settings_sender_name_auto),
        onClick = { dialogOpen = true },
        chevron = true,
    )
    if (dialogOpen) {
        SenderNameDialog(
            initial = current.orEmpty(),
            onDismiss = { dialogOpen = false },
            onConfirm = { value ->
                dialogOpen = false
                onChange(value)
            },
        )
    }
}

@Composable
private fun SenderNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_sender_name_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_sender_name_desc),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogFieldLabel(stringResource(R.string.settings_sender_name_label))
                    RelayTextField(
                        value = value,
                        onValueChange = { input ->
                            // Cap à 40 chars au save (cf. SenderNameProviderImpl.MAX_NAME_LENGTH).
                            value = input.take(40)
                        },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    onConfirm(value.trim().takeIf { it.isNotBlank() })
                },
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  v1.3.0 — Auto-suppression historique (rétention 30/60/180 jours + safety 5 j)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Label statut affiché en sous-titre de la `NavigationRow` Réglages → Sécurité →
 * Nettoyage de l'historique. Wording centré sur ce qui est CONSERVÉ pour lever toute
 * ambiguïté ("On garde les 30 derniers jours" plutôt que "Après 30 jours"). Composable
 * car les libellés sont localisés (FR/EN) via `stringResource`.
 */
@Composable
private fun retentionLabel(days: Int?): String = when (days) {
    null, 0 -> stringResource(R.string.settings_auto_delete_status_off)
    30 -> stringResource(R.string.settings_auto_delete_status_30)
    60 -> stringResource(R.string.settings_auto_delete_status_60)
    180 -> stringResource(R.string.settings_auto_delete_status_180)
    else -> stringResource(R.string.settings_auto_delete_status_n_days, days)
}

/**
 * Dialog "Nettoyage de l'historique". Combine :
 *  - un sélecteur radio 4 options (Désactivé / 30 / 60 / 180 j) qui pilote l'auto mensuel ;
 *  - un bouton "Effacer maintenant" qui applique IMMÉDIATEMENT la même profondeur, après
 *    un sous-dialog de confirmation montrant le nombre de messages concernés (pour éviter
 *    un wipe massif accidentel).
 *
 * v1.3.0 : la sélection est persistée à chaque tap radio via [onSelect] ; le dialog ne
 * ferme pas tant que l'utilisateur ne clique pas Close ou Effacer. Le bouton manuel est
 * désactivé tant que `current` vaut null/0 (rien à effacer sans profondeur choisie).
 */
@Composable
private fun AutoDeleteDialog(
    current: Int?,
    onSelect: (Int?) -> Unit,
    onPurgeNow: () -> Unit,
    onRequestConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val options: List<Pair<Int?, String>> = listOf(
        null to stringResource(R.string.settings_auto_delete_off),
        30 to stringResource(R.string.settings_auto_delete_30),
        60 to stringResource(R.string.settings_auto_delete_60),
        180 to stringResource(R.string.settings_auto_delete_180),
    )
    val purgeEnabled = (current ?: 0) > 0
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_auto_delete_title),
        content = {
            Column {
                Text(
                    text = stringResource(R.string.settings_auto_delete_desc),
                    style = RelayText.Timestamp,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = current == value,
                            onClick = { onSelect(value) },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = brandBlue(),
                            ),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(label, style = RelayText.MessageBody)
                    }
                }
                Spacer(Modifier.size(8.dp))
                // Bouton manuel : déclenche d'abord [onRequestConfirm] pour afficher
                // le sous-dialog (qui appelle [onPurgeNow] si l'utilisateur confirme).
                RelayDestructiveButton(
                    text = if (purgeEnabled) {
                        stringResource(R.string.settings_auto_delete_purge_now)
                    } else {
                        stringResource(R.string.settings_auto_delete_purge_now_disabled)
                    },
                    onClick = onRequestConfirm,
                    enabled = purgeEnabled,
                )
                // `onPurgeNow` est passé au parent pour la composition du sous-dialog ;
                // on l'utilise ici aussi pour empêcher le linter "unused parameter".
                @Suppress("UNUSED_EXPRESSION") onPurgeNow
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * v1.3.0 — sous-dialog de confirmation du bouton "Effacer maintenant". Affiche le nombre
 * de messages concernés (lu via [countLoader]) avant de laisser l'utilisateur valider.
 * Si zéro message ne correspond, le bouton de confirmation est désactivé et le corps est
 * remplacé par un message neutre.
 */
@Composable
private fun PurgeNowConfirmDialog(
    days: Int,
    countLoader: suspend () -> Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var count by remember { mutableStateOf<Int?>(null) }
    // v1.25.3 (audit L9) — le `FocusRequester` était bien attaché au bouton Annuler mais
    // `requestFocus()` n'était jamais appelé : un Entrée ou un D-pad tombait sur le bouton
    // destructif. Même patron que les deux autres confirmations destructives de cet écran.
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    LaunchedEffect(days) {
        count = runCatching { countLoader() }.getOrDefault(0)
    }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_auto_delete_purge_confirm_title),
        content = {
            val resolved = count
            val body = when {
                resolved == null -> stringResource(R.string.settings_auto_delete_purge_confirm_body, 0, days)
                resolved == 0 -> stringResource(R.string.settings_auto_delete_purge_confirm_zero)
                else -> stringResource(R.string.settings_auto_delete_purge_confirm_body, resolved, days)
            }
            Text(body, color = cs.onSurfaceVariant)
        },
        confirmButton = {
            RelayDestructiveButton(
                text = stringResource(R.string.action_delete_now),
                onClick = onConfirm,
                enabled = (count ?: 0) > 0,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus).focusable(),
            )
        },
    )
}

/**
 * v1.9.0 — Récap visuel d'un Safety call armé, affiché dans Settings →
 * section Safety Call à la place du `NavigationRow` simple quand
 * `enabled=true`. Donne à l'utilisateur la confirmation visuelle que la
 * config a bien été sauvée + tous ses détails clés sans avoir à rouvrir
 * le wizard.
 *
 * Affiche :
 *  - Chip "Activé" coloré (`primaryContainer`)
 *  - v1.27.2 — heure de démarrage du compte à rebours courant (masquée tant qu'aucun reset n'a
 *    eu lieu)
 *  - Durée totale configurée (formatée via [SafetyCallTemplate.formatDuration])
 *  - Temps restant avant déclenchement (heures, ou "Moins de 2h" si imminent)
 *  - Liste des contacts (1 ligne avec noms concaténés + "+N autres" si > 2)
 *  - Modèle de message choisi (libellé localisé)
 *  - 2 boutons d'action : "Modifier" (→ setup) + "Je vais bien" (reset timer)
 *
 * Les noms des contacts utilisent [SafetyCallContact.sanitizedDisplayName]
 * ou le numéro si pas de nom — cohérent avec l'écran setup.
 */
@Composable
private fun SafetyCallArmedRecap(
    config: com.filestech.sms.domain.safetycall.SafetyCallConfig,
    remainingMs: Long,
    onModify: () -> Unit,
    onImOk: () -> Unit,
) {
    // v1.28.12 — le libellé de durée vient des ressources : il était rendu en français en dur
    // (« 24 heures », « 2 jours »), y compris sur un appareil en anglais.
    val durationLabel = com.filestech.sms.system.safety.rememberSafetyMessageTexts()
        .durationLabel(config.timeoutMs)
    // v1.27.2 — moment où le compte à rebours COURANT a démarré. Avec la durée et le restant
    // affichés juste en dessous, le bloc devient vérifiable de tête : départ + durée = échéance.
    //
    // La valeur lue est [SafetyCallConfig.lastActivityAt], c'est-à-dire le dernier reset — pas la
    // date où la fonction a été activée une fois pour toutes. C'est volontaire, et c'est la seule
    // valeur honnête : ouvrir Relay relance le minuteur (cf. `MainActivity.onResume`), tout
    // comme « Je vais bien ». Afficher une date d'activation figée aurait laissé calculer une
    // échéance fausse sur une fonction où l'heure de déclenchement est précisément l'enjeu — d'où
    // le libellé « Minuteur lancé », et non « Activé le ».
    //
    // `0L` = jamais initialisé : on n'affiche alors rien plutôt qu'une date née de l'époque Unix.
    val formatters = rememberChatFormatters()
    val todayLabel = stringResource(R.string.date_today)
    val yesterdayLabel = stringResource(R.string.date_yesterday)
    val startedLabel: String? = config.lastActivityAt.takeIf { it > 0L }?.let { startedAt ->
        stringResource(
            R.string.settings_safety_call_armed_started,
            formatters.daySeparatorLabel(startedAt, todayLabel, yesterdayLabel),
            formatters.time.format(Date(startedAt)),
        )
    }
    // v1.10.0 perf P2 — [remainingMs] vient du ViewModel (StateFlow tick 60s),
    // plus de System.currentTimeMillis() à chaque recomposition.
    val remainingLabel = when {
        remainingMs <= 0L -> stringResource(R.string.settings_safety_call_armed_remaining_imminent)
        remainingMs < 2 * 3_600_000L ->
            stringResource(R.string.settings_safety_call_armed_remaining_imminent)
        else -> {
            // v1.28.12 — le libellé passe par [libelleDeDuree], donc par les pluriels des
            // cinq langues. Il était construit ICI, à la main, en français, puis injecté
            // dans un cadre traduit : un lecteur allemand lisait « Noch 3 jours ». Les
            // pluriels existaient pourtant déjà et étaient bien employés deux fichiers
            // plus loin. Relevé par deux audits indépendants le 2026-09-17.
            val heures = (remainingMs / 3_600_000L).toInt()
            stringResource(
                R.string.settings_safety_call_armed_remaining,
                libelleDeDuree(heures),
            )
        }
    }
    val contactsLabel = run {
        val labels = config.contacts.map { c ->
            c.sanitizedDisplayName() ?: c.phoneNumber
        }
        when {
            labels.isEmpty() -> ""
            labels.size == 1 -> stringResource(
                R.string.settings_safety_call_armed_contacts_one,
                labels[0],
            )
            labels.size <= 2 -> stringResource(
                R.string.settings_safety_call_armed_contacts_one,
                labels.joinToString(", "),
            )
            else -> stringResource(
                R.string.settings_safety_call_armed_contacts_many,
                labels.take(2).joinToString(", "),
                labels.size - 2,
            )
        }
    }
    val templateLabel = stringResource(
        when (config.template) {
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.CHECK_IN ->
                R.string.safety_call_setup_template_check_in
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.URGENT ->
                R.string.safety_call_setup_template_urgent
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.FOLLOW_UP ->
                R.string.safety_call_setup_template_follow_up
            com.filestech.sms.domain.safetycall.SafetyCallTemplate.CUSTOM ->
                R.string.safety_call_setup_template_custom
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header : titre + chip "Armé"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_safety_call_title),
                style = RelayText.ThreadTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.settings_safety_call_armed_chip),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        // v1.27.2 — la séquence de relances passe AVANT tout le reste : l'alerte est déjà
        // partie, la durée et le temps restant n'ont plus de sens à cet instant. Le libellé dit
        // exactement où en est la séquence, pour qu'on ne se demande pas si ça continue.
        if (config.isTriggered) {
            // 🔴 v1.27.3 — CE BLOC ÉTAIT LE JUMEAU ASYMÉTRIQUE DE LA NOTIFICATION.
            //
            // Il lisait `messagesSent`, qui compte les créneaux **réservés**, là où la notification
            // avait été corrigée (P-06) pour lire `messagesDelivered`, les envois **conclus**.
            // Pendant un envoi en vol, les Réglages affirmaient donc qu'un message était parti
            // alors que rien ne l'était — exactement le défaut que P-06 avait relevé, resté ouvert
            // sur ce jumeau-ci.
            //
            // Et la première réservation portant `messagesDelivered` à zéro, afficher le compte
            // brut aurait annoncé « 0 message sur 4 » : un envoi en cours a donc son propre texte,
            // qui n'affirme rien.
            val delivered = config.messagesDelivered
            val total = com.filestech.sms.domain.safetycall.SafetyCallConfig.TOTAL_MESSAGES
            Text(
                text = if (delivered == 0) {
                    stringResource(R.string.settings_safety_call_triggered_state_sending)
                } else {
                    pluralStringResource(
                        R.plurals.settings_safety_call_triggered_state,
                        delivered,
                        delivered,
                        total,
                    )
                },
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                // La séquence close, « les relances partent toutes les 15 minutes » est faux : il
                // n'en reste aucune, et surtout le Safety call s'est désactivé tout seul. Ne pas le
                // dire laissait croire à une protection encore en veille.
                text = if (delivered >= total) {
                    stringResource(R.string.settings_safety_call_triggered_hint_done)
                } else {
                    stringResource(R.string.settings_safety_call_triggered_hint)
                },
                style = RelayText.Timestamp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
        }
        // Détails — chaque ligne en bodyMedium / onSurfaceVariant pour
        // hiérarchie visuelle (titre en gros, détails en plus discret).
        if (!config.isTriggered && startedLabel != null) {
            Text(
                text = startedLabel,
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_safety_call_armed_duration, durationLabel),
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = remainingLabel,
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (contactsLabel.isNotEmpty()) {
            Text(
                text = contactsLabel,
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_safety_call_armed_template, templateLabel),
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        // 2 actions côte à côte. v1.9.0 UX :
        //  - "Modifier" → RelayTextButton (action secondaire, neutre)
        //  - "Je vais bien" → RelayPrimaryButton car c'est l'action positive
        //    principale du récap.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayTextButton(
                text = stringResource(R.string.settings_safety_call_armed_modify),
                onClick = onModify,
                modifier = Modifier.weight(1f),
            )
            RelayPrimaryButton(
                text = stringResource(R.string.settings_safety_call_armed_im_ok_short),
                onClick = onImOk,
                modifier = Modifier.weight(1f),
            )
        }
        // v1.27.2 — ce que font réellement les deux boutons, écrit sous eux.
        //
        // « Je vais bien » remet le minuteur à zéro et n'envoie RIEN. Rien ne le disait, et le
        // libellé laissait imaginer un message adressé aux contacts — c'est ce qu'a compris
        // l'utilisateur. Sur une fonction où l'on doit savoir exactement ce qui part et ce qui
        // ne part pas, l'ambiguïté n'est pas acceptable.
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_safety_call_armed_actions_hint),
            style = RelayText.Timestamp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * v1.10.0 — Récap visuel d'un Mode urgence armé, affiché dans Settings →
 * section Mode urgence à la place du `NavigationRow` quand `enabled=true`.
 * Pendant exact de [SafetyCallArmedRecap] pour cohérence visuelle.
 *
 * Affiche :
 *  - Chip "Armé" coloré (`primary` brand-blue) avec icône WarningAmber
 *  - Modèle de message choisi (NEED_HELP / DANGER / DISCREET)
 *  - Géoloc incluse oui/non
 *  - Liste des contacts (réutilise la liste Safety Call)
 *  - 2 actions : "Modifier" (→ setup) + "Ouvrir" (→ EmergencyScreen pour
 *    accéder au bouton URGENCE hold 3 s)
 *
 * **Note d'UX** : pas de bouton "trigger" ici car l'écran Settings est
 * la mauvaise surface pour un trigger d'urgence (parcours mental "je
 * configure" vs "j'ai besoin"). Le bouton URGENCE rouge vit uniquement
 * dans [com.filestech.sms.ui.screens.emergency.EmergencyScreen].
 */
@Composable
private fun EmergencyArmedRecap(
    config: com.filestech.sms.domain.emergency.EmergencyConfig,
    contacts: List<com.filestech.sms.domain.safetycall.SafetyCallContact>,
    onModify: () -> Unit,
    onOpen: () -> Unit,
) {
    val templateLabel = stringResource(
        when (config.template) {
            com.filestech.sms.domain.emergency.EmergencyTemplate.NEED_HELP ->
                R.string.emergency_setup_template_need_help
            com.filestech.sms.domain.emergency.EmergencyTemplate.DANGER ->
                R.string.emergency_setup_template_danger
            com.filestech.sms.domain.emergency.EmergencyTemplate.DISCREET ->
                R.string.emergency_setup_template_discreet
        },
    )
    // 2026-10-29 — la permission réelle, pas le seul réglage : cf. [libelleDePositionArmee].
    val localisation = rememberPermissionLocalisation()
    val locationLabel = stringResource(
        libelleDePositionArmee(
            inclure = config.includeLocation,
            accordee = localisation.accordee,
            exacte = localisation.exacte,
        ),
    )
    val contactsLabel = run {
        val labels = contacts.map { c -> c.sanitizedDisplayName() ?: c.phoneNumber }
        when {
            labels.isEmpty() -> stringResource(R.string.settings_emergency_armed_no_contacts)
            labels.size <= 2 -> stringResource(
                R.string.settings_emergency_armed_contacts_one,
                labels.joinToString(", "),
            )
            else -> stringResource(
                R.string.settings_emergency_armed_contacts_many,
                labels.take(2).joinToString(", "),
                labels.size - 2,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header : titre + chip "Armé" identique à SafetyCallArmedRecap.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_emergency_title),
                style = RelayText.ThreadTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.settings_emergency_armed_chip),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.settings_emergency_armed_template, templateLabel),
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = locationLabel,
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = contactsLabel,
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        // 2 actions côte à côte (mêmes types que SafetyCallArmedRecap) :
        //  - "Modifier" → RelayTextButton (secondaire)
        //  - "Ouvrir" → RelayPrimaryButton car c'est l'action de référence
        //    (accéder au bouton URGENCE).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayTextButton(
                text = stringResource(R.string.settings_emergency_armed_modify),
                onClick = onModify,
                modifier = Modifier.weight(1f),
            )
            RelayPrimaryButton(
                text = stringResource(R.string.settings_emergency_armed_open),
                onClick = onOpen,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * v1.13.0 — Dialog setup PIN/pass coffre : 2 étapes (saisie + confirmation).
 * Bouton "Sauver" actif uniquement si :
 *  - PIN ≥ 4 caractères
 *  - confirmation == PIN
 *  - v1.27.10 : le champ « PIN actuel » est rempli, quand [requireCurrent] l'exige
 *
 * ## v1.27.10 — pourquoi le remplacement se fait dans CE dialogue, en un seul aller-retour
 *
 * La revue externe GitLab !38458 a montre qu'on remplacait le PIN du coffre sans jamais
 * demander celui en place. La correction evidente — un premier dialogue qui verifie, un second
 * qui saisit — aurait recree le probleme sous une autre forme : entre les deux, il aurait fallu
 * faire vivre une autorisation, et c'est precisement ce report d'etat qui se contourne. Un seul
 * dialogue, un seul appel a [com.filestech.sms.security.VaultPinManager.changeVaultPin], qui
 * verifie et remplace sans rien laisser entre les deux.
 *
 * **Sécurité** :
 *  - 2 champs `PasswordVisualTransformation` (jamais en clair).
 *  - 3 `String` locaux au composable, jamais persistés. Conversion en
 *    `CharArray` UNIQUEMENT à la validation, immédiatement passé au manager qui les wipe.
 *  - Pas d'autofill, pas de prédictions (`KeyboardType.Password`).
 *  - [appPinHint] = true si l'user a un PIN d'app configuré → warning UX
 *    visible "choisis un PIN différent" (pas une validation crypto — l'user
 *    DOIT pouvoir choisir le même PIN s'il insiste, c'est sa décision).
 *  - v1.13.0 audit NEW-2 : limitation acceptée — les `String pin` et
 *    `String confirm` sont des objets JVM immutable. Chaque frappe
 *    crée une nouvelle String, l'ancienne reste en heap jusqu'au GC.
 *    Acceptable car : (1) durée de vie < temps de saisie (~30 s), (2)
 *    `FLAG_SECURE` actif empêche screen-capture, (3) heap dump nécessite
 *    root (vecteur déjà hors-threat-model). Mitigation max appliquée :
 *    `confirm = ""` AVANT `onConfirm(snapshot)` pour minimiser la fenêtre
 *    (cf. ligne ~2515).
 */
@Composable
private fun VaultPinSetupDialog(
    appPinHint: Boolean,
    requireCurrent: Boolean,
    onSubmit: suspend (current: CharArray?, newPin: CharArray) -> com.filestech.sms.security.PinVerdict,
    probeLockout: suspend () -> Long,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var currentWrong by remember { mutableStateOf(false) }
    val lockout = com.filestech.sms.ui.components.rememberLockoutState(
        if (requireCurrent) probeLockout else null,
    )
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val tooShort = pin.isNotEmpty() && pin.length < 4
    val mismatch = confirm.isNotEmpty() && confirm != pin
    val lockedOut = lockout.active
    val lockoutMessage = lockout.message()
    val canConfirm = pin.length >= 4 && confirm == pin && !submitting && !lockedOut &&
        (!requireCurrent || current.isNotEmpty())

    RelayDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = stringResource(
            if (requireCurrent) {
                R.string.settings_vault_pin_change
            } else {
                R.string.settings_vault_pin_set_title
            },
        ),
        content = {
            // v1.27.1 (N3) — DANS le contenu du dialogue : PIN du coffre.
            ProtectSecretInput()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_vault_pin_set_subtitle),
                    style = RelayText.Preview,
                )
                // Feedback round 8 — the row's long description moved here:
                // the setup dialog is where the vault PIN is explained.
                Text(
                    text = stringResource(R.string.settings_vault_pin_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (appPinHint) {
                    Text(
                        text = stringResource(R.string.settings_vault_pin_same_as_app),
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // v1.27.10 (revue externe GitLab !38458) — champ « PIN actuel ». Sa presence
                // est TOUTE la correction : sans lui, remplacer le PIN du coffre ne demandait
                // que le PIN d'application, dont on cherche precisement a se proteger.
                if (requireCurrent) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DialogFieldLabel(stringResource(R.string.settings_vault_pin_current_label))
                        RelayTextField(
                            value = current,
                            onValueChange = {
                                current = it.take(64)
                                currentWrong = false
                            },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                            ),
                            enabled = !submitting && !lockedOut,
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                        if (currentWrong) {
                            Text(
                                text = stringResource(R.string.pin_error_invalid),
                                style = RelayText.Timestamp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        // v1.27.10 — hors du champ, sinon Material 3 le rend en couleur
                        // « desactive » : cf. [com.filestech.sms.ui.components.LockoutNotice].
                        if (lockedOut) {
                            com.filestech.sms.ui.components.LockoutNotice(lockoutMessage)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogFieldLabel(stringResource(R.string.settings_vault_pin_new_label))
                    RelayTextField(
                        value = pin,
                        onValueChange = { pin = it.take(64) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                        ),
                        enabled = !submitting && !lockedOut,
                        modifier = if (requireCurrent) Modifier else Modifier.focusRequester(focusRequester),
                    )
                    if (tooShort) {
                        Text(
                            text = stringResource(R.string.settings_vault_pin_too_short),
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DialogFieldLabel(stringResource(R.string.settings_vault_pin_confirm_title))
                    RelayTextField(
                        value = confirm,
                        onValueChange = { confirm = it.take(64) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        enabled = !submitting && !lockedOut,
                    )
                    if (mismatch) {
                        Text(
                            text = stringResource(R.string.settings_vault_pin_mismatch),
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    submitting = true
                    // Meme hygiene qu'en v1.13.0 (audit SEC-1) : les `String` sont vides AVANT
                    // le PBKDF2 (~100 ms), les `CharArray` portent le secret pendant l'appel
                    // suspend et sont wipes par le manager.
                    val currentSnapshot = if (requireCurrent) current.toCharArray() else null
                    val newSnapshot = pin.toCharArray()
                    current = ""
                    pin = ""
                    confirm = ""
                    scope.launch {
                        val verdict = onSubmit(currentSnapshot, newSnapshot)
                        submitting = false
                        when (verdict) {
                            is com.filestech.sms.security.PinVerdict.Ok -> onSaved()
                            is com.filestech.sms.security.PinVerdict.Invalid -> currentWrong = true
                            is com.filestech.sms.security.PinVerdict.LockedOut ->
                                lockout.arm(verdict.untilWall)
                        }
                    }
                },
                enabled = canConfirm,
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !submitting,
            )
        },
    )
}

// ============================================================================
// v1.15.0 — Sections extraites de SettingsScreen pour réduire la taille du
// Composable principal (était >1000 lignes). Comportement strictement identique.
// ============================================================================

/**
 * v1.15.0 — Section Notifications. Extraite de [SettingsScreen] (lignes 333-400 v1.14.9).
 *
 * Pourquoi extraire ? La fonction `SettingsScreen` dépassait 1000 lignes avec 10 sections
 * imbriquées + 14 `mutableStateOf` au niveau parent — lisibilité dégradée, previews Compose
 * impossibles section par section. La factorisation conserve les pickers state au parent
 * (multi-callsite via callbacks) tout en isolant le rendu par section.
 */
@Composable
private fun NotificationsSection(
    notifications: com.filestech.sms.domain.settings.NotificationSettings,
    mutedCategories: Set<MessageCategory>,
    onUpdate: (transform: (com.filestech.sms.domain.settings.AppSettings) -> com.filestech.sms.domain.settings.AppSettings) -> Unit,
    onOpenPreviewPicker: () -> Unit,
    onOpenStylePicker: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Round 12 — whether the "notifications off" banner will actually
    // render, hoisted here so the banner `item` below is only registered
    // when it shows. Registered unconditionally it left an empty segment
    // (a 2dp strip) between "Enable notifications" and "Reply from
    // notification" whenever Android allowed notifications, doubling that
    // gap. Same check as the banner itself, re-read on resume so it
    // disappears on its own once notifications are re-enabled.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showBanner by remember(notifications.style) {
        mutableStateOf(!canauxVisibles(ctx, listOf(canalDesMessages(notifications.style))))
    }
    DisposableEffect(lifecycle, notifications.style) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                showBanner = !canauxVisibles(ctx, listOf(canalDesMessages(notifications.style)))
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    RelaySectionLabel(stringResource(R.string.settings_section_notifications))
    RelaySettingsGroup {
        item {
            RelaySwitchRow(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.settings_notifications_enabled),
                checked = notifications.enabled,
                onCheckedChange = { v -> onUpdate { it.copy(notifications = it.notifications.copy(enabled = v)) } },
            )
        }
        // v1.28.13 — l'avertissement annoncé en v1.8.0 et jamais branché : notifications coupées
        // par Android (permission, interrupteur de l'application ou canal des messages), alors que
        // l'interrupteur ci-dessus dit « activées ». The banner is its own
        // segment, so the group splits around it and the shapes stay right.
        // It fills the segment (no inset) so the error card, not the
        // segment surface, is what reads as the warning. Gated on
        // `showBanner` (hoisted above): when the banner would render
        // nothing, the item is not registered at all, so the gap between
        // "Enable notifications" and "Reply from notification" stays the
        // normal 2dp instead of doubling.
        if (notifications.enabled && showBanner) {
            item {
                BanniereNotificationsCoupees(
                    message = stringResource(R.string.notifications_off_messages),
                    canaux = listOf(canalDesMessages(notifications.style)),
                )
            }
        }
        item {
            RelaySwitchRow(
                icon = Icons.AutoMirrored.Outlined.Reply,
                title = stringResource(R.string.settings_inline_reply),
                checked = notifications.inlineReply,
                onCheckedChange = { v -> onUpdate { it.copy(notifications = it.notifications.copy(inlineReply = v)) } },
            )
        }
        // Round 11 — mute notifications per category. Same switches as
        // the tab long-press: flipping one here toggles the same
        // `conversations.mutedCategories` entry, and the tab row reads it.
        // Round 12 — the bell shows the state: crossed out when this
        // category's notifications are off (muted), plain when they're on.
        val muteSubtitle = stringResource(R.string.category_mute_subtitle)
        for (category in MessageCategory.entries) {
            val isMuted = category in mutedCategories
            item {
                RelaySwitchRow(
                    icon = if (isMuted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    title = categoryLabel(category),
                    subtitle = muteSubtitle,
                    checked = isMuted,
                    onCheckedChange = { v ->
                        onUpdate {
                            val muted = if (v) {
                                it.conversations.mutedCategories + category
                            } else {
                                it.conversations.mutedCategories - category
                            }
                            it.copy(conversations = it.conversations.copy(mutedCategories = muted))
                        }
                    },
                )
            }
        }
        // v1.28.3 (audit global D-01) — le toggle « Vibrer » est retiré : aucun code ne le
        // lisait, la vibration appartient au canal et se règle dans les paramètres système.
        item(onClick = onOpenPreviewPicker) {
            RelaySettingRow(
                icon = Icons.Outlined.Visibility,
                title = stringResource(R.string.settings_notif_preview),
                chevron = true,
            )
        }
        item(onClick = onOpenStylePicker) {
            RelaySettingRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.settings_notif_style),
                chevron = true,
            )
        }
        // v1.8.0 (bug 3 fix MEDIUM 3c) — deeplink réglages système app.
        // ACTION_APP_NOTIFICATION_SETTINGS (API 26+) ouvre directement la
        // page Paramètres → Apps → Relay → Notifications, où l'user
        // peut vérifier que ses canaux ne sont pas désactivés.
        item(onClick = { ouvrirLesNotificationsDeLApplication(ctx) }) {
            RelaySettingRow(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.settings_notif_open_system),
                chevron = true,
            )
        }
    }
}

/**
 * v1.15.0 — Section Sécurité. Extraite de [SettingsScreen] (lignes 402-491 v1.14.9).
 *
 * Pickers (`lockModePickerOpen`, `vaultPinSetupOpen`, `vaultPinDisableOpen`,
 * `showPurgeBlockedConfirm`, `autoDeletePickerOpen`) restent gérés par le parent —
 * la section ne reçoit que les callbacks d'ouverture. Cohérent avec [NotificationsSection].
 *
 * Comportement vault-PIN masqué en PanicDecoy préservé (audit UX-2 v1.13.0).
 */
@Composable
private fun SecuritySection(
    security: com.filestech.sms.domain.settings.SecuritySettings,
    isPanicDecoy: Boolean,
    onUpdate: (transform: (com.filestech.sms.domain.settings.AppSettings) -> com.filestech.sms.domain.settings.AppSettings) -> Unit,
    onOpenLockModePicker: () -> Unit,
    /** v1.26.1 (audit F4) — ouvre le sélecteur du délai de verrouillage automatique. */
    onOpenAutoLockDelayPicker: () -> Unit,
    // v1.26.0 — code panique. Voir [AppLockManager.setPanicCode] : la mecanique du mode leurre
    // etait complete, mais aucun ecran ne permettait de definir le code, donc il etait
    // inatteignable.
    panicCodeSet: Boolean,
    onOpenPanicCodeSetup: () -> Unit,
    onOpenPanicCodeClearConfirm: () -> Unit,
    onOpenVaultPinSetup: () -> Unit,
    onOpenVaultPinDisable: () -> Unit,
    onOpenVaultPinForgetConfirm: () -> Unit,
    onOpenPurgeBlockedConfirm: () -> Unit,
    onOpenAutoDeletePicker: () -> Unit,
    // Feedback round 8 — the Safety call, Emergency mode and Blocking
    // rows moved into this section (they were their own cards). Their
    // state and callbacks arrive here so this one card holds them all.
    blocking: com.filestech.sms.domain.settings.BlockingSettings,
    onOpenBlocked: () -> Unit,
    safetyCallRemainingMs: Long,
    onOpenSafetyCall: () -> Unit,
    onSafetyCallImOk: () -> Unit,
    onOpenEmergencySetup: () -> Unit,
    onOpenEmergency: () -> Unit,
    positionAccordee: Boolean,
    onRequestLocationPermission: () -> Unit,
) {
    RelaySectionLabel(stringResource(R.string.settings_section_security))
    RelaySettingsGroup {
        // App lock entry — opens the lock-mode picker (None / PIN / Biometric). Subtitle
        // reflects the current state so the user sees at a glance whether the lock is armed.
        //
        // v1.25.3 — le `when` ne traitait que `PIN` et renvoyait tout le reste sur « Aucun » via
        // un `else` : avec la biométrie armée, Réglages annonçait **« Aucun »** alors que le
        // verrou fonctionnait. Le commentaire d'origine (« None / PIN ») date d'avant l'ajout du
        // mode biométrique et n'avait jamais suivi. `when` désormais exhaustif et **sans `else`**,
        // pour que le compilateur impose de traiter toute nouvelle valeur de [LockMode] au lieu
        // de la faire silencieusement passer pour un verrou absent.
        val currentLockLabel = when (security.lockMode) {
            com.filestech.sms.domain.settings.LockMode.OFF ->
                stringResource(R.string.lock_mode_off)
            com.filestech.sms.domain.settings.LockMode.PIN ->
                stringResource(R.string.lock_mode_pin)
            com.filestech.sms.domain.settings.LockMode.BIOMETRIC ->
                stringResource(R.string.lock_mode_biometric)
            // `PATTERN` est une valeur historique de l'enum : aucun chemin d'UI ne l'arme
            // (vérifié — aucune référence dans le code). Si un jour on l'implémente, il faudra
            // sa propre ressource ici ; l'absence d'`else` forcera la question.
            com.filestech.sms.domain.settings.LockMode.PATTERN ->
                stringResource(R.string.lock_mode_off)
        }
        // v1.26.1 (audit C1) — ⚠️ **JAMAIS en mode leurre**, et c'est la garde la plus
        // importante de cet écran. Sans elle, l'agresseur ouvrait ce sélecteur, choisissait
        // « Aucun » et une simple confirmation suffisait à appeler `clearPin()` — lequel pose
        // `LockState.Disabled`. Or TOUTES les gardes du leurre testent `is PanicDecoy` : passer
        // en `Disabled` les désarmait d'un seul coup (coffre de nouveau listé et navigable,
        // sections Urgence et Safety call de retour), et `clearPin()` effaçait au passage le
        // code panique lui-même. Le mode leurre se neutralisait donc depuis l'écran qu'il est
        // censé protéger. Même garde que les deux lignes ci-dessous, pour la même raison.
        // Défense en profondeur : `AppLockManager.clearPin()` refuse aussi en `PanicDecoy`.
        if (!isPanicDecoy) {
            item(onClick = onOpenLockModePicker) {
                RelaySettingRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.settings_app_lock),
                    subtitle = currentLockLabel,
                    chevron = true,
                )
            }
            // v1.26.1 (audit F4) — le délai était lu par [AutoLockObserver] mais figé sur son
            // défaut faute de sélecteur. Masqué en leurre comme la ligne au-dessus : c'est un
            // réglage de verrouillage, il en révélerait l'existence.
            item(onClick = onOpenAutoLockDelayPicker) {
                RelaySettingRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(R.string.settings_auto_lock_delay),
                    subtitle = autoLockDelayOptions()
                        .firstOrNull { it.first == security.autoLockDelay }?.second.orEmpty(),
                    chevron = true,
                )
            }
        }
        // v1.26.0 — deux conditions, pour deux raisons sans rapport entre elles.
        //
        // 1. Un PIN principal doit exister : sans lui, le code panique serait le seul secret
        //    connu et l'application s'ouvrirait en leurre pour toujours.
        //    `AppLockManager.setPanicCode` refuse deja ce cas ; on evite en plus de proposer une
        //    action vouee au refus.
        //
        // 2. ⚠️ **JAMAIS en mode leurre.** La ligne annonce « Configure » quand un code existe :
        //    l'agresseur qui ouvre les Reglages apprendrait ainsi qu'un second code lui est
        //    cache, et que l'ecran qu'il regarde est un decor. Ce serait trahir la fonction au
        //    seul endroit ou elle compte. Meme garde que le toggle du coffre juste en dessous
        //    (audit UX-2, v1.13.0), pour la meme raison — l'existence d'un reglage est deja une
        //    information.
        if (security.lockMode != com.filestech.sms.domain.settings.LockMode.OFF && !isPanicDecoy) {
            item(onClick = if (panicCodeSet) onOpenPanicCodeClearConfirm else onOpenPanicCodeSetup) {
                RelaySettingRow(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.settings_panic_code),
                    subtitle = if (panicCodeSet) {
                        stringResource(R.string.settings_panic_code_set)
                    } else {
                        stringResource(R.string.settings_panic_code_desc)
                    },
                    chevron = true,
                )
            }
        }
        item {
            RelaySwitchRow(
                icon = Icons.Outlined.PrivacyTip,
                title = stringResource(R.string.settings_flag_secure),
                checked = security.flagSecure,
                onCheckedChange = { v -> onUpdate { it.copy(security = it.security.copy(flagSecure = v)) } },
                infoText = stringResource(R.string.settings_flag_secure_desc),
            )
        }
        // v1.26.1 (audit H3) — masqué en leurre comme ses deux voisines. Cette ligne était la
        // seule des trois à ne pas porter la garde, alors qu'elle nomme le coffre dans son
        // titre ET dans sa description (« Verrouille automatiquement le coffre-fort chiffré… ») :
        // elle révélait donc l'existence du coffre à l'agresseur, exactement ce que le toggle
        // du PIN coffre juste en dessous évite depuis la v1.13.0.
        if (!isPanicDecoy) {
            item {
                RelaySwitchRow(
                    icon = Icons.Outlined.LockOpen,
                    title = stringResource(R.string.settings_lock_vault_on_leave),
                    checked = security.lockVaultOnLeave,
                    onCheckedChange = { v -> onUpdate { it.copy(security = it.security.copy(lockVaultOnLeave = v)) } },
                    infoText = stringResource(R.string.settings_lock_vault_on_leave_desc),
                )
            }
        }
        // v1.11.0 — Sujet 3 anti-smishing.
        // Feedback round 8 — the long explanation lives in the info popup now.
        item {
            RelaySwitchRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.settings_smishing_detection),
                checked = security.smishingDetectionEnabled,
                onCheckedChange = { v ->
                    onUpdate {
                        it.copy(security = it.security.copy(smishingDetectionEnabled = v))
                    }
                },
                infoText = stringResource(R.string.settings_smishing_detection_desc),
            )
        }
        // v1.13.0 — PIN/pass distinct pour le coffre (second-factor).
        // ToggleRow non-onChange (le toggle déclenche un setup dialog au lieu d'écrire
        // directement la valeur, sinon on activerait le gate sans avoir posé de hash,
        // ce qui dégraderait l'UX au prochain unlock vault). C'est le `vaultPinSetupOpen`
        // qui contrôle la suite.
        // v1.13.0 audit UX-2 — masqué en PanicDecoy : l'existence du toggle révélerait
        // la présence d'un coffre dans Settings, alors que le cadenas TopAppBar et la
        // nav vers Vault sont déjà masqués en décoy. Cohérence cross-écran.
        // Feedback round 8 — the long description moved into the PIN setup
        // dialog itself (it already opens a popup); the row stays one-line.
        if (!isPanicDecoy) {
            item {
                RelaySwitchRow(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.settings_vault_pin_title),
                    checked = security.vaultPinEnabled,
                    onCheckedChange = { v ->
                        if (v) {
                            onOpenVaultPinSetup()
                        } else {
                            onOpenVaultPinDisable()
                        }
                    },
                )
            }
            if (security.vaultPinEnabled) {
                item(onClick = onOpenVaultPinSetup) {
                    RelaySettingRow(
                        icon = Icons.Outlined.Edit,
                        title = stringResource(R.string.settings_vault_pin_change),
                        chevron = true,
                    )
                }
                // v1.27.10 — la porte de sortie « PIN oublie » a sa propre entree, visible, au
                // lieu d'etre le fonctionnement par defaut de la bascule OFF. Elle annonce ce
                // qu'elle detruit ; la bascule, elle, demande maintenant le PIN.
                item(onClick = onOpenVaultPinForgetConfirm) {
                    RelaySettingRow(
                        icon = Icons.Outlined.WarningAmber,
                        title = stringResource(R.string.settings_vault_pin_forgot_title),
                        subtitle = stringResource(R.string.settings_vault_pin_forgot_desc),
                        chevron = true,
                    )
                }
            }
        }
        // Feedback round 8 — the long description moved into the confirm
        // dialog (it already explains the purge); the row stays one-line.
        item(onClick = onOpenPurgeBlockedConfirm) {
            RelaySettingRow(
                iconPainter = painterResource(R.drawable.ic_trash),
                title = stringResource(R.string.settings_purge_blocked),
                chevron = true,
            )
        }
        // v1.3.0 — auto-purge historique selon rétention choisie. Feedback
        // round 8 — the row shows only the current retention; the long
        // explainer lives in the picker dialog (it already shows it).
        val currentRetentionLabel = retentionLabel(security.autoDeleteOlderThanDays)
        item(onClick = onOpenAutoDeletePicker) {
            RelaySettingRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.settings_auto_delete_title),
                subtitle = currentRetentionLabel,
                chevron = true,
            )
        }
        // Feedback round 8 — Safety call, Emergency mode and Blocking
        // moved into this card (they were their own sections above). The
        // decoy guards travel with them: Safety call and Emergency stay
        // hidden in a decoy session, Blocking stays visible (it is an
        // ordinary feature). The recap blocks get a divider above them
        // because they are no longer the first thing in their own card.
        // v1.9.0 — Safety call. Feature de sécurité PERSONNELLE (envoyer SMS à mes
        // proches si je ne donne plus signe de vie), distincte de la
        // sécurité du DEVICE (verrouillage, panic mode).
        // v1.10.0 audit SEC-1 (extension) — masquée en PanicDecoy par
        // cohérence avec Mode urgence : ne pas révéler à l'agresseur
        // l'existence des features de sécurité personnelle.
        if (!isPanicDecoy) {
            val safetyCall = security.safetyCall
            // 🔴 v1.27.3 — CETTE CONDITION EST REVENUE À `enabled` SEUL, ET C'EST DÉLIBÉRÉ.
            //
            // Une première tentative de fermer SC-1273-05 avait écrit `enabled ||
            // isTriggered` ici. C'était faux : ce composant s'appelle `SafetyCallArmedRecap`
            // et sa puce dit « Activé » en couleur primaire. Avec une séquence terminale non
            // acquittée — `enabled = false`, `triggeredAt` encore posé — les Réglages
            // annonçaient donc **« Activé » sur une protection éteinte**. Constaté par
            // Patrice sur son S24 dans les minutes suivant l'installation.
            //
            // C'est exactement le défaut que tout ce lot combat, reproduit en voulant le
            // corriger : faire croire à une protection qui n'existe pas. L'état terminal
            // s'affiche donc dans la branche « désactivé », où il est vrai.
            if (safetyCall.enabled) {
                // Récap visible quand armé : durée, restant, contacts,
                // template + 2 actions (Modifier / Je vais bien).
                item {
                    SafetyCallArmedRecap(
                        config = safetyCall,
                        remainingMs = safetyCallRemainingMs,
                        onModify = onOpenSafetyCall,
                        onImOk = onSafetyCallImOk,
                    )
                }
            } else {
                item(onClick = onOpenSafetyCall) {
                    RelaySettingRow(
                        icon = Icons.Outlined.Shield,
                        title = stringResource(R.string.settings_safety_call_title),
                        // Feedback round 8 — the row shows only the state
                        // (Disabled / Disabled after an alert); the long
                        // description moved to the Safety call setup screen.
                        subtitle = if (safetyCall.isTriggered) {
                            stringResource(R.string.settings_safety_call_disabled_after_alert)
                        } else {
                            stringResource(R.string.settings_safety_call_disabled)
                        },
                        chevron = true,
                    )
                }
            }
        }
        // v1.10.0 — Mode urgence. Ici c'est l'user qui DÉCLENCHE activement (bouton hold 3s) ;
        // le Safety call est passif (timer d'inactivité). Cohérence des
        // contacts garantie par la réutilisation de la même liste.
        // v1.10.0 audit SEC-1 — entièrement masquée en PanicDecoy
        // (l'agresseur ne doit pas savoir que la feature existe).
        if (!isPanicDecoy) {
            val emergency = security.emergency
            if (emergency.enabled) {
                // v1.10.0 polish — récap visuel quand armé (parallèle exact de SafetyCallArmedRecap
                // pour cohérence UX).
                item {
                    EmergencyArmedRecap(
                        config = emergency,
                        contacts = security.safetyCall.contacts,
                        onModify = onOpenEmergencySetup,
                        onOpen = onOpenEmergency,
                    )
                }
            } else {
                // Feedback round 8 — the row shows only the state (Disabled);
                // the long description moved to the Emergency setup screen.
                item(onClick = onOpenEmergencySetup) {
                    RelaySettingRow(
                        icon = Icons.Outlined.WarningAmber,
                        title = stringResource(R.string.settings_emergency_title),
                        subtitle = stringResource(R.string.settings_emergency_disabled),
                        chevron = true,
                    )
                }
            }
            // v1.12.0 — Toggle raccourci urgence en notification persistante lock-screen.
            // Disponible uniquement si emergency.enabled (un raccourci qui ouvre dans le vide
            // n'a aucun sens).
            if (emergency.enabled) {
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.Notifications,
                        title = stringResource(R.string.settings_emergency_shortcut_title),
                        subtitle = stringResource(R.string.settings_emergency_shortcut_desc),
                        checked = security.emergencyShortcutEnabled,
                        onCheckedChange = { v ->
                            onUpdate {
                                it.copy(security = it.security.copy(emergencyShortcutEnabled = v))
                            }
                        },
                    )
                }
                // v1.28.13 — le raccourci EST une notification : sans elles, l'interrupteur reste sur
                // « activé » alors que rien n'apparaît sur l'écran verrouillé. The
                // banner fills its own segment (see the notifications one).
                if (security.emergencyShortcutEnabled) {
                    item {
                        BanniereNotificationsCoupees(
                            message = stringResource(R.string.notifications_off_emergency_shortcut),
                            canaux = listOf(CANAL_DU_RACCOURCI_D_URGENCE),
                        )
                    }
                }
                // v1.12.0 — Toggle de l'action police dans la notification d'urgence.
                // Audit fix S2 : disponible uniquement si le raccourci urgence est lui-même ON.
                // Sans raccourci, il resterait un orphelin qui dupliquerait juste 112.
                // v1.28.12 — le numéro suit le pays du réseau, et la tuile police de l'écran
                // Urgence ne dépend plus de ce réglage : elle s'affiche selon le pays.
                if (security.emergencyShortcutEnabled) {
                    item {
                        RelaySwitchRow(
                            icon = Icons.Outlined.Phone,
                            title = stringResource(R.string.settings_emergency_call_police_title),
                            subtitle = stringResource(R.string.settings_emergency_call_police_desc),
                            checked = security.emergencyCallPoliceEnabled,
                            onCheckedChange = { v ->
                                onUpdate {
                                    it.copy(security = it.security.copy(emergencyCallPoliceEnabled = v))
                                }
                            },
                        )
                    }
                }
                // v1.14.0 — opt-in SMS "Je vais bien" sur kill-switch.
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = stringResource(R.string.settings_send_i_am_ok_sms_title),
                        subtitle = stringResource(R.string.settings_send_i_am_ok_sms_desc),
                        checked = security.sendIAmOkSmsOnReset,
                        onCheckedChange = { v ->
                            onUpdate {
                                it.copy(security = it.security.copy(sendIAmOkSmsOnReset = v))
                            }
                        },
                    )
                }
                // v1.14.5 — Toggle "Inclure position GPS dans le SMS" accessible directement
                // depuis Settings (avant : seul EmergencySetupScreen exposait ce toggle, l'user
                // devait y naviguer pour activer la géoloc). Quand l'user passe OFF→ON, on
                // demande la permission ACCESS_FINE_LOCATION runtime immédiatement (avec les
                // mêmes considérations que dans EmergencySetupScreen).
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.PushPin,
                        title = stringResource(R.string.settings_emergency_include_location_title),
                        subtitle = stringResource(R.string.settings_emergency_include_location_desc),
                        checked = emergency.includeLocation,
                        onCheckedChange = { v ->
                            onUpdate {
                                it.copy(security = it.security.copy(
                                    emergency = it.security.emergency.copy(includeLocation = v),
                                ))
                            }
                            // Au passage OFF→ON sans perm déjà accordée → demande runtime.
                            if (v && !positionAccordee) {
                                onRequestLocationPermission()
                            }
                        },
                    )
                }
            }
        }
        // Blocking — an ordinary feature, visible in a decoy session like
        // the rest of this card.
        // v1.28.13 (balayage des permissions refusées, MR F-Droid !38458) — sans accès aux
        // contacts, `IncomingBlockPolicy` ne peut savoir qui est inconnu et laisse TOUT
        // passer, à dessein (bloquer sur une ignorance couperait toute réception). Mais
        // l'interrupteur restait sur « activé » sans rien dire : on le dit sous lui.
        val contactsAccordes = rememberPermissionAccordee(android.Manifest.permission.READ_CONTACTS)
        item {
            RelaySwitchRow(
                iconPainter = painterResource(R.drawable.ic_block),
                title = stringResource(R.string.settings_block_unknown),
                checked = blocking.blockUnknown,
                onCheckedChange = { v -> onUpdate { it.copy(blocking = it.blocking.copy(blockUnknown = v)) } },
                // 2026-10-29 — l'effet est dit AVANT d'activer : `IncomingBlockPolicy` écarte le
                // message à la réception, sans notification ni copie (pas même le filet de
                // secours de `SmsDeliverReceiver`), et un expéditeur alphanumérique n'est
                // jamais un contact. Activé sans accès aux contacts, l'avertissement prime :
                // rien n'est alors bloqué.
                // Feedback round 8 — the long description lives in the info
                // popup now; the no-contacts warning still shows under the
                // title when it applies (it is a state warning, not a
                // description).
                subtitle = if (blocking.blockUnknown && !contactsAccordes) {
                    stringResource(R.string.settings_block_unknown_no_contacts)
                } else {
                    null
                },
                infoText = stringResource(R.string.settings_block_unknown_desc),
            )
        }
        item(onClick = onOpenBlocked) {
            RelaySettingRow(
                icon = Icons.Outlined.PersonOff,
                title = stringResource(R.string.settings_manage_blocked),
                chevron = true,
            )
        }
    }
}
