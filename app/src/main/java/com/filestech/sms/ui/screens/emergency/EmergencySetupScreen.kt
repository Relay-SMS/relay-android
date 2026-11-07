package com.filestech.sms.ui.screens.emergency

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.filestech.sms.R
import com.filestech.sms.domain.emergency.EmergencyTemplate
import com.filestech.sms.system.safety.rememberSafetyMessageTexts
import com.filestech.sms.ui.components.BanniereRoleSmsManquant
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.RelaySection
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.rememberPermissionLocalisation
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import com.google.accompanist.permissions.ExperimentalPermissionsApi

/**
 * v1.10.0 — Écran de configuration du Mode urgence.
 *
 * 3 sections :
 *  1. **Activation** — Switch enable/disable + description
 *  2. **Message** — RadioButtons sur les 3 templates (NEED_HELP / DANGER /
 *     DISCREET) + preview du rendu
 *  3. **Géolocalisation** — Switch inclure les coordonnées (`includeLocation`)
 *     + alerte explicative ("nécessite la permission Localisation")
 *  4. **Contacts** — Lien vers le setup Safety Call (réutilise la même
 *     liste, pas de duplication)
 *
 * Validation au save : aucun champ requis individuellement (tout est
 * optionnel). Si `enabled = true` ET pas de contacts Safety Call, on
 * laisse sauver mais le bouton URGENCE sera grisé jusqu'à la config.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun EmergencySetupScreen(
    onBack: () -> Unit,
    onOpenSafetyCallSetup: () -> Unit,
    viewModel: EmergencyViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    // v1.27.2 — etat REELLEMENT ENREGISTRE. Distinct de `draft.enabled`, qui n est qu une
    // intention tant que l activation n a pas ete validee.
    val savedEnabled = viewModel.state.collectAsStateWithLifecycle().value.enabled
    val contactsCount by viewModel.safetyCallContactsCount.collectAsStateWithLifecycle()
    // v1.25.5 — la liste elle-même, pour l'afficher au lieu d'un simple compte.
    val emergencyContacts by viewModel.safetyCallContacts.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // v1.28.12 — le retour du sélecteur système ne porte rien d'utile : c'est le `ON_RESUME`
    // de [BanniereRoleSmsManquant] qui relit l'état du rôle et fait disparaître la bannière.
    val lanceurRoleSms = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { }

    // v1.27.2 — AVERTISSEMENT AU RETOUR. Constate sur appareil : « si je fais retour ca m indique
    // rien ». Quitter jetait le brouillon en silence — sur une fonction qui envoie de vrais SMS
    // avec la position, croire l avoir activee alors qu elle ne l est pas est le mauvais sens
    // d echec. Le Safety call previent depuis la v1.25.5 ; celui-ci etait reste en arriere.
    //
    // `BackHandler` arme seulement une fois la destination RESUMED : compose pendant la
    // transition d arrivee, il capterait la fin du geste parti de l ecran precedent et ouvrirait
    // ce dialogue par-dessus lui. Voir le correctif du Coffre en v1.25.4.
    val navEntryOwner = LocalLifecycleOwner.current
    val navEntryState by navEntryOwner.lifecycle.currentStateAsState()
    val navEntryResumed = navEntryState.isAtLeast(Lifecycle.State.RESUMED)
    var confirmExitOpen by remember { mutableStateOf(false) }
    val requestExit: () -> Unit = {
        if (viewModel.hasUnsavedChanges) confirmExitOpen = true else onBack()
    }
    BackHandler(enabled = navEntryResumed) { requestExit() }
    // v1.10.0 audit S1+U2 — prompt permission ACCESS_FINE_LOCATION quand
    // l'user active "Inclure la position". Sans ça, le switch ON sans
    // permission = SMS sans coordonnées en cas d'urgence (faux sentiment
    // de sécurité).
    // v1.28.13 — exacte OU approximative, cf. [rememberPermissionLocalisation].
    val localisation = rememberPermissionLocalisation()
    val locationGranted = localisation.accordee

    // v1.27.2 — résolues à la composition : un `context.getString` dans le `collect` ajouterait
    // une instance de `LocalContextGetResourceValueCall` au-delà de la baseline lint.
    val emergencyEnabledMsg = stringResource(R.string.emergency_saved_enabled)
    val emergencyDisabledMsg = stringResource(R.string.emergency_saved_disabled)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            // v1.10.0 audit C2 — `when` exhaustif (le compilateur signale
            // tout nouveau case ajouté à Event).
            when (event) {
                // v1.27.2 — l'interrupteur a éteint sur-le-champ. On le confirme sans fermer
                // l'écran : la personne est peut-être en train d'ajuster d'autres réglages, et
                // rien ne reste à valider pour cette action-là.
                is EmergencyViewModel.Event.DisabledImmediately -> {
                    snackbarHost.showSnackbar(emergencyDisabledMsg)
                }
                is EmergencyViewModel.Event.Saved -> {
                    // v1.27.2 — le message nomme ce qui vient de se passer. Il affichait
                    // jusqu'ici le libellé du BOUTON (`action_save`), qui ne disait pas si le
                    // mode urgence venait d'être activé ou éteint.
                    snackbarHost.showSnackbar(
                        if (event.enabled) emergencyEnabledMsg else emergencyDisabledMsg,
                    )
                    onBack()
                }
                is EmergencyViewModel.Event.Triggered -> Unit // vient d'EmergencyScreen
                // v1.27.2 — émis par le bouton « Désactiver » d'[EmergencyScreen], qui gère
                // lui-même son message et son retour. Rien à faire ici.
                EmergencyViewModel.Event.Disabled -> Unit
            }
        }
    }

    if (confirmExitOpen) {
        UnsavedChangesDialog(
            onSave = {
                confirmExitOpen = false
                // `save` emet `Saved`, dont l observateur appelle deja `onBack()`.
                viewModel.save()
            },
            onDiscard = {
                confirmExitOpen = false
                onBack()
            },
            onDismiss = { confirmExitOpen = false },
        )
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
                        stringResource(R.string.emergency_setup_title),
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
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // v1.28.12 — AVANT tout le reste : sans le role d'application SMS par defaut, le
            // bouton d'urgence n'enverra rien. Cet ecran l'ignorait entierement.
            BanniereRoleSmsManquant(
                aLeRole = { viewModel.defaultAppManager.isDefault() },
                onCorriger = {
                    viewModel.defaultAppManager.buildChangeDefaultIntent()
                        ?.let { lanceurRoleSms.launch(it) }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // 1. Activation
            RelaySection(title = stringResource(R.string.emergency_setup_enable_title)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.emergency_setup_enable_desc),
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // v1.27.2 — indicateur d'état, calqué sur celui du Safety call.
                        //
                        // ⚠️ Il est lu depuis l'état RÉELLEMENT ENREGISTRÉ, jamais depuis la
                        // position de l'interrupteur : celui-ci n'est qu'une intention tant que
                        // l'activation n'a pas été validée, et un indicateur qui le suivrait
                        // mentirait exactement comme lui. C'est la leçon du Safety call.
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                color = if (savedEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        // L'interrupteur éteint immédiatement ; il n'allume qu'au moment
                        // d'enregistrer. On nomme donc l'écart plutôt que de le laisser deviner.
                        if (draft.enabled && !savedEnabled) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.safety_call_setup_state_unsaved),
                                style = RelayText.Timestamp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = viewModel::setEnabled,
                    )
                }
            }

            // 2. Template du message
            RelaySection(title = stringResource(R.string.emergency_setup_template_title)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    EmergencyTemplate.entries.forEach { template ->
                        val labelRes = when (template) {
                            EmergencyTemplate.NEED_HELP -> R.string.emergency_setup_template_need_help
                            EmergencyTemplate.DANGER -> R.string.emergency_setup_template_danger
                            EmergencyTemplate.DISCREET -> R.string.emergency_setup_template_discreet
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = draft.template == template,
                                    onClick = { viewModel.setTemplate(template) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = draft.template == template,
                                onClick = null,
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = RelayText.MessageBody,
                                )
                                Text(
                                    // v1.28.12 — MEME source que l'envoi, cf. rememberSafetyMessageTexts.
                                    // v1.28.13 (relecture GPT) — même LIEN aussi : absent quand la
                                    // position ne partira pas, avec sa marge quand elle est approximative.
                                    // Le lien en dur l'affichait même option coupée.
                                    text = rememberSafetyMessageTexts().emergencyBody(
                                        template,
                                        if (draft.includeLocation && locationGranted) {
                                            lienDExemple(approximative = !localisation.exacte)
                                        } else {
                                            null
                                        },
                                    ),
                                    style = RelayText.Timestamp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 3. Géolocalisation
            RelaySection(title = stringResource(R.string.emergency_setup_location_title)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.emergency_setup_location_desc),
                                style = RelayText.Timestamp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.includeLocation,
                            onCheckedChange = { newValue ->
                                viewModel.setIncludeLocation(newValue)
                                // v1.10.0 audit S1 — au passage OFF→ON sans
                                // permission, on prompte. Si l'user refuse,
                                // le warning ci-dessous s'affiche et le SMS
                                // partira sans coordonnées en cas d'urgence.
                                if (newValue && !locationGranted) {
                                    localisation.demander()
                                }
                            },
                        )
                    }
                    // v1.10.0 audit U2 — feedback explicite sur l'état réel
                    // de la permission. Si l'user a activé le switch mais
                    // refusé/non accordé la permission, on l'avertit que le
                    // SMS d'urgence partira sans coordonnées.
                    if (draft.includeLocation && !locationGranted) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.emergency_setup_location_permission_warning),
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (draft.includeLocation && !localisation.exacte) {
                        // v1.28.13 — position approximative : accordée, envoyée avec sa marge, et dite.
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.emergency_location_approximate),
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RelayTextButton(
                            text = stringResource(R.string.emergency_location_grant_precise),
                            onClick = localisation.demander,
                        )
                    }
                }
            }

            // 4. Contacts (lien vers Safety call setup)
            RelaySection(title = stringResource(R.string.emergency_setup_contacts_title)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (contactsCount == 0) {
                            stringResource(R.string.emergency_setup_contacts_empty)
                        } else {
                            stringResource(R.string.emergency_setup_contacts_count, contactsCount)
                        },
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v1.25.5 — la liste, et plus seulement le compte.
                    //
                    // « 2 contacts » oblige à ouvrir un autre écran pour savoir QUI sera prévenu, et
                    // ce détour passe par la configuration du Safety call — une manipulation
                    // déroutante là où l'information devrait être immédiate. Sur un écran d'urgence,
                    // savoir qui recevra l'alerte est la question principale.
                    //
                    // Lecture seule : la modification reste au bouton ci-dessous, pour que ce carnet
                    // n'ait qu'un seul endroit où s'éditer.
                    emergencyContacts.forEach { contact ->
                        Spacer(Modifier.height(6.dp))
                        contact.sanitizedDisplayName()?.let { name ->
                            Text(text = name, style = RelayText.MessageBody)
                        }
                        Text(
                            text = contact.phoneNumber,
                            style = RelayText.Timestamp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    RelayTextButton(
                        text = stringResource(R.string.emergency_setup_contacts_cta),
                        onClick = onOpenSafetyCallSetup,
                    )
                }
            }

            // Bouton de save — bleu de la marque, texte blanc.
            Spacer(Modifier.height(8.dp))
            RelayPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = viewModel::save,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Dialogue « quitter sans enregistrer » : sauver le brouillon ou le jeter. */
@Composable
private fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.safety_call_setup_unsaved_title),
        content = {
            Text(
                text = stringResource(R.string.emergency_setup_unsaved_body),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.safety_call_setup_save),
                onClick = onSave,
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_discard),
                onClick = onDiscard,
            )
        },
    )
}
