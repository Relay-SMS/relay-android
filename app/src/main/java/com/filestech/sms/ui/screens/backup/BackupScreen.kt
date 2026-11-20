package com.filestech.sms.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.backup.BackupService
import com.filestech.sms.data.backup.NotesBackupService
import com.filestech.sms.domain.backup.RestoreNotesResult
import com.filestech.sms.domain.backup.RestoreResult
import com.filestech.sms.domain.usecase.RestoreBackupUseCase
import com.filestech.sms.domain.usecase.RestoreNotesBackupUseCase
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.RelaySection
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.security.ProtectSecretInput
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
    private val restoreBackup: RestoreBackupUseCase,
    // Notes backup (.notebk) — same split as the `.smsbk` pair above: the service for
    // the export, the use case for the restore.
    private val notesBackupService: NotesBackupService,
    private val restoreNotesBackup: RestoreNotesBackupUseCase,
    // v1.27.2 (audit externe 2026-08-04 #4) — distingue le refus « second facteur coffre »
    // du refus « session leurre » : les deux reviennent en AppError.Locked, mais seul le
    // premier peut être nommé à l'écran. Cf. [exportEncrypted].
    private val appLock: com.filestech.sms.security.AppLockManager,
    // v1.27.13 — le second facteur du coffre se prouve DANS ce flux, cf. [exportSecondFactor].
    private val vaultPin: com.filestech.sms.security.VaultPinManager,
    private val vaultSession: com.filestech.sms.security.VaultSessionState,
) : ViewModel() {

    sealed interface Event {
        data class ExportDone(val uri: android.net.Uri, val pages: Int = 0) : Event
        data object ExportFailed : Event

        // v1.27.2 (audit externe 2026-08-04 #4) — l'export exige la session coffre
        // déverrouillée quand le coffre n'est pas vide ; l'UI invite à l'ouvrir d'abord.
        data object ExportVaultLocked : Event

        // v1.15.2 — Événements restore. Le succès porte le récap chiffré pour l'affichage,
        // l'échec porte un kind typé qui mappe vers une string d'erreur localisée côté UI.
        data class RestoreDone(val result: RestoreResult) : Event
        data class RestoreFailed(val kind: RestoreFailureKind) : Event

        // Notes backup (.notebk) — same shapes as the `.smsbk` events above, kept
        // separate so the screen can show notes-specific strings. No vault-locked
        // variant: the vault second-factor gate does not apply to notes.
        data class NotesExportDone(val uri: android.net.Uri) : Event
        data object NotesExportFailed : Event
        data class NotesRestoreDone(val result: RestoreNotesResult) : Event
        data class NotesRestoreFailed(val kind: RestoreFailureKind) : Event
    }

    /** v1.15.2 — Catégorise les échecs de restore pour message UI ciblé. */
    enum class RestoreFailureKind { WRONG_PASSPHRASE_OR_CORRUPTED, INVALID_FORMAT, STORAGE }

    private val _events = oneShotEvents<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // v1.15.2 — Flag observable pour disabler le bouton "Restaurer" et afficher un indicateur
    // pendant le travail crypto + import (peut prendre quelques secondes sur grosse archive).
    // v1.17.0 audit KOTLIN-L1 — `MutableStateFlow` expose déjà `compareAndSet` atomique. Le
    // guard anti-réentrance utilise CAS(false→true) côté `restoreFromUri` plutôt qu'un
    // read-then-write non-atomique. Sûr même si appelé depuis un dispatcher non-Main.
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring

    // Notes restore twin of [_isRestoring]: same CAS anti-reentrance guard, separate
    // flag so a notes restore and an SMS restore can never share one.
    private val _isRestoringNotes = MutableStateFlow(false)
    val isRestoringNotes: StateFlow<Boolean> = _isRestoringNotes

    /**
     * v1.27.13 — ce que l'utilisateur doit prouver avant que l'export puisse aboutir.
     *
     * Consulte AVANT d'ouvrir le selecteur de fichier : le refus arrivait jusqu'ici apres le
     * choix d'une destination et la saisie d'une passphrase, sur une condition connue des le
     * depart — et `CreateDocument` ayant deja cree le fichier, un `.smsbk` vide restait sur le
     * stockage.
     */
    suspend fun exportSecondFactor(): com.filestech.sms.security.VaultSecondFactor =
        backupService.exportSecondFactor()

    /** v1.27.13 — meme verification que la porte du coffre, temporisation comprise. */
    suspend fun verifyVaultPin(candidate: CharArray): com.filestech.sms.security.PinVerdict =
        vaultPin.verifyVaultPin(candidate)

    /** v1.27.13 — millisecondes de temporisation restantes, pour le compte a rebours. */
    suspend fun vaultLockoutRemainingMs(): Long = vaultPin.vaultLockoutRemainingMs()

    /**
     * v1.27.13 — le second facteur vient d'etre prouve pour CET export.
     *
     * La preuve n'ouvre pas le coffre pour la suite : [exportEncrypted] ouvre la session juste
     * avant d'ecrire et la referme aussitot apres, y compris en cas d'echec. Un export ne doit
     * pas laisser derriere lui un coffre consultable — l'utilisateur a prouve son facteur pour
     * sauvegarder, pas pour ouvrir.
     */
    private var vaultProofGranted = false

    fun grantVaultProof() { vaultProofGranted = true }

    /**
     * Triggers an encrypted `.smsbk` export. The [passphrase] CharArray is consumed (wiped) by
     * [BackupService.writeSmsbk]. The UI is responsible for asking the user the passphrase and
     * passing a fresh CharArray every time.
     */
    fun exportEncrypted(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            // v1.27.13 — la session n'est ouverte que le temps de l'ecriture. Le `finally`
            // la referme meme si `writeSmsbk` echoue ou leve : sans lui, un export rate
            // laisserait le coffre ouvert, ce que l'utilisateur n'a pas demande.
            val borrowed = vaultProofGranted
            if (borrowed) vaultSession.markUnlocked()
            val r = try {
                backupService.writeSmsbk(uri, passphrase)
            } finally {
                if (borrowed) {
                    vaultSession.lock()
                    vaultProofGranted = false
                }
            }
            _events.tryEmit(
                when {
                    r is Outcome.Success -> Event.ExportDone(uri)
                    // v1.27.2 (audit externe 2026-08-04 #4) — AppError.Locked = « le coffre
                    // exige son second facteur avant l'export »… SAUF en session leurre, où
                    // writeSmsbk refuse aussi avec Locked : là, message GÉNÉRIQUE. Un libellé
                    // « déverrouillez le coffre » trahirait l'existence d'un contenu caché au
                    // détenteur du code panique — la ligne qui trahit le leurre a déjà été un
                    // vrai défaut de ce dépôt.
                    r is Outcome.Failure && r.error is AppError.Locked &&
                        appLock.state.value !is
                        com.filestech.sms.security.AppLockManager.LockState.PanicDecoy ->
                        Event.ExportVaultLocked
                    else -> Event.ExportFailed
                },
            )
        }
    }

    /**
     * v1.15.2 — Triggers a restore from an encrypted `.smsbk` URI. The [passphrase] CharArray is
     * consumed (wiped) by [BackupService.readSmsbk]. Caller is responsible for handing a fresh
     * CharArray each call. `_isRestoring` est posé à true pendant le travail pour griser le
     * bouton UI et empêcher un double-tap qui produirait 2 imports concurrents.
     */
    fun restoreFromUri(uri: android.net.Uri, passphrase: CharArray) {
        // v1.17.0 audit KOTLIN-L1 — Anti-réentrance ATOMIQUE via compareAndSet. Avant : `if
        // (_isRestoring.value) ... else _isRestoring.value = true` n'était pas atomique côté
        // contrat — un futur refacto qui appellerait restoreFromUri hors Main thread aurait pu
        // produire une race (deux callers passent le check avant que l'un ne mette à true).
        // CAS(false → true) garantit qu'un seul caller continue, même hors Main.
        if (!_isRestoring.compareAndSet(false, true)) {
            // Restore déjà en cours — refus + wipe passphrase reçue (pas de fuite mémoire).
            passphrase.fill(' ')
            return
        }
        viewModelScope.launch {
            try {
                val outcome = restoreBackup(uri.toString(), passphrase)
                when (outcome) {
                    is Outcome.Success -> _events.tryEmit(Event.RestoreDone(outcome.value))
                    is Outcome.Failure -> {
                        // Map l'AppError vers un kind UI-friendly. Le BackupService catégorise
                        // déjà via le message (cf. readSmsbk errorMapper).
                        val error = outcome.error
                        val kind = when {
                            error is AppError.Validation -> {
                                val msg = error.message.orEmpty()
                                when {
                                    msg.contains("decrypt failed") -> RestoreFailureKind.WRONG_PASSPHRASE_OR_CORRUPTED
                                    else -> RestoreFailureKind.INVALID_FORMAT
                                }
                            }
                            else -> RestoreFailureKind.STORAGE
                        }
                        _events.tryEmit(Event.RestoreFailed(kind))
                    }
                }
            } finally {
                _isRestoring.value = false
            }
        }
    }

    /**
     * Triggers an encrypted `.notebk` notes export. The [passphrase] CharArray is
     * consumed (wiped) by [NotesBackupService.writeNotesBackup]. No vault gate: notes
     * are not vault content, so there is no second factor to prove first.
     */
    fun exportNotes(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            val r = notesBackupService.writeNotesBackup(uri, passphrase)
            _events.tryEmit(
                when (r) {
                    is Outcome.Success -> Event.NotesExportDone(uri)
                    else -> Event.NotesExportFailed
                },
            )
        }
    }

    /**
     * Triggers a restore from an encrypted `.notebk` URI. Same contract as
     * [restoreFromUri]: fresh CharArray per call, wiped by the service, CAS guard
     * against a double-tap producing two concurrent imports.
     */
    fun restoreNotesFromUri(uri: android.net.Uri, passphrase: CharArray) {
        if (!_isRestoringNotes.compareAndSet(false, true)) {
            // Restore already in progress — refuse + wipe the passphrase received
            // (no memory leak).
            passphrase.fill(' ')
            return
        }
        viewModelScope.launch {
            try {
                val outcome = restoreNotesBackup(uri.toString(), passphrase)
                when (outcome) {
                    is Outcome.Success -> _events.tryEmit(Event.NotesRestoreDone(outcome.value))
                    is Outcome.Failure -> {
                        val error = outcome.error
                        val kind = when {
                            error is AppError.Validation -> {
                                val msg = error.message.orEmpty()
                                when {
                                    msg.contains("decrypt failed") -> RestoreFailureKind.WRONG_PASSPHRASE_OR_CORRUPTED
                                    else -> RestoreFailureKind.INVALID_FORMAT
                                }
                            }
                            else -> RestoreFailureKind.STORAGE
                        }
                        _events.tryEmit(Event.NotesRestoreFailed(kind))
                    }
                }
            } finally {
                _isRestoringNotes.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val snackbarHost = remember { SnackbarHostState() }

    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var askingPassphrase by remember { mutableStateOf(false) }
    // v1.27.13 — la sauvegarde ne peut pas aboutir tant que le coffre est ferme ; on le dit
    // avant d'ouvrir le selecteur de fichier, pas apres la saisie de la passphrase.
    var askingVaultPin by remember { mutableStateOf(false) }
    var vaultBiometricOnlyDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            askingPassphrase = true
        }
    }

    // v1.15.2 — État du flow restore. `restoreUri` = URI .smsbk pické via SAF ; non-null
    // déclenche le dialog de passphrase. `restoreFlowActive` empêche le double-pick.
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val isRestoring by viewModel.isRestoring.collectAsState()
    val restoreLauncher = rememberLauncherForActivityResult(
        // OpenDocument plutôt que GetContent : on garde une URI persistable / re-readable.
        // Mime `*/*` car certains pickers FS ne reconnaissent pas le mime custom ;
        // le service revalide via la magic bytes du fichier, donc safe.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) restoreUri = uri
    }

    // Notes backup (.notebk) — same shapes as the `.smsbk` flow above, separate
    // launchers and state so the two flows never share a pending URI.
    var pendingNotesUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var askingNotesPassphrase by remember { mutableStateOf(false) }
    val notesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingNotesUri = uri
            askingNotesPassphrase = true
        }
    }
    var restoreNotesUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val isRestoringNotes by viewModel.isRestoringNotes.collectAsState()
    val notesRestoreLauncher = rememberLauncherForActivityResult(
        // Mime `*/*`: same reason as the `.smsbk` picker — the service validates via
        // the `NTBK` magic bytes, not the MIME type.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) restoreNotesUri = uri
    }

    // v1.27.2 (audit externe 2026-08-04 #4) — résolue à la composition via stringResource :
    // un `context.getString` DANS le collect ajouterait une instance de
    // LocalContextGetResourceValueCall au-delà de la baseline lint (on n'enterre rien dedans).
    val vaultLockedMsg = stringResource(R.string.backup_export_vault_locked)
    // v1.28.3 — gabarit resolu au niveau composable, formate a l'emission : un `Context` capture
    // dans une lambda non composable ne suit pas les changements de configuration.
    val sansPiecesJointesFmt = stringResource(R.string.backup_restore_without_attachments)
    val coffreSansFacteurMsg = stringResource(R.string.backup_restore_vault_without_factor)
    // Round 34 — CI lint (LocalContextGetResourceValueCall) : les messages
    // des notes ET les anciens messages de sauvegarde sont resolus a la
    // composition, comme vaultLockedMsg ci-dessus. Les trois messages
    // d'echec de restauration servent aux deux restaurations (`.smsbk`
    // et notes), d'ou un seul jeu de valeurs.
    val exportSuccessMsg = stringResource(R.string.backup_export_success)
    val exportFailedMsg = stringResource(R.string.backup_export_failed)
    val restoreSuccessFmt = stringResource(R.string.backup_restore_success)
    val restoreWrongPassMsg = stringResource(R.string.backup_restore_failed_wrong_pass)
    val restoreInvalidFormatMsg = stringResource(R.string.backup_restore_failed_format)
    val restoreStorageMsg = stringResource(R.string.backup_restore_failed_storage)
    val notesExportSuccessMsg = stringResource(R.string.backup_notes_export_success)
    val notesExportFailedMsg = stringResource(R.string.backup_notes_export_failed)
    val notesRestoreSuccessFmt = stringResource(R.string.backup_notes_restore_success)
    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is BackupViewModel.Event.ExportDone -> snackbarHost.showSnackbar(exportSuccessMsg)
                BackupViewModel.Event.ExportFailed -> snackbarHost.showError(exportFailedMsg)
                // v1.27.2 (audit externe 2026-08-04 #4) — le coffre n'est pas vide et sa
                // session est verrouillée : inviter à l'ouvrir plutôt qu'un échec opaque.
                BackupViewModel.Event.ExportVaultLocked -> snackbarHost.showError(vaultLockedMsg)
                // v1.15.2 — Événements restore : snackbar avec récap chiffré succès, OU
                // erreur typée mappée vers la bonne string localisée.
                // v1.28.3 — le bilan dit desormais combien de messages sont revenus SANS leurs
                // pieces jointes. Le format `.smsbk` n'en transporte aucune, et rien ne le disait :
                // un MMS sans legende restaure n'apparait meme dans aucun fil. Une perte enoncee
                // vaut infiniment mieux qu'une perte muette.
                is BackupViewModel.Event.RestoreDone -> snackbarHost.showSnackbar(
                    buildString {
                        append(
                            restoreSuccessFmt.format(
                                ev.result.totalConversationsInBackup,
                                ev.result.conversationsCreated,
                                ev.result.messagesImported,
                                ev.result.messagesSkipped,
                            ),
                        )
                        if (ev.result.messagesWithoutAttachments > 0) {
                            append(' ')
                            append(sansPiecesJointesFmt.format(ev.result.messagesWithoutAttachments))
                        }
                        // v1.28.5 — un coffre restaure sans second facteur ici : dit, pas tu.
                        if (ev.result.vaultRestoredWithoutSecondFactor) {
                            append(' ')
                            append(coffreSansFacteurMsg)
                        }
                    },
                )
                is BackupViewModel.Event.RestoreFailed -> {
                    val msg = when (ev.kind) {
                        BackupViewModel.RestoreFailureKind.WRONG_PASSPHRASE_OR_CORRUPTED ->
                            restoreWrongPassMsg
                        BackupViewModel.RestoreFailureKind.INVALID_FORMAT ->
                            restoreInvalidFormatMsg
                        BackupViewModel.RestoreFailureKind.STORAGE ->
                            restoreStorageMsg
                    }
                    snackbarHost.showError(msg)
                }
                is BackupViewModel.Event.NotesExportDone -> snackbarHost.showSnackbar(notesExportSuccessMsg)
                BackupViewModel.Event.NotesExportFailed -> snackbarHost.showError(notesExportFailedMsg)
                is BackupViewModel.Event.NotesRestoreDone -> snackbarHost.showSnackbar(
                    notesRestoreSuccessFmt.format(
                        ev.result.totalNotesInBackup,
                        ev.result.notesCreated,
                        ev.result.messagesImported,
                        ev.result.messagesSkipped,
                    ),
                )
                is BackupViewModel.Event.NotesRestoreFailed -> {
                    // Same failure strings as the `.smsbk` restore: the meanings are
                    // identical ("wrong passphrase or corrupted file", "not a valid
                    // Relay backup file", "could not read the file").
                    val msg = when (ev.kind) {
                        BackupViewModel.RestoreFailureKind.WRONG_PASSPHRASE_OR_CORRUPTED ->
                            restoreWrongPassMsg
                        BackupViewModel.RestoreFailureKind.INVALID_FORMAT ->
                            restoreInvalidFormatMsg
                        BackupViewModel.RestoreFailureKind.STORAGE ->
                            restoreStorageMsg
                    }
                    snackbarHost.showError(msg)
                }
            }
        }
    }

    // Grouped-list page base: #F5F5F5 in light, #1B1B1B in dark — the section content sits
    // on the canvas (see RelaySection). Picked from the surface luminance so it follows the
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
                title = { Text(stringResource(R.string.settings_section_backup)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            RelaySection(title = stringResource(R.string.settings_backup_now)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.backup_explainer),
                        style = RelayText.Preview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    RelayPrimaryButton(
                        text = stringResource(R.string.settings_backup_now),
                        onClick = {
                            // v1.27.13 — on demande AVANT de faire travailler l'utilisateur. Le selecteur
                            // n'est meme pas ouvert si la sauvegarde ne peut pas aboutir : il aurait cree
                            // un fichier vide qu'on aurait ensuite refuse de remplir.
                            scope.launch {
                                when (viewModel.exportSecondFactor()) {
                                    com.filestech.sms.security.VaultSecondFactor.NONE ->
                                        launcher.launch("smsrelay_${System.currentTimeMillis()}.smsbk")
                                    // Le facteur se prouve ICI, pas dans un ecran qu'il faudrait quitter
                                    // pour revenir — le quitter refermerait justement la session.
                                    com.filestech.sms.security.VaultSecondFactor.PIN ->
                                        askingVaultPin = true
                                    // Seul cas non couvert : coffre garde par la biometrie SEULE. La porte
                                    // biometrique est adossee au Keystore et vit dans l'ecran du coffre ; la
                                    // recopier ici creerait deux implementations d'une meme garde, et c'est
                                    // ce motif qui a produit les vrais defauts de ce depot. On le dit
                                    // franchement, avec la seule action qui debloque.
                                    com.filestech.sms.security.VaultSecondFactor.BIOMETRIC ->
                                        vaultBiometricOnlyDialog = true
                                }
                            }
                        },
                    )
                }
            }
            RelaySection(title = stringResource(R.string.settings_restore)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.backup_restore_explainer),
                        style = RelayText.Preview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    RelayPrimaryButton(
                        // v1.15.2 — Disable le bouton pendant l'opération crypto + import. Évite un
                        // 2ᵉ pick file qui produirait 2 restores concurrents (le VM a déjà un guard
                        // anti-réentrance, mais on défend en profondeur côté UI).
                        enabled = !isRestoring,
                        text = if (isRestoring) {
                            stringResource(R.string.backup_restore_in_progress)
                        } else {
                            stringResource(R.string.backup_restore_pick_file)
                        },
                        onClick = {
                            // mime `*/*` car certains pickers (Samsung My Files) ne reconnaissent pas
                            // le mime custom — la validation se fait via magic bytes côté
                            // BackupService.readSmsbk.
                            restoreLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    if (isRestoring) {
                        Spacer(Modifier.size(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = brandBlue(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
            NotesBackupSections(
                isRestoringNotes = isRestoringNotes,
                onExportNotes = {
                    // No vault gate for notes: the vault second factor does
                    // not apply, so the picker opens directly.
                    notesLauncher.launch("relay_notes_${System.currentTimeMillis()}.notebk")
                },
                onRestoreNotes = { notesRestoreLauncher.launch(arrayOf("*/*")) },
            )
        }
    }

    // v1.15.2 — Dialog passphrase de restore (single field, pas de confirm — l'user tape une
    // passphrase qu'il connaît, pas une qu'il pose). Sur confirm : convert en CharArray fresh,
    // wipe la String UI, déléger au VM (qui wipera le CharArray côté service).
    restoreUri?.let { uri ->
        RestorePassphraseDialog(
            onConfirm = { passphrase ->
                val safeUri = uri
                restoreUri = null
                viewModel.restoreFromUri(safeUri, passphrase)
            },
            onDismiss = { restoreUri = null },
        )
    }

    restoreNotesUri?.let { uri ->
        RestorePassphraseDialog(
            explainerRes = R.string.backup_notes_restore_explainer,
            onConfirm = { passphrase ->
                val safeUri = uri
                restoreNotesUri = null
                viewModel.restoreNotesFromUri(safeUri, passphrase)
            },
            onDismiss = { restoreNotesUri = null },
        )
    }

    // v1.27.13 — le second facteur se prouve ici meme, avec le composant et la temporisation
    // de la porte du coffre. Le prouver dans l'ecran du coffre ne servait a rien : en sortir
    // referme la session, donc la condition redevenait fausse avant qu'on puisse l'utiliser.
    if (askingVaultPin) {
        com.filestech.sms.ui.components.PinEntryDialog(
            title = stringResource(R.string.backup_vault_pin_title),
            description = stringResource(R.string.backup_vault_pin_body),
            confirmLabel = stringResource(R.string.action_confirm),
            onVerify = { candidate -> viewModel.verifyVaultPin(candidate) },
            probeLockout = { viewModel.vaultLockoutRemainingMs() },
            onVerified = {
                askingVaultPin = false
                viewModel.grantVaultProof()
                launcher.launch("smsrelay_${System.currentTimeMillis()}.smsbk")
            },
            onCancel = { askingVaultPin = false },
        )
    }

    if (vaultBiometricOnlyDialog) {
        RelayDialog(
            onDismissRequest = { vaultBiometricOnlyDialog = false },
            title = stringResource(R.string.backup_vault_biometric_title),
            content = {
                Text(
                    text = stringResource(R.string.backup_vault_biometric_body),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = { vaultBiometricOnlyDialog = false },
                    confirm = true,
                )
            },
        )
    }

    if (askingPassphrase) {
        PassphraseDialog(
            onConfirm = { passphrase ->
                val uri = pendingUri
                askingPassphrase = false
                pendingUri = null
                if (uri != null) viewModel.exportEncrypted(uri, passphrase)
            },
            onDismiss = {
                askingPassphrase = false
                pendingUri = null
            },
        )
    }

    if (askingNotesPassphrase) {
        PassphraseDialog(
            onConfirm = { passphrase ->
                val uri = pendingNotesUri
                askingNotesPassphrase = false
                pendingNotesUri = null
                if (uri != null) viewModel.exportNotes(uri, passphrase)
            },
            onDismiss = {
                askingNotesPassphrase = false
                pendingNotesUri = null
            },
        )
    }
}

/**
 * Notes backup (.notebk) — the export and restore sections, extracted from
 * [BackupScreen] so the screen stays under the Detekt long-method limit. The
 * launchers and pending-URI state stay in the screen: the passphrase dialogs
 * below the Scaffold own them.
 */
@Composable
private fun NotesBackupSections(
    isRestoringNotes: Boolean,
    onExportNotes: () -> Unit,
    onRestoreNotes: () -> Unit,
) {
    RelaySection(title = stringResource(R.string.backup_notes_export_title)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.backup_notes_export_explainer),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            RelayPrimaryButton(
                text = stringResource(R.string.backup_notes_export_button),
                onClick = onExportNotes,
            )
        }
    }
    RelaySection(title = stringResource(R.string.backup_notes_restore_title)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.backup_notes_restore_explainer),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            RelayPrimaryButton(
                // Same depth-in-defense as the `.smsbk` restore button: disable
                // during the crypto + import work (the VM has its own CAS guard).
                enabled = !isRestoringNotes,
                text = if (isRestoringNotes) {
                    stringResource(R.string.backup_notes_restore_in_progress)
                } else {
                    stringResource(R.string.backup_notes_restore_pick_file)
                },
                onClick = onRestoreNotes,
            )
            if (isRestoringNotes) {
                Spacer(Modifier.size(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = brandBlue(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PassphraseDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    // v1.15.2 — Toggle visibilité passphrase (icône œil). Permet de vérifier la saisie pour
    // éviter une erreur de frappe qui rendrait le backup irrestaurable. Désactivé par défaut.
    var pwVisible by remember { mutableStateOf(false) }
    val matches = pw.length >= MIN_PASSPHRASE_LEN && pw == pw2
    val tooShort = pw.isNotEmpty() && pw.length < MIN_PASSPHRASE_LEN
    val mismatch = pw2.isNotEmpty() && pw != pw2

    // v1.28.6 — LE CLAVIER CACHAIT LE BOUTON « Enregistrer », signale par Patrice : il fallait
    // refermer le clavier pour valider. Deux corrections, et non une seule :
    //
    //  1. la touche de validation du clavier enregistre — c'est le geste naturel une fois la
    //     confirmation tapee, et c'est deja le motif de [PinEntryDialog] ;
    //  2. le contenu du dialogue DEFILE, pour que les boutons restent atteignables meme quand la
    //     fenetre reduite par le clavier ne peut plus tout afficher. Sans cela, la premiere
    //     correction masquerait le defaut sans le fermer : quelqu'un qui ne trouve pas la touche
    //     de validation resterait coince.
    //
    // L'action de confirmation vit dans UNE lambda, partagee par le bouton et le clavier. Deux
    // copies auraient divergé — c'est le motif d'asymetrie le plus frequent de ce depot — et
    // celle-ci efface des secrets.
    val enregistrer = {
        val chars = pw.toCharArray()
        pw = ""
        pw2 = ""
        onConfirm(chars)
    }

    RelayDialog(
        // v1.28.6 — LE DEFILEMENT SEUL NE SUFFISAIT PAS, et Patrice l'a constate : un
        // `verticalScroll` ne sert a rien tant que la FENETRE du dialogue garde toute la hauteur
        // de l'ecran. Une `Dialog` Compose a sa propre fenetre ; avec `decorFitsSystemWindows`
        // a `true` — le defaut — elle ignore l'encart du clavier, et les boutons se retrouvent
        // simplement dessous, hors de l'ecran. Il n'y avait donc rien a faire defiler.
        //
        // En passant a `false` (via les `properties` du dialogue partage), l'application prend la
        // main sur les encarts : `imePadding` remonte le dialogue au-dessus du clavier,
        // `safeDrawingPadding` le garde hors de la barre d'etat et de la barre de navigation, et
        // le contenu defile pour le cas ou meme cette hauteur reduite ne suffirait pas. Les trois
        // sont necessaires : le premier resout le defaut, le deuxieme empeche de le remplacer par
        // un dialogue sous la barre d'etat, le troisieme couvre les tres petits ecrans et les
        // polices agrandies.
        modifier = Modifier
            .safeDrawingPadding()
            .imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_passphrase_title),
        content = {
            // v1.27.1 (N3) — DANS le contenu du dialogue : phrase secrète d'export.
            ProtectSecretInput()
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_passphrase_explain),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RelayTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    placeholder = stringResource(R.string.backup_passphrase),
                    visualTransformation = if (pwVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = {
                        PassphraseVisibilityToggle(
                            visible = pwVisible,
                            onToggle = { pwVisible = !pwVisible },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // v1.15.2 — Indicateur visuel du minimum requis (sous le champ).
                // v1.28.6 — il ne s'affiche PLUS EN PERMANENCE. Le texte d'explication juste
                // au-dessus dit déjà « Au minimum 8 caractères » : la même phrase sous le champ
                // était un doublon, et elle coûtait une ligne — celle qui, clavier ouvert,
                // faisait sortir le second champ et les boutons de l'écran. Elle ne paraît donc
                // plus que quand elle APPREND quelque chose : la saisie est trop courte.
                if (tooShort) {
                    Text(
                        text = stringResource(
                            R.string.backup_passphrase_min_length,
                            MIN_PASSPHRASE_LEN,
                        ),
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                RelayTextField(
                    value = pw2,
                    onValueChange = { pw2 = it },
                    singleLine = true,
                    placeholder = stringResource(R.string.backup_passphrase_repeat),
                    visualTransformation = if (pwVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    // La touche de validation n'enregistre QUE si le bouton l'aurait fait : meme
                    // predicat `matches`, pas un second jugement ecrit a cote.
                    keyboardActions = KeyboardActions(onDone = { if (matches) enregistrer() }),
                    // v1.15.2 — Œil aussi sur le champ confirmation : le state `pwVisible` est
                    // partagé donc cliquer ici ou sur le 1er champ produit le même effet
                    // (les deux champs se masquent/affichent ensemble). Permet à l'user de
                    // toggler depuis le champ qu'il est en train de remplir.
                    trailingIcon = {
                        PassphraseVisibilityToggle(
                            visible = pwVisible,
                            onToggle = { pwVisible = !pwVisible },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // v1.28.6 — L'ÉCART EST DIT PAR LE CHAMP CONCERNÉ, et non par un `Text` posé
                // dessous en `labelSmall` : cette taille-là était illisible (constaté par
                // Patrice), et le message flottait sans être rattaché à un champ. En ligne
                // d'erreur sous le champ, il a sa taille, sa couleur d'erreur et sa place.
                if (mismatch) {
                    Text(
                        text = stringResource(R.string.backup_passphrase_mismatch),
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = enregistrer,
                enabled = matches,
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { pw = ""; pw2 = ""; onDismiss() },
            )
        },
    )
}

private const val MIN_PASSPHRASE_LEN = 8

/**
 * v1.15.2 — Dialog passphrase pour restore. Asymétrie avec [PassphraseDialog] (export) :
 *  - 1 seul champ (pas de "repeat") : l'user TAPE une passphrase qu'il connaît, pas qu'il pose.
 *  - Aucune validation côté UI (longueur etc.) — c'est la passphrase originale, on l'accepte
 *    telle quelle. La validation a lieu côté crypto : si elle est fausse → erreur typée
 *    `WRONG_PASSPHRASE_OR_CORRUPTED` → snackbar localisée.
 *  - CharArray construite à la confirmation, wipée par [BackupService.readSmsbk] côté service.
 *  - String UI wipée immédiatement après conversion en CharArray pour réduire l'exposition.
 *  - [explainerRes] permet à la restauration des notes de réutiliser ce dialogue avec son
 *    propre texte (celui par défaut nomme le fichier `.smsbk`).
 */
@Composable
private fun RestorePassphraseDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    explainerRes: Int = R.string.backup_restore_explainer,
) {
    var pw by remember { mutableStateOf("") }
    // v1.15.2 — Toggle visibilité passphrase. Critique en restore : si l'user se trompe, le
    // déchiffrement échoue avec une erreur générique → il ne saura pas si c'est une faute de
    // frappe ou la mauvaise passphrase. L'œil lui permet de vérifier visuellement.
    var pwVisible by remember { mutableStateOf(false) }
    // v1.28.6 — le jumeau du dialogue d'export, corrigé en même temps que lui : le clavier y
    // cachait le bouton de la même façon. Corriger un seul des deux aurait reproduit l'asymétrie
    // que ce dépôt rencontre le plus souvent — et celui-ci se présente au pire moment, quand on
    // restaure après avoir perdu ses données.
    val restaurer = {
        val chars = pw.toCharArray()
        pw = ""
        onConfirm(chars)
    }
    RelayDialog(
        // v1.28.6 — meme correction que [PassphraseDialog], son jumeau : cf. le commentaire y
        // figurant pour le raisonnement complet.
        modifier = Modifier
            .safeDrawingPadding()
            .imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_restore_passphrase_title),
        content = {
            // v1.27.1 (N3) — DANS le contenu du dialogue : phrase secrète de restauration.
            ProtectSecretInput()
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(explainerRes),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RelayTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    placeholder = stringResource(R.string.backup_restore_passphrase_field),
                    visualTransformation = if (pwVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) restaurer() }),
                    trailingIcon = {
                        PassphraseVisibilityToggle(
                            visible = pwVisible,
                            onToggle = { pwVisible = !pwVisible },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.backup_restore_action),
                onClick = restaurer,
                enabled = pw.isNotEmpty(),
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { pw = ""; onDismiss() },
            )
        },
    )
}

/** Œil de visibilité d'une passphrase : même geste et même état sur les deux champs. */
@Composable
private fun PassphraseVisibilityToggle(
    visible: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.action_hide_password else R.string.action_show_password,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
