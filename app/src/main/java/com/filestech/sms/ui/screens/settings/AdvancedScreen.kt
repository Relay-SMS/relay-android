package com.filestech.sms.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.ui.components.RelayCard
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText

/**
 * Advanced screen, reached from Settings ("Advanced", between "App Language"
 * and "Help").
 *
 * Feedback round 6 — the "Advanced" section moved out of Settings onto its
 * own screen: Default SMS app (+ Set default), Re-sync, Keep-alive service,
 * Reset all, Erase data. Same rows, same dialogs, same ViewModel wiring —
 * just housed here instead of at the bottom of the main Settings list. The
 * screen title reuses the section string, so no new strings were needed.
 *
 * No panic-decoy gating: the section was deliberately visible in decoy
 * sessions as a section (see the reset-all comment below), and nothing here
 * names the vault or the emergency mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNuke by remember { mutableStateOf(false) }
    // v1.28.6 — le compte rendu de la purge, lu avant de redemarrer l'application. `null` = pas de
    // dialogue ; sinon il confirme, ou dit ce qui a resiste — cf. [SettingsViewModel.Event.DataWiped].
    // v1.28.9 — le compte rendu entier, et non plus le seul nombre de copies système.
    var nukeResultat by remember { mutableStateOf<SettingsViewModel.Event.DataWiped?>(null) }
    var showResyncConfirm by remember { mutableStateOf(false) }
    var showResetAllConfirm by remember { mutableStateOf(false) }
    val defaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        /* no-op */
    }

    val snackbarHost = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    // Only the two events this screen's actions can emit: the re-sync
    // confirmation snackbar and the erase-data report dialog. The other
    // Settings events belong to rows that stayed on the main screen.
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                SettingsViewModel.Event.ResyncRequested -> {
                    snackbarHost.showSnackbar(ctx.getString(R.string.settings_resync_started))
                }
                // v1.28.6 — la purge totale a laisse des messages dans le telephone. Un DIALOGUE
                // et non un message glissant : le processus redemarre juste apres, un snackbar
                // disparaitrait avec lui sans avoir ete lu.
                is SettingsViewModel.Event.DataWiped -> {
                    nukeResultat = e
                }
                else -> Unit
            }
        }
    }

    // Grouped-list page base: #F5F5F5 in light, #1B1B1B in dark — the same
    // canvas Settings and About sit on (see RelaySection). Picked from the
    // surface luminance so it follows the active scheme, dark variants
    // included.
    val pageBackground = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        RelayColors.CanvasDark
    } else {
        RelayColors.Surface
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_section_advanced)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            RelayCard {
                // v1.27.12 — le bouton passe SOUS la description au lieu d'occuper le
                // `trailingContent`. Meme defaut que le bandeau de [ConversationsScreen] et meme
                // cause : le bouton y prenait sa largeur intrinseque et laissait au texte une
                // colonne de quelques caracteres. Visible des qu'on cumule un ecran etroit et une
                // police agrandie — mesure sur un Redmi 9C a 360 dp et `font_scale` 1,33.
                val estParDefaut = viewModel.defaultAppManager.isDefault()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    // Feedback round 5 — the glyph centres on the title line,
                    // not on the whole row (same nesting as [ToggleRow] and
                    // [RelayRow]): the icon and the title share an inner row,
                    // and the description starts under the title text.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sms,
                            contentDescription = null,
                            // Feedback round 4 — row icons follow the theme's on-surface
                            // colour (white in dark mode, black in light) instead of the
                            // brand blue, which was hard to see in dark mode.
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_default_sms_app),
                            style = RelayText.MessageBody,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = if (estParDefaut) {
                            stringResource(R.string.settings_is_default)
                        } else {
                            stringResource(R.string.error_not_default_app)
                        },
                        // Feedback round 6 — M3 supporting text is bodyMedium
                        // (14sp/20), not the 13sp/16 timestamp style.
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Feedback round 4 — a little gap between the row title
                        // and its description line (same 2dp as [RelayRow]).
                        modifier = Modifier.padding(start = 40.dp, top = 2.dp),
                    )
                    if (!estParDefaut) {
                        Spacer(Modifier.size(10.dp))
                        RelayPrimaryButton(
                            text = stringResource(R.string.settings_set_default),
                            onClick = {
                                viewModel.defaultAppManager.buildChangeDefaultIntent()
                                    ?.let { defaultLauncher.launch(it) }
                            },
                        )
                    }
                }
                // Audit U15: label said "Archived" but the row opened the Blocked screen. Removed —
                // archived conversations are reached from the Conversations top bar (icon Inventory2),
                // and the Blocked list has its own dedicated row right above.
                // v1.3.0 — re-sync complète depuis le content provider Android. Utile pour
                // récupérer un historique purgé par erreur (le system provider conserve les SMS
                // indépendamment de l'app). Le re-import dedup via l'index UNIQUE telephony_uri.
                NavigationRow(
                    title = stringResource(R.string.settings_resync_title),
                    leadingIcon = Icons.Outlined.Sync,
                    description = stringResource(R.string.settings_resync_desc),
                    // v1.8.0 — dialog de confirmation (peut faire ré-apparaître
                    // des conversations précédemment supprimées si toujours
                    // présentes dans le content provider système).
                    onClick = { showResyncConfirm = true },
                    dividerAbove = true,
                )
                // v1.3.10 + v1.4.1 — Mode résistant OPT-IN (KeepAliveService foreground
                // permanent). Toggle exposé pour TOUS les téléphones car la détection ROM
                // est conservatrice : certains utilisateurs sur ROM "propre" pourraient
                // quand même vouloir activer ; certains sur ROM "agressive" pourraient avoir
                // whitelisté SMS Tech à la main et ne pas vouloir la notif persistante. La
                // description liste les ROMs concernées pour aider la décision.
                // **v1.4.1** : retrait du dialog onboarding qui auto-proposait l'activation
                // sur Xiaomi/Redmi — trop pushy, des users tapaient "Activer" sans
                // comprendre la notif persistante.
                ToggleRow(
                    title = stringResource(R.string.settings_keep_alive_title),
                    leadingIcon = Icons.Outlined.Bolt,
                    description = stringResource(R.string.settings_keep_alive_desc),
                    value = state.advanced.keepAliveService,
                    dividerAbove = true,
                    onChange = { v ->
                        // v1.3.10 (C4) — single source of truth: only persist the flag here.
                        // [MainApplication] observes the DataStore via `distinctUntilChanged`
                        // and calls `KeepAliveService.start/stop` within ~50 ms — well under
                        // the threshold of perceptibility. The previous inline `start/stop`
                        // was redundant and caused a double `startForeground` on the same
                        // notification id, which produced a brief notif-flash on MIUI.
                        viewModel.update { it.copy(advanced = it.advanced.copy(keepAliveService = v)) }
                    },
                )
                // v1.8.0 — dialog de confirmation (les préférences reviennent
                // aux defaults, mais les conversations restent intactes).
                //
                // v1.27.11 (revue externe GitLab !38458, constat 1) — cette rangée reste
                // volontairement VISIBLE en session leurre, contrairement aux sections
                // Sauvegarde / Safety call / Mode urgence : une app SMS ordinaire sait
                // réinitialiser ses réglages, et la masquer signalerait qu'il y a quelque chose
                // à cacher. Ce qui la rendait dangereuse ici n'était pas sa visibilité mais son
                // effet — elle remettait `lockMode` et `vaultPinEnabled` à leurs défauts. C'est
                // [SettingsViewModel.resetAll] qui préserve désormais le bloc sécurité, et c'est
                // le bon endroit : un garde d'écran ne dit rien du prochain point d'entrée.
                NavigationRow(
                    title = stringResource(R.string.settings_reset_all),
                    leadingIcon = Icons.Outlined.RestartAlt,
                    onClick = { showResetAllConfirm = true },
                    dividerAbove = true,
                )
                NavigationRow(
                    title = stringResource(R.string.settings_nuke_data),
                    leadingIcon = Icons.Outlined.DeleteForever,
                    onClick = { showNuke = true },
                    destructive = true,
                    dividerAbove = true,
                )
            }
        }
    }

    // v1.8.0 — Dialog de confirmation : Resync depuis le téléphone.
    // Pattern Files Tech : autofocus Cancel + bouton confirm primary action.
    // Action non destructive (re-importe depuis content://sms, pas de perte).
    if (showResyncConfirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        RelayDialog(
            onDismissRequest = { showResyncConfirm = false },
            title = stringResource(R.string.settings_resync_confirm_title),
            content = { Text(stringResource(R.string.settings_resync_confirm_body)) },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        viewModel.forceResyncFromTelephony()
                        showResyncConfirm = false
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showResyncConfirm = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                )
            },
        )
    }

    // v1.8.0 — Dialog de confirmation : Réinitialiser tous les réglages.
    // Pattern Files Tech : autofocus Cancel — l'user qui tape rapidement après
    // un précédent dialog ne réinitialise pas par réflexe.
    // Action remet les réglages aux défauts mais NE touche PAS aux messages
    // (donc non-destructive au sens contenu utilisateur), ni — depuis v1.27.11 — aux
    // réglages de sécurité. Le corps du dialogue le dit maintenant explicitement : il
    // annonçait « toutes vos préférences » alors que la portée réelle s'arrête au confort.
    if (showResetAllConfirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        RelayDialog(
            onDismissRequest = { showResetAllConfirm = false },
            title = stringResource(R.string.settings_reset_all_confirm_title),
            content = { Text(stringResource(R.string.settings_reset_all_confirm_body)) },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        viewModel.resetAll()
                        showResetAllConfirm = false
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showResetAllConfirm = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                )
            },
        )
    }

    // Audit U16: destructive dialog — Cancel really autofocused (FocusRequester wired below)
    // + confirm action in the error red so accidental taps require visual intent.
    // The "nuke" call is irreversible.
    if (showNuke) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        RelayDialog(
            onDismissRequest = { showNuke = false },
            title = stringResource(R.string.settings_nuke_data),
            // v1.28.6 — l'avertissement dit desormais que les messages du TELEPHONE partent aussi
            // (c'est ce que la purge fait depuis cette version), et, quand SMS Tech n'est pas
            // l'application SMS par defaut, que le systeme le lui refusera. Deux textes plutot
            // qu'un seul conditionnel : celui qui s'ajoute est un AVERTISSEMENT, il porte la
            // couleur d'erreur, et la promesse principale ne se reecrit pas selon l'etat.
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_nuke_confirm))
                    if (!viewModel.defaultAppManager.isDefault()) {
                        Text(
                            text = stringResource(R.string.settings_nuke_confirm_not_default),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        viewModel.nukeData()
                        showNuke = false
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showNuke = false },
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .focusable(),
                )
            },
        )
    }

    // v1.28.6 — L'EFFACEMENT N'A PAS TOUT EMPORTE, ET CELA SE DIT.
    //
    // Le dialogue de confirmation promet « irréversible ». Quand le système refuse la suppression
    // des messages du téléphone — en pratique parce que SMS Tech n'est pas l'application SMS par
    // défaut — la promesse est fausse, et une promesse fausse est pire que pas de promesse. Un
    // seul bouton : il n'y a rien à décider, seulement à savoir. Sa fermeture déclenche le
    // redémarrage que la purge avait mis en attente pour laisser ce message se lire.
    // v1.28.9 (septième note d'Andrew, constat 2) — trois causes possibles, dites séparément par
    // [EffacementIncompletTexte] : l'écran est au seuil de complexité de detekt.
    nukeResultat?.let { resultat ->
        val incomplet = resultat.incomplet
        RelayDialog(
            onDismissRequest = { /* lecture obligatoire : seul le bouton ferme */ },
            title = stringResource(
                if (incomplet) {
                    R.string.settings_nuke_residue_title
                } else {
                    R.string.settings_nuke_done_title
                },
            ),
            icon = {
                Icon(
                    imageVector = if (incomplet) Icons.Outlined.WarningAmber else Icons.Outlined.Check,
                    contentDescription = null,
                    tint = if (incomplet) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            },
            titleColor = if (incomplet) MaterialTheme.colorScheme.error else null,
            content = {
                if (incomplet) {
                    EffacementIncompletTexte(resultat)
                } else {
                    Text(stringResource(R.string.settings_nuke_done_body))
                }
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_close),
                    onClick = {
                        nukeResultat = null
                        viewModel.terminerEffacement()
                    },
                )
            },
        )
    }
}
