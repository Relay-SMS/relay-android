package com.filestech.sms.ui.screens.safetycall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.filestech.sms.R
import com.filestech.sms.domain.safetycall.SafetyCallConfig
import com.filestech.sms.domain.safetycall.SafetyCallTemplate
import com.filestech.sms.domain.safetycall.SafetyCallTriggerRecord
import com.filestech.sms.system.notifications.CANAL_DU_SAFETY_CALL
import com.filestech.sms.system.safety.rememberSafetyMessageTexts
import com.filestech.sms.ui.components.BanniereNotificationsCoupees
import com.filestech.sms.ui.components.BanniereRoleSmsManquant
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayPickerDialog
import com.filestech.sms.ui.components.RelayPickerOption
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.RelayRow
import com.filestech.sms.ui.components.RelaySection
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.theme.BrandWarning
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.util.libelleDeDuree
import java.text.DateFormat
import java.util.Date

/**
 * v1.9.0 — Écran de configuration du Safety call.
 *
 * Structure verticale en 4 sections, chacune dans une [Card] M3 distincte
 * pour une coordination visuelle claire :
 *  1. **État** — Switch enable/disable + description du fonctionnement
 *  2. **Durée** — 4 RadioButtons (24h, 48h, 72h, Custom) — Custom ouvre
 *     un dialog d'input numérique en heures
 *  3. **Contacts** — Liste actuelle (avec bouton remove) + bouton "Ajouter"
 *     qui ouvre un dialog avec champs Nom + Téléphone
 *  4. **Message** — 4 RadioButtons templates (Vérification, Urgent, Suivi,
 *     Personnalisé) + champ de saisie si CUSTOM + aperçu rendu du SMS final
 *
 * Bouton **Save** en bas, validé via [SafetyCallSetupViewModel.save]. Les
 * erreurs de validation s'affichent via SnackBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyCallSetupScreen(
    onBack: () -> Unit,
    viewModel: SafetyCallSetupViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    // v1.27.2 — l'etat REELLEMENT enregistre, pour que l'indicateur ne suive pas le brouillon.
    val savedEnabled by viewModel.savedEnabled.collectAsStateWithLifecycle()
    // v1.27.3 — historique des declenchements passes, lu depuis DataStore et non depuis le
    // brouillon : ce sont des faits, pas une saisie.
    val history by viewModel.history.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var addContactDialogOpen by remember { mutableStateOf(false) }
    var customDurationDialogOpen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // v1.28.12 — le retour du sélecteur système ne porte rien d'utile : c'est le `ON_RESUME`
    // de [BanniereRoleSmsManquant] qui relit l'état du rôle et fait disparaître la bannière.
    val lanceurRoleSms = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { }

    /*
     * v1.25.5 — quitter sans enregistrer ne doit plus jeter la saisie en silence.
     *
     * Le parcours qui a fait remonter le défaut : Mode urgence → « gérer les contacts » → Safety
     * call → « Ajouter un contact » → le dialogue, dont le bouton s'appelait « Enregistrer ».
     * Le contact apparaissait dans la liste, donc tout indiquait que c'était acquis. Or seul le
     * bouton du bas écrit sur le disque : le Retour jetait le brouillon, sans un mot.
     *
     * Le libellé du dialogue est corrigé (« Ajouter »), mais renommer ne suffit pas — on prévient
     * aussi avant de perdre quoi que ce soit.
     *
     * `BackHandler` armé seulement une fois la destination `RESUMED` : composé pendant la
     * transition d'arrivée, il capterait la fin du geste parti de l'écran précédent et ouvrirait
     * ce dialogue par-dessus lui. Voir le correctif du Coffre en v1.25.4.
     */
    val navEntryOwner = LocalLifecycleOwner.current
    val navEntryState by navEntryOwner.lifecycle.currentStateAsState()
    val navEntryResumed = navEntryState.isAtLeast(Lifecycle.State.RESUMED)
    var confirmExitOpen by remember { mutableStateOf(false) }
    val requestExit: () -> Unit = {
        if (viewModel.hasUnsavedChanges) confirmExitOpen = true else onBack()
    }
    BackHandler(enabled = navEntryResumed) { requestExit() }

    // v1.27.2 — un message PAR action, résolu à la composition (un `ctx.getString` dans le
    // `collect` ajouterait une instance de `LocalContextGetResourceValueCall` hors baseline).
    val safetyEnabledMsg = stringResource(R.string.safety_call_saved_enabled)
    val safetyDisabledMsg = stringResource(R.string.safety_call_saved_disabled)
    val contactAddedMsg = stringResource(R.string.safety_call_contact_added)
    val contactRemovedMsg = stringResource(R.string.safety_call_contact_removed)
    val disabledNowMsg = stringResource(R.string.safety_call_setup_disabled_now)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SafetyCallSetupViewModel.Event.Saved -> {
                    // Nomme l'état enregistré : « Contacts et réglages enregistrés » ne disait
                    // pas si la surveillance courait ou non.
                    snackbarHost.showSnackbar(
                        if (event.enabled) safetyEnabledMsg else safetyDisabledMsg,
                    )
                    onBack()
                }
                // v1.27.2 — l'ajout se confirme. Le dialogue se refermait en silence.
                SafetyCallSetupViewModel.Event.ContactAdded ->
                    snackbarHost.showSnackbar(contactAddedMsg)
                SafetyCallSetupViewModel.Event.ContactRemoved ->
                    snackbarHost.showSnackbar(contactRemovedMsg)
                // v1.27.2 — l'interrupteur a éteint sur-le-champ : on le confirme, sans quitter
                // l'écran (l'utilisateur peut vouloir continuer à modifier).
                SafetyCallSetupViewModel.Event.DisabledImmediately ->
                    snackbarHost.showSnackbar(disabledNowMsg)
                is SafetyCallSetupViewModel.Event.ValidationError -> {
                    val msgRes = when (event.reason) {
                        SafetyCallSetupViewModel.ValidationReason.NoContacts ->
                            R.string.safety_call_setup_save_validation_no_contacts
                        SafetyCallSetupViewModel.ValidationReason.InvalidPhone ->
                            R.string.safety_call_setup_contact_invalid
                        SafetyCallSetupViewModel.ValidationReason.MaxContactsReached ->
                            R.string.safety_call_setup_contact_max_reached
                        SafetyCallSetupViewModel.ValidationReason.EmptyCustomMessage ->
                            R.string.safety_call_setup_save_validation_custom_empty
                        // v1.26.1 (audit M5) — carnet partagé avec le Mode urgence.
                        SafetyCallSetupViewModel.ValidationReason.SharedWithEmergency ->
                            R.string.safety_call_setup_shared_with_emergency
                    }
                    // v1.9.0 — validation = erreur rouge, pas confirmation bleue.
                    snackbarHost.showError(ctx.getString(msgRes))
                }
            }
        }
    }

    // Grouped-list page base: #F5F5F5 in light, #1B1B1B in dark — the section rows sit on
    // the canvas (see RelaySection). Picked from the surface luminance so it follows the
    // active scheme, dark variants included.
    val pageBackground = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        RelayColors.CanvasDark
    } else {
        RelayColors.Surface
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.safety_call_setup_title),
                        style = RelayText.ThreadTitle,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
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
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Feedback round 8 — the Settings row shows only the state now;
            // the long description lives here, at the top of the setup screen.
            Text(
                text = stringResource(R.string.settings_safety_call_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // v1.28.12 — AVANT tout le reste : sans le rôle d'application SMS par défaut, rien
            // ne partira à l'échéance. L'écran l'ignorait entièrement, et l'utilisateur pouvait
            // armer une protection inerte en croyant être couvert.
            BanniereRoleSmsManquant(
                aLeRole = { viewModel.defaultAppManager.isDefault() },
                onCorriger = {
                    viewModel.defaultAppManager.buildChangeDefaultIntent()
                        ?.let { lanceurRoleSms.launch(it) }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // v1.28.13 — l'avertissement avant envoi et l'arrêt de la séquence passent par des
            // notifications : sans elles, le Safety call part sans prévenir, et on ne l'arrête plus
            // qu'en rouvrant l'application.
            BanniereNotificationsCoupees(
                message = stringResource(R.string.notifications_off_safety_call),
                canaux = listOf(CANAL_DU_SAFETY_CALL),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            StatusSection(
                draft = draft,
                savedEnabled = savedEnabled,
                onToggle = viewModel::setEnabled,
            )
            DurationSection(
                draft = draft,
                onSelect = viewModel::setTimeoutMs,
                onOpenCustom = { customDurationDialogOpen = true },
            )
            ContactsSection(
                draft = draft,
                onAdd = { addContactDialogOpen = true },
                onRemove = viewModel::removeContact,
            )
            TemplateSection(
                draft = draft,
                onSelectTemplate = viewModel::setTemplate,
                onCustomMessageChange = viewModel::setCustomMessage,
            )
            Spacer(Modifier.size(8.dp))
            // v1.27.3 — l'avertissement se lit JUSTE AVANT le geste qui engage. Demande de Patrice
            // le 2026-08-06, après que deux de ses propres tests se soient arrêtés là-dessus.
            // Il garde son fond orange : la teinte est délibérée (avertissement de sécurité, pas
            // un bandeau décoratif) et le texte noir dessus est le seul couple qui passe le
            // seuil AA — voir le KDoc de TimerResetWarning.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                TimerResetWarning()
            }
            Spacer(Modifier.size(8.dp))
            RelayPrimaryButton(
                text = stringResource(R.string.safety_call_setup_save),
                onClick = viewModel::save,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // v1.27.3 — l'historique vient APRÈS le bouton d'enregistrement : ce n'est pas un champ
            // du formulaire mais une lecture du passé, et il ne doit pas s'interposer entre la
            // saisie et l'action qui la valide.
            HistorySection(history = history)
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmExitOpen) {
        RelayDialog(
            onDismissRequest = { confirmExitOpen = false },
            title = stringResource(R.string.safety_call_setup_unsaved_title),
            content = {
                Text(
                    text = stringResource(R.string.safety_call_setup_unsaved_body),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.safety_call_setup_save),
                    onClick = {
                        confirmExitOpen = false
                        // `save` émet `Saved`, dont l'observateur appelle déjà `onBack()`. En cas
                        // d'échec de validation, on reste sur l'écran avec le motif affiché — c'est
                        // voulu : sortir alors reviendrait à perdre la saisie malgré l'avertissement.
                        viewModel.save()
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_discard),
                    onClick = {
                        confirmExitOpen = false
                        onBack()
                    },
                )
            },
        )
    }

    if (addContactDialogOpen) {
        AddContactDialog(
            onDismiss = { addContactDialogOpen = false },
            onConfirm = { name, phone ->
                // v1.25.5 — on ne referme QUE si l'ajout a abouti. Le dialogue se fermait
                // auparavant dans tous les cas : un numéro refusé disparaissait avec lui, et le
                // motif du refus s'affichait dans un bandeau, derrière un dialogue déjà parti.
                if (viewModel.addContact(name, phone)) addContactDialogOpen = false
            },
        )
    }
    if (customDurationDialogOpen) {
        CustomDurationDialog(
            initialHours = (draft.timeoutMs / 3_600_000L).toInt().coerceIn(1, MAX_CUSTOM_HOURS),
            onDismiss = { customDurationDialogOpen = false },
            onConfirm = { hours ->
                viewModel.setTimeoutMs(hours.toLong() * 3_600_000L)
                customDurationDialogOpen = false
            },
        )
    }
}

@Composable
private fun StatusSection(
    draft: SafetyCallConfig,
    savedEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    RelaySection(title = stringResource(R.string.safety_call_setup_section_status)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    stringResource(R.string.safety_call_setup_toggle_enable),
                    style = RelayText.MessageBody,
                )
                Text(
                    stringResource(R.string.safety_call_setup_toggle_desc),
                    style = RelayText.Timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.enabled, onCheckedChange = onToggle)
        }

        // v1.27.2 — l'état RÉELLEMENT enregistré, et non la position de l'interrupteur.
        //
        // Un indicateur qui suivrait le brouillon mentirait exactement comme lui : c'est ce qui a
        // fait croire à une désactivation qui n'avait jamais été écrite. Ici, ce qui s'affiche
        // vient de DataStore.
        // Phase 8 — le choix clair/sombre suit le thème ACTIF (luminance de la surface),
        // pas le thème système : un `ThemeMode` forcé (sombre sur un système clair, ou
        // l'inverse) choisissait sinon la mauvaise teinte — le vert clair `#2E7D32` sur
        // fond sombre ne donne que 2.6:1. Voir le même motif `pageBackground` plus haut.
        val activeColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            com.filestech.sms.ui.theme.BrandSuccessDark
        } else {
            com.filestech.sms.ui.theme.BrandSuccessLight
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.safety_call_setup_state_label),
                style = RelayText.MessageBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (savedEnabled) {
                    stringResource(R.string.safety_call_setup_state_on)
                } else {
                    stringResource(R.string.safety_call_setup_state_off)
                },
                style = RelayText.MessageBody,
                fontWeight = FontWeight.SemiBold,
                color = if (savedEnabled) activeColor else MaterialTheme.colorScheme.error,
            )
        }

        // Et quand l'intention diverge de l'enregistré, on le NOMME — plutôt que de laisser
        // deviner que l'interrupteur n'a pas encore d'effet.
        if (draft.enabled != savedEnabled) {
            Text(
                text = stringResource(R.string.safety_call_setup_state_unsaved),
                style = RelayText.Timestamp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

/** Libellé de la durée courante : « 24 heures », ou « Personnalisée · 96 heures » hors standard. */
@Composable
private fun durationLabelText(timeoutMs: Long): String {
    val hours = (timeoutMs / 3_600_000L).toInt()
    return when (timeoutMs) {
        SafetyCallConfig.TIMEOUT_24H_MS -> stringResource(R.string.safety_call_setup_duration_24h)
        SafetyCallConfig.TIMEOUT_48H_MS -> stringResource(R.string.safety_call_setup_duration_48h)
        SafetyCallConfig.TIMEOUT_72H_MS -> stringResource(R.string.safety_call_setup_duration_72h)
        // v1.28.12 (mesure sur appareil, S24 en italien) — le PLURIEL, pas la forme
        // abrégée. La puce affichait « 1 h » au milieu de « 24 ore / 48 ore / 72 ore » :
        // traduite, donc invisible à la parité, mais abrégée là où ses voisines ne le
        // sont pas. Un lecteur italien y voit du texte non traduit.
        else -> stringResource(R.string.safety_call_setup_duration_custom) + " · " +
            pluralStringResource(R.plurals.safety_duration_hours, hours, hours)
    }
}

@Composable
private fun DurationSection(
    draft: SafetyCallConfig,
    onSelect: (Long) -> Unit,
    onOpenCustom: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val current = draft.timeoutMs
    val isStandard = current == SafetyCallConfig.TIMEOUT_24H_MS ||
        current == SafetyCallConfig.TIMEOUT_48H_MS ||
        current == SafetyCallConfig.TIMEOUT_72H_MS
    val cs = MaterialTheme.colorScheme
    RelaySection(title = stringResource(R.string.safety_call_setup_section_duration)) {
        RelayRow(
            title = durationLabelText(current),
            onClick = { pickerOpen = true },
            trailing = {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        Text(
            stringResource(R.string.safety_call_setup_duration_custom_hint),
            style = RelayText.Timestamp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
    if (pickerOpen) {
        RelayPickerDialog(
            title = stringResource(R.string.safety_call_setup_section_duration),
            options = listOf(
                RelayPickerOption(
                    label = stringResource(R.string.safety_call_setup_duration_24h),
                    selected = current == SafetyCallConfig.TIMEOUT_24H_MS,
                    onClick = {
                        onSelect(SafetyCallConfig.TIMEOUT_24H_MS)
                        pickerOpen = false
                    },
                ),
                RelayPickerOption(
                    label = stringResource(R.string.safety_call_setup_duration_48h),
                    selected = current == SafetyCallConfig.TIMEOUT_48H_MS,
                    onClick = {
                        onSelect(SafetyCallConfig.TIMEOUT_48H_MS)
                        pickerOpen = false
                    },
                ),
                RelayPickerOption(
                    label = stringResource(R.string.safety_call_setup_duration_72h),
                    selected = current == SafetyCallConfig.TIMEOUT_72H_MS,
                    onClick = {
                        onSelect(SafetyCallConfig.TIMEOUT_72H_MS)
                        pickerOpen = false
                    },
                ),
                RelayPickerOption(
                    label = if (isStandard) {
                        stringResource(R.string.safety_call_setup_duration_custom)
                    } else {
                        durationLabelText(current)
                    },
                    selected = !isStandard,
                    onClick = {
                        pickerOpen = false
                        onOpenCustom()
                    },
                ),
            ),
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun ContactsSection(
    draft: SafetyCallConfig,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    RelaySection(title = stringResource(R.string.safety_call_setup_section_contacts)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            draft.contacts.forEachIndexed { index, contact ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        contact.sanitizedDisplayName()?.let {
                            Text(it, style = RelayText.MessageBody)
                        }
                        Text(
                            contact.phoneNumber,
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.safety_call_setup_remove_contact),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (draft.contacts.size < SafetyCallConfig.MAX_CONTACTS) {
                RelayTextButton(
                    text = stringResource(R.string.safety_call_setup_add_contact),
                    onClick = onAdd,
                )
            } else {
                Text(
                    stringResource(R.string.safety_call_setup_contact_max_reached),
                    style = RelayText.Timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TemplateSection(
    draft: SafetyCallConfig,
    onSelectTemplate: (SafetyCallTemplate) -> Unit,
    onCustomMessageChange: (String) -> Unit,
) {
    RelaySection(title = stringResource(R.string.safety_call_setup_section_template)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TemplateOption(
                label = stringResource(R.string.safety_call_setup_template_check_in),
                selected = draft.template == SafetyCallTemplate.CHECK_IN,
                onClick = { onSelectTemplate(SafetyCallTemplate.CHECK_IN) },
            )
            TemplateOption(
                label = stringResource(R.string.safety_call_setup_template_urgent),
                selected = draft.template == SafetyCallTemplate.URGENT,
                onClick = { onSelectTemplate(SafetyCallTemplate.URGENT) },
            )
            TemplateOption(
                label = stringResource(R.string.safety_call_setup_template_follow_up),
                selected = draft.template == SafetyCallTemplate.FOLLOW_UP,
                onClick = { onSelectTemplate(SafetyCallTemplate.FOLLOW_UP) },
            )
            TemplateOption(
                label = stringResource(R.string.safety_call_setup_template_custom),
                selected = draft.template == SafetyCallTemplate.CUSTOM,
                onClick = { onSelectTemplate(SafetyCallTemplate.CUSTOM) },
            )
            if (draft.template == SafetyCallTemplate.CUSTOM) {
                Spacer(Modifier.size(8.dp))
                RelayTextField(
                    value = draft.customMessage,
                    onValueChange = onCustomMessageChange,
                    placeholder = stringResource(R.string.safety_call_setup_template_custom_label),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
            // Aperçu du SMS final rendu (placeholder [DURÉE] remplacé).
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.safety_call_setup_template_preview),
                style = RelayText.Timestamp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(12.dp),
            ) {
                Text(
                    // v1.28.12 — MEME source que l'envoi (`TriggerSafetyCallUseCase`).
                    text = rememberSafetyMessageTexts()
                        .safetyCallBody(draft.template, draft.timeoutMs, draft.customMessage)
                        .ifBlank { "—" },
                    style = RelayText.Preview,
                )
            }
        }
    }
}

@Composable
private fun TemplateOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            RadioButton(selected = selected, onClick = null)
        }
        Spacer(Modifier.size(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

/**
 * v1.27.3 — **le comportement le plus surprenant de la fonction, dit à l'endroit où il compte.**
 *
 * # Pourquoi ce bandeau existe
 *
 * Rien dans l'application n'expliquait que le minuteur repart de zéro à chaque ouverture. Patrice
 * l'a découvert en testant : deux de ses essais du 2026-08-05 se sont arrêtés d'eux-mêmes parce qu'il
 * avait rouvert Relay entre-temps, et il a d'abord cru à un défaut.
 *
 * # Le texte est exact, et sa précision n'est pas cosmétique
 *
 * La remise à zéro exige **deux** conditions, pas une : `repeatOnLifecycle(RESUMED)` **et** un état
 * de verrou `Unlocked`/`Disabled` (`MainActivity.observeRealOpenForSafetyCallReset`). Donc ouvrir et
 * **déverrouiller** — arriver sur l'écran de verrouillage sans entrer son code ne remet rien à zéro.
 *
 * ⚠️ Et surtout : **utiliser le téléphone pour autre chose ne compte pas.** Une formulation du genre
 * « si vous touchez à l'application » laisserait croire qu'il faut cesser de se servir de son
 * téléphone, ce qui rendrait la fonction inutilisable. Sur une fonction de sécurité, un avertissement
 * approximatif est pire que pas d'avertissement.
 *
 * La dernière phrase énonce la conséquence — l'alerte part — parce que c'est elle qui donne son sens
 * à tout le reste.
 */
@Composable
private fun TimerResetWarning() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandWarning, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // 🔴 v1.27.3 (relecture Codex du 2026-08-06, SC-UI-1273-02) — **noir, pas blanc.**
        //
        // La première version posait du blanc sur cet orange en s'appuyant sur un contraste de
        // 4,87:1 que j'avais écrit dans le code. **Ce chiffre était faux.** Calcul WCAG refait :
        //
        //   luminance relative de #E65100 .... 0,22708
        //   blanc opaque ..................... 3,79:1   ← sous le seuil AA de 4,5:1
        //   blanc à 85 % composé ............. 3,12:1   ← pire encore
        //   noir opaque ...................... 5,54:1   ← conforme
        //
        // Le titre est en `bodyMedium` et le corps en `bodySmall` : ni l'un ni l'autre n'entre dans
        // la définition du « grand texte », le seuil applicable est donc bien 4,5:1.
        //
        // Et **plus d'opacité sur le corps** : c'est la phrase qui porte la règle complète — ouvrir
        // ET déverrouiller remet le minuteur à zéro, se servir du téléphone pour autre chose ne
        // change rien. La dégrader était exactement le mauvais arbitrage.
        //
        // Le fond orange et l'alias [BrandWarning] sont conservés : la teinte est délibérée, et
        // toucher `BrandBlocked` globalement affecterait d'autres écrans.
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.safety_call_reset_warning_title),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.safety_call_reset_warning_body),
                color = Color.Black,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * v1.27.3 — **historique des déclenchements**, du plus récent au plus ancien.
 *
 * # La question à laquelle cette section répond
 *
 * « Est-ce que ça s'est déjà déclenché, quand, et vers qui ? » — sans réponse jusqu'ici. La
 * notification de fin de séquence ne pouvait pas la porter : elle se balaie, elle ne survit pas au
 * redémarrage, et elle disparaît dès qu'on la tape, y compris sans l'avoir lue.
 *
 * # La garde du mode leurre est en amont, et c'est délibéré
 *
 * Rien ici ne teste `isPanicDecoy`, parce que cet écran n'est **pas atteignable** sous contrainte :
 * la route est dépilée à l'entrée en mode leurre (`AppRoot`) et la section qui y mène est masquée
 * dans les Réglages. Une troisième garde sur l'affichage laisserait croire que l'accès, lui, est
 * ouvert — c'est exactement l'inversion qui a produit le constat P-05.
 *
 * # Ce qui n'est pas affiché
 *
 * Jamais le corps des messages. Un historique qui les conserverait deviendrait, pour qui s'emparerait
 * du téléphone, la description écrite d'un réseau de soutien et de la façon de l'alerter.
 */
@Composable
private fun HistorySection(history: List<SafetyCallTriggerRecord>) {
    // Un seul formateur pour toute la liste, mémorisé : `DateFormat` n'est pas thread-safe, et la
    // composition est mono-thread par définition. Même motif que [ChatFormatters].
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    RelaySection(title = stringResource(R.string.safety_call_history_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (history.isEmpty()) {
                Text(
                    stringResource(R.string.safety_call_history_empty),
                    style = RelayText.MessageBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@RelaySection
            }
            history.forEachIndexed { index, record ->
                if (index > 0) Spacer(Modifier.size(12.dp))
                Text(
                    dateFormat.format(Date(record.triggeredAt)),
                    style = RelayText.MessageBody,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    pluralStringResource(
                        R.plurals.safety_call_history_count,
                        record.messagesDelivered,
                        record.messagesDelivered,
                        record.totalMessages,
                    ),
                    style = RelayText.Timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.recipients.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.safety_call_history_recipients,
                            record.recipients.joinToString(", "),
                        ),
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!record.isComplete) {
                    // Une séquence écourtée et une séquence menée à terme sont deux issues très
                    // différentes ; le seul couple de nombres les rendrait mal.
                    Text(
                        stringResource(R.string.safety_call_history_stopped),
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String?, phone: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.safety_call_setup_add_contact),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    placeholder = stringResource(R.string.safety_call_setup_contact_name_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RelayTextField(
                    value = phone,
                    onValueChange = { phone = it.take(20) },
                    placeholder = stringResource(R.string.safety_call_setup_contact_phone_label),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_add),
                onClick = { onConfirm(name.ifBlank { null }, phone) },
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
 * v1.9.0 — cap absolu de la durée custom saisie en heures.
 * Aligne sur [com.filestech.sms.domain.safetycall.SafetyCallConfig.TIMEOUT_MAX_MS]
 * (30 jours = 720 heures). Au-delà, Safety Call perd son sens pratique.
 */
private const val MAX_CUSTOM_HOURS: Int = 720

@Composable
private fun CustomDurationDialog(
    initialHours: Int,
    onDismiss: () -> Unit,
    onConfirm: (hours: Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialHours.toString()) }
    val parsed = value.toIntOrNull()?.coerceIn(1, MAX_CUSTOM_HOURS)
    // v1.9.0 — aperçu auto sous le champ : `"96 h ≈ 4 jours"` dès que ≥ 24h.
    // Aide l'user à se représenter visuellement la durée sans qu'il ait à
    // diviser mentalement par 24.
    val supportingLabel = when {
        parsed == null -> pluralStringResource(R.plurals.safety_duration_hours, 0, 0)
        // v1.28.12 — l'aperçu passe par [libelleDeDuree] et par le format d'heure déjà
        // traduit. Il était écrit ici à la main, en français : un Italien qui saisissait
        // 96 lisait « 96 h ≈ 4 jours ». Même défaut, même jour, même correctif que dans
        // l'écran des réglages — et c'est parce qu'ils étaient écrits deux fois qu'ils
        // ont divergé de leurs traductions deux fois.
        parsed >= 24 -> stringResource(
            R.string.safety_call_setup_duration_approx,
            pluralStringResource(R.plurals.safety_duration_hours, parsed, parsed),
            libelleDeDuree(parsed),
        )
        else -> pluralStringResource(R.plurals.safety_duration_hours, parsed, parsed)
    }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.safety_call_setup_duration_custom),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter { it.isDigit() }.take(3) },
                    placeholder = stringResource(R.string.safety_call_setup_duration_custom),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = supportingLabel,
                    style = RelayText.Timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
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
