package com.filestech.sms.ui.screens.thread

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.core.result.AppError
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.e2e.GroupEncryptionState
import com.filestech.sms.e2e.E2eNoticesForItem
import com.filestech.sms.e2e.E2eThreadNotices
import com.filestech.sms.sync.client.ClientCallState
import com.filestech.sms.ui.components.AttachmentKind
import com.filestech.sms.ui.components.AttachmentPickerSheet
import com.filestech.sms.ui.components.AudioMessageBubble
import com.filestech.sms.ui.components.BubbleMenuToggles
import com.filestech.sms.ui.components.BurstPosition
import com.filestech.sms.ui.components.ComposerReplyChip
import com.filestech.sms.ui.components.ContactIntents
import com.filestech.sms.ui.components.E2eDecryptFailedRow
import com.filestech.sms.ui.components.E2eNoticeRow
import com.filestech.sms.ui.components.E2eVerificationSheet
import com.filestech.sms.ui.components.EmojiPickerButton
import com.filestech.sms.ui.components.EmojiPickerPanel
import com.filestech.sms.ui.components.EmojiPickerTab
import com.filestech.sms.ui.components.EmojiPickerTabStore
import com.filestech.sms.ui.components.MessageBubble
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.components.ReplyQuotePreview
import com.filestech.sms.ui.components.ScheduleMenuActions
import com.filestech.sms.ui.components.SchedulePresetMenu
import com.filestech.sms.ui.components.SearchHighlight
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.SmsTechSnackbarVisuals
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.components.stickerSendUri
import com.filestech.sms.ui.components.toPlaybackUri
import com.filestech.sms.ui.components.toggleReaction
import com.filestech.sms.ui.screens.clientcall.ClientCallStatusRow
import com.filestech.sms.ui.screens.clientcall.ClientCallViewModel
import com.filestech.sms.ui.security.copyToClipboardSensitive
import com.filestech.sms.ui.theme.BrandDanger
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.MessageTextSize
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.brandBluePressed
import com.filestech.sms.ui.theme.onBrandBlue
import com.filestech.sms.ui.theme.resolveComposerButtonColors
import com.filestech.sms.ui.util.daySeparatorLabel
import com.filestech.sms.ui.util.rememberChatFormatters
import com.filestech.sms.ui.util.rememberSimLabeler
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import kotlin.math.max

// v1.2.3 audit U21: BrandDanger is defined once in `com.filestech.sms.ui.theme.Color`.
// References below resolve via the file-level import.

// v1.28.12 — `formatFileSize` retirée : plus aucun appelant, et son KDoc affirmait encore
// « Used by the attachment dialog ». Elle écrivait ses unités en dur en ANGLAIS, tandis que
// son jumeau de `MediaAttachmentBubble` les écrivait en dur en FRANÇAIS — deux langues et
// deux conventions décimales pour la même taille, sur deux écrans voisins. Les deux passent
// désormais par le formateur de la plateforme, qui suit la langue de l'application.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    conversationId: Long,
    onBack: () -> Unit,
    /**
     * v1.3.11 (F5) — navigation hook fired when the user picks an existing target
     * conversation in the forward sheet. The caller (`AppRoot`) typically does
     * `popBackStack(); navigate(Thread(id))` so the user returns to the conversation list
     * after the forwarded message is sent rather than stacking thread on top of thread.
     */
    onForwardToConversation: (Long) -> Unit = {},
    /**
     * v1.3.11 (F5) — navigation hook fired when the user picks "Nouveau destinataire"
     * in the forward sheet. The caller routes to [com.filestech.sms.ui.screens.compose
     * .ComposeScreen] which then funnels back into a thread once a contact is picked.
     */
    onForwardToNewContact: () -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    // Client mode — tablet calls: the dial + call-state ViewModel,
    // resolved in the body (not a parameter — the signature is
    // Detekt-baselined). A separate owner from the AppRoot popup's
    // instance; both read the same engine flow, so they never
    // disagree.
    val clientCallViewModel: ClientCallViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Client mode — tablet calls: the live call, for the outgoing
    // dial state below the header (null in hub mode).
    val clientCall by clientCallViewModel.call.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    // Round 12 — recent reaction emojis for the quick-reaction bar on a
    // bubble tap (most recent first; empty until the first reaction).
    val recentReactions by viewModel.recentReactions.collectAsStateWithLifecycle()
    // Playback progress ticks at 5 Hz while a voice clip plays, and every tick recomposes
    // this screen. Passing `{ playbackState }` inline to each message item would create a
    // fresh lambda on every one of those recompositions, so no item could ever skip — the
    // whole visible list would re-compose 5 times a second (and on every draft keystroke,
    // which also recomposes the screen). This provider is one stable instance that always
    // reads the latest tick through `rememberUpdatedState`: items can skip, and the one
    // playing audio bubble still re-reads it through its own `derivedStateOf`.
    val latestPlaybackState = rememberUpdatedState(playbackState)
    val playbackProvider = remember { { latestPlaybackState.value } }

    // v1.26.1 (audit H1, suite de revue) — le Coffre s'est refermé alors qu'on est dans un de
    // ses fils : on ressort, au lieu de laisser un fil VIDE dont le bouton « Envoyer » ne fait
    // plus rien en silence (tous les chemins d'envoi retournent sur `conversation == null`).
    // Le cas est fréquent : `lockVaultOnLeave` vaut `true` par défaut et le Coffre se referme
    // dès que l'application passe en arrière-plan — bascule d'app, écran éteint, sélecteur de
    // photos, appel entrant.
    //
    // ⚠️ On attend que la destination soit RESUMED. La fermeture du Coffre survient justement
    // pendant `onStop`, donc l'effet se déclenche alors que l'écran n'est PAS au premier plan :
    // dépiler à ce moment-là viserait l'écran voisin, exactement le dépilement parasite corrigé
    // en v1.25.4. `withResumed` diffère la sortie au retour de l'utilisateur.
    val vaultHidden by viewModel.vaultHidden.collectAsStateWithLifecycle()
    val threadNavOwner = LocalLifecycleOwner.current
    LaunchedEffect(vaultHidden) {
        if (vaultHidden) {
            threadNavOwner.lifecycle.withResumed { onBack() }
        }
    }
    // v1.29.1 (issue #60) — « active » pour les notifications seulement quand l'écran est visible :
    // écran éteint, autre application ou autre écran de l'app passent par ON_STOP.
    LifecycleStartEffect(viewModel) {
        viewModel.onVisibilityChanged(true)
        onStopOrDispose { viewModel.onVisibilityChanged(false) }
    }
    // Feedback round 7 — re-resolve the header contact photo when the screen
    // comes back to foreground: covers a READ_CONTACTS grant made outside
    // the thread (same pattern as the main list's contactPhotoTick).
    DisposableEffect(threadNavOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContactPhoto()
            }
        }
        threadNavOwner.lifecycle.addObserver(observer)
        onDispose { threadNavOwner.lifecycle.removeObserver(observer) }
    }
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val formatters = rememberChatFormatters()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // v1.3.11 (F1) — retract the soft keyboard + drop IME focus after every send so the
    // sender can read the just-sent message at the bottom of the thread without manually
    // collapsing the keyboard (Apple Messages / Google Messages convention).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // v1.3.11 (F3) — single Clipboard channel for all bubble copy actions.
    // v1.27.0 (N4) — passe par [copyToClipboardSensitive] et non plus par `LocalClipboardManager` :
    // ce dernier n'expose pas `ClipDescription.EXTRA_IS_SENSITIVE`, donc Android 13+ affichait en
    // surimpression une vignette du texte copié — y compris pour un message issu du COFFRE, hors
    // de tout ce que le coffre protège. Cf. `THREAT-MODEL.md` I7 / N4.
    val copyMessageBody: (Message) -> Unit = { m ->
        val body = m.body.trim()
        if (body.isNotEmpty()) {
            context.copyToClipboardSensitive(label = "message", text = body)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val label = context.getString(R.string.toast_copied)
            scope.launch { snackbarHost.showSnackbar(label) }
        }
    }

    // v1.7.1 — Translation feature delegated to the system. After ML Kit was
    // removed in v1.7.0 (FLOSS compliance for F-Droid — MR !38458), we don't
    // ship an in-app translator anymore : the user picks their preferred
    // translation app via ACTION_PROCESS_TEXT (Google Translate, DeepL,
    // Aves Translate, LibreTranslate…). The system shows its standard chooser,
    // so the user stays in control of which app gets the message body.
    //
    // Security : we pass `EXTRA_PROCESS_TEXT_READONLY = true` so the receiving
    // app cannot modify the original text. Empty / blank bodies are filtered
    // out — no point opening a chooser with nothing to translate.
    //
    // No `resolveActivity` check any more: it needed a `<queries>` for
    // PROCESS_TEXT, and that declaration also put every app declaring a text
    // action into the selection toolbar of every field (see the manifest). The
    // chooser runs in the system, sees every app, and says itself when none
    // can take the text.
    val translateMessageExternal: (Message) -> Unit = { m ->
        val body = m.body.trim()
        if (body.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, body)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    // v1.3.11 (F4) — phone number tapped inside any bubble body / caption opens the
    // [PhoneActionsDialog] hosted below. Single state owned by ThreadScreen so we don't
    // multiply dialog instances across hundreds of bubbles in a busy thread.
    var pickingPhoneNumber by remember { mutableStateOf<String?>(null) }
    val onPhoneClick: (String) -> Unit = { pickingPhoneNumber = it }
    // v1.3.11 (F5) — message being forwarded. Non-null = open the picker sheet. The
    // selected destination conversation (or new-recipient flow) consumes the staged
    // payload via [IncomingShareHolder] which the dest [ThreadViewModel] picks up at
    // open through `consumeIncomingShareIfAny`.
    var forwardingMessage by remember { mutableStateOf<Message?>(null) }

    var detailsPanelOpen by remember { mutableStateOf(false) }
    // E2E — the safety-number verification sheet, opened from the
    // details-panel encryption row or a key-change notice. One sheet
    // for the thread: the peer is this 1:1 conversation's single
    // address. Groups never open it (no single peer, no group
    // safety sheet in v1) and a linked device never opens it
    // either (no identity keys on a client — the panel hides the
    // row there).
    var e2eVerificationOpen by remember { mutableStateOf(false) }
    val e2ePeerAddress = state.conversation?.takeIf { !it.isGroup }?.firstAddress?.normalized
    // E2E — this thread encrypts: the composer placeholder, the send
    // icon, the composer button colours (round 48) and the timestamp
    // locks all key off this one flag. Round 49: a group in the
    // Encrypted state encrypts too (design §13).
    val isThreadEncrypted = state.e2ePeerState == E2ePeerState.ENCRYPTED ||
        state.e2eGroupState == GroupEncryptionState.Encrypted
    // v1.11.0 — Sujet 5 apparence : dialog ouvert depuis le panneau latéral de détails.
    var appearanceOpen by remember { mutableStateOf(false) }
    // v1.28.3 — nommer le groupe (panneau latéral de détails).
    var renameOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    // Round 17 — unpin confirmation: non-null = the "Remove the pin?"
    // dialog is open for this message (menu Unpin + banner X both land
    // here, so a stray tap can never drop the pin).
    var pendingUnpin by remember { mutableStateOf<Message?>(null) }
    var askBlock by remember { mutableStateOf(false) }
    // v1.3.0 — état du picker de réaction emoji : `pickingReactionFor`
    // non-null = le bottom-sheet de réaction est affiché pour ce message.
    // Round 24 — the old "Plus" custom-emoji dialog is gone with the old
    // quick-pick sheet (the full grid + search covers every emoji now).
    var pickingReactionFor by remember { mutableStateOf<Long?>(null) }
    // v1.3.1 — premier envoi de réaction par SMS : on stocke (messageId, emoji) tant que
    // l'utilisateur n'a pas validé le dialog de confirmation. `null` = pas de dialog ouvert.
    // Le messageId est porté pour permettre au ViewModel de re-vérifier l'état au moment du
    // confirm (anti-race F4 : l'user a pu changer/retirer la réaction entre temps).
    var askDelete by remember { mutableStateOf(false) }
    var attachmentSheetOpen by remember { mutableStateOf(false) }
    // Round 13 — emoji/GIF/sticker picker panel. It replaces the composer in the
    // bottom bar while open (the list shrinks above it, like a keyboard); the
    // keyboard is hidden on open and stays hidden until the panel closes.
    // The last-used tab survives restarts via [EmojiPickerTabStore] — UI state,
    // not a setting, so no Settings screen row.
    val pickerTabStore = remember(context) { EmojiPickerTabStore(context) }
    val lastPickerTab by pickerTabStore.tab.collectAsStateWithLifecycle(
        initialValue = EmojiPickerTab.EMOJI,
    )
    var emojiPickerOpen by remember { mutableStateOf(false) }
    var emojiPickerTab by remember { mutableStateOf(EmojiPickerTab.EMOJI) }
    // Round 14 — height (px) of everything stacked above the picker (reply chip,
    // attachments bar, composer), measured live: the panel takes its resting
    // heights from the space below it.
    var abovePanelHeightPx by remember { mutableStateOf(0) }
    // Round 17 — height (px) of the top header, measured live: the panel's
    // full height stops at its bottom edge instead of overlapping it.
    var topBarHeightPx by remember { mutableStateOf(0) }
    val onPickerTabSelected: (EmojiPickerTab) -> Unit = { tab ->
        emojiPickerTab = tab
        scope.launch { pickerTabStore.setTab(tab) }
    }
    val openEmojiPicker: () -> Unit = {
        emojiPickerTab = lastPickerTab
        keyboardController?.hide()
        focusManager.clearFocus()
        emojiPickerOpen = true
    }
    // Round 14 — the composer icon toggles the panel now that the composer stays
    // visible above it: a second tap closes it again (no shrink animation on
    // this path, same as the sticker/GIF close — only the back arrow animates).
    val toggleEmojiPicker: () -> Unit = {
        if (emojiPickerOpen) emojiPickerOpen = false else openEmojiPicker()
    }
    // Round 4 — the camera icon opens the camera DIRECTLY instead of the attachment
    // sheet. The capture file lives in `cacheDir/mms_outgoing/`, one of the
    // FileProvider cache-paths declared in `file_provider_paths.xml`, so the
    // system camera app can write to it via the content URI. On success the
    // photo reuses the picker's staging path: `onAttachmentPicked` copies it
    // into `media_outgoing/` and stages it in the composer. No CAMERA permission
    // is needed — the external camera app does the capture. If creating the file
    // or the URI fails, fall back to the attachment sheet so the user can still
    // attach a photo.
    var pendingCameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            pendingCameraPhotoUri?.let { viewModel.onAttachmentPicked(it, AttachmentKind.PHOTO) }
        }
        pendingCameraPhotoUri = null
    }
    val onCameraClick: () -> Unit = {
        runCatching {
            val dir = File(context.cacheDir, "mms_outgoing").apply { mkdirs() }
            val file = File(dir, "camera-${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onSuccess { uri ->
            pendingCameraPhotoUri = uri
            takePictureLauncher.launch(uri)
        }.onFailure {
            attachmentSheetOpen = true
        }
    }
    // Audit R3 (v1.14.8) — Retry d'un message FAILED avec errorCode WATCHDOG_TIMEOUT
    // peut produire un doublon (le radio a peut-être livré, on n'a juste pas reçu le
    // broadcast de confirmation). On confirme via dialog avant retry. SYNCHRONOUS reste
    // un retry direct sans dialog (le radio a rejeté l'envoi, pas de risque doublon).
    var pendingRetryWatchdog by remember { mutableStateOf<Message?>(null) }
    // v1.15.1 — Flow Programmer un SMS : 2 étapes (DatePicker puis TimePicker).
    // `scheduleStep` null = aucun picker ouvert ; "date" = DatePicker actif ;
    // "time" = TimePicker actif (date déjà choisie, stockée dans scheduledEpochMidnight).
    // Round 32 — "message" = le dialogue de saisie du texte, en premier quand
    // le brouillon est vide (depuis le panneau latéral) ; le texte saisi part
    // dans `scheduledBodyOverride` et ne touche jamais au brouillon.
    // Round 33 — "presets" = la liste radio des heures rapides (comme le
    // popup du bureau) ; un preset programme aussitôt, "Pick a date and
    // time" mène aux pickers "date" puis "time".
    var scheduleStep by remember { mutableStateOf<String?>(null) }
    var scheduledBodyOverride by remember { mutableStateOf<String?>(null) }
    var scheduledEpochMidnight by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

    // Feedback round 5 — search inside this conversation. The query searches
    // the FULL history (see [mapThreadSearchMatches]): the repository returns
    // every matching message id in the thread, and a jump to a match outside
    // the loaded window loads the window around it. Matches stay in place
    // with highlighting — nothing is hidden — and the up/down controls jump
    // between matching messages. `threadSearchIndex` is the position inside
    // [threadSearchMatches] (-1 = no matches); the count ("2 of 5") counts
    // matching messages in the whole thread.
    var threadSearchOpen by remember { mutableStateOf(false) }
    var threadSearchQuery by remember { mutableStateOf("") }
    var threadSearchIndex by remember { mutableStateOf(0) }
    // Feedback round 5 — set when a jump loaded a window away from the tail,
    // so closing the search knows to come back to the bottom.
    var searchJumpedOutOfWindow by remember { mutableStateOf(false) }
    var pendingJumpToBottom by remember { mutableStateOf(false) }
    val threadSearchFocusRequester = remember { FocusRequester() }
    val searchResults by viewModel.threadSearchResults.collectAsStateWithLifecycle()
    val threadSearchMatches = remember(state.messages, searchResults) {
        mapThreadSearchMatches(searchResults.messageIds, state.messages, searchResults.query)
    }
    val threadSearchCurrentIndex = if (threadSearchMatches.isEmpty()) {
        -1
    } else {
        threadSearchIndex.coerceIn(0, threadSearchMatches.lastIndex)
    }
    val threadSearchCurrentMatch = threadSearchMatches.getOrNull(threadSearchCurrentIndex)
    // Per-message highlight, keyed by message id so each bubble only
    // re-composes when its own highlight changes.
    val threadSearchHighlightById = remember(threadSearchMatches, threadSearchCurrentMatch) {
        threadSearchMatches.associate { match ->
            match.messageId to SearchHighlight(
                ranges = match.ranges,
                current = match.messageId == threadSearchCurrentMatch?.messageId,
            )
        }
    }
    val closeThreadSearch: () -> Unit = {
        threadSearchOpen = false
        threadSearchQuery = ""
        threadSearchIndex = 0
        viewModel.setThreadSearchQuery("")
        if (searchJumpedOutOfWindow) {
            searchJumpedOutOfWindow = false
            viewModel.clearWindowAnchor()
            pendingJumpToBottom = true
        }
        focusManager.clearFocus()
    }
    // Back closes the search pill before it leaves the screen — same pattern
    // as the conversations list search.
    androidx.activity.compose.BackHandler(enabled = threadSearchOpen) { closeThreadSearch() }
    LaunchedEffect(threadSearchOpen) {
        if (threadSearchOpen) {
            // Feedback round 5 — same retry as the conversations list search:
            // a request landing before the field's focus node is attached is
            // silently lost, leaving the field unfocused with no cursor and no
            // keyboard. Retry once after the pill slides in.
            val focused = runCatching { threadSearchFocusRequester.requestFocus() }
                .getOrDefault(false)
            if (!focused) {
                kotlinx.coroutines.delay(THREAD_SEARCH_FOCUS_RETRY_MS)
                runCatching { threadSearchFocusRequester.requestFocus() }
            }
        }
    }

    // Round 43 — the list is reversed (reverseLayout, see the
    // LazyColumn below): it starts at the bottom by layout, so there
    // is no first-paint jump to hide and no `initialScrollDone` /
    // `listSettled` to track. A new message lands at index 0 and
    // appears on its own when the user is at the bottom; when scrolled
    // up, the list re-anchors by key and the reading position holds.
    // E2E — the in-thread notices, placed among the loaded messages
    // by date (above the first message dated at or after each notice;
    // newer than everything → after the last message). The merge is
    // window-friendly: a notice older than the window renders above
    // its first loaded message, never vanishing, so paging is
    // undisturbed — and the LazyColumn still holds messages only
    // (the notices ride inside the message items, plus one trailing
    // item when a notice is newer than the last message).
    val e2eNoticePlacement = remember(state.messages, state.e2eNotices) {
        E2eThreadNotices.place(state.messages, state.e2eNotices)
    }
    // Round 43 — the trailing E2E notice item (notices newer than
    // every message) renders BELOW the newest message, so it leads
    // the reversed list at index 0 when present and the messages
    // shift by one — `trailingNoticeCount` is that shift, and the
    // newest message's LazyColumn index. [ThreadListIndexing] maps
    // between chronological message indices and LazyColumn indices.
    val trailingNoticeCount = if (e2eNoticePlacement.trailing.isNotEmpty()) 1 else 0
    val messageCount = state.messages.size
    // The LazyColumn feed, newest first: `asReversed` is a view, not
    // a copy. `itemsIndexed` below maps each position back to its
    // chronological index for separators, notices and neighbours.
    val reversedMessages = remember(state.messages) { state.messages.asReversed() }
    // Feedback round 5 — jump to the current search match. Plain scroll, not
    // animated: a jump can span hundreds of messages, and animateScrollToItem
    // over that distance is visibly choppy (same reason as the first-paint
    // scroll below). A match outside the loaded window loads the window
    // around it first; when that window lands the match gains an index and
    // this effect re-runs to scroll to it.
    LaunchedEffect(threadSearchCurrentMatch) {
        val match = threadSearchCurrentMatch ?: return@LaunchedEffect
        if (match.messageIndex >= 0) {
            // Round 43 — `messageIndex` is chronological; the reversed
            // list needs its LazyColumn index.
            listState.scrollToItem(
                ThreadListIndexing.lazyIndexForMessage(
                    match.messageIndex,
                    messageCount,
                    trailingNoticeCount,
                ),
            )
        } else {
            searchJumpedOutOfWindow = true
            viewModel.jumpToSearchMatch(match.messageId)
        }
    }
    // Feedback round 5 — jump to the pinned message from the banner. Same
    // shape as the search jump above: when the message is already in the
    // loaded window, scroll to it right away; when the jump loaded a window
    // around it, this re-runs once the window lands (state.messages is a
    // key) and scrolls then. Plain scroll, not animated — same reason.
    val pinJumpTarget by viewModel.pinJumpTarget.collectAsStateWithLifecycle()
    LaunchedEffect(pinJumpTarget, state.messages) {
        val target = pinJumpTarget ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.id == target }
        if (index >= 0) {
            // Round 43 — `index` is chronological; the reversed list
            // needs its LazyColumn index.
            listState.scrollToItem(
                ThreadListIndexing.lazyIndexForMessage(
                    index,
                    messageCount,
                    trailingNoticeCount,
                ),
            )
            viewModel.clearPinJumpTarget()
        }
    }
    // Feedback round 5 — after closing a search that jumped away from the
    // tail, come back to the bottom once the tail window is back (not before:
    // scrolling while still anchored would land on the anchor).
    val isWindowAnchored by viewModel.isWindowAnchored.collectAsStateWithLifecycle()
    LaunchedEffect(isWindowAnchored, state.messages, pendingJumpToBottom) {
        if (!pendingJumpToBottom || isWindowAnchored || state.messages.isEmpty()) return@LaunchedEffect
        // Round 43 — the bottom of the reversed list is index 0.
        listState.scrollToItem(0)
        pendingJumpToBottom = false
    }
    // Round 43 — reversed: the bottom of the thread is LazyColumn
    // index 0, and the newest message sits at `trailingNoticeCount`
    // (0, or 1 when the trailing E2E notice item leads). At the bottom
    // when the lowest visible index is at or below it — the notice
    // item is small, so seeing it means the newest message is visible
    // too. The remember key matters: without it the derived state
    // would capture the first trailing count and go stale when a
    // notice appears or disappears.
    val isAtBottom by remember(trailingNoticeCount) {
        androidx.compose.runtime.derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            first == null || first.index <= trailingNoticeCount
        }
    }
    // Round 44 — pre-insertion snapshot of `isAtBottom` for the
    // new-message auto-scroll below: after a new message lands at
    // index 0 the anchor shifts (old index 0 → index 1) and the live
    // derived state already reads false, so the effect must read the
    // value from BEFORE the insertion. Updated whenever the user
    // scrolls (the derived state flips); starts true — the first
    // paint sits at the bottom by layout.
    var wasAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(isAtBottom) {
        wasAtBottom = isAtBottom
    }
    // Round 43 — the picker-open re-scroll (round 13) is gone: the
    // reversed list is bottom-anchored by layout, so the tail stays
    // visible on its own as the panel grows and the viewport shrinks
    // — the same mechanism that now handles the keyboard.
    // Round 43 — the IME scroll compensation is gone: the frame-locked
    // delta tracker (round 41), the 350ms settle snap and the drag
    // latch (round 42) all existed because the list was top-anchored
    // and every keyboard resize had to be compensated by scroll code.
    // The reversed list is bottom-anchored by layout — the keyboard
    // resize moves the content with it, frame by frame, with no scroll
    // code to drop a delta or fire late. Removing the two snapshotFlow
    // collectors (this one and the pin's) also removes the round-41
    // ANR mechanism for good.
    // The IME inset is still read, but ONLY for the emoji picker's
    // bottom reserve below (`reservedBottomPx`): the panel must not
    // overlap the keyboard. No collector, no timer, no scroll code
    // reads it anymore. (The inset object is stable per window — only
    // its values change — and `WindowInsets.ime` is a @Composable
    // accessor, so it is captured here, once.)
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeBottomPx = imeInsets.getBottom(density)
    // Audit R4 (v1.14.8) — Compteur de messages non-vus apparus pendant que l'user était
    // scrollé vers le haut. Affiche un chip "↓ N nouveau(x)" cliquable en bas-droite.
    // Sans ça, un message arrivait silencieusement et restait invisible.
    //
    // v1.24.0 (finding A) — le suivi se fait par **id**, plus par index. Avec la fenêtre
    // glissante, charger des messages plus anciens les insère EN TÊTE et décale tous les index :
    // une soustraction d'index compterait alors comme "non vus" des messages que l'utilisateur a
    // déjà lus, voire afficherait un nombre absurde. L'id du dernier message vu est stable quoi
    // qu'il arrive à la fenêtre.
    var lastSeenMessageId by remember { androidx.compose.runtime.mutableLongStateOf(-1L) }
    val unseenCount by remember {
        androidx.compose.runtime.derivedStateOf {
            if (lastSeenMessageId < 0L) {
                0
            } else {
                val seenIndex = state.messages.indexOfLast { it.id == lastSeenMessageId }
                // -1 = le message vu est sorti de la fenêtre par le haut (purge, suppression) :
                // tout ce qui reste est postérieur, donc rien à signaler comme non vu.
                if (seenIndex < 0) 0 else state.messages.lastIndex - seenIndex
            }
        }
    }
    val newestMessageId = state.messages.lastOrNull()?.id
    LaunchedEffect(isAtBottom, newestMessageId) {
        if (isAtBottom && newestMessageId != null) lastSeenMessageId = newestMessageId
    }
    // Round 44 — the new-message auto-scroll is BACK. Round 43 deleted
    // it, assuming a new message at index 0 would appear on its own —
    // it does not: LazyColumn preserves the scroll position by ITEM KEY
    // across insertions, so when a new message lands at index 0 the
    // previously-visible bottom item shifts to index 1 and stays put on
    // screen, pushing the new message BELOW the viewport, cut off
    // (device-tested round 43: a just-sent message showed only its top
    // until the user scrolled down). Plain scroll, not animated — same
    // reason as the search/pin jumps. The at-bottom check reads the
    // `wasAtBottom` SNAPSHOT (never the live derived state — see
    // above), and the arrival guard skips deletions: when the newest
    // message is deleted the id changes too, but there is nothing new
    // to reveal, and scrolling then would yank a scrolled-up reader to
    // the bottom. No IME effect here: once the list is at index 0 the
    // reversed list's bottom anchor handles the keyboard-close resize
    // on its own (the IME scroll code round 43 deleted stays deleted).
    var previousNewestId by remember { androidx.compose.runtime.mutableLongStateOf(-1L) }
    LaunchedEffect(newestMessageId) {
        val newest = state.messages.lastOrNull()
        // Arrived (vs the newest being deleted): the previous newest
        // is still in the list, or there was none yet (first load).
        val arrived = previousNewestId < 0L ||
            state.messages.any { it.id == previousNewestId }
        previousNewestId = newest?.id ?: -1L
        if (!arrived) return@LaunchedEffect
        if (newest == null) return@LaunchedEffect
        if (ThreadListIndexing.shouldAutoScrollToBottom(wasAtBottom, newest.isOutgoing)) {
            listState.scrollToItem(0)
        }
    }
    // Round 43 — the tail pin is gone: in the reversed list the tail
    // is anchored to the bottom by layout, so a bubble growing (a link
    // preview or image loading in) pushes OLDER content up, never the
    // newest message down — the cut-off tail the pin recovered from
    // cannot happen. Removing its snapshotFlow collector also removes
    // the last half of the round-41 ANR mechanism.

    // Round 43 — the explicit re-anchor is gone: loading older
    // messages APPENDS to the reversed feed (older = higher
    // LazyColumn indices), so existing items never shift and the
    // view stays put on its own — no anchor to capture or restore.

    // Feedback round 5 — auto-load older messages on scroll-up (replaces the
    // "Load older messages" button). Round 43 — reversed: the topmost
    // visible item has the HIGHEST LazyColumn index, so "near the top"
    // is the last visible item within [AUTO_LOAD_TOP_THRESHOLD] of the
    // last item (the oldest loaded message). Loading older APPENDS to
    // the reversed feed, so no anchor is captured or restored — the
    // view stays put on its own.
    //
    // Guards: [ThreadViewModel.loadOlder] is a no-op while a load is in flight
    // or when fully loaded, so a fast fling cannot stack loads. The first
    // paint sits at the bottom (index 0) by layout, so it never fires a
    // spurious load that would defeat the 50-message first window. Once a
    // load lands, the user sits ~200 items from the top, so the trigger
    // goes quiet until they scroll up again.
    val nearTop by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && last.index >= info.totalItemsCount - 1 - AUTO_LOAD_TOP_THRESHOLD
        }
    }
    LaunchedEffect(nearTop, state.hasMoreMessages, state.isLoadingOlder) {
        // The guard lives in a plain `val`, not in the `if` condition,
        // so the condition itself stays simple (Detekt ComplexCondition).
        val canLoad = nearTop && state.hasMoreMessages && !state.isLoadingOlder
        if (!canLoad) {
            return@LaunchedEffect
        }
        viewModel.loadOlder()
    }

    // v1.28.3 (F21) — gabarits resolus au niveau COMPOSABLE, et non via `LocalContext` dans le
    // collecteur : un `Context` capture dans une lambda non composable ne suit pas les changements
    // de configuration. Meme parti pris que `ScheduledMessagesScreen`, ou le commentaire d'origine
    // de cette regle est ecrit.
    val partialFmt = stringResource(R.string.send_partial)
    val partialBlockedFmt = stringResource(R.string.send_partial_blocked)

    LaunchedEffect(Unit) {
        viewModel.onConversationOpened()
        viewModel.events.collect { e ->
            when (e) {
                // v1.3.7 — branche le `isError` du Event vers le `SnackbarVisuals` custom
                // pour que le `SnackbarHost` ci-dessous puisse colorer rouge vs slate-blue.
                is ThreadViewModel.Event.ShowSnackbar -> snackbarHost.showSnackbar(
                    SmsTechSnackbarVisuals(message = e.message, isError = e.isError),
                )
                is ThreadViewModel.Event.PdfReady -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, e.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, "PDF")) }
                    snackbarHost.showSnackbar(context.getString(R.string.thread_export_success, e.pages))
                }
                // v1.3.7 — un échec d'envoi est par définition une erreur → rouge.
                //
                // v1.28.3 (F21) — le refus pour cause de blocage se dit à part. « Échec de
                // l'envoi » laisserait croire à une panne passagère, alors que rien ne repartira
                // tant que l'utilisateur n'aura pas débloqué le numéro qu'il a lui-même bloqué.
                is ThreadViewModel.Event.SendError -> snackbarHost.showError(
                    context.getString(
                        when (e.error) {
                            is AppError.RecipientBlocked -> R.string.error_send_recipient_blocked
                            // Group-send own-number fix — the fail-fast for a group send with
                            // no number on file: the user must set it in Settings → Sending,
                            // and a generic "Failed to send" would send them retrying a send
                            // that cannot succeed until they do.
                            is AppError.OwnNumberMissing -> R.string.error_send_own_number_missing
                            // Client mode C3/C5 — the linked phone could
                            // not be reached (offline), or the send was
                            // a scheduled media send (still unsupported
                            // — the schedule frame has no attachments
                            // field): both say so, instead of a generic
                            // "Failed to send". A media send that
                            // exceeded the phone's MMS budget says so
                            // with the phone's own words, and a photo
                            // that could not be prepared says so too.
                            is AppError.ClientOffline -> R.string.error_send_client_offline
                            is AppError.ClientMediaUnsupported -> R.string.error_send_client_media
                            is AppError.ClientMmsCapReached -> R.string.snack_thread_attach_cap_reached
                            is AppError.ClientPhotoPrepareFailed -> R.string.error_send_client_photo_prepare
                            // v1.28.3 (audit global B-1) — la bulle rouge d'un MMS ne se relance
                            // pas d'ici ; le dire vaut mieux qu'un « Échec » qui fait recommencer.
                            is AppError.MmsRetryUnsupported -> R.string.error_retry_mms_unsupported
                            is AppError.GroupEchoRetryUnsupported -> R.string.error_retry_group_echo
                            else -> R.string.error_send_failed
                        },
                    ),
                )
                // v1.28.3 (F21) — envoi partiel. Rouge aussi : quelque chose n'est pas arrivé,
                // et le message est d'autant plus nécessaire que le brouillon, lui, a bien été
                // effacé. Le motif « bloqué » est dit à part, parce qu'il n'appelle pas la même
                // action — attendre le réseau ne débloquera personne.
                is ThreadViewModel.Event.PartialSend -> snackbarHost.showError(
                    if (e.blocked > 0 && e.failed == 0) {
                        partialBlockedFmt.format(e.sent, e.blocked)
                    } else {
                        partialFmt.format(e.sent, e.failed + e.blocked)
                    },
                )
                is ThreadViewModel.Event.OpenAddContact -> {
                    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                        putExtra(ContactsContract.Intents.Insert.PHONE, e.rawNumber)
                    }
                    runCatching { context.startActivity(intent) }
                }
                is ThreadViewModel.Event.OpenEditContact -> {
                    // Round 42 — ACTION_EDIT + the contact URI: the
                    // editor opens on THAT person's card, not the
                    // contacts list (see ContactIntents.editContact).
                    ContactIntents.editContact(context, e.contactId)
                }
                is ThreadViewModel.Event.OpenCreateContact -> {
                    // Fiche vierge pré-remplie (ACTION_INSERT) — évite le sélecteur système
                    // et le défilement jusqu'à "créer un nouveau contact".
                    ContactIntents.createContact(context, e.rawNumber)
                }
                is ThreadViewModel.Event.OpenDialer -> {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(e.rawNumber)}"))
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    // Client mode — tablet calls: a dial that did not go through
    // explains itself in the thread's snackbar (the phone's
    // permission case says so — a generic failure would send the
    // user retrying a dial that cannot succeed until it is fixed).
    // A dial that went through needs no snackbar: the status row
    // below the header shows it instead.
    LaunchedEffect(clientCallViewModel) {
        clientCallViewModel.events.collect { event ->
            val failed = event as? ClientCallViewModel.Event.DialFailed ?: return@collect
            snackbarHost.showSnackbar(
                context.getString(
                    when (failed.reason) {
                        ClientCallViewModel.DialFailure.Offline ->
                            R.string.client_call_dial_offline

                        ClientCallViewModel.DialFailure.NoPermission ->
                            R.string.client_call_dial_no_permission

                        ClientCallViewModel.DialFailure.Failed ->
                            R.string.client_call_dial_failed
                    },
                ),
            )
        }
    }

    // v1.28.3 — la règle de titre vit sur le modèle (nom choisi > nom résolu > numéros).
    // Feedback round 10 — the header title is the phone number itself for a
    // 1-to-1 non-contact, so it is formatted for display (`(xxx) xxx-xxxx`).
    // Names and group titles pass through unchanged; the stored number is
    // never modified.
    val title = state.conversation?.title.orEmpty().formatPhoneNumberForDisplay()
    // v1.23.x — numéro en sous-titre de l'en-tête pour une conversation 1-to-1 QUI A un nom de
    // contact (sinon le titre EST déjà le numéro, inutile de le répéter). Permet de voir tout de
    // suite quel numéro on a ouvert (ex. distinguer un numéro FR d'un numéro étranger).
    val headerSubtitleNumber = state.conversation
        ?.takeIf { it.displayName != null && it.addresses.size == 1 }
        ?.addresses?.firstOrNull()?.raw?.formatPhoneNumberForDisplay()
    val canCall = state.conversation?.addresses?.size == 1

    // Client mode — tablet calls: the outgoing call for THIS
    // thread (matched on the normalized number), for the status row
    // floating over the list. An incoming call never renders here —
    // the AppRoot dropdown owns it. The last value is remembered so
    // the row's exit animation has something to draw (the pinned
    // banner's `lastPinned` pattern).
    val outgoingCall = clientCall?.takeIf { call ->
        !call.incoming && state.conversation?.addresses?.any {
            it.normalized == PhoneAddress.of(call.number).normalized
        } == true
    }
    var lastOutgoingCall by remember { mutableStateOf<ClientCallState?>(null) }
    if (outgoingCall != null) lastOutgoingCall = outgoingCall

    // #8 contextual reply — index messages by id so each bubble can resolve its quoted target
    // in O(1) during recomposition. `remember(state.messages)` cap rebuilds to the actual list
    // identity changes (the Flow emits a fresh list when Room invalidates, but pure UI ticks
    // — playback ticker, draft typing — do not re-run this).
    val youLabel = stringResource(R.string.chat_you)
    val deletedLabel = stringResource(R.string.thread_reply_deleted)
    // Hoisted (not `context.getString` in the `onMicPermissionDenied` lambda):
    // `LocalContext.current` reads are not configuration-aware, and lint fails
    // the build on them inside a lambda passed to a composable.
    val voicePermissionDenied = stringResource(R.string.voice_permission_denied)
    val contactDisplayName = state.conversation?.displayName
    val messageById = remember(state.messages) {
        state.messages.associateBy { it.id }
    }
    fun previewOf(tgt: Message): ReplyQuotePreview = ReplyQuotePreview(
        senderLabel = if (tgt.isOutgoing) youLabel else (contactDisplayName ?: tgt.address),
        body = tgt.body.take(140),
        isFromSelf = tgt.isOutgoing,
    )
    // v1.24.0 (finding A) — avec la fenêtre glissante, la cible d'une citation peut être hors
    // de la tranche chargée. Sans ce repli, la bulle afficherait « message supprimé » alors que
    // le message existe toujours. Les cibles manquantes sont résolues une fois, à la demande.
    val missingQuoteIds = remember(state.messages, state.quotedOutsideWindow) {
        state.messages.mapNotNull { it.replyToMessageId }
            .filterTo(mutableSetOf()) { it !in messageById && it !in state.quotedOutsideWindow }
    }
    LaunchedEffect(missingQuoteIds) {
        if (missingQuoteIds.isNotEmpty()) viewModel.ensureQuotedResolved(missingQuoteIds)
    }
    fun previewFor(msg: Message): ReplyQuotePreview? {
        val tgtId = msg.replyToMessageId ?: return null
        val tgt = messageById[tgtId] ?: state.quotedOutsideWindow[tgtId]
            ?: return ReplyQuotePreview(senderLabel = "—", body = deletedLabel, isFromSelf = false)
        return previewOf(tgt)
    }

    Scaffold(
        topBar = {
            // Feedback round 5 — the top bar swaps between the thread header
            // and the search pill, like the conversations list search (round 4):
            // the pill slides in from the right over the header and slides back
            // out when it closes; the header just fades.
            // Round 17 — measured so the picker panel can stop at its bottom.
            Box(modifier = Modifier.onSizeChanged { topBarHeightPx = it.height }) {
                AnimatedVisibility(
                    visible = !threadSearchOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
            ThreadHeader(
                title = title,
                subtitleNumber = headerSubtitleNumber,
                isGroup = state.conversation?.isGroup == true,
                // Feedback round 7 — contact photo fallback: the custom
                // avatar still wins; the contact photo is resolved in
                // [ThreadViewModel] like the main list does.
                avatarUri = state.conversation?.avatarUri ?: state.contactPhotoUri,
                // Feedback round 10 — grey person avatar for a non-contact.
                isUnknownContact = state.conversation?.let {
                    !it.isGroup && it.displayName == null
                } == true,
                canCall = canCall,
                onBack = onBack,
                // Client mode — tablet calls: the header call button
                // dials through the hub (the phone places the call,
                // the audio stays there) instead of opening this
                // device's dialer, which has no SIM to call with.
                // Hub mode keeps the system dialer.
                onCall = {
                    if (state.isClientMode) {
                        state.conversation?.addresses?.firstOrNull()?.raw?.let {
                            clientCallViewModel.dial(it)
                        }
                    } else {
                        viewModel.requestCall()
                    }
                },
                // The ⋮ opens the details side panel directly (no
                // overflow menu any more) — same as tapping the name.
                onDetails = { detailsPanelOpen = true },
                onSearchClick = { threadSearchOpen = true },
                onMoreClick = { detailsPanelOpen = true },
            )
                }
                AnimatedVisibility(
                    visible = threadSearchOpen,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                ) {
                    ThreadSearchHeader(
                        query = threadSearchQuery,
                        onQueryChange = {
                            threadSearchQuery = it
                            threadSearchIndex = 0
                            viewModel.setThreadSearchQuery(it)
                        },
                        matchLabel = when {
                            threadSearchQuery.isBlank() -> null
                            threadSearchMatches.isEmpty() ->
                                stringResource(R.string.thread_search_no_results)
                            else -> stringResource(
                                R.string.thread_search_match_count,
                                threadSearchCurrentIndex + 1,
                                threadSearchMatches.size,
                            )
                        },
                        canNavigate = threadSearchMatches.isNotEmpty(),
                        onPrevious = {
                            threadSearchIndex = previousThreadSearchIndex(
                                threadSearchCurrentIndex,
                                threadSearchMatches.size,
                            )
                        },
                        onNext = {
                            threadSearchIndex = nextThreadSearchIndex(
                                threadSearchCurrentIndex,
                                threadSearchMatches.size,
                            )
                        },
                        onClose = closeThreadSearch,
                        focusRequester = threadSearchFocusRequester,
                    )
                }
            }
        },
        snackbarHost = {
            // v1.3.7 — branchement bi-couleur cohérent avec le système de marque :
            //   - confirmations positives (`isError = false`, défaut) → fond slate-blue
            //     `inverseSurface` (override de marque, cf. `Color.kt:SnackbarBg = BrandBlue`).
            //   - notifications d'échec (`isError = true`, posé par les call sites
            //     d'erreur via `showError` ou `Event.ShowSnackbar(isError = true)`) →
            //     fond [BrandDanger] (`#C62828`), le rouge fort utilisé partout dans
            //     l'app pour les destructives. Volontairement PAS `errorContainer`
            //     Material 3 qui est pâle en light mode. v1.9.0 — extrait dans
            //     `ui.components.SmsTechSnackbarHost` pour partage cross-écrans.
            SmsTechSnackbarHost(snackbarHost)
        },
        bottomBar = {
            // Client mode C3 — the composer is live on a linked
            // device: sends go via the hub phone (the router's wire
            // branch). Media sends fail cleanly there until C5.
            // Round 20 — the keyboard padding lives on the WHOLE bottom bar
            // (composer + picker), not on the composer alone: with it on the
            // composer, tapping the picker search field shoved the composer
            // to the top of the screen, left a keyboard-sized dead gap, and
            // pushed the panel down behind the keyboard. As one unit, the
            // composer stays glued above the picker and the picker above the
            // keyboard. The window does not resize for the IME (edge-to-edge),
            // so this padding is what lifts the bar at all — load-bearing.
            Column(modifier = Modifier.imePadding()) {
                // Round 14 — the composer stays visible ABOVE the picker
                // (Kristin's screenshot): picked emojis land in the draft box
                // the user can see, instead of a hidden draft. The reply chip,
                // attachments bar and composer are measured together — the
                // panel takes its resting heights from the space below them.
                Column(
                    modifier = Modifier.onSizeChanged { abovePanelHeightPx = it.height },
                ) {
                    // #8 contextual reply chip — renders above the composer when a reply is armed.
                    // Cancelling drops the target without sending; sending the message clears it
                    // automatically through `ThreadViewModel.doSend`.
                    state.replyingTo?.let { target ->
                        ComposerReplyChip(
                            preview = previewOf(target),
                            onCancel = viewModel::cancelReply,
                        )
                    }
                    // v1.3.4 — bande staging des pièces jointes empilées (image / vidéo /
                    // fichier). Affichée si non vide. Chaque chip a un X pour retirer la
                    // PJ + supprimer son fichier cache. Tap sur Envoyer dans le composer
                    // ci-dessous = 1 MMS multipart (texte + toutes les PJ).
                    if (state.pendingAttachments.isNotEmpty()) {
                        com.filestech.sms.ui.components.PendingAttachmentsBar(
                            pending = state.pendingAttachments.map { p ->
                                com.filestech.sms.ui.components.PendingAttachmentChipData(
                                    id = p.file.absolutePath, // String stable (M4)
                                    file = p.file,
                                    mimeType = p.mimeType,
                                    displayName = p.displayName,
                                )
                            },
                            onRemove = { id -> viewModel.removePendingAttachment(id) },
                        )
                    }
                    ComposerBar(
                        voice = state.voice,
                        playback = playbackState,
                        draft = state.draft,
                        isSendingVoice = state.isSendingVoice,
                        isSending = state.isSending,
                        hasPendingAttachments = state.pendingAttachments.isNotEmpty(),
                        // E2E — an encrypted 1:1 thread: "Relay message"
                        // placeholder + the encrypted-send glyph.
                        isEncrypted = isThreadEncrypted,
                        onDraftChanged = viewModel::updateDraft,
                        onSendText = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.send()
                            // v1.3.11 (F1) — drop focus + hide IME so the freshly-sent
                            // message becomes visible at the bottom of the thread.
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        // Round 13 — long-press the send button to schedule: the
                        // preset time goes through the existing `scheduleSend` path,
                        // "Pick date and time" opens the DatePicker → TimePicker flow.
                        scheduleMenuActions = ScheduleMenuActions(
                            onPresetSelected = { epoch -> viewModel.scheduleSend(epoch) },
                            onPickDateTime = { scheduleStep = "date" },
                        ),
                        onStartRecording = viewModel::startVoiceRecording,
                        onStopRecording = viewModel::stopVoiceRecording,
                        onCancelRecording = viewModel::discardVoiceDraft,
                        onTogglePreview = viewModel::togglePreviewPlayback,
                        onSeekPreview = viewModel::seekPreviewTo,
                        onDiscardDraft = viewModel::discardVoiceDraft,
                        onSendVoice = {
                            viewModel.sendVoiceMms()
                            // v1.3.11 (F1) — symmetrical retract for voice MMS dispatch.
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        attachmentActions = ComposerAttachmentActions(
                            onAttachClick = { attachmentSheetOpen = true },
                            onCameraClick = onCameraClick,
                        ),
                        // Round 13 — the composer icon shows the last-used picker tab
                        // and opens the panel to it. Round 14 — it toggles now that
                        // the composer stays visible above the panel.
                        emojiPickerButton = EmojiPickerButton(
                            iconRes = when (lastPickerTab) {
                                EmojiPickerTab.EMOJI -> R.drawable.ic_emoji_happy_outline
                                EmojiPickerTab.GIF -> R.drawable.ic_gif
                                EmojiPickerTab.STICKER -> R.drawable.ic_sticker
                            },
                            onClick = toggleEmojiPicker,
                        ),
                        // Round 14 — the panel is below the composer now: tapping
                        // the text field (keyboard coming up) closes it, and the
                        // composer drops its own navigation-bar padding while the
                        // panel is open — the panel carries it.
                        pickerOpen = emojiPickerOpen,
                        onTextFieldFocused = { if (emojiPickerOpen) emojiPickerOpen = false },
                        onMicPermissionDenied = {
                            // v1.3.7 — refus de permission = erreur utilisateur visible → rouge.
                            scope.launch {
                                snackbarHost.showError(voicePermissionDenied)
                            }
                        },
                    )
                }
                // Round 13 — emoji/GIF/sticker picker: it sits BELOW the composer
                // while open, so the message list shrinks above it instead of
                // being covered. An emoji tap appends to the draft (visible in
                // the box above — the draft is a plain String, no cursor is
                // tracked, so insert-at-cursor is not available) and leaves the
                // panel open for more picks. A sticker or GIF tap stages the file
                // through the existing attachment path (`onAttachmentPicked`, the
                // same one the attach button uses) and closes the panel — the
                // staged chip sits in the composer bar above, and Send dispatches
                // it as one MMS. The sticker URI comes from `stickerSendUri`, not
                // `stickerAssetUri`: the latter is a `file:///android_asset/`
                // URI the send path cannot read, while the former copies the PNG
                // into the outgoing-MMS cache and returns a FileProvider
                // `content://` URI (correct `image/png` MIME, too).
                if (emojiPickerOpen) {
                    EmojiPickerPanel(
                        selectedTab = emojiPickerTab,
                        onTabSelected = onPickerTabSelected,
                        onClose = { emojiPickerOpen = false },
                        onEmojiSelected = { emoji ->
                            onPickerTabSelected(EmojiPickerTab.EMOJI)
                            viewModel.updateDraft(state.draft + emoji)
                        },
                        onStickerSelected = { unicode ->
                            onPickerTabSelected(EmojiPickerTab.STICKER)
                            scope.launch {
                                stickerSendUri(context, unicode)?.let { uri ->
                                    viewModel.onAttachmentPicked(uri, AttachmentKind.PHOTO)
                                }
                            }
                            emojiPickerOpen = false
                        },
                        onGifSelected = { uri ->
                            onPickerTabSelected(EmojiPickerTab.GIF)
                            viewModel.onAttachmentPicked(Uri.parse(uri), AttachmentKind.PHOTO)
                            emojiPickerOpen = false
                        },
                        reservedTopPx = abovePanelHeightPx.toFloat(),
                        reservedHeaderPx = topBarHeightPx.toFloat(),
                        reservedBottomPx = imeBottomPx.toFloat(),
                    )
                }
            }
        },
    ) { padding ->
        // Audit P-Q8 (v1.2.0): `formatters` is already declared at function scope (above the
        // Scaffold). Re-declaring it here forced a fresh allocation on every Scaffold-body
        // recomposition — wasted work given the formatters are stateless cached singletons.
        val todayLabel = stringResource(R.string.date_today)
        val yesterdayLabel = stringResource(R.string.date_yesterday)
        // v1.6.1 (audit PERF-04) — pré-calcul des séparateurs de jour HORS du lambda
        // `itemsIndexed`. Avant : `sameDay` + `daySeparatorLabel` allouaient 3 Calendar
        // par message visible (≈ 600 allocations sur thread de 200 msgs au premier rendu)
        // ET re-couraient à chaque recomposition partielle (mise à jour `read` d'un
        // message). Désormais : un seul `remember(state.messages, todayLabel,
        // yesterdayLabel)` recalcule la liste de labels (`null` = pas de séparateur, sinon
        // le label localisé prêt à afficher). Le bloc se reconstruit uniquement quand la
        // liste change OU si la langue change (todayLabel/yesterdayLabel sont keys).
        val daySeparatorLabels: List<String?> = remember(
            state.messages, todayLabel, yesterdayLabel,
        ) {
            state.messages.mapIndexed { index, msg ->
                val prev = state.messages.getOrNull(index - 1)
                if (prev == null || !sameDay(prev.date, msg.date)) {
                    formatters.daySeparatorLabel(
                        timestampMillis = msg.date,
                        todayLabel = todayLabel,
                        yesterdayLabel = yesterdayLabel,
                    )
                } else null
            }
        }
        // Feedback round 7 — sticky date header: the date of
        // the topmost visible message, floating at the top of the list while
        // scrolling. The label changes the moment the visible messages cross
        // into another day; while many messages share one day the pill stays
        // up for the whole stretch, so the user always knows what date they
        // are viewing. `derivedStateOf` only re-emits when the label changes,
        // so the overlay re-composes once per day boundary, not per frame.
        // Round 43 — reversed: the topmost visible item has the HIGHEST
        // LazyColumn index, and the day boundary for it is its own
        // separator or the nearest OLDER message's (higher LazyColumn
        // index = older). The inline separator lives at the top of the
        // boundary item: the floating pill hides only while the whole
        // boundary item is on screen (its top — the separator — visible).
        // The fully-visible check is the same under either offset
        // convention, so it does not depend on how LazyColumn measures
        // offsets in a reversed list.
        val stickyDateLabel by remember(daySeparatorLabels, trailingNoticeCount) {
            derivedStateOf {
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                val topIndex = visible.lastOrNull()?.index ?: return@derivedStateOf null
                val lastIndex = info.totalItemsCount - 1
                val boundaryChronoIndex = (topIndex..lastIndex).firstNotNullOfOrNull { lazy ->
                    val chrono = ThreadListIndexing.chronoIndexForLazy(
                        lazy,
                        messageCount,
                        trailingNoticeCount,
                    )
                    chrono?.takeIf { daySeparatorLabels.getOrNull(it) != null }
                } ?: return@derivedStateOf null
                val label = daySeparatorLabels[boundaryChronoIndex]
                    ?: return@derivedStateOf null
                val boundaryLazyIndex = ThreadListIndexing.lazyIndexForMessage(
                    boundaryChronoIndex,
                    messageCount,
                    trailingNoticeCount,
                )
                val boundaryItem = visible.firstOrNull { it.index == boundaryLazyIndex }
                val fullyVisible = boundaryItem != null &&
                    boundaryItem.offset >= info.viewportStartOffset &&
                    boundaryItem.offset + boundaryItem.size <= info.viewportEndOffset
                if (fullyVisible) null else label
            }
        }
        // Feedback round 8 — the sticky date pill floats only TEMPORARILY:
        // it appears while the list is scrolling and slides up out of view
        // shortly after scrolling stops, so it never sits on the thread
        // while the user is reading. `isScrollInProgress` covers drag and
        // fling in both directions; the hide is a 1 s idle delay, and the
        // LaunchedEffect restarts (cancelling the delay) if scrolling
        // resumes before it fires.
        var showStickyDate by remember { mutableStateOf(false) }
        val isScrolling = listState.isScrollInProgress
        LaunchedEffect(isScrolling) {
            if (isScrolling) {
                showStickyDate = true
            } else {
                kotlinx.coroutines.delay(STICKY_DATE_HIDE_DELAY_MS)
                showStickyDate = false
            }
        }
        // v1.18.0 — Bundle de callbacks stable réutilisé par chaque item. Capture les setters
        // `var by remember` (pendingDelete, pendingUnpin, pickingReactionFor,
        // forwardingMessage, pendingRetryWatchdog) + les références ViewModel + les lambdas
        // locales. Recréé
        // uniquement si viewModel change (singleton scope ViewModel — donc 1 fois par
        // composition de ThreadScreen). Référence stable → Compose peut skipper la
        // recomposition des items dont le `msg` n'a pas changé.
        // Client mode C2 — `state.isClientMode` is a key too: the retry
        // gate below must rebuild when the mode flips, or a mode
        // switch mid-thread would keep the old behaviour.
        val itemActions = remember(viewModel, state.isClientMode) {
            ThreadMessageItemActions(
                onTogglePlayback = { audio -> viewModel.togglePlayback(audio.localUri, audio.toPlaybackUri()) },
                onSeekPlayback = viewModel::seekPlaybackTo,
                onTogglePlaybackAt = viewModel::togglePlayback,
                onTapFailed = { msg ->
                    // Client mode C3 — retry is wired on a linked
                    // device (same-key resend via the hub); the
                    // watchdog confirmation still applies.
                    // Audit R3 (v1.14.8) — WATCHDOG_TIMEOUT → confirmation dialog (risque doublon).
                    // SYNCHRONOUS = rejeté radio, retry direct sans risque.
                    if (msg.errorCode == SendErrorCode.WATCHDOG_TIMEOUT) {
                        pendingRetryWatchdog = msg
                    } else {
                        viewModel.retry(msg.id)
                    }
                },
                onDelete = { msg -> pendingDelete = msg },
                onReply = { msg -> viewModel.startReply(msg) },
                onReact = { msgId -> pickingReactionFor = msgId },
                // Round 12 — quick-reaction bar: sends the emoji immediately
                // (same path as the picker); recording the recent happens in
                // the ViewModel's setReaction. Round 21 — `null` removes the
                // reaction (the bar toggles the applied emoji off).
                onQuickReact = { msgId, emoji -> viewModel.setReaction(msgId, emoji) },
                // Round 21 — badge tap removes the reaction, single tap, no
                // confirmation (restores the pre-refresh behaviour).
                onRemoveReaction = { msgId -> viewModel.setReaction(msgId, null) },
                onForward = { msg -> forwardingMessage = msg },
                onTranslate = { msg -> translateMessageExternal(msg) },
                onCopy = { msg -> copyMessageBody(msg) },
                // Feedback round 5 — épingler / désépingler (la VM compare à
                // l'épingle courante du fil). Round 17 — unpinning asks
                // first (a stray tap on "Unpin" drops the pin); pinning
                // goes straight through.
                onTogglePin = { msg ->
                    if (msg.id == state.conversation?.pinnedMessageId) {
                        pendingUnpin = msg
                    } else {
                        viewModel.togglePin(msg.id)
                    }
                },
                onPhoneClick = onPhoneClick,
                // E2E — a key-change notice tap opens the safety-number
                // sheet for this thread's peer.
                onE2eNoticeVerify = { e2eVerificationOpen = true },
            )
        }
        // v1.22.0 (double SIM) — résolveur SIM (subId → libellé) calculé une fois pour tout le
        // fil. Retourne `null` en mono-SIM / permission absente / subId inconnu, donc aucun tag
        // n'apparaît dans ces cas. Lambda stable (mémoïsée) : passable aux items sans casser le
        // scoping de recomposition de [ThreadMessageItem].
        val simLabeler = rememberSimLabeler()
        // Feedback round 5 — pinned-message banner: the live copy from the
        // loaded window wins when the message is loaded (so the banner
        // follows text edits); the resolved outside-window copy is the
        // fallback for an old pinned message. Hidden while searching — the
        // search pill replaces the header, and the banner belongs to it.
        val pinnedMessageId = state.conversation?.pinnedMessageId
        val pinnedMessage = remember(state.messages, state.pinnedOutsideWindow, pinnedMessageId) {
            pinnedMessageId?.let { id ->
                state.messages.find { it.id == id } ?: state.pinnedOutsideWindow
            }
        }
        val pinnedAt = state.conversation?.pinnedMessageAt
        // Feedback round 9 — the last pinned message is remembered so the
        // banner can animate OUT when it is unpinned: unpinning clears
        // `pinnedMessage` immediately, so without this the exit animation
        // would have nothing to draw.
        var lastPinned by remember { mutableStateOf<Pair<Message, Long>?>(null) }
        if (pinnedMessage != null && pinnedAt != null) {
            lastPinned = pinnedMessage to pinnedAt
        }
        // Feedback round 7 — measured height of the floating pinned banner
        // (px): the sticky date pill sits just below it, and the banner's
        // height varies (32dp X box present or not), so it is measured
        // rather than guessed.
        var pinnedBannerHeightPx by remember { mutableStateOf(0) }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            // Round 43 — REVERSED: the list is bottom-anchored by
            // layout (index 0 = newest = bottom), so the keyboard
            // resize, the picker, content growth and new messages all
            // move the content with them — no scroll compensation.
            // The feed is newest-first ([reversedMessages]); the
            // chronological index for each item is recovered inside.
            reverseLayout = true,
            // Feedback round 5 — room below the last message for a reaction
            // badge hanging off its bottom edge (see [BubbleReactionOverlay]):
            // without it the badge on the last message is clipped by the list
            // viewport, where the composer starts.
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // E2E — notices newer than the last message (usually the
            // activation notice, recorded before the first encrypted
            // reply). They render BELOW the newest message, at the
            // very bottom of the thread — so in the reversed list
            // they are the FIRST item (index 0), before the newest
            // message. [trailingNoticeCount] is the shift they add
            // to every message's LazyColumn index.
            if (e2eNoticePlacement.trailing.isNotEmpty()) {
                item(key = "e2e-trailing-notices", contentType = "e2e-notices") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        for (notice in e2eNoticePlacement.trailing) {
                            E2eNoticeRow(
                                notice = notice,
                                contactName = title,
                                onVerify = { e2eVerificationOpen = true },
                                        memberNames = state.memberNames,
                            )
                        }
                    }
                }
            }
            // v1.24.0 (finding A) — `key` stable pour que l'insertion des
            // messages plus anciens ne détruise pas la position de scroll.
            // Feedback round 5 — the "Load older messages" button is gone:
            // older messages load automatically on scroll-up (see the
            // auto-load effect above); they APPEND to this reversed feed,
            // so existing items never shift. Round 43 — `index` here is
            // the position in the reversed feed (0 = newest); the
            // chronological index is `lastIndex - index` (see below).
            itemsIndexed(
                reversedMessages,
                key = { _, m -> m.id },
                // Audit PERF-M1 (v1.14.8) — `contentType` indique au recycleur Compose que
                // les bulles audio / media / texte sont 3 layouts distincts. Sans ce hint
                // Compose tente de réutiliser des compositions incompatibles → allocations
                // forcées + jank sur scroll. 3 buckets stables = recyclage propre.
                contentType = { _, m ->
                    when {
                        m.audioAttachment != null -> "audio"
                        m.attachments.any { !it.isAudio } -> "media"
                        else -> "text"
                    }
                },
            ) { index, msg ->
                // Round 43 — `index` is the position in the reversed
                // feed (0 = newest); neighbours, separators and
                // notices are all chronological, so they use the
                // chronological index.
                val chronoIndex = state.messages.lastIndex - index
                // v1.18.0 — Dispatcher extrait vers [ThreadMessageItem]. Avant : ~140 lignes
                // inline (audio / media / text bubble). Maintenant : 1 appel. Les valeurs
                // dérivées (showTimestamp, burstPosition, senderLabel) sont calculées DANS
                // l'item Composable, plus dans le scope LazyColumn. Recompositions
                // mieux scopées : un item ne re-compose que sur changement de SON msg / prev /
                // next, pas sur changement du state global du parent.
                // Feedback round 5 — search highlight for this message, if any.
                val searchHighlight = threadSearchHighlightById[msg.id] ?: SearchHighlight()
                ThreadMessageItem(
                    msg = msg,
                    prev = state.messages.getOrNull(chronoIndex - 1),
                    next = state.messages.getOrNull(chronoIndex + 1),
                    daySeparatorLabel = daySeparatorLabels.getOrNull(chronoIndex),
                    // v1.28.4 — dans un groupe, la bulle nomme son expéditeur, pas le groupe.
                    conversationDisplayName = if (state.conversation?.isGroup == true) {
                        state.memberNames[msg.address] ?: msg.address
                    } else {
                        state.conversation?.displayName
                    },
                    // Relay — l'étiquette d'expéditeur ne s'affiche QUE dans les
                    // groupes ; en 1:1, aucune étiquette au-dessus des bulles.
                    isGroup = state.conversation?.isGroup == true,
                    bubbleColorArgb = state.conversation?.bubbleColorArgb,
                    smishingReasons = state.smishingVerdicts[msg.id] ?: emptyList(),
                    playbackProvider = playbackProvider,
                    repliedToPreview = previewFor(msg),
                    // v1.22.0 (double SIM) — tag SIM sur les messages REÇUS uniquement (la bulle
                    // du contact, cf. demande). Les sortants portent aussi un `sub_id` (SIM
                    // d'envoi par défaut) mais afficher "Vous · SIM 2" sur chaque burst sortant
                    // serait du bruit non sollicité.
                    simLabel = if (msg.isIncoming) simLabeler(msg.subId) else null,
                    searchHighlight = searchHighlight,
                    // Feedback round 5 — ce message est-il l'épinglé du fil ?
                    // Le menu bulle affiche alors « Désépingler » au lieu d'« Épingler ».
                    isPinnedMessage = msg.id == state.conversation?.pinnedMessageId,
                    recentReactions = recentReactions,
                    actions = itemActions,
                    // E2E — the notices dated at or before this
                    // message, rendered above its bubble (the
                    // contact name is the conversation title: the
                    // name or formatted number the header shows).
                    // Round 49: the group member names travel too,
                    // so a group notice can name its subject.
                    e2eNotices = E2eNoticesForItem(
                        notices = e2eNoticePlacement.before.getOrNull(chronoIndex) ?: emptyList(),
                        contactName = title,
                        memberNames = state.memberNames,
                    ),
                    // Client mode C2 — the thread is read-only: the
                    // bubble menus hide their write actions.
                    isClientMode = state.isClientMode,
                    // Client mode C5 — the download states for this
                    // thread's bubbles (empty in hub mode).
                    downloadStates = state.attachmentDownloads,
                    onRequestDownload = { id -> viewModel.requestAttachmentDownload(id) },
                )
            }
        }
        // Audit R4 (v1.14.8) — Chip flottant "↓ N nouveau(x) message(s)" affiché en
        // bas-droite quand l'user est scrollé vers le haut ET que de nouveaux messages
        // sont arrivés. Tap → scroll animé + reset du compteur via le LaunchedEffect.
        // Feedback round 5 — auto-load indicator: while older messages are
        // loading above, a small chip (spinner + text) floats at the top of
        // the list. It is an overlay, not a list item, so it never shifts
        // the message indices or the scroll anchor. It only ever shows
        // during a top load — never when fully loaded.
        // Feedback round 6 — the pinned banner floats over the top of the
        // list (no background band around it), fixed under the header with
        // a slight gap — like the Figma design, where the first message
        // peeks out from behind it. It draws under the loading chip above,
        // so the transient spinner stays visible while older messages load.
        // Hidden while searching — the search pill replaces the header, and
        // the banner belongs to it. Feedback round 9 — the banner animates:
        // it slides UP into its top spot when a message is pinned, and
        // flies UP out through the top when it is closed/unpinned. The
        // Column wrapper is load-bearing: `AnimatedVisibility` has a
        // `ColumnScope` overload, and this spot sits in a `Box` (whose
        // scope cannot call it) inside a `Column` — the compiler picked
        // the `ColumnScope` overload and failed. Wrapping in a `Column`
        // gives it the scope it needs.
        Column(modifier = Modifier.fillMaxWidth()) {
            // Client mode — tablet calls: the outgoing dial state
            // floats over the top of the list, above the pinned
            // banner — "Calling…" while the dial is in flight, "On
            // call" once the phone reports it off-hook. The list
            // stays usable underneath: the call is on the phone.
            AnimatedVisibility(
                visible = outgoingCall != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
                lastOutgoingCall?.let { ClientCallStatusRow(phase = it.phase) }
            }
            AnimatedVisibility(
                visible = pinnedMessage != null && pinnedAt != null && !threadSearchOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
            val pinned = lastPinned ?: return@AnimatedVisibility
            PinnedMessageBanner(
                message = pinned.first,
                pinnedAt = pinned.second,
                isGroup = state.conversation?.isGroup == true,
                title = title,
                memberNames = state.memberNames,
                formatters = formatters,
                // Client mode C2 — unpin is a hub-side write in C2,
                // so the banner shows without its unpin action.
                canUnpin = !state.isPanicDecoy,
                onJump = { viewModel.jumpToPinnedMessage(pinned.first.id) },
                // Round 17 — the banner X asks first too (same dialog as
                // the menu Unpin).
                onUnpin = { pendingUnpin = pinned.first },
                // Feedback round 7 — measured so the sticky date pill can
                // sit just below the banner (see [pinnedBannerHeightPx]).
                modifier = Modifier.onSizeChanged { pinnedBannerHeightPx = it.height },
            )
            }
        }
        // Feedback round 7 — sticky date header, floating at the top of the
        // list under the pinned banner (if any). `null` while the group's own
        // inline separator is on screen, or when the list is empty. Hidden
        // while older messages load: the "loading older" chip owns the same
        // top-centre spot, and the list is mid-shift then anyway.
        // Feedback round 8 — the pill floats only temporarily (see
        // [showStickyDate]): it slides in while scrolling and slides up out
        // of view shortly after scrolling stops.
        if (!state.isLoadingOlder) {
            val bannerHeightPx = if (pinnedMessage != null && pinnedAt != null && !threadSearchOpen) {
                pinnedBannerHeightPx
            } else {
                0
            }
            StickyDatePill(
                label = stickyDateLabel,
                visible = stickyDateLabel != null && showStickyDate,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = with(LocalDensity.current) { bannerHeightPx.toDp() } + 8.dp,
                    ),
            )
        }
        if (state.isLoadingOlder) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.thread_loading_older),
                    style = RelayText.BubbleMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Feedback round 5 — after a jump to the pinned message the window
        // is anchored on it, so the newest messages are not loaded. This
        // button (same spot and style as the unseen-messages chip) takes the
        // user back to the latest window; the return-to-bottom effect above
        // scrolls once the tail window lands. It takes precedence over the
        // unseen chip while anchored — both would stack on the same corner.
        val isPinAnchored by viewModel.isPinAnchored.collectAsStateWithLifecycle()
        if (isPinAnchored && state.messages.isNotEmpty()) {
            FilledTonalButton(
                shape = RoundedCornerShape(50),
                onClick = {
                    viewModel.clearWindowAnchor()
                    pendingJumpToBottom = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(text = stringResource(R.string.thread_pinned_latest))
            }
        }
        // Feedback round 5 — the four-way guard lives in a plain val, not
        // in the `if` condition (Detekt ComplexCondition, same fix as the
        // auto-load guard in batch 2).
        val showUnseenChip = !isAtBottom && unseenCount > 0 &&
            state.messages.isNotEmpty() && !isPinAnchored
        if (showUnseenChip) {
            FilledTonalButton(
                shape = RoundedCornerShape(50),
                onClick = {
                    scope.launch {
                        // Round 43 — the bottom of the reversed list is index 0.
                        listState.animateScrollToItem(0)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.pluralStringResource(
                        id = R.plurals.thread_unseen_messages,
                        count = unseenCount,
                        unseenCount,
                    ),
                )
            }
        }
        }
        }
    }

    // Audit R3 (v1.14.8) — Dialog confirmation retry WATCHDOG_TIMEOUT (risque doublon).
    pendingRetryWatchdog?.let { msg ->
        AlertDialog(
            onDismissRequest = { pendingRetryWatchdog = null },
            title = { Text(stringResource(R.string.thread_retry_watchdog_title)) },
            text = { Text(stringResource(R.string.thread_retry_watchdog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = msg.id
                    pendingRetryWatchdog = null
                    viewModel.retry(id)
                }, shape = RoundedCornerShape(50)) { Text(stringResource(R.string.thread_retry_watchdog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRetryWatchdog = null }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.thread_retry_watchdog_cancel))
                }
            },
        )
    }

    // v1.15.1 — DatePicker pour programmer un message. Date d'aujourd'hui min, pas de cap max.
    // Round 32 — message step: the panel's Schedule row lands here when the
    // composer draft is empty, so scheduling never needs a draft. The typed
    // text becomes the body override for `scheduleSend`; dismissing clears
    // it, like every other exit of this flow.
    if (scheduleStep == "message") {
        com.filestech.sms.ui.components.ScheduleMessageDialog(
            allowEmpty = state.pendingAttachments.isNotEmpty(),
            onDismiss = {
                scheduleStep = null
                scheduledBodyOverride = null
            },
            onConfirm = { text ->
                scheduledBodyOverride = text
                scheduleStep = "presets"
            },
        )
    }
    // Round 33 — preset step (the desktop popup's radio list): four
    // quick times, or "Pick a date and time" for the pickers below. A
    // preset schedules straight away through the existing `scheduleSend`
    // path, with the message step's body override when there is one.
    if (scheduleStep == "presets") {
        com.filestech.sms.ui.components.SchedulePresetDialog(
            onPresetSelected = { epoch ->
                scheduleStep = null
                viewModel.scheduleSend(epoch, scheduledBodyOverride)
                scheduledBodyOverride = null
            },
            onPickCustom = { scheduleStep = "date" },
            onDismiss = {
                scheduleStep = null
                scheduledBodyOverride = null
            },
        )
    }
    if (scheduleStep == "date") {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = {
                scheduleStep = null
                scheduledBodyOverride = null
            },
            confirmButton = {
                TextButton(onClick = {
                    val selected = datePickerState.selectedDateMillis
                    if (selected != null) {
                        scheduledEpochMidnight = selected
                        scheduleStep = "time"
                    } else {
                        scheduleStep = null
                        scheduledBodyOverride = null
                    }
                }, shape = RoundedCornerShape(50)) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scheduleStep = null
                    scheduledBodyOverride = null
                }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            androidx.compose.material3.DatePicker(
                state = datePickerState,
                title = { Text(stringResource(R.string.thread_schedule_date_title), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            )
        }
    }
    // v1.15.1 — TimePicker (étape 2) : compose la date choisie + l'heure pour obtenir l'epoch.
    // Round 33 — 12-hour with the AM/PM selector, not 24-hour: the
    // schedule flow must offer AM/PM, like the desktop popup.
    if (scheduleStep == "time") {
        val timePickerState = androidx.compose.material3.rememberTimePickerState(
            initialHour = 9,
            initialMinute = 0,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = {
                scheduleStep = null
                scheduledBodyOverride = null
            },
            title = { Text(stringResource(R.string.thread_schedule_time_title)) },
            text = {
                androidx.compose.material3.TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    // Combine date (millis at midnight UTC selon DatePicker) + heure/minute locales.
                    // DatePicker retourne l'epoch UTC à 00:00 — on rajoute le décalage local pour
                    // que l'heure choisie soit interprétée dans le fuseau de l'user.
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = scheduledEpochMidnight
                    cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val year = cal.get(java.util.Calendar.YEAR)
                    val month = cal.get(java.util.Calendar.MONTH)
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val localCal = java.util.Calendar.getInstance().apply {
                        clear()
                        set(year, month, day, timePickerState.hour, timePickerState.minute, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val epochMs = localCal.timeInMillis
                    scheduleStep = null
                    // Round 32 — the override is consumed here (null on
                    // the composer path, where the draft is scheduled)
                    // and cleared, so a later flow never reuses it.
                    viewModel.scheduleSend(epochMs, scheduledBodyOverride)
                    scheduledBodyOverride = null
                    // v1.25.3 (audit L10) — « Programmer » et non « Continuer » : c'est l'étape
                    // finale du sélecteur, elle valide l'envoi différé. `action_schedule`
                    // existait déjà en FR et EN, simplement jamais référencée.
                }, shape = RoundedCornerShape(50)) { Text(stringResource(R.string.action_schedule)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scheduleStep = null
                    scheduledBodyOverride = null
                }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // v1.3.11 (F4) — phone number actions dialog. Triggered from any bubble's
    // [MessageTextWithLinks] (text body or attachment caption) through the
    // [onPhoneClick] lambda built above.
    pickingPhoneNumber?.let { number ->
        com.filestech.sms.ui.components.PhoneActionsDialog(
            number = number,
            onDismiss = { pickingPhoneNumber = null },
            onSnack = { msg -> scope.launch { snackbarHost.showSnackbar(msg) } },
        )
    }

    // v1.3.11 (F5) — forward bottom-sheet. The order of operations matters:
    //   1. stageForward() posts the payload to IncomingShareHolder FIRST so it is in
    //      place by the time the destination ThreadViewModel hydrates.
    //   2. forwardingMessage is cleared BEFORE navigation so the sheet dismisses
    //      cleanly even if the user backs out of the dest screen.
    //   3. The caller's onForwardToConversation does pop+navigate so we don't stack a
    //      new thread on top of the source thread (cleaner back behaviour).
    forwardingMessage?.let { msg ->
        com.filestech.sms.ui.components.ForwardMessageSheet(
            currentConversationId = conversationId,
            onDismiss = { forwardingMessage = null },
            onPickConversation = { destId ->
                viewModel.stageForward(msg)
                forwardingMessage = null
                onForwardToConversation(destId)
            },
            onPickNewContact = {
                viewModel.stageForward(msg)
                forwardingMessage = null
                onForwardToNewContact()
            },
        )
    }

    // Round 24 — the "+" reaction picker: the full emoji grid (all 487
    // emojis, titled sections, search at the top) instead of the old
    // 24-emoji quick-pick sheet with its "Other emoji" button and
    // validate step. Tap reacts immediately; tapping the applied emoji
    // again removes the reaction (the sheet applies [toggleReaction]
    // and passes `null` for a removal — same toggle as the
    // quick-reaction row and the desktop picker).
    pickingReactionFor?.let { msgId ->
        val existingReaction = state.messages.firstOrNull { it.id == msgId }?.reactionEmoji
        com.filestech.sms.ui.components.ReactionEmojiPickerSheet(
            currentReaction = existingReaction,
            onPicked = { emoji ->
                viewModel.setReaction(msgId, emoji)
                pickingReactionFor = null
            },
            onDismiss = { pickingReactionFor = null },
        )
    }
    pendingDelete?.let { msg ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = stringResource(R.string.thread_confirm_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.deleteMessage(msg.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    // Round 17 — unpin confirmation (menu Unpin + banner X): a stray tap
    // must not drop the pin. Orange, not red — unpinning undoes with one
    // tap (re-pin), like blocking.
    pendingUnpin?.let { msg ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.action_unpin),
            message = stringResource(R.string.thread_confirm_unpin_message),
            confirmLabel = stringResource(R.string.action_unpin),
            confirmColor = com.filestech.sms.ui.theme.BrandBlocked,
            onConfirm = {
                viewModel.togglePin(msg.id)
                pendingUnpin = null
            },
            onDismiss = { pendingUnpin = null },
        )
    }

    if (attachmentSheetOpen) {
        AttachmentPickerSheet(
            onDismiss = { attachmentSheetOpen = false },
            onAttachmentPicked = { uri, kind ->
                viewModel.onAttachmentPicked(uri, kind)
            },
        )
    }

    // Attachment confirmation dialog — v1.2.1 UX fix. The picker drops the file into the
    // staging slot, and the user explicitly validates here before any MMS dispatch. Cancel
    // deletes the cached copy.
    // v1.3.4 — le dialog modal `pendingAttachment` (v1.2.1) est SUPPRIMÉ au profit
    // d'une bande staging dans le composer (cf. `PendingAttachmentsBar` ci-dessous,
    // affichée dans `bottomBar` au-dessus du champ texte). L'utilisateur empile autant
    // de PJ qu'il veut, écrit éventuellement du texte, et tape Envoyer une seule fois
    // pour tout dispatcher en 1 MMS multipart.

    // Details side panel (replaces the old stats dialog): big avatar,
    // action rows, group members, and the Photos / Videos / Files tabs.
    // The panel keeps its own media ViewModel; everything else here is
    // the same flows the old overflow menu wired.
    if (detailsPanelOpen) {
        val detailsConversation = state.conversation
        if (detailsConversation != null) {
            com.filestech.sms.ui.screens.details.ConversationDetailsPanel(
                conversation = detailsConversation,
                title = title,
                subtitleNumber = headerSubtitleNumber,
                // Round 43 — the panel's 96dp header avatar gets the
                // best available photo (full-size when the contact
                // has one — the thumbnail pixelates at that size).
                contactPhotoUri = state.contactPhotoLargeUri,
                // E2E — the encryption row: 1:1 hub threads only
                // (groups have no single peer; a linked device holds
                // no identity keys, so it never probes or shows its
                // own encryption state). Null = no row. Round 49:
                // the GROUP encryption row rides the actions bundle
                // below (the panel is at the 20-parameter cap).
                e2ePeerState = state.e2ePeerState.takeIf {
                    !state.isClientMode && !detailsConversation.isGroup
                },
                e2eVerified = state.e2eVerified,
                onOpenE2eVerification = { e2eVerificationOpen = true },
                // Round 41 — manual probe retry from the Details
                // panel: one probe now, bypassing the budget.
                onRetryE2eProbe = { viewModel.retryE2eProbe() },
                isUnknownContact = !detailsConversation.isGroup &&
                    detailsConversation.displayName == null,
                memberNames = state.memberNames,
                memberPhotos = state.memberPhotos,
                showAddToContacts = !detailsConversation.isGroup &&
                    !state.hasContact && !state.isClientMode,
                showEditContact = !detailsConversation.isGroup &&
                    state.hasContact && !state.isClientMode,
                // Round 32 — Schedule is always available from the
                // panel: with an empty draft the flow opens its
                // message step first, so there is never "nothing to
                // schedule". (The composer's own button keeps its
                // draft-or-attachment gate — it has no message step.)
                canSchedule = true,
                canExport = state.messages.isNotEmpty(),
                isExporting = state.isExporting,
                actions = com.filestech.sms.ui.screens.details.ConversationDetailsActions(
                    onSchedule = {
                        // Draft non-blank: straight to the preset
                        // times. Blank: type the message first, then
                        // the presets (a preset schedules it, "Pick a
                        // date and time" opens the pickers).
                        scheduleStep = if (state.draft.isNotBlank()) "presets" else "message"
                    },
                    onExportPdf = { viewModel.exportToPdf() },
                    onChatColors = { appearanceOpen = true },
                    onToggleMute = { viewModel.toggleMute() },
                    onBlock = { askBlock = true },
                    // Client mode C3 — Schedule and Block are wired
                    // on a linked device (wire frames); Rename and
                    // Delete stay hidden (no rename frame — a local
                    // rename would be overwritten by the next sync;
                    // no delete frame at all).
                    onDelete = if (state.isClientMode) {
                        null
                    } else {
                        { askDelete = true }
                    },
                    onRename = if (state.isClientMode) {
                        null
                    } else {
                        { renameOpen = true }
                    },
                    onCreateContact = { viewModel.requestCreateContact() },
                    onAddContact = { viewModel.requestAddContact() },
                    onEditContact = { viewModel.requestEditContact() },
                    onMemberClick = { addr -> viewModel.openMemberContact(addr) },
                    // v1.12.0 — vault row, hidden in PanicDecoy.
                    onMoveVault = if (state.isPanicDecoy) {
                        null
                    } else {
                        {
                            viewModel.moveCurrentConversationToVault(
                                intoVault = state.conversation?.inVault != true,
                            )
                        }
                    },
                    // The panel only changes the avatar — the bubble
                    // colour passes through untouched.
                    onSetAvatar = { uri ->
                        viewModel.setAppearance(
                            state.conversation?.bubbleColorArgb,
                            uri,
                        )
                    },
                    // Round 45 — the regular-SMS handshake retry,
                    // in the encryption box's expanded area.
                    onRetryE2eProbeViaSms = { viewModel.retryE2eProbeViaSms() },
                    // Round 49 — the group encryption row (design
                    // §13): group hub threads only (1:1 threads use
                    // the peer-state row above; a linked device
                    // holds no identity keys). Null = no row.
                    e2eGroupState = state.e2eGroupState.takeIf {
                        !state.isClientMode && detailsConversation.isGroup
                    },
                ),
                onDismiss = { detailsPanelOpen = false },
            )
        }
    }
    // E2E — the safety-number verification sheet: opened from the
    // details-panel encryption row or a key-change notice. The peer
    // is this 1:1 thread's single address; groups never open it.
    if (e2eVerificationOpen) {
        val e2eAddress = e2ePeerAddress
        if (e2eAddress != null) {
            E2eVerificationSheet(
                address = e2eAddress,
                contactName = title,
                onDismissRequest = { e2eVerificationOpen = false },
            )
        }
    }
    // v1.11.0 — Sujet 5 apparence : dialog couleur bulle (Chat colors).
    if (appearanceOpen) {
        com.filestech.sms.ui.components.AppearanceDialog(
            currentBubbleColorArgb = state.conversation?.bubbleColorArgb,
            onDismiss = { appearanceOpen = false },
            onConfirm = { color ->
                // The dialog only changes the colour — the avatar
                // passes through untouched.
                viewModel.setAppearance(color, state.conversation?.avatarUri)
                appearanceOpen = false
            },
        )
    }
    // v1.28.3 — nommer le groupe.
    if (renameOpen) {
        com.filestech.sms.ui.components.RenameGroupDialog(
            current = state.conversation?.customName,
            onDismiss = { renameOpen = false },
            onConfirm = { nom ->
                viewModel.renameGroup(nom)
                renameOpen = false
            },
        )
    }
    if (askBlock) {
        // v1.25.3 (audit M18) — le corps du dialogue reprenait le titre de la conversation (un
        // nom ou un numéro) en guise d'explication : l'utilisateur confirmait un geste
        // irréversible sans jamais lire ce qu'il allait déclencher. Les ressources explicatives
        // existaient déjà et n'étaient utilisées nulle part.
        DestructiveConfirmDialog(
            title = stringResource(R.string.conversation_block_confirm_title),
            message = stringResource(R.string.conversation_block_confirm_body),
            confirmLabel = stringResource(R.string.action_block),
            confirmColor = com.filestech.sms.ui.theme.BrandBlocked,
            onConfirm = {
                askBlock = false
                // v1.2.5: block-from-detail now also removes the conversation so the user is
                // not left staring at the very thread they just blocked. List-level Block
                // (bottom sheet) keeps the block-only behaviour for users wanting to keep
                // the history.
                viewModel.blockSenders { onBack() }
            },
            onDismiss = { askBlock = false },
        )
    }
    if (askDelete) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.conversations_delete_confirm_title),
            message = stringResource(R.string.conversations_delete_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                askDelete = false
                viewModel.deleteThisConversation { onBack() }
            },
            onDismiss = { askDelete = false },
        )
    }
    // v1.2.1: the "Confirm before broadcast" setting (Réglages → Envoi) drives every
    // outgoing path — SMS text, MMS voice, and MMS attachments. Toggle OFF → instant send
    // everywhere. Toggle ON → this dialog (for text), the voice confirm below, and the
    // attachment confirm below it. Three dialogs, one setting, consistent.
    //
    // ⚠️ v1.28.3 (F30) — ce commentaire a ÉTÉ FAUX de la v1.3.4 à la v1.28.2. Il renvoyait à un
    // « attachment confirm at the bottom of the file » qui n'existait plus : la v1.3.4 avait
    // retiré le dialogue de confirmation des pièces jointes au profit de la bande de staging du
    // composeur. Le remplacement était juste pour ce que faisait ce dialogue-là — valider un
    // fichier au moment du choix — mais il a emporté avec lui la confirmation d'ENVOI, sans que
    // personne le remarque, ce commentaire continuant d'affirmer le contraire. Le chemin média
    // est de nouveau couvert, par le dialogue ajouté plus bas.
    state.pendingSend?.let { body ->
        // v1.2.3 audit U10: Send is the positive primary action — autofocus + Button (heavier
        // weight) instead of two ambiguous TextButtons. Material 3 confirm-flow guideline.
        val sendFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { sendFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingSend() },
            title = { Text(stringResource(R.string.settings_confirm_send_title)) },
            text = { Text(body) },
            confirmButton = {
                androidx.compose.material3.Button(
                    shape = RoundedCornerShape(50),
                    onClick = { viewModel.confirmPendingSend() },
                    modifier = Modifier.focusRequester(sendFocus),
                ) {
                    Text(stringResource(R.string.action_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingSend() }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (state.pendingVoiceConfirm) {
        val sendFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { sendFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingVoice() },
            title = { Text(stringResource(R.string.voice_confirm_title)) },
            text = { Text(stringResource(R.string.voice_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.Button(
                    shape = RoundedCornerShape(50),
                    onClick = { viewModel.confirmPendingVoice() },
                    modifier = Modifier.focusRequester(sendFocus),
                ) {
                    Text(stringResource(R.string.action_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingVoice() }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // v1.28.3 (F30) — troisième dialogue, celui que le commentaire du haut promettait déjà.
    // Annuler ne supprime AUCUN fichier : les pièces jointes restent dans la bande de staging,
    // exactement comme après un échec d'envoi, pour que refuser ne coûte pas de les rechoisir.
    if (state.pendingAttachmentSend) {
        val sendFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { sendFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingAttachmentSend() },
            title = { Text(stringResource(R.string.settings_confirm_send_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.thread_confirm_attachments_body,
                        state.pendingAttachments.size,
                        state.pendingAttachments.size,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    shape = RoundedCornerShape(50),
                    onClick = { viewModel.confirmPendingAttachmentSend() },
                    modifier = Modifier.focusRequester(sendFocus),
                ) {
                    Text(stringResource(R.string.action_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingAttachmentSend() }, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Feedback round 5 — pinned-message banner, measured off
 * `02___Thread_Pinned.png` (390x844, 1x): a solid brand-blue card
 * (16dp radius, 12dp side margins, 4dp under the header, ~63dp tall)
 * with the sender on the first line ("You" for your own messages) and an
 * X at the top-right, then the message text (one line, truncated) and the
 * "Pinned · date at time" line (pin date, white at 70%) sharing the second
 * line, bottom-aligned. Tapping the card jumps to the message (loading the
 * window around it first when it is outside the loaded window); the X
 * removes the pin.
 *
 * Feedback round 6 — the card FLOATS over the top of the message list
 * (an overlay inside the list's Box, not a row above it), so there is no
 * background band around it — the first message peeks out from behind it,
 * like the design. The sender is 13sp (smaller than the 15sp preview,
 * measured from the design), the date has no year ("11/8"), and the X's
 * right edge aligns with the vertical plane where the "M" in "PM" ends.
 */
@Composable
private fun PinnedMessageBanner(
    message: Message,
    pinnedAt: Long,
    isGroup: Boolean,
    title: String,
    memberNames: Map<String, String>,
    formatters: com.filestech.sms.ui.util.ChatFormatters,
    canUnpin: Boolean,
    onJump: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Round 20 — the banner is a [brandBlue] surface with [onBrandBlue]
    // content: fixed brand blue + white, or the wallpaper pair when
    // dynamic colours are on.
    val bannerContent = onBrandBlue()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brandBlue())
            .clickable(onClick = onJump)
            .semantics { role = Role.Button }
            // Feedback round 7 — more air below the message text and date:
            // the sender row's 32dp X box made the top read roomier than
            // the bottom (~16dp vs ~11.5dp of visible blue). 12dp below
            // evens them out.
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pinnedSenderLabel(
                        message = message,
                        isGroup = isGroup,
                        title = title,
                        memberNames = memberNames,
                        youLabel = stringResource(R.string.chat_you),
                    ),
                    // Feedback round 6 — sender 15sp -> 13sp, smaller than
                    // the 15sp preview (measured from the Figma design).
                    style = RelayText.MessageBody.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = bannerContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canUnpin) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onUnpin)
                            .semantics { role = Role.Button },
                        // Feedback round 6 — the glyph hugs the box's right
                        // edge, so its right side aligns with the vertical
                        // plane where the "M" in "PM" ends on the line below.
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_circle_outline),
                            contentDescription = stringResource(R.string.action_unpin),
                            tint = bannerContent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = pinnedPreviewText(
                        message = message,
                        photoLabel = stringResource(R.string.attach_photo),
                        videoLabel = stringResource(R.string.attach_video),
                        voiceLabel = stringResource(R.string.thread_pinned_voice),
                        fileLabel = stringResource(R.string.thread_pinned_attachment),
                    ),
                    style = RelayText.MessageBody.copy(fontSize = 15.sp),
                    color = bannerContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Feedback round 7 — FixedTypeSize, like the bubble
                // timestamps: without it the date followed the system font
                // scale and rendered larger than the 12sp bubble times.
                FixedTypeSize {
                    Text(
                        text = stringResource(
                            R.string.thread_pinned_label,
                            // Feedback round 6 — no year: "11/8" instead of
                            // "11/8/26" (see ChatFormatters.shortDateNoYear).
                            formatters.shortDateNoYear.format(Date(pinnedAt)),
                            formatters.time.format(Date(pinnedAt)),
                        ),
                        style = RelayText.BubbleMeta,
                        color = bannerContent.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Relay UI refresh — thread header: 64dp, canvas background. Back chevron (24dp,
 * on-surface) + 37dp avatar + name; the search, call and ⋮ glyphs are 24dp
 * on-surface-variant, and the ⋮ uses `ic_more_vert_tall` so its DRAWN height
 * (19.4dp) matches the search and call glyphs. The 36dp hit areas leave the
 * ⋮ centre 27dp from the edge, like the home screen. Tapping the avatar,
 * the name, or the ⋮ opens the details side panel.
 */
@Composable
private fun ThreadHeader(
    title: String,
    subtitleNumber: String?,
    isGroup: Boolean,
    avatarUri: String?,
    /** Feedback round 10 — 1-to-1 thread with a non-contact: grey person avatar. */
    isUnknownContact: Boolean = false,
    canCall: Boolean,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onDetails: () -> Unit,
    /** Feedback round 5 — opens the in-conversation search pill. */
    onSearchClick: () -> Unit,
    /** The ⋮ opens the details side panel directly (no overflow menu). */
    onMoreClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .background(cs.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Round 4 — 56dp -> 64dp: the header was still cramped after round 3
                // (44dp -> 56dp). Same elements and order; the extra height is pure
                // breathing room.
                // Feedback round 6 — end padding 4dp -> 9dp, so the ⋮ sits 27dp
                // from the edge like the home screen's (see ConversationsScreen).
                .height(64.dp)
                .padding(start = 4.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                // Feedback round 7 — on-surface, 24dp, like the Settings
                // back arrow (was brand blue, 22dp).
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = cs.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDetails)
                    .semantics { role = Role.Button },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RelayAvatar(
                    label = title,
                    // Feedback round 6 — 32dp -> 37dp: 5px taller, as measured
                    // in Figma. The initials keep their fixed size, so the
                    // bigger circle gives them the same breathing room the
                    // 48dp home avatars already have.
                    size = 37.dp,
                    customUri = avatarUri,
                    isGroup = isGroup,
                    isUnknownContact = isUnknownContact,
                )
                // Feedback round 5 — avatar-to-text gap 8dp -> 16dp, matching
                // the pinned-thread screenshot (measured 17dp; 16dp is the
                // standard step, within 1px).
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = RelayText.ThreadTitle,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitleNumber != null) {
                        Text(
                            text = subtitleNumber,
                            // Feedback round 5 — 13sp -> 12sp, matching the
                            // pinned-thread screenshot (number band ~7px tall).
                            style = RelayText.Timestamp.copy(fontSize = 12.sp),
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Feedback round 5 — header icon order matches the Figma thread:
            // search (magnifier), call, ⋮. The magnifier opens the
            // in-conversation search pill. Same icon and tint as the
            // conversations list search (`ic_search`, on-surface-variant like
            // the ⋮ next to it — feedback round 7: the thread used on-surface,
            // darker than the home header).
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    // Feedback round 5 — 44dp -> 36dp, matching the
                    // pinned-thread screenshot (icon centres ~33-36dp apart).
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSearchClick)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.thread_search_hint),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (canCall) {
                // Feedback round 6 — 5dp between the search and the call
                // icon, so the magnifier sits a touch further left.
                Spacer(Modifier.width(5.dp))
                // The outlined call icon (`ic_call_outline`, outline
                // handset), like the desktop thread header. Tinted
                // on-surface-variant at the call site, like the search
                // magnifier and the ⋮ next to it. The call action
                // itself is unchanged. The 22dp size is kept: the
                // outline glyph fills its 19x19 viewBox, so it
                // renders at the same ~50px as the search and ⋮
                // glyphs. The 36dp touch box is unchanged.
                Box(
                    modifier = Modifier
                        // Feedback round 5 — 44dp -> 36dp, as the search box.
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCall)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_outline),
                        contentDescription = stringResource(R.string.action_call),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    // Feedback round 5 — 44dp -> 36dp, as the search box.
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMoreClick)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                // Feedback round 7 — `ic_more_vert_tall` instead of Material
                // MoreVert: the drawn glyph is 19.4dp tall, matching the
                // search and call glyphs (MoreVert drew at 16dp). Tinted
                // on-surface-variant like the home header's ⋮.
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert_tall),
                    contentDescription = stringResource(R.string.action_more),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * Feedback round 5 — the in-conversation search pill that replaces the
 * thread header while searching, matching the conversations list search
 * pill (round 4): a full-width pill (48dp high, fully rounded), "Search in
 * this conversation" placeholder, no magnifier inside, and Kristin's
 * close-circle icon at the right end — tapping it closes the pill. The
 * match count ("2 of 5", or "No results") and the up/down jump controls sit
 * to the right of the pill; the keyboard's search key jumps to the next
 * match. Matches stay in place in the thread with highlighting — nothing is
 * hidden — so the user keeps the conversation context around a match.
 */
@Composable
private fun ThreadSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    /** "2 of 5", "No results", or null while the query is still blank. */
    matchLabel: String?,
    canNavigate: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    val cs = MaterialTheme.colorScheme
    // Hoisted: stringResource is @Composable and cannot run inside .semantics {}.
    val closeLabel = stringResource(R.string.action_close)
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .background(cs.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelayTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).height(48.dp),
                placeholder = stringResource(R.string.thread_search_hint),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onNext() },
                ),
                cornerRadius = 24.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                focusRequester = focusRequester,
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose)
                            .semantics {
                                contentDescription = closeLabel
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_circle_outline),
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
            matchLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            IconButton(onClick = onPrevious, enabled = canNavigate) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.thread_search_previous),
                    tint = cs.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onNext, enabled = canNavigate) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.thread_search_next),
                    tint = cs.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * v1.25.3 — teinte du bouton de confirmation. Rouge par défaut (suppression, geste sans
     * retour) ; l'appelant passe [com.filestech.sms.ui.theme.BrandBlocked] pour un blocage, qui
     * se défait d'un tap et ne mérite donc pas le même signal.
     */
    confirmColor: Color = BrandDanger,
) {
    // v1.2.5: revert to the solid BrandDanger fill — the Material 3 `errorContainer` token
    // resolves to a pastel pink in the light scheme, which the user explicitly didn't want
    // for a destructive confirm. Strong red + white text matches the brand identity used by
    // the delete button on conversation rows and the snackbar background.
    val cancelFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { cancelFocus.requestFocus() }
    }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = { Text(message) },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                // 🔴 v1.27.4 — LE LIBELLÉ SUIT LA TEINTE, il n'est plus blanc par principe.
                //
                // Ce bouton sert DEUX teintes : le rouge [BrandDanger] par défaut, et l'orange
                // [com.filestech.sms.ui.theme.BrandBlocked] pour un blocage. Blanc convenait au
                // rouge (**4,96:1**) et pas à l'orange (**3,79:1**, sous le seuil AA de 4,5:1) —
                // mais forcer le noir aurait fait tomber le rouge à **4,23:1**. Un seul premier plan
                // écrit en dur ne pouvait pas être juste pour les deux : c'est le jumeau
                // asymétrique, et il était ici invisible parce que les deux appelants partagent
                // cette ligne. Voir [com.filestech.sms.ui.theme.onBrandContainer].
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = confirmColor,
                    contentColor = com.filestech.sms.ui.theme.onBrandContainer(confirmColor),
                ),
                shape = RoundedCornerShape(50),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Composer bar — three modes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Round 13 — the composer's attachment actions, bundled like [ScheduleMenuActions]
 * so [ComposerBar] stays within the parameter-count limit: the + opens the
 * attachment sheet, the camera captures directly (TakePicture).
 */
private data class ComposerAttachmentActions(
    val onAttachClick: () -> Unit,
    val onCameraClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Suppress("LongParameterList")
@Composable
private fun ComposerBar(
    voice: ThreadViewModel.VoiceState,
    playback: VoicePlaybackController.PlaybackState,
    draft: String,
    isSendingVoice: Boolean,
    /** v1.26.1 (audit H7) — envoi texte/media en cours : desactive le bouton Envoyer. */
    isSending: Boolean,
    /** v1.3.4 M1 audit fix — flag pour basculer mic → send button quand des PJ sont stagées. */
    hasPendingAttachments: Boolean,
    /** E2E — this 1:1 thread encrypts: the placeholder reads "Relay
     * message", the send button shows the encrypted-send glyph, and
     * the composer buttons are blue (round 48 — green when plain). */
    isEncrypted: Boolean = false,
    onDraftChanged: (String) -> Unit,
    onSendText: () -> Unit,
    /** Round 13 — long-press the send button to schedule (see [ComposingRow]). */
    scheduleMenuActions: ScheduleMenuActions,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onTogglePreview: () -> Unit,
    onSeekPreview: (Int) -> Unit,
    onDiscardDraft: () -> Unit,
    onSendVoice: () -> Unit,
    /** Round 13 — attachment actions, bundled (see [ComposerAttachmentActions]). */
    attachmentActions: ComposerAttachmentActions,
    /** Round 13 — picker: the composer button (last-used tab icon + tap). */
    emojiPickerButton: EmojiPickerButton,
    /** Round 14 — the picker is open below the composer: the composer drops its own
     * navigation-bar padding (the panel carries it) and the text field closing the
     * panel on focus is wired through [onTextFieldFocused]. */
    pickerOpen: Boolean,
    onTextFieldFocused: () -> Unit,
    onMicPermissionDenied: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted ->
        if (!granted) onMicPermissionDenied()
        // Note: we do NOT auto-start recording here. The user must press-and-hold again — this
        // avoids surprising them with a recording that begins the moment they tap "Allow".
    }
    val permissionGranted = micPermission.status == PermissionStatus.Granted

    // Round 20 — no `imePadding` here: it lives on the bottom-bar Column
    // in `ThreadScreen` so the keyboard lifts composer + picker as one
    // unit (on the composer alone it shoved the composer to the top of the
    // screen and pushed the panel behind the keyboard).
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (pickerOpen) Modifier else Modifier.navigationBarsPadding()),
    ) {
        when (voice) {
            // Idle and Recording share the same row layout — switching between them must NOT
            // remove the mic button from the composition tree, otherwise its pointer-input
            // would be cancelled mid-gesture and the push-to-talk release event would be lost.
            ThreadViewModel.VoiceState.Idle,
            is ThreadViewModel.VoiceState.Recording -> ComposingRow(
                isRecording = voice is ThreadViewModel.VoiceState.Recording,
                elapsedMs = (voice as? ThreadViewModel.VoiceState.Recording)?.elapsedMs ?: 0L,
                amplitude = (voice as? ThreadViewModel.VoiceState.Recording)?.amplitude ?: 0,
                draft = draft,
                permissionGranted = permissionGranted,
                hasPendingAttachments = hasPendingAttachments,
                isSending = isSending,
                isEncrypted = isEncrypted,
                onDraftChanged = onDraftChanged,
                onSendText = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSendText()
                },
                scheduleMenuActions = scheduleMenuActions,
                onRequestPermission = { micPermission.launchPermissionRequest() },
                onPressStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStartRecording()
                },
                onReleaseStop = onStopRecording,
                onReleaseCancel = onCancelRecording,
                onCancelHintCrossed = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                onAttachClick = attachmentActions.onAttachClick,
                onCameraClick = attachmentActions.onCameraClick,
                emojiButtonIconRes = emojiPickerButton.iconRes,
                onEmojiButtonClick = emojiPickerButton.onClick,
                pickerOpen = pickerOpen,
                onTextFieldFocused = onTextFieldFocused,
            )

            is ThreadViewModel.VoiceState.Reviewing -> ReviewingMode(
                durationMs = voice.durationMs,
                playback = playback,
                isSending = isSendingVoice,
                isEncrypted = isEncrypted,
                onTogglePlay = onTogglePreview,
                onSeek = onSeekPreview,
                onDiscard = onDiscardDraft,
                onSend = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSendVoice()
                },
            )
        }
    }
}

/**
 * Idle/Recording row. Push-to-talk gesture lives on the mic button: press-down starts the
 * recording, release stops it, dragging left past [CANCEL_THRESHOLD_DP] discards instead. The
 * mic stays in the composition tree across the Idle↔Recording transition so the in-flight
 * pointer gesture is never interrupted.
 *
 * Relay UI refresh: the composer is a filled pill (`surfaceVariant`, 24dp radius, min
 * 48dp, grows to 5 lines) with Kristin's smiley leading and her camera + add-circle
 * inside trailing; the send affordance is a 48dp circle with a white paper-airplane
 * send icon, shown only when there is text (or staged attachments), and a mic glyph when the
 * composer is empty. Round 48 — the circles are the theme blue when the thread encrypts,
 * the plain green (`#3BA803`) when it does not. The mic circle is the same 48dp height as the pill, so the row
 * reads as one aligned unit. Round 4: the camera opens the camera directly
 * ([onCameraClick]), only the + opens the attachment sheet ([onAttachClick]).
 * Feedback round 6 — reverted to the 48dp pill: the 36dp version read cramped
 * and the mic did not match the pill height.
 */

/** U+FE0F — variation selector 16, trailing a base emoji (❤️). */
private const val BACKSPACE_VARIATION_SELECTOR = 0xFE0F

/** U+200D — zero-width joiner, the glue in ZWJ sequences. */
private const val BACKSPACE_ZERO_WIDTH_JOINER = 0x200D

/** U+20E3 — combining enclosing keycap (1️⃣). */
private const val BACKSPACE_KEYCAP = 0x20E3

/** Skin-tone modifiers U+1F3FB–U+1F3FF. */
private val BACKSPACE_SKIN_TONES = 0x1F3FB..0x1F3FF

/** Regional indicators U+1F1E6–U+1F1FF — flags are pairs of these. */
private val BACKSPACE_REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

/**
 * Round 15 — [text] with its last grapheme cluster removed: one VISIBLE
 * character, so a backspace tap deletes a whole emoji instead of leaving
 * half of it behind. Hand-rolled on purpose: `BreakIterator` splits ZWJ
 * sequences and skin tones on the JDK (verified: 👩‍❤️‍👨 lost only its
 * last man), and its behaviour varies by device ICU version, so the
 * cluster is walked back by hand — trailing VS16 / tone modifiers, then
 * the ZWJ chain (each component may carry its own modifiers), then a
 * flag pair or keycap base. Empty text stays empty.
 */
private fun dropLastGrapheme(text: String): String {
    if (text.isEmpty()) return text
    var start = text.offsetByCodePoints(text.length, -1)
    var codePoint = text.codePointAt(start)
    // Keycap first: U+20E3 sits after its base (and VS16), so step back
    // onto the VS16 and let the modifier loop below consume it.
    if (codePoint == BACKSPACE_KEYCAP && start > 0) {
        start = text.offsetByCodePoints(start, -1)
        codePoint = text.codePointAt(start)
    }
    // Trailing VS16 / tone modifiers belong to the char before them.
    while (codePoint == BACKSPACE_VARIATION_SELECTOR || codePoint in BACKSPACE_SKIN_TONES) {
        if (start == 0) return ""
        start = text.offsetByCodePoints(start, -1)
        codePoint = text.codePointAt(start)
    }
    // ZWJ chain: while a joiner precedes the cluster, extend back over the
    // joiner and the component before it (which may carry modifiers too).
    while (start > 0 && text.codePointBefore(start) == BACKSPACE_ZERO_WIDTH_JOINER) {
        start = text.offsetByCodePoints(start, -1)
        if (start == 0) break
        start = text.offsetByCodePoints(start, -1)
        codePoint = text.codePointAt(start)
        while (codePoint == BACKSPACE_VARIATION_SELECTOR || codePoint in BACKSPACE_SKIN_TONES) {
            if (start == 0) break
            start = text.offsetByCodePoints(start, -1)
            codePoint = text.codePointAt(start)
        }
    }
    // Regional-indicator flag pair: a lone RI takes the one before it.
    if (codePoint in BACKSPACE_REGIONAL_INDICATORS &&
        start > 0 && text.codePointBefore(start) in BACKSPACE_REGIONAL_INDICATORS
    ) {
        start = text.offsetByCodePoints(start, -1)
    }
    return text.substring(0, start)
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
private fun ComposingRow(
    isRecording: Boolean,
    elapsedMs: Long,
    amplitude: Int,
    draft: String,
    permissionGranted: Boolean,
    /** v1.3.4 M1 — quand `true`, force l'affichage du bouton Send au lieu du mic même
     *  si `draft.isBlank()` (l'user a juste empilé des PJ et veut les envoyer sans texte). */
    hasPendingAttachments: Boolean,
    /** v1.26.1 (audit H7) — envoi en cours : le bouton Envoyer est désactivé le temps du vol. */
    isSending: Boolean,
    /** E2E — this 1:1 thread encrypts: the placeholder reads "Relay
     * message", the send button shows the encrypted-send glyph, and
     * the composer buttons are blue (round 48 — green when plain). */
    isEncrypted: Boolean = false,
    onDraftChanged: (String) -> Unit,
    onSendText: () -> Unit,
    /** Round 13 — long-press the send button to schedule (see below). */
    scheduleMenuActions: ScheduleMenuActions,
    onRequestPermission: () -> Unit,
    onPressStart: () -> Unit,
    onReleaseStop: () -> Unit,
    onReleaseCancel: () -> Unit,
    onCancelHintCrossed: () -> Unit,
    onAttachClick: () -> Unit,
    /** Round 4 — camera icon: direct capture (TakePicture), not the sheet. */
    onCameraClick: () -> Unit,
    /** Round 13 — picker: the composer icon shows the last-used tab. */
    emojiButtonIconRes: Int,
    /** Round 13 — picker: tapping the composer icon opens the panel. */
    onEmojiButtonClick: () -> Unit,
    /** Round 15 — the picker is open below the composer: tapping the composer
     * icon closes it AND focuses the text field, so the keyboard pops up and
     * the user can type or delete straight away. */
    pickerOpen: Boolean,
    /** Round 14 — the text field gained focus (keyboard coming up): the caller
     * closes the picker so the two never stack. */
    onTextFieldFocused: () -> Unit,
) {
    // v1.3.4 M1 — afficher le Mic seulement si pas de PJ stagées ; sinon l'utilisateur
    // doit pouvoir envoyer les PJ via le bouton Send même sans texte.
    val showMic = isRecording || (draft.isBlank() && !hasPendingAttachments)
    // Round 13 — long-press schedule menu on the send button. Opens only when
    // there is something to schedule (draft non-blank or attachments pending —
    // the same gate as the header's Schedule action); a tap still sends immediately.
    val canSchedule = draft.isNotBlank() || hasPendingAttachments
    var scheduleMenuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Round 13 — the leading icon shows the last-used picker tab (smile, GIF or
    // sticker) and opens the picker panel to that tab. It used to put the focus on
    // the field and open the keyboard (the system keyboard's own emoji key); the
    // panel replaces that now.
    val composerFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // Live cancel hint: true once the finger has dragged past the cancel threshold while pressing.
    var cancelHinted by remember { mutableStateOf(false) }
    // Reset the hint when leaving Recording (release/cancel finished).
    LaunchedEffect(isRecording) { if (!isRecording) cancelHinted = false }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (isRecording) {
                    RecordingStrip(
                        elapsedMs = elapsedMs,
                        amplitude = amplitude,
                        cancelHinted = cancelHinted,
                    )
                } else {
                    // Le texte saisi suit la taille du texte des messages, comme les bulles.
                    MessageTextSize {
                        RelayTextField(
                            value = draft,
                            onValueChange = onDraftChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .onFocusChanged { if (it.hasFocus) onTextFieldFocused() },
                            // E2E — an encrypted 1:1 thread reads "Relay
                            // message"; every other thread reads "Message".
                            placeholder = stringResource(
                                if (isEncrypted) {
                                    R.string.thread_compose_placeholder_encrypted
                                } else {
                                    R.string.thread_compose_placeholder
                                },
                            ),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default,
                            ),
                            cornerRadius = 24.dp,
                            // Feedback round 7 — vertical padding 8dp -> 6dp:
                            // the decoration row is 36dp tall (the in-field
                            // icon boxes), so at 8dp the pill rendered 52dp
                            // while the 48dp mic/send circles bottom-aligned
                            // against it sat 2dp low. At 6dp the pill is
                            // genuinely 48dp (6+36+6) and the circles centre
                            // on it. The icon boxes stay 36dp — the icons
                            // themselves must not get any smaller.
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            // Feedback round 5 — the requester belongs on the inner
                            // text field, not on the outer modifier: attached to the
                            // outer Box it pointed at a non-focusable node, so
                            // `composerFocus.requestFocus()` (smiley tap) was a silent
                            // no-op — no focus, no keyboard, no cursor.
                            focusRequester = composerFocus,
                            leadingIcon = {
                                // Round 13 — opens the emoji/GIF/sticker picker panel
                                // to the last-used tab. Round 15 — when the panel is
                                // open, the same tap closes it AND focuses the text
                                // field, so the keyboard pops up and the user can
                                // type or delete straight away. (Opening the panel
                                // never focuses — the keyboard stays hidden there.)
                                ComposerIconButton(
                                    painter = painterResource(emojiButtonIconRes),
                                    contentDescription = stringResource(R.string.thread_compose_emoji),
                                    onClick = {
                                        onEmojiButtonClick()
                                        if (pickerOpen) {
                                            runCatching { composerFocus.requestFocus() }
                                        }
                                    },
                                )
                            },
                            trailingIcon = {
                                // Dans l'ordre des captures de Kristin : caméra puis +.
                                // La caméra ouvre l'appareil photo directement (capture
                                // TakePicture) ; seul le + ouvre la feuille de pièces jointes.
                                // Round 4 — la caméra s'efface dès qu'on tape : elle glisse
                                // vers la droite en fondu, et le champ gagne la place libérée.
                                // Piloté par `showMic`, le même état qui fait muter mic ->
                                // envoyer, pour que les deux restent synchronisés. Le
                                // `shrink/expandHorizontally` referme le créneau lui-même :
                                // sans lui, le champ ne se ré-élargirait qu'une fois
                                // l'animation terminée, en laissant un trou entre-temps.
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AnimatedVisibility(
                                        visible = showMic,
                                        enter = slideInHorizontally { it } + fadeIn() +
                                            expandHorizontally(expandFrom = Alignment.End),
                                        exit = slideOutHorizontally { it } + fadeOut() +
                                            shrinkHorizontally(shrinkTowards = Alignment.End),
                                    ) {
                                        ComposerIconButton(
                                            painter = painterResource(R.drawable.ic_camera_outline),
                                            contentDescription = stringResource(R.string.attach_photo),
                                            onClick = onCameraClick,
                                        )
                                    }
                                    // Round 15 — while the picker drawer is open,
                                    // the + becomes a backspace key: tapping it
                                    // deletes the last character of the draft (a
                                    // whole emoji), so a changed mind doesn't mean
                                    // swapping back to the keyboard and back to
                                    // the drawer. The + returns when it closes.
                                    if (pickerOpen) {
                                        ComposerIconButton(
                                            painter = painterResource(R.drawable.ic_backspace),
                                            contentDescription = stringResource(R.string.action_backspace),
                                            onClick = { onDraftChanged(dropLastGrapheme(draft)) },
                                        )
                                    } else {
                                        ComposerIconButton(
                                            painter = painterResource(R.drawable.ic_add_circle_outline),
                                            contentDescription = stringResource(R.string.action_attach),
                                            onClick = onAttachClick,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (showMic) {
                MicButton(
                    isRecording = isRecording,
                    cancelHinted = cancelHinted,
                    permissionGranted = permissionGranted,
                    isEncrypted = isEncrypted,
                    onRequestPermission = onRequestPermission,
                    onPressStart = onPressStart,
                    onReleaseStop = onReleaseStop,
                    onReleaseCancel = onReleaseCancel,
                    onCancelHintChanged = { hinted ->
                        if (hinted && !cancelHinted) onCancelHintCrossed()
                        cancelHinted = hinted
                    },
                )
            } else {
                // Round 13 — long-press the send button to schedule. The menu is
                // anchored to this Box so it floats near the button; a tap still
                // sends immediately. It only opens when there is something to
                // schedule (draft or attachments) — otherwise a long-press does
                // nothing. v1.26.1 (audit H7) — désactivé pendant l'envoi, comme
                // le bouton vocal l'est déjà.
                Box {
                    SendCircleButton(
                        enabled = !isSending,
                        isEncrypted = isEncrypted,
                        onClick = onSendText,
                        onLongClick = if (canSchedule) {
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scheduleMenuOpen = true
                            }
                        } else {
                            null
                        },
                    )
                    SchedulePresetMenu(
                        expanded = scheduleMenuOpen,
                        onDismissRequest = { scheduleMenuOpen = false },
                        onPresetSelected = { epoch ->
                            scheduleMenuOpen = false
                            scheduleMenuActions.onPresetSelected(epoch)
                        },
                        onPickDateTime = {
                            scheduleMenuOpen = false
                            scheduleMenuActions.onPickDateTime()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Small round hit area (36dp) for a glyph living inside the composer pill — Kristin's
 * smiley leading, camera and + trailing. The glyph itself is 24dp in the secondary
 * colour (round 4 — 22dp still read small next to the 48dp mic/send tile); the vector
 * drawables are Kristin's own icons, tinted here from the theme.
 *
 * Feedback round 7 — the boxes stay 36dp (the icons must not get any smaller): the
 * pill is brought to 48dp by trimming the field's vertical content padding instead
 * (8dp -> 6dp at the call site: 6+36+6 = 48), so the 48dp mic/send circles centre
 * on it. The camera glyph itself was also re-centred in its vector (its artwork
 * sat 1.5dp high — see `ic_camera_outline.xml`).
 */
@Composable
private fun ComposerIconButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Relay UI refresh — send affordance: a 48dp circle with a white paper-airplane
 * send icon, the same height as the composer pill. It slides up with a spring each
 * time it appears and scales to 0.90 while pressed; the haptic tick is fired by the
 * caller's [onClick] (see [ComposerBar]).
 *
 * Round 48 — the circle is the theme blue when this thread encrypts, the plain
 * green (`#3BA803`) when it does not ([resolveComposerButtonColors], the same
 * signal as the outgoing bubbles). The glyph switch (lock vs plain plane) is
 * unchanged; only the colour moves. The long-press schedule entry rides this
 * same button, so it inherits the colour — there is no separate schedule
 * button in the composer.
 *
 * Round 13 — [onLongClick] opens the schedule menu (long-press to schedule); it is
 * null when there is nothing to schedule, so a long-press then does nothing. The
 * press-scale animation and the tap path are unchanged: [combinedClickable] reads
 * the same [interactionSource] the scale does.
 */
@Composable
private fun SendCircleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /**
     * E2E — the thread encrypts: the send glyph gains its lock
     * (Kristin's send-encrypted vector, 21×24dp to keep its 20:23
     * ratio against the plain 24dp paper plane).
     */
    isEncrypted: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        label = "sendPressScale",
    )
    // The button (re)enters composition when the composer gains text: animate the
    // slide-up once per appearance, from 4dp below at 0.9 alpha.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val slideOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "sendSlideUp",
    )
    val slideAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0.9f,
        label = "sendSlideAlpha",
    )
    // Round 48 — blue when this thread encrypts, the plain green
    // when it does not (the same signal as the outgoing bubbles).
    // Resolved here, at the leaf, so the colour follows the same
    // `isEncrypted` recomposition as the placeholder and the glyph.
    val buttonColors = resolveComposerButtonColors(
        encrypted = isEncrypted,
        defaultBlue = brandBlue(),
        defaultBluePressed = brandBluePressed(),
        onDefaultBlue = onBrandBlue(),
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .offset(y = slideOffset)
            .alpha(slideAlpha)
            .alpha(if (enabled) 1f else 0.38f)
            .scale(pressScale)
            .clip(CircleShape)
            .background(if (pressed) buttonColors.pressedBackground else buttonColors.background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = stringResource(R.string.action_send),
                onLongClickLabel = if (onLongClick != null) {
                    stringResource(R.string.thread_schedule_action)
                } else {
                    null
                },
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        // E2E — an encrypted thread sends with the lock glyph.
        if (isEncrypted) {
            Icon(
                painter = painterResource(R.drawable.ic_send_encrypted),
                contentDescription = stringResource(R.string.action_send),
                tint = buttonColors.content,
                modifier = Modifier.size(width = 21.dp, height = 24.dp),
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.action_send),
                tint = buttonColors.content,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Live recording strip: pulse, timer, waveform, and a slide-to-cancel hint. */
@Composable
private fun RecordingStrip(
    elapsedMs: Long,
    amplitude: Int,
    cancelHinted: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val danger = BrandDanger

    val infinite = rememberInfiniteTransition(label = "rec-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec-pulse-alpha",
    )

    val history = remember { mutableStateListOf<Int>() }
    LaunchedEffect(elapsedMs, amplitude) {
        history.add(amplitude)
        if (history.size > WAVE_BARS) history.removeAt(0)
    }

    // v1.2.4 audit U15: animate the cancel-hint background so the swipe-towards-cancel
    // gesture has a continuous color cue instead of the previous abrupt flip.
    val cancelBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (cancelHinted) danger.copy(alpha = 0.12f) else cs.surfaceVariant,
        label = "rec-cancel-bg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(cancelBg)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(danger.copy(alpha = pulse)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            if (cancelHinted) {
                Text(
                    text = stringResource(R.string.voice_action_cancel),
                    style = MaterialTheme.typography.labelMedium,
                    color = danger,
                    modifier = Modifier.weight(1f),
                )
            } else {
                WaveformLive(
                    history = history,
                    color = cs.primary,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
            }
        }
    }
}

/**
 * Push-to-talk mic button. Driven by a single pointer gesture:
 *
 *  - DOWN  → [onPressStart] (or [onRequestPermission] if RECORD_AUDIO isn't granted yet)
 *  - DRAG  → emits [onCancelHintChanged] when the finger crosses the cancel threshold
 *  - UP    → [onReleaseCancel] if the user was in the cancel zone, [onReleaseStop] otherwise
 *
 * The button visually grows + reddens while recording so the user can see the active state.
 */
@Composable
private fun MicButton(
    isRecording: Boolean,
    cancelHinted: Boolean,
    permissionGranted: Boolean,
    /**
     * Round 48 — this 1:1 thread encrypts: the idle mic circle is
     * the theme blue; in a plain thread it is the plain green
     * (`#3BA803`), like the send circle. Recording/cancel stay red.
     */
    isEncrypted: Boolean = false,
    onRequestPermission: () -> Unit,
    onPressStart: () -> Unit,
    onReleaseStop: () -> Unit,
    onReleaseCancel: () -> Unit,
    onCancelHintChanged: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { CANCEL_THRESHOLD_DP.toPx() }
    val danger = BrandDanger

    // Round 20 — the idle tile is [brandBlue] (fixed brand blue, or the
    // wallpaper `primary` when dynamic colours are on) with [onBrandBlue]
    // content; the recording/cancel states stay the danger red with white.
    // Round 48 — in a plain thread the idle tile is the plain green
    // instead (the same signal as the send circle and the outgoing
    // bubbles); recording/cancel are untouched.
    val buttonColors = resolveComposerButtonColors(
        encrypted = isEncrypted,
        defaultBlue = brandBlue(),
        defaultBluePressed = brandBluePressed(),
        onDefaultBlue = onBrandBlue(),
    )
    val containerColor = when {
        cancelHinted -> danger
        isRecording -> danger
        else -> buttonColors.background
    }
    val contentColor = if (cancelHinted || isRecording) Color.White else buttonColors.content

    val pushToTalk = Modifier.pointerInput(permissionGranted) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!permissionGranted) {
                onRequestPermission()
                down.consume()
                return@awaitEachGesture
            }
            onPressStart()
            down.consume()
            var inCancelZone = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (!change.pressed) {
                    if (inCancelZone) onReleaseCancel() else onReleaseStop()
                    return@awaitEachGesture
                }
                val dragX = change.position.x - down.position.x
                val newInCancelZone = dragX < -cancelThresholdPx
                if (newInCancelZone != inCancelZone) {
                    inCancelZone = newInCancelZone
                    onCancelHintChanged(inCancelZone)
                }
                change.consume()
            }
            @Suppress("UNREACHABLE_CODE") Unit
        }
    }

    // Relay UI refresh: a 48dp circle with Kristin's white mic — the same height
    // as the composer pill, matching the send circle (round 48 — blue when this
    // thread encrypts, the plain green when it does not). While recording it
    // grows to 56dp and turns red, as before.
    // Phase 8 — TalkBack : le geste press-and-hold (`pointerInput`) n'expose aucune
    // action d'accessibilité — un utilisateur TalkBack entendait « Enregistrer un
    // message vocal » sans pouvoir le déclencher. La sémantique ci-dessous donne au
    // bouton un rôle + une description + un `onClick` (tap = démarrer / arrêter,
    // l'équivalent accessibilité du maintien) ; l'activation tactile ne change pas.
    val micDescription = stringResource(
        if (isRecording) R.string.voice_action_stop else R.string.voice_action_record,
    )
    Box(
        modifier = Modifier
            .size(if (isRecording) 56.dp else 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = micDescription
                role = Role.Button
                onClick {
                    if (!permissionGranted) {
                        onRequestPermission()
                    } else if (isRecording) {
                        onReleaseStop()
                    } else {
                        onPressStart()
                    }
                    true
                }
            }
            .then(pushToTalk),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isRecording) 56.dp else 48.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic_outline),
                contentDescription = null,
                tint = contentColor,
                // Round 4 — 24dp in both states (was 20dp idle): the mic glyph now
                // matches the other composer icons and the send arrow at 24dp, so
                // the whole row reads at one visual weight. The tile stays 48dp.
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private val CANCEL_THRESHOLD_DP = 96.dp

/** Preview strip: play/pause, scrubber + duration, discard + send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewingMode(
    durationMs: Long,
    playback: VoicePlaybackController.PlaybackState,
    isSending: Boolean,
    /**
     * Round 48 — this 1:1 thread encrypts: the send tile is the
     * theme blue; in a plain thread it is the plain green
     * (`#3BA803`), like the composer send circle — the clip goes
     * out plain there too.
     */
    isEncrypted: Boolean = false,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Trust the controller's duration when available (the encoded clip's actual duration may
    // differ slightly from the wall-clock recording time we captured in the ViewModel).
    val totalMs = playback.durationMs.takeIf { it > 0 } ?: durationMs.toInt()
    val positionMs = playback.positionMs.coerceAtMost(totalMs)
    val sliderValue by remember(positionMs, totalMs) {
        derivedStateOf { if (totalMs <= 0) 0f else positionMs.toFloat() / totalMs }
    }
    // Round 13 — the play disc and the filled progress share the bright
    // `onSurface` tone (white in dark mode, black in light mode), matching the
    // reference screenshot; the unfilled track is the muted
    // `secondaryContainer` (#3D4758 in dark mode, as measured in the shot).
    val controlColor = cs.onSurface
    // Round 13 — tap/drag-to-seek on the thumbless bar: the same gesture shape
    // as the voice bubbles' waveform (`awaitEachGesture` + `awaitFirstDown`,
    // then a drag loop until the finger lifts).
    val canSeek = !isSending && totalMs > 0
    val seekGesture = Modifier.pointerInput(canSeek, totalMs) {
        if (!canSeek) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onSeek(((down.position.x / size.width).coerceIn(0f, 1f) * totalMs).toInt())
            down.consume()
            var dragging = true
            while (dragging) {
                val event = awaitPointerEvent()
                val move = event.changes.firstOrNull()
                if (move == null || !move.pressed) {
                    dragging = false
                } else {
                    onSeek(((move.position.x / size.width).coerceIn(0f, 1f) * totalMs).toInt())
                    move.consume()
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Round 13 — bright X (was the muted `onSurfaceVariant`).
        IconButton(onClick = onDiscard, enabled = !isSending) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.voice_action_discard),
                tint = cs.onSurface,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surfaceVariant)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Round 13 — Kristin's voice-message play disc (`ic_play_circle`),
            // the same disc the sent voice bubbles use, sized to the reference
            // screenshot: the disc is about two thirds of the 48dp pill height
            // (~32dp, the same proportion as in the voice bubbles), so it sits
            // with breathing room instead of flush. 48dp touch target, 32dp
            // visible disc in both states so the button never grows when
            // playback starts. Playing uses the AudioMessageBubble treatment: a
            // solid disc carrying two pause bars in the pill colour.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !isSending, onClick = onTogglePlay)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                if (playback.isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(controlColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.voice_action_pause),
                            tint = cs.surfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_circle),
                        contentDescription = stringResource(R.string.voice_action_play),
                        tint = controlColor,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Round 13 — thumbless progress bar: a fully-rounded track, bright
            // filled portion + muted `secondaryContainer` remainder. The track
            // clips the filled portion, so the filled/unfilled boundary stays a
            // clean vertical line and the outer ends stay rounded. Tap/drag
            // seeks (same gesture as the voice bubbles' waveform).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(cs.secondaryContainer)
                    .then(seekGesture)
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            sliderValue.coerceIn(0f, 1f),
                            0f..1f,
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(sliderValue.coerceIn(0f, 1f))
                        .height(12.dp)
                        .background(controlColor),
                )
            }
            Spacer(Modifier.width(12.dp))
            // Round 18 — the timer matches the bubble timestamps: the same
            // 12sp [RelayText.BubbleMeta] in the same muted tone, fixed
            // against the message-text scale and the system font scale
            // (see [BubbleTimestamp]). It was `labelLarge` (14sp) in
            // bright `onSurface`, which read bigger and bolder than any
            // other timestamp in the thread.
            FixedTypeSize {
                Text(
                    text = formatDuration(
                        if (positionMs > 0) positionMs.toLong() else totalMs.toLong(),
                    ),
                    style = RelayText.BubbleMeta,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (isSending) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            // Round 18 — fixed brand blue, like the composer send button
            // ([SendCircleButton]): `cs.primary` is the LIGHTENED `#7AA0F5`
            // in the dark scheme, so the tile rendered pale blue with a
            // dark-navy arrow instead of the brand blue with a white arrow
            // Kristin asked for. Round 20 — [brandBlue] / [onBrandBlue] keep
            // that fixed pair when dynamic colours are off, and follow the
            // wallpaper pair when they are on. Round 48 — in a plain
            // thread the tile is the plain green instead, like the
            // composer send circle.
            val buttonColors = resolveComposerButtonColors(
                encrypted = isEncrypted,
                defaultBlue = brandBlue(),
                defaultBluePressed = brandBluePressed(),
                onDefaultBlue = onBrandBlue(),
            )
            FilledIconButton(
                onClick = onSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = buttonColors.background,
                    contentColor = buttonColors.content,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.voice_action_send),
                )
            }
        }
    }
}

/**
 * Live waveform: draws [WAVE_BARS] vertical bars whose heights are derived from the rolling
 * amplitude history. Older samples scroll out on the left, newest on the right.
 */
@Composable
private fun WaveformLive(
    history: List<Int>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (history.isEmpty()) return@Canvas
        drawBars(history, color)
    }
}

private fun DrawScope.drawBars(history: List<Int>, color: Color) {
    val n = WAVE_BARS
    val gap = 2f
    val barWidth = (size.width - gap * (n - 1)) / n
    val maxAmp = 32_767f
    // Pad the front so brand-new recordings start aligned to the right.
    val padding = (n - history.size).coerceAtLeast(0)
    for (i in 0 until n) {
        val sample = if (i < padding) 0 else history[i - padding]
        val norm = (sample.toFloat() / maxAmp).coerceIn(0f, 1f)
        val h = max(4f, norm * size.height)
        val x = i * (barWidth + gap)
        val top = (size.height - h) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(x, top),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}

/** Formats a duration in ms as `m:ss`. */
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private const val WAVE_BARS: Int = 28

// ============================================================================================
//  Thread layout helpers — burst grouping + date dividers
// ============================================================================================

/** Max gap (ms) between two messages of the same sender to count as one burst. */
private const val BURST_GAP_MS: Long = 60_000L

/** Delay before retrying the thread search focus request (see `ThreadScreen`). */
private const val THREAD_SEARCH_FOCUS_RETRY_MS: Long = 100L

/**
 * Feedback round 8 — idle delay before the floating sticky date pill slides
 * away after scrolling stops: the pill floats only while the list is
 * scrolling, then hides a split second after the last scroll movement, so
 * it never sits on the thread while the user is reading.
 */
private const val STICKY_DATE_HIDE_DELAY_MS: Long = 1_000L

/**
 * Feedback round 5 — auto-load older messages when the first visible message
 * is within this many items of the top of the loaded window. 5 gives a
 * 200 ms Room load time to land before the user reaches the top, without
 * firing loads the user never scrolls near.
 */
private const val AUTO_LOAD_TOP_THRESHOLD: Int = 5

/** True when [a] and [b] fall on the same calendar day. */
private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * Feedback round 4 — one timestamp per same-sender run, on its LAST message.
 *
 * The time shows on message M when M is the last message of its same-sender
 * run: the next message in the thread is from a different sender, or M is the
 * last message in the thread. A run of length 1 (senders alternating) shows the
 * time on every message. Waits between messages do NOT split a run — only a
 * reply from the other side does — so texting, waiting, texting again, waiting,
 * texting again (all within a minute, same sender, no reply in between) shows
 * exactly one timestamp, on the last message.
 *
 * Sender here is the direction ([Message.isOutgoing]), the same notion the
 * burst-shape grouping ([computeBurstPosition]) uses: in a 1:1 thread direction
 * IS the sender. (In a group thread, consecutive incoming messages from two
 * different people therefore share one run, exactly as the shape grouping
 * treats them as one burst.)
 */
internal fun isLastOfSenderRun(
    msg: com.filestech.sms.domain.model.Message,
    next: com.filestech.sms.domain.model.Message?,
): Boolean = next == null || next.isOutgoing != msg.isOutgoing

/**
 * Feedback round 4 — the delivery status ("Sent" / "Delivered" / …) is shown
 * independently of the timestamp rule: it belongs on the last outgoing message
 * of a run, so a delivered message can say "Delivered" even when its timestamp
 * is suppressed. (Before, the status label only rendered where the timestamp
 * did, so a burst hid every "Delivered" except on the timestamped message —
 * and the timestamped message is usually the newest, whose delivery report
 * hasn't landed yet, so the thread showed "Sent" and never "Delivered".)
 */
internal fun isLastOutgoingOfRun(
    msg: com.filestech.sms.domain.model.Message,
    next: com.filestech.sms.domain.model.Message?,
): Boolean = msg.isOutgoing && (next == null || !next.isOutgoing)

/**
 * Decides where a message sits inside its burst (a run of consecutive messages from the same
 * sender, within [BURST_GAP_MS] of one another, not crossing a calendar day).
 *
 *  - Solo: stands alone
 *  - First: joined by the next message
 *  - Middle: joined on both sides
 *  - Last: joined by the previous message only
 *
 * The bubble shape function in [MessageBubble] keys off this so a series of bubbles flattens
 * into a single stack visually.
 */
private fun computeBurstPosition(
    prev: com.filestech.sms.domain.model.Message?,
    current: com.filestech.sms.domain.model.Message,
    next: com.filestech.sms.domain.model.Message?,
): BurstPosition {
    val joinsBefore = prev != null &&
        prev.isOutgoing == current.isOutgoing &&
        sameDay(prev.date, current.date) &&
        current.date - prev.date <= BURST_GAP_MS
    val joinsAfter = next != null &&
        next.isOutgoing == current.isOutgoing &&
        sameDay(current.date, next.date) &&
        next.date - current.date <= BURST_GAP_MS
    return when {
        !joinsBefore && !joinsAfter -> BurstPosition.Solo
        !joinsBefore && joinsAfter -> BurstPosition.First
        joinsBefore && joinsAfter -> BurstPosition.Middle
        else -> BurstPosition.Last
    }
}

/**
 * Centered day pill between bursts on the very first message and on every day boundary.
 * Relay UI refresh: a quiet pill on the surface fill, 13sp secondary text, generous
 * vertical air — no dividers above or below.
 */
@Composable
private fun DateSeparator(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DatePill(label = label)
    }
}

/**
 * Feedback round 8 — the floating sticky date pill, extracted so
 * [AnimatedVisibility] resolves to its plain overload: called inline from
 * the thread's `Column` it would resolve to the `ColumnScope` overload
 * (which cannot run inside the overlay `Box`) and fail to compile. The
 * pill slides in from the top while scrolling and slides back up out of
 * view shortly after scrolling stops (see [showStickyDate]).
 */
@Composable
private fun StickyDatePill(
    label: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        label?.let { DatePill(label = it) }
    }
}

/**
 * Feedback round 7 — the day pill itself, shared by the inline [DateSeparator]
 * and the floating sticky date header: one styling, so the two can never drift
 * apart. Quiet pill on the surface fill, 13sp secondary text.
 */
@Composable
private fun DatePill(
    label: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // Phase 8 — le libellé est du méta-texte de mise en page, fixe (voir
        // [FixedTypeSize]), comme l'heure des bulles.
        FixedTypeSize {
            Text(
                text = label,
                style = RelayText.SystemNote,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Snackbar variant — extracted to shared `ui.components.SmsTechSnackbar` in
//  v1.9.0 for re-use by all screens (Settings, Backup, SafetyCallSetup…).
//  Local copies removed ; this file now imports from there.
// ─────────────────────────────────────────────────────────────────────────────

// ============================================================================
// v1.18.0 — Refactor ThreadScreen : extraction du dispatcher LazyColumn item en
// `ThreadMessageItem` private @Composable. Avant : 140 lignes inline avec ~15
// captures locales (state setters + lambdas + VM refs), fonction principale
// `ThreadScreen` atteignait ~850 lignes. Bénéfice : lisibilité + recompositions
// Compose mieux scopées (chaque item ne re-compose que sur changement de SON
// state, pas sur recomposition globale du parent).
// ============================================================================

/**
 * Bundle des callbacks transmis à [ThreadMessageItem]. Annoté `@Stable` : Compose peut
 * skipper la recomposition de l'item quand la référence d'instance ne change pas. Le parent
 * (`ThreadScreen`) crée l'instance UNE SEULE FOIS via `remember(viewModel, snackbarHost, ...)`
 * — donc référence stable sur toute la vie de la composition. Les setters de state Compose
 * (`pendingDelete = msg`, `pickingReactionFor = msg.id`, …) sont capturés au moment de la
 * création des lambdas et restent fonctionnels via le delegate `var by remember`.
 *
 * Pas de `data class` (ByteArray-equivalent : les lambdas n'ont pas d'`equals` content-based,
 * data class générerait un `equals` par référence — fragile). Class simple suffisante.
 */
@androidx.compose.runtime.Stable
private class ThreadMessageItemActions(
    val onTogglePlayback: (com.filestech.sms.domain.model.Attachment) -> Unit,
    // Position en millisecondes ; aligné sur la signature [ThreadViewModel.seekPlaybackTo].
    val onSeekPlayback: (Int) -> Unit,
    /**
     * Round 48 — the two-arg playback toggle (`key`, `uri`) for
     * encrypted voice notes: the key is the attachment's
     * `localUri` (the ciphertext path — the stable identity),
     * the URI is the decrypted playback file. The one-arg
     * toggle couples key + URI from a single attachment and
     * cannot express that split.
     */
    val onTogglePlaybackAt: (String, android.net.Uri) -> Unit,
    /** Tap sur bulle FAILED — l'action interne distingue WATCHDOG_TIMEOUT vs SYNCHRONOUS. */
    val onTapFailed: (Message) -> Unit,
    val onDelete: (Message) -> Unit,
    val onReply: (Message) -> Unit,
    val onReact: (Long) -> Unit,
    /** Round 12 — quick-reaction bar: sends [String] emoji for [Long] message id at once. */
    val onQuickReact: (Long, String?) -> Unit,
    /** Round 21 — badge tap: removes the reaction (`setReaction(id, null)`). */
    val onRemoveReaction: (Long) -> Unit,
    val onForward: (Message) -> Unit,
    val onTranslate: (Message) -> Unit,
    val onCopy: (Message) -> Unit,
    /** Feedback round 5 — bascule « épingler » (un seul message épinglé par fil). */
    val onTogglePin: (Message) -> Unit,
    val onPhoneClick: (String) -> Unit,
    /**
     * E2E — a key-change notice tap: opens the safety-number
     * verification sheet for this thread's peer.
     */
    val onE2eNoticeVerify: () -> Unit,
)

/**
 * Item renderer de la LazyColumn thread. Dispatch sur 3 sous-Composables selon le type :
 * audio → [AudioMessageBubble], image/video/file → [MediaAttachmentBubble], sinon
 * [MessageBubble] texte. Calcule les valeurs dérivées (showTimestamp, burstPosition,
 * senderLabel) localement pour minimiser la surface d'API.
 *
 * **Compose recomposition** : grâce à `@Stable ThreadMessageItemActions`, l'item ne recompose
 * que si :
 *   - le `msg` change (nouvelle instance Message via Room Flow)
 *   - les voisins `prev`/`next` changent (insert/delete devant)
 *   - `playbackProvider` re-évalué (mais c'est une `() -> PlaybackState` lambda stable,
 *     délibérément lambda et pas value pour qu'AudioMessageBubble re-lise via derivedStateOf
 *     uniquement quand SA propre bulle joue — pas toutes les bulles à chaque tick 5 Hz)
 *   - le `daySeparatorLabel` change (rare, dépend du jour de la date du message)
 *
 * Reads localisés à l'instance : `conversationDisplayName`, `bubbleColorArgb`,
 * `smishingReasons`, `simLabel` sont passés comme valeurs ; pas de capture du `state` global au
 * parent. `simLabel` (String `@Stable`) est dérivé de `msg.subId` côté parent — déjà couvert par
 * le trigger `msg`.
 */
@Composable
private fun ThreadMessageItem(
    msg: Message,
    prev: Message?,
    next: Message?,
    daySeparatorLabel: String?,
    conversationDisplayName: String?,
    /** Relay — `true` si le fil est un groupe : seul ce cas montre l'étiquette. */
    isGroup: Boolean = false,
    bubbleColorArgb: Int?,
    smishingReasons: List<com.filestech.sms.domain.smishing.SmishingReason>,
    playbackProvider: () -> com.filestech.sms.data.voice.VoicePlaybackController.PlaybackState,
    repliedToPreview: ReplyQuotePreview?,
    /**
     * v1.22.0 (double SIM) — libellé court de la SIM d'arrivée de ce message (ex. "SIM 2",
     * "POST", nom donné par l'user). `null` sur mono-SIM / permission absente / message sans
     * `sub_id` : aucun tag n'est alors ajouté. Résolu par le parent via [rememberSimLabeler].
     */
    simLabel: String? = null,
    /**
     * Feedback round 5 — thread search highlight for this message's body /
     * caption: every occurrence of the query, and whether the search is
     * currently sitting on this message. Empty by default: no search, no
     * highlight.
     */
    searchHighlight: SearchHighlight = SearchHighlight(),
    /** Feedback round 5 — ce message est l'épinglé du fil (menu « Désépingler »). */
    isPinnedMessage: Boolean = false,
    /** Round 12 — recent reaction emojis for the quick-reaction bar. */
    recentReactions: List<String> = emptyList(),
    actions: ThreadMessageItemActions,
    /**
     * Client mode C2 — the thread is read-only: reply / react /
     * forward / pin / delete are hub-side in C2, so the bubbles get
     * `null` for those actions (null = no menu item, no quick bar)
     * while Copy, Translate, playback and phone-click stay wired.
     */
    isClientMode: Boolean = false,
    /**
     * Client mode C5 — the attachment download states for this
     * thread's bubbles, keyed by local attachment id. Empty in hub
     * mode; the bubbles render as before.
     */
    downloadStates: Map<Long, com.filestech.sms.sync.client.ClientDownloadState> = emptyMap(),
    /**
     * Client mode C5 — asks the downloader for an attachment's bytes
     * (tap-to-download / tap-to-retry). The item gates it on
     * [isClientMode]: null in hub mode, so the bubbles never ask.
     */
    onRequestDownload: ((Long) -> Unit)? = null,
    /**
     * E2E — the notices dated at or before this message, rendered
     * above its bubble like the day separator, with the contact
     * name their wording needs. Null (or empty) for plain threads.
     */
    e2eNotices: E2eNoticesForItem? = null,
) {
    // Taille du texte des messages : tout l'élément (séparateur, bulle, citation, horodatage).
    MessageTextSize {
        // Date separator (calendar day boundary) — affiché au-dessus du 1er msg du jour.
        daySeparatorLabel?.let { label ->
            DateSeparator(label = label)
        }

        // E2E — the notices dated at or before this message
        // (activation / key change), above the bubble like the day
        // separator above them.
        e2eNotices?.let { e2e ->
            for (notice in e2e.notices) {
                E2eNoticeRow(
                    notice = notice,
                    contactName = e2e.contactName,
                    onVerify = actions.onE2eNoticeVerify,
                    memberNames = e2e.memberNames,
                )
            }
        }

        // Feedback round 4 — one timestamp per same-sender run, on its LAST
        // message: the time shows when the next message is from a different
        // sender, or this is the last message in the thread. Waits between
        // messages do NOT split a run — only a reply from the other side does
        // (texting, waiting, texting again, all within a minute = one run, one
        // timestamp, on the last message). Replaces the old 1-minute gap rule
        // (`msg.date - prev.date > 60_000L`), which hid the time on every
        // message sent within a minute of the previous one.
        val showTimestamp = isLastOfSenderRun(msg, next)
        // Feedback round 4 — delivery status, decoupled from the timestamp:
        // it belongs on the last outgoing message of a run, so a delivered
        // message can say "Delivered" even when its timestamp is suppressed.
        // (Before, the status label only rendered where the timestamp did, so
        // a burst hid every "Delivered" except on the timestamped message.)
        val showStatus = isLastOutgoingOfRun(msg, next)
        val burstPosition = computeBurstPosition(prev, msg, next)
        val audio = msg.audioAttachment
        // Feedback round 6 — per-message callbacks, remembered so the bubbles
        // can skip when the parent recomposes. Fresh inline lambdas are new
        // instances on every recomposition, which defeats Compose skipping one
        // level down (the round-5 stable-callbacks fix stopped at
        // [itemActions]). Keyed on the whole `msg` (data-class equals): a new
        // instance with identical content keeps the lambdas, a real content
        // change rebuilds them — so a callback reading `msg.reactionEmoji`
        // never closes over a stale message. The calls stay unconditional (above
        // the `when`), so every branch reuses the same instances.
        val onTogglePlay = remember(msg, actions) {
            {
                // `audio` is non-null wherever this callback is invoked (the
                // audio branch), but it is nullable at this hoisted spot —
                // the `when` smart-cast does not reach here.
                if (audio != null) actions.onTogglePlayback(audio)
            }
        }
        val onDelete = remember(msg, actions) {
            {
                actions.onDelete(msg)
            }
        }
        val onReply = remember(msg, actions) {
            {
                actions.onReply(msg)
            }
        }
        val onReact = remember(msg, actions) {
            {
                actions.onReact(msg.id)
            }
        }
        // Round 12 — quick-reaction bar on tap: recents + the send path,
        // bundled in one parameter for [MessageBubble] (Detekt parameter
        // limit). Round 23 — EVERY message, incoming or outgoing: the old
        // `msg.isIncoming` gate is what made reactions vanish on the
        // user's own messages — the "React" menu item AND the tap bar
        // both disappeared, so it read as intermittent (it depended on
        // which message was tapped). The local reaction is always
        // allowed: the SMS dispatch to the recipient stays gated to
        // incoming messages in the ViewModel (`setReaction` refuses
        // outgoing targets, and `SendReactionUseCase` refuses them too),
        // so reacting to your own message sets the local badge and sends
        // nothing. Round 21 — tapping the emoji already applied removes
        // the reaction (toggle): see [toggleReaction].
        val quickReactions = remember(msg, actions, recentReactions) {
            com.filestech.sms.ui.components.QuickReactionConfig(
                recentEmojis = recentReactions,
                onSelect = { emoji ->
                    actions.onQuickReact(msg.id, toggleReaction(msg.reactionEmoji, emoji))
                },
            )
        }
        val onForward = remember(msg, actions) {
            {
                actions.onForward(msg)
            }
        }
        val onCopy = remember(msg, actions) {
            {
                actions.onCopy(msg)
            }
        }
        val onTranslate = remember(msg, actions) {
            {
                actions.onTranslate(msg)
            }
        }
        val onTapFailed = remember(msg, actions) {
            {
                if (msg.status == Message.Status.FAILED) actions.onTapFailed(msg)
            }
        }
        // Feedback round 5 — menu toggles for this message, built once for
        // the three bubble types below (one bundle parameter instead of
        // two — the text bubble is at the Detekt 20-parameter limit).
        // Round 23 — pin only: the star toggle is gone with the menu's
        // "Star" entry.
        val menuToggles = remember(msg, actions, isPinnedMessage) {
            BubbleMenuToggles(
                isPinned = isPinnedMessage,
                onTogglePin = { actions.onTogglePin(msg) },
                // Round 21 — badge tap removes the reaction. A badge can
                // sit on an outgoing message too (the other side's
                // Tapback, or your own reaction), and tapping it clears
                // the local badge — same as the original wiring.
                onRemoveReaction = { actions.onRemoveReaction(msg.id) },
            )
        }
        // Client mode C3 — the gated actions, one decision for the
        // three bubble types below: `null` hides the menu item (or
        // the quick bar). Only Delete stays gated (no delete frame);
        // Reply, React, Forward and the pin/remove-reaction toggles
        // are wired on a linked device. Copy, Translate, playback
        // and phone-click are not gated — they never write.
        val gatedOnDelete = if (isClientMode) null else onDelete
        val gatedOnReply = onReply
        val gatedOnReact = onReact
        val gatedQuickReactions = quickReactions
        val gatedOnForward = onForward
        val gatedToggles = menuToggles
        // v1.3.3 bug #2 — pour les pièces jointes NON-audio (image/video/file), on délègue à
        // MediaAttachmentBubble qui ajoute le tap-to-view (ACTION_VIEW via FileProvider).
        // v1.28.3 (audit global, X-04 — mesuré sur le S9) — TOUTES, pas la première : F16 gardait
        // toutes les parties d'un MMS, l'écran n'en montrait qu'une.
        // Feedback round 6 — the media filter ran on every composition,
        // allocating a new list per bubble; remembered on the attachments
        // (structural list equals: a re-mapped identical list keeps it).
        val mediaAttachments = remember(msg.attachments) { msg.attachments.filter { !it.isAudio } }
        val mediaAttachment = mediaAttachments.firstOrNull()
        // v1.3.3 #7 — étiquette d'expéditeur uniquement sur la 1ʳᵉ bulle d'un burst (Solo ou First)
        // pour ne pas surcharger visuellement les suites consécutives du même expéditeur.
        // Relay — uniquement dans les groupes : en 1:1, l'expéditeur est évident et
        // l'étiquette ne fait que du bruit. Le suffixe SIM (ci-dessous) est conservé : il ne
        // s'applique que quand une étiquette existe, donc uniquement en groupe.
        val baseSenderLabel: String? = if (
            isGroup && (
                burstPosition == BurstPosition.Solo ||
                burstPosition == BurstPosition.First
            )
        ) {
            if (msg.isOutgoing) {
                stringResource(R.string.bubble_sender_self)
            } else {
                conversationDisplayName?.takeIf { it.isNotBlank() } ?: msg.address
            }
        } else null
        // v1.22.0 (double SIM) — suffixe le libellé SIM au nom de l'expéditeur sur la 1ʳᵉ bulle du
        // burst ("Marie · SIM 2"), ce qui distingue d'un coup d'œil les conversations reçues sur la
        // puce locale vs étrangère. `simLabel` vaut déjà `null` hors multi-SIM (ou message sans
        // `sub_id`), donc les cas mono-SIM et les messages historiques restent strictement inchangés.
        val senderLabel: String? = when {
            baseSenderLabel == null -> null
            simLabel.isNullOrBlank() -> baseSenderLabel
            else -> "$baseSenderLabel · $simLabel"
        }

        when {
            // E2E — the undecryptable-message placeholder: a
            // notice-style row, not a bubble — no lock, no menu,
            // and tapping it does nothing in v1.
            msg.decryptFailed -> {
                E2eDecryptFailedRow()
            }
            // Round 48 — an incoming encrypted-media MMS: the
            // wrapper renders the pending row while any part's
            // key is missing, else the decrypted bubble. BEFORE
            // the audio/media branches: the stored MIME is
            // `application/octet-stream`, so the media branch
            // would otherwise claim it as a generic file. INCOMING
            // only — our own outgoing encrypted media renders
            // from the plaintext original the sender kept.
            msg.isIncoming && msg.type == Message.Type.MMS && msg.encrypted &&
                msg.attachments.any {
                    com.filestech.sms.data.mms.RelayMediaFilename.isEncryptedPart(it.mimeType, it.fileName)
                } -> {
                EncryptedMediaBubble(
                    message = msg,
                    showTimestamp = showTimestamp,
                    showStatus = showStatus,
                    senderLabel = senderLabel,
                    playbackProvider = playbackProvider,
                    repliedToPreview = repliedToPreview,
                    searchHighlight = searchHighlight,
                    onDelete = gatedOnDelete,
                    onReply = gatedOnReply,
                    onReact = gatedOnReact,
                    quickReactions = gatedQuickReactions,
                    onForward = gatedOnForward,
                    toggles = gatedToggles,
                    onCopy = if (msg.body.isNotBlank()) onCopy else null,
                    onPhoneClick = actions.onPhoneClick,
                    onSeekPlayback = actions.onSeekPlayback,
                    onTogglePlaybackAt = actions.onTogglePlaybackAt,
                )
            }
            audio != null -> {
                AudioMessageBubble(
                    message = msg,
                    audio = audio,
                    playbackProvider = playbackProvider,
                    onTogglePlay = onTogglePlay,
                    onSeekTo = actions.onSeekPlayback,
                    onDelete = gatedOnDelete,
                    onReply = gatedOnReply,
                    // Round 23 — "Réagir" on EVERY message: the v1.3.1
                    // incoming-only gate is gone (it made reactions vanish
                    // on outgoing messages — see [quickReactions] above).
                    // The picker's "+" and the menu row both use it.
                    onReact = gatedOnReact,
                    // Round 23 — the menu's top row IS the quick-reaction
                    // row (same bundle as the text bubble).
                    quickReactions = gatedQuickReactions,
                    onForward = gatedOnForward,
                    // Feedback round 5 — bascule « épingler » sur les
                    // trois types de bulle. Round 23 — pin only: the star
                    // toggle is gone with the menu's "Star" entry.
                    toggles = gatedToggles,
                    repliedToPreview = repliedToPreview,
                    showTimestamp = showTimestamp,
                    showStatus = showStatus,
                    senderLabel = senderLabel,
                    // Feedback round 5 — voice messages carry no searchable text
                    // body, so there is nothing to highlight on an audio bubble.
                    // Client mode C5 — the bubble auto-requests its bytes
                    // when they are missing (null in hub mode).
                    onRequestDownload = if (isClientMode) {
                        { onRequestDownload?.invoke(audio.id) }
                    } else {
                        null
                    },
                )
            }
            mediaAttachment != null -> {
                com.filestech.sms.ui.components.MediaAttachmentBubble(
                    message = msg,
                    attachment = mediaAttachment,
                    autres = mediaAttachments.drop(1),
                    showTimestamp = showTimestamp,
                    showStatus = showStatus,
                    onDelete = gatedOnDelete,
                    onReply = gatedOnReply,
                    // Round 23 — "Réagir" on EVERY message: the v1.3.1
                    // incoming-only gate is gone (see [quickReactions]
                    // above). The menu row's "+" uses it.
                    onReact = gatedOnReact,
                    // Round 23 — the menu's top row IS the quick-reaction
                    // row: long-press opens the menu, tap still opens the
                    // viewer. The round-22 long-press quick bar is gone,
                    // replaced by the menu row.
                    quickReactions = gatedQuickReactions,
                    // v1.3.11 (F3) — copy exposed only when the bubble carries a user-typed caption.
                    onCopy = if (msg.body.isNotBlank()) onCopy else null,
                    onForward = gatedOnForward,
                    // Feedback round 5 — bascule « épingler » sur les
                    // trois types de bulle. Round 23 — pin only: the star
                    // toggle is gone with the menu's "Star" entry.
                    toggles = gatedToggles,
                    onPhoneClick = actions.onPhoneClick,
                    repliedToPreview = repliedToPreview,
                    senderLabel = senderLabel,
                    searchHighlight = searchHighlight,
                    // Client mode C5 — the download states + the
                    // tap-to-download callback (null in hub mode, so
                    // the bubbles never ask).
                    downloadStates = downloadStates,
                    onRequestDownload = if (isClientMode) onRequestDownload else null,
                )
            }
            else -> {
                MessageBubble(
                    message = msg,
                    showTimestamp = showTimestamp,
                    showStatus = showStatus,
                    burstPosition = burstPosition,
                    onTap = onTapFailed,
                    onDelete = gatedOnDelete,
                    onReply = gatedOnReply,
                    // v1.7.1 — Translate via ACTION_PROCESS_TEXT system intent. Only wired when
                    // the body is non-blank (nothing to translate on attachment-only messages).
                    onTranslate = if (msg.body.isNotBlank()) onTranslate else null,
                    // Round 23 — "Réagir" on EVERY message: the v1.3.1
                    // incoming-only gate is gone (see [quickReactions]
                    // above). The tap bar's "+" and the menu row's "+"
                    // both use it.
                    onReact = gatedOnReact,
                    // Round 12 — quick-reaction bar on tap (round 23:
                    // every message, not just incoming).
                    quickReactions = gatedQuickReactions,
                    onCopy = if (msg.body.isNotBlank()) onCopy else null,
                    onForward = gatedOnForward,
                    // Feedback round 5 — bascule « épingler » sur les
                    // trois types de bulle. Round 23 — pin only: the star
                    // toggle is gone with the menu's "Star" entry.
                    toggles = gatedToggles,
                    onPhoneClick = actions.onPhoneClick,
                    repliedToPreview = repliedToPreview,
                    senderLabel = senderLabel,
                    customBubbleColorArgb = bubbleColorArgb,
                    smishingReasons = smishingReasons,
                    searchHighlight = searchHighlight,
                )
            }
        }
    }
}
