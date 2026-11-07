package com.filestech.sms.ui.screens.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.core.ext.asEvents
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.usecase.CancelScheduledMessageUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.RetryScheduledMessageUseCase
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayEmptyState
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.theme.RelayText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * v1.15.1 — Liste des messages programmés en attente d'envoi.
 *
 * Avant cette version, l'infrastructure (ScheduleMessageUseCase + ScheduledMessageWorker + DAO)
 * existait mais sans UI exposée — un user ayant programmé un message via un chemin théorique
 * n'avait aucun moyen de le voir ou de l'annuler. Cet écran ferme ce cycle.
 *
 * Source : route accessible depuis Settings → "Messages programmés". Affiche les messages
 * `PENDING` triés par scheduledAt ASC. Tap sur une ligne → dialog confirmation annulation.
 *
 * v1.25.3 (audit H6) — seconde section « Échecs ». L'écran ne montrait que `PENDING` : dès
 * qu'un envoi était abandonné il quittait la liste sans laisser de trace, et l'utilisateur
 * n'avait ni l'information, ni le moyen de relancer. Les échecs sont désormais visibles, avec
 * relance et retrait explicites.
 */
@HiltViewModel
class ScheduledMessagesViewModel @Inject constructor(
    repo: ScheduledMessageRepository,
    private val cancel: CancelScheduledMessageUseCase,
    private val retry: RetryScheduledMessageUseCase,
    private val delete: DeleteScheduledMessageUseCase,
) : ViewModel() {

    val pending: StateFlow<List<ScheduledMessage>> = repo.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val failed: StateFlow<List<ScheduledMessage>> = repo.observeFailed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * v1.28.3 (F20) — émet `false` quand l'annulation n'a PAS pu prendre.
     *
     * L'envoi était alors déjà revendiqué par le worker : le message part, et rien ne l'arrête
     * plus. L'écran affichait pourtant le même bouton et la même boîte de confirmation, puis ne
     * disait rien.
     *
     * v1.28.3 (audit du 2026-09-09) — passé de `Channel` à [oneShotEvents], le patron du projet
     * pour un événement d'interface qui ne doit pas se rejouer à la rotation. Ma première version
     * introduisait une SECONDE façon de faire la même chose que `ThreadViewModel` et
     * `SettingsViewModel` — la duplication que ce chantier passe son temps à défaire. Le `Channel`
     * offrait certes une capacité de 64 contre 16 ici, mais soixante-quatre refus d'annulation
     * consécutifs sans personne pour les lire ne décrivent aucune situation réelle.
     */
    private val _cancelRefused = oneShotEvents<Unit>()
    val cancelRefused = _cancelRefused.asEvents()

    fun cancelMessage(id: Long) {
        viewModelScope.launch {
            val outcome = cancel(id)
            val pris = (outcome as? com.filestech.sms.core.result.Outcome.Success)?.value == true
            if (!pris) _cancelRefused.tryEmit(Unit)
        }
    }

    fun retryMessage(id: Long) {
        viewModelScope.launch { retry(id) }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch { delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesScreen(
    onBack: () -> Unit,
    viewModel: ScheduledMessagesViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    var confirmCancelFor by remember { mutableStateOf<ScheduledMessage?>(null) }
    var confirmDeleteFor by remember { mutableStateOf<ScheduledMessage?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Résolue au niveau composable et non via `LocalContext.getString` dans le clic : le
    // `Context` capturé dans une lambda non-composable ne suit pas les changements de
    // configuration (`LocalContextGetResourceValueCall`).
    val retriedMessage = stringResource(R.string.scheduled_retried)

    // v1.28.3 (F20) — l'annulation refusée se dit. Voir `ScheduledMessagesViewModel.cancelMessage`.
    val cancelRefusedMessage = stringResource(R.string.scheduled_cancel_too_late)
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.cancelRefused.collect { snackbarHost.showSnackbar(cancelRefusedMessage) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scheduled_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        if (pending.isEmpty() && failed.isEmpty()) {
            // v1.15.1 — Empty state enrichi : icône + titre + mode d'emploi détaillé.
            // L'user qui ouvre cette page sans message programmé doit comprendre comment
            // en créer un — sinon il referme et la feature reste invisible.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RelayEmptyState(
                    icon = Icons.Outlined.Schedule,
                    headline = stringResource(R.string.scheduled_empty),
                    body = stringResource(R.string.scheduled_empty_explainer),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Top,
            ) {
                // L'en-tête « En attente » n'a de sens que s'il y a une seconde section à en
                // distinguer : sans échec, la liste reste exactement celle d'avant.
                if (pending.isNotEmpty() && failed.isNotEmpty()) {
                    item(key = "header-pending") {
                        SectionHeader(title = stringResource(R.string.scheduled_section_pending))
                    }
                }
                items(pending, key = { "pending-${it.id}" }) { msg ->
                    ScheduledRow(
                        message = msg,
                        whenLabelRes = R.string.scheduled_when,
                        whenColor = MaterialTheme.colorScheme.primary,
                        trailing = {
                            RelayTextButton(
                                text = stringResource(R.string.action_cancel),
                                onClick = { confirmCancelFor = msg },
                            )
                        },
                    )
                }
                if (failed.isNotEmpty()) {
                    // v1.28.3 (F20) — la section accueille désormais deux natures de lignes.
                    // Tant qu'elle n'en contient qu'une, elle s'annonce comme avant ; dès qu'un
                    // envoi interrompu s'y trouve, le titre et l'explication le disent, sans
                    // quoi l'utilisateur lirait « Échecs » au-dessus d'un message peut-être
                    // parti — l'erreur exactement dans le mauvais sens.
                    val interrompus = failed.any { it.state == ScheduledMessage.State.INTERRUPTED }
                    item(key = "header-failed") {
                        SectionHeader(
                            title = stringResource(
                                if (interrompus) {
                                    R.string.scheduled_section_failed_or_interrupted
                                } else {
                                    R.string.scheduled_section_failed
                                },
                            ),
                            explainer = stringResource(R.string.scheduled_failed_explainer) +
                                if (interrompus) {
                                    "\n" + stringResource(R.string.scheduled_interrupted_explainer)
                                } else {
                                    ""
                                },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    items(failed, key = { "failed-${it.id}" }) { msg ->
                        ScheduledRow(
                            message = msg,
                            // v1.28.3 (F20) — un envoi interrompu n'est pas un envoi échoué, et
                            // le lui faire dire serait mentir dans le sens dangereux : il est
                            // peut-être parti. Il partage la section parce qu'il y trouve les
                            // deux seules actions qui lui conviennent — relancer, retirer — mais
                            // il porte son propre libellé.
                            whenLabelRes = if (msg.state == ScheduledMessage.State.INTERRUPTED) {
                                R.string.scheduled_interrupted_when
                            } else {
                                R.string.scheduled_failed_when
                            },
                            whenColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RelayTextButton(
                                        text = stringResource(R.string.action_retry),
                                        onClick = {
                                            viewModel.retryMessage(msg.id)
                                            scope.launch { snackbarHost.showSnackbar(retriedMessage) }
                                        },
                                    )
                                    RelayDestructiveButton(
                                        text = stringResource(R.string.action_delete),
                                        onClick = { confirmDeleteFor = msg },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    confirmCancelFor?.let { msg ->
        RelayDialog(
            onDismissRequest = { confirmCancelFor = null },
            title = stringResource(R.string.scheduled_cancel_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.scheduled_cancel_confirm_body),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.scheduled_cancel_confirm_action),
                    onClick = {
                        val id = msg.id
                        confirmCancelFor = null
                        viewModel.cancelMessage(id)
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_back),
                    onClick = { confirmCancelFor = null },
                )
            },
        )
    }

    // Retrait définitif : même exigence de confirmation que l'annulation ci-dessus — un tap
    // sur une icône ne doit jamais suffire à effacer un message que l'utilisateur a écrit.
    confirmDeleteFor?.let { msg ->
        RelayDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = stringResource(R.string.scheduled_delete_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.scheduled_delete_confirm_body),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        val id = msg.id
                        confirmDeleteFor = null
                        viewModel.deleteMessage(id)
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmDeleteFor = null },
                )
            },
        )
    }
}

/** En-tête de section, avec un explicatif optionnel sous le titre. */
@Composable
private fun SectionHeader(
    title: String,
    explainer: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = RelayText.SectionHeader,
            color = color,
        )
        if (explainer != null) {
            Text(
                text = explainer,
                style = RelayText.Timestamp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Ligne commune aux deux sections : même corps, mêmes destinataires, même date. Seuls le libellé
 * de date et les actions de fin varient. Factorisée pour qu'une évolution du rendu n'ait pas à
 * être répliquée — donc à terme désynchronisée — entre attente et échec.
 *
 * Géométrie de la ligne de conversation : destinataires en 17sp/500, corps en 15sp secondaire,
 * date en 13sp — les actions (envoyer maintenant / supprimer) sont des boutons texte bleu /
 * rouge en fin de ligne.
 */
@Composable
private fun ScheduledRow(
    message: ScheduledMessage,
    whenLabelRes: Int,
    whenColor: Color,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val recipients = message.addresses.joinToString(", ") { it.toString() }
    val whenLabel = remember(message.scheduledAt) {
        // Format date+time locale system — pas de lib externe, DateFormat AOSP.
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        df.format(Date(message.scheduledAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.scheduled_to, recipients),
                style = RelayText.ConvoName,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message.body,
                style = RelayText.Preview,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(whenLabelRes, whenLabel),
                style = RelayText.Timestamp,
                color = whenColor,
            )
        }
        trailing()
    }
}
