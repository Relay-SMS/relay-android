package com.filestech.sms.ui.screens.notes

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.ui.components.AppearanceDialog
import com.filestech.sms.ui.components.AttachmentKind
import com.filestech.sms.ui.components.AttachmentPickerSheet
import com.filestech.sms.ui.components.EmojiPickerButton
import com.filestech.sms.ui.components.EmojiPickerPanel
import com.filestech.sms.ui.components.EmojiPickerTab
import com.filestech.sms.ui.components.EmojiPickerTabStore
import com.filestech.sms.ui.components.PendingAttachmentChipData
import com.filestech.sms.ui.components.PendingAttachmentsBar
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayEmptyState
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.SmsTechSnackbarVisuals
import com.filestech.sms.ui.components.stickerSendUri
import com.filestech.sms.ui.screens.details.NoteDetailsPanel
import com.filestech.sms.ui.security.copyToClipboardSensitive
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.util.daySeparatorLabel
import com.filestech.sms.ui.util.rememberChatFormatters
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Notes — one note's thread: the thread screen's shape (64dp header,
 * day separators, bounded window + auto load-older with anchor
 * restore, composer + pending chips + attachment sheet + camera,
 * details panel, PDF export) with the note's own rules:
 *
 *   - header: back arrow, note avatar (tappable → panel), title,
 *     ⋮ → panel. NO call icon, NO search magnifier in v1;
 *   - entries render through [NoteMessageItem] (delete only — no
 *     reply, reactions, forward, or retry);
 *   - the composer is [NoteComposerBar] (no schedule; tap-to-record
 *     voice) with the thread's emoji/GIF/sticker picker panel below
 *     it (round 36 — a GIF or sticker stages through the note
 *     attachment path, an emoji appends to the draft);
 *   - the screen pops when the note disappears (deleted from the
 *     list, or from its own panel).
 *
 * Round 36 — the header and the composer live in the Scaffold's
 * `topBar`/`bottomBar` slots (the thread's shape), NOT inside the
 * content: the content padding already carries the status bar (top)
 * and the navigation bar (bottom), so a header with its own
 * `statusBarsPadding` — or a bottom bar with its own
 * `navigationBarsPadding` — inside the content is padded TWICE
 * (the round-34 header gap + the floating composer).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NoteThreadScreen(
    onBack: () -> Unit,
    viewModel: NoteThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Round 34 — CI lint (LocalContextGetResourceValueCall) : les messages
    // lus dans les lambdas (collect d'evenements, ouverture de piece
    // jointe) sont resolus a la composition, comme dans BackupScreen.
    val exportSuccessFmt = stringResource(R.string.thread_export_success)
    val openFailedMsg = stringResource(R.string.note_open_failed)

    // The note is gone (deleted from the list while this thread was
    // open, or from its own panel): pop. Gated on `isLoading` so the
    // first paint — note still null — never pops.
    LaunchedEffect(state.note, state.isLoading) {
        if (!state.isLoading && state.note == null) onBack()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is NoteThreadViewModel.Event.ShowSnackbar ->
                    snackbarHost.showSnackbar(
                        SmsTechSnackbarVisuals(message = e.message, isError = e.isError),
                    )

                is NoteThreadViewModel.Event.PdfReady -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(e.shareUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(shareIntent, "PDF")) }
                    snackbarHost.showSnackbar(exportSuccessFmt.format(e.pages))
                }
                // The state-based pop above handles the exit; the
                // event exists for the ViewModel test, not the screen.
                NoteThreadViewModel.Event.NoteDeleted -> Unit
            }
        }
    }

    val listState = rememberLazyListState()
    val messages = state.messages
    // Day labels, precomputed outside the item lambda (the thread's
    // PERF-04 pattern: one pass over the messages, not 3 Calendar
    // allocations per visible item per recomposition).
    val formatters = rememberChatFormatters()
    val todayLabel = stringResource(R.string.date_today)
    val yesterdayLabel = stringResource(R.string.date_yesterday)
    val daySeparatorLabels: List<String?> = remember(messages, todayLabel, yesterdayLabel) {
        messages.mapIndexed { index, msg ->
            val prev = messages.getOrNull(index - 1)
            if (prev == null || !sameDay(prev.date, msg.date)) {
                formatters.daySeparatorLabel(
                    timestampMillis = msg.date,
                    todayLabel = todayLabel,
                    yesterdayLabel = yesterdayLabel,
                )
            } else {
                null
            }
        }
    }

    // — Scroll bookkeeping (the thread's shape, simplified: no search
    // anchor, no sticky date pill) —
    //
    // Round 36 — the picker state (see [rememberNotePickerState]):
    // the scroll effects need to know when the panel opens (the
    // viewport shrinks and the tail slides behind it), and the
    // top-bar height is measured for the panel's full-height stop.
    val picker = rememberNotePickerState()
    var topBarHeightPx by remember { mutableStateOf(0) }
    val listSettled = NoteThreadScrollEffects(
        listState = listState,
        messages = messages,
        hasMore = state.hasMore,
        isLoadingOlder = state.isLoadingOlder,
        onLoadOlder = viewModel::loadOlder,
        pickerOpen = picker.isOpen,
    )

    // — Composer + attachments —

    var attachmentSheetOpen by remember { mutableStateOf(false) }
    var pendingCameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            pendingCameraPhotoUri?.let { viewModel.onAttachmentPicked(it, AttachmentKind.PHOTO) }
        }
        pendingCameraPhotoUri = null
    }
    // The camera opens directly (TakePicture), not the sheet — the
    // thread's round-4 rule. The capture file lives in
    // `cacheDir/mms_outgoing/` (a declared FileProvider cache-path),
    // so the system camera app can write to it. No CAMERA permission
    // is needed — the external camera app does the capture. If the
    // file or URI fails, fall back to the sheet.
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

    // — Voice permission (Accompanist, like the thread) —
    //
    // Unlike the thread's press-and-hold (which never auto-starts on
    // grant), the note mic is tap-to-record: granting after a tap
    // starts the recording the tap asked for.
    val voicePermissionDenied = stringResource(R.string.voice_permission_denied)
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            scope.launch {
                snackbarHost.showSnackbar(
                    SmsTechSnackbarVisuals(message = voicePermissionDenied, isError = true),
                )
            }
        }
    }
    val micPermissionGranted = micPermission.status == PermissionStatus.Granted

    // — Dialogs / panel —

    // The rename/appearance/delete-note states live in [NoteThreadDialogs]
    // (only the panel opens them); these three stay hoisted here because
    // the header, the message list, and the composer open them.
    var detailsPanelOpen by remember { mutableStateOf(false) }
    var pendingDeleteMessage by remember { mutableStateOf<NoteMessage?>(null) }
    var pendingEditMessage by remember { mutableStateOf<NoteMessage?>(null) }

    val note = state.note
    val openAttachment = rememberNoteAttachmentOpener(
        resolver = viewModel.resolver,
        onRegisterPlaybackFile = viewModel::registerExternalPlaybackFile,
        onOpenFailed = {
            scope.launch {
                snackbarHost.showSnackbar(
                    SmsTechSnackbarVisuals(
                        message = openFailedMsg,
                        isError = true,
                    ),
                )
            }
        },
    )
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
        topBar = {
            // Round 36 — the header lives in the `topBar` slot (the
            // thread's shape): the slot does NOT add the status-bar
            // inset itself, so the header's own `statusBarsPadding`
            // applies it exactly once. Measured so the picker panel
            // can stop at its bottom edge.
            if (note != null) {
                Box(modifier = Modifier.onSizeChanged { topBarHeightPx = it.height }) {
                    NoteThreadHeader(
                        title = note.title,
                        onBack = onBack,
                        onDetails = { detailsPanelOpen = true },
                    )
                }
            }
        },
        bottomBar = {
            NoteThreadBottomBar(
                state = state,
                viewModel = viewModel,
                permissionGranted = micPermissionGranted,
                onRequestPermission = { micPermission.launchPermissionRequest() },
                onAttachClick = { attachmentSheetOpen = true },
                onCameraClick = onCameraClick,
                picker = picker,
                topBarHeightPx = topBarHeightPx,
            )
        },
    ) { padding ->
        // Round 36 — the content is the list only: the Scaffold
        // padding already accounts for the top and bottom bars, so
        // nothing here adds its own system-bar padding (the
        // round-34 double insets lived here).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (messages.isEmpty() && !state.isLoading) {
                RelayEmptyState(
                    iconRes = R.drawable.ic_note,
                    headline = stringResource(R.string.note_empty_title),
                    body = stringResource(R.string.note_empty_body),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (listSettled) 1f else 0f),
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { _, msg -> msg.id },
                        contentType = { _, _ -> "note-message" },
                    ) { index, msg ->
                        NoteMessageItem(
                            noteMessage = msg,
                            prev = messages.getOrNull(index - 1),
                            next = messages.getOrNull(index + 1),
                            daySeparatorLabel = daySeparatorLabels.getOrNull(index),
                            resolver = viewModel.resolver,
                            playbackProvider = { playbackState },
                            onTogglePlayback = viewModel::togglePlayback,
                            onSeekPlaybackTo = viewModel::seekPlaybackTo,
                            onOpenAttachment = openAttachment,
                            customBubbleColorArgb = note?.bubbleColorArgb,
                            onDelete = { pendingDeleteMessage = msg },
                            onCopy = if (msg.body.isNotBlank()) {
                                {
                                    context.copyToClipboardSensitive(
                                        label = "message",
                                        text = msg.body,
                                    )
                                }
                            } else {
                                null
                            },
                            onTranslate = if (msg.body.isNotBlank()) {
                                {
                                    val body = msg.body.trim()
                                    val intent = Intent(Intent.ACTION_PROCESS_TEXT)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_PROCESS_TEXT, body)
                                        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    context.startActivity(Intent.createChooser(intent, null))
                                }
                            } else {
                                null
                            },
                            // Round 36 phase 2 — editing is text-only
                            // entries only (a media entry's caption is
                            // not editable this round).
                            onEdit = if (msg.attachments.isEmpty() && msg.body.isNotBlank()) {
                                { pendingEditMessage = msg }
                            } else {
                                null
                            },
                            downloadStates = state.noteDownloads,
                            onRequestDownload = if (state.isClientMode) {
                                { id -> viewModel.requestNoteDownload(id) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    NoteThreadDialogs(
        attachmentSheetOpen = attachmentSheetOpen,
        onAttachmentSheetOpenChange = { attachmentSheetOpen = it },
        pendingDeleteMessage = pendingDeleteMessage,
        onPendingDeleteMessageChange = { pendingDeleteMessage = it },
        pendingEditMessage = pendingEditMessage,
        onPendingEditMessageChange = { pendingEditMessage = it },
        detailsPanelOpen = detailsPanelOpen,
        onDetailsPanelOpenChange = { detailsPanelOpen = it },
        note = note,
        panelAttachments = state.panelAttachments,
        canExport = state.canExport,
        isExporting = state.isExporting,
        viewModel = viewModel,
        onOpenAttachment = openAttachment,
    )
}

/**
 * Notes — the thread's scroll bookkeeping, extracted from [NoteThreadScreen]
 * so the screen stays under the Detekt long-method and complexity limits.
 * Returns whether the list has settled at its initial position (the first
 * paint stays hidden until the jump to the bottom lands).
 *
 * The shape is the thread's, simplified: no search anchor, no sticky date
 * pill. A new entry scrolls into view only while the user sits at the
 * bottom; scrolling up auto-loads older history with an anchor restore
 * (a 200-item prepend exceeds Compose's ~130-item key re-anchor window,
 * so without it the user would teleport 200 entries back). Opening the
 * picker panel ([pickerOpen]) scrolls back to the bottom when the user
 * was at the tail — the panel shrinks the viewport from the bottom and
 * the newest entries would slide behind it (the thread's round-13 fix).
 */
@Composable
private fun NoteThreadScrollEffects(
    listState: LazyListState,
    messages: List<NoteMessage>,
    hasMore: Boolean,
    isLoadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    pickerOpen: Boolean = false,
): Boolean {
    var initialScrollDone by remember { mutableStateOf(false) }
    val listSettled = initialScrollDone || messages.isEmpty()
    // First paint sits at the bottom (the newest entry), hidden until
    // the jump lands so the list never flashes the top first.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !initialScrollDone) {
            initialScrollDone = true
            listState.scrollToItem(messages.lastIndex)
        }
    }
    // A new entry scrolls into view only while the user sits at the
    // bottom — reading older history must not yank the list.
    val pinnedToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && last.index >= info.totalItemsCount - 1
        }
    }
    val lastMessageId = messages.lastOrNull()?.id
    LaunchedEffect(lastMessageId) {
        if (initialScrollDone && pinnedToBottom && lastMessageId != null) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    // Round 36 — the picker (the thread's round-13 fix): opening it
    // shrinks the viewport from the bottom as the panel grows (300ms
    // enter animation), sliding the newest entries behind the panel.
    // If the user was at the tail when it opened, scroll to the
    // bottom once the panel has finished growing; a user scrolled up
    // reading older entries is left alone.
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen) return@LaunchedEffect
        val wasAtBottom = pinnedToBottom
        delay(320)
        if (wasAtBottom && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    // Auto load-older on scroll-up (the thread's feedback-round-5
    // replacement for the "Load older messages" button): when the
    // first visible item is within 5 of the top and older history
    // exists, the next page loads by itself. The anchor (the message
    // on screen + its pixel offset) restores the view when the older
    // page lands above.
    var pendingAnchor by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val nearTop by remember {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            first != null && first.index <= NOTE_AUTO_LOAD_TOP_THRESHOLD
        }
    }
    LaunchedEffect(nearTop, hasMore, isLoadingOlder, listSettled) {
        val canLoad = nearTop && hasMore && listSettled
        if (!canLoad || isLoadingOlder) return@LaunchedEffect
        val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            ?: return@LaunchedEffect
        val anchorMsg = messages.getOrNull(firstVisible.index) ?: return@LaunchedEffect
        pendingAnchor = anchorMsg.id to firstVisible.offset
        onLoadOlder()
    }
    val oldestMessageId = messages.firstOrNull()?.id
    LaunchedEffect(oldestMessageId) {
        val (anchorId, anchorOffset) = pendingAnchor ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == anchorId }
        if (index >= 0) {
            listState.scrollToItem(index, -anchorOffset)
            pendingAnchor = null
        }
    }
    return listSettled
}

/**
 * Notes — the thread's dialogs and details panel, extracted from
 * [NoteThreadScreen] so the screen stays under the Detekt long-method
 * and complexity limits. The rename/appearance/delete-note states live
 * here (only the panel opens them); the sheet, delete-message, and
 * panel states stay hoisted in the screen (the composer, the message
 * list, and the header open them).
 */
@Composable
private fun NoteThreadDialogs(
    attachmentSheetOpen: Boolean,
    onAttachmentSheetOpenChange: (Boolean) -> Unit,
    pendingDeleteMessage: NoteMessage?,
    onPendingDeleteMessageChange: (NoteMessage?) -> Unit,
    pendingEditMessage: NoteMessage?,
    onPendingEditMessageChange: (NoteMessage?) -> Unit,
    detailsPanelOpen: Boolean,
    onDetailsPanelOpenChange: (Boolean) -> Unit,
    note: Note?,
    panelAttachments: List<Attachment>,
    canExport: Boolean,
    isExporting: Boolean,
    viewModel: NoteThreadViewModel,
    onOpenAttachment: (Attachment) -> Unit,
) {
    var renameOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var pendingDeleteNote by remember { mutableStateOf(false) }

    if (attachmentSheetOpen) {
        AttachmentPickerSheet(
            onDismiss = { onAttachmentSheetOpenChange(false) },
            onAttachmentPicked = viewModel::onAttachmentPicked,
            subtitle = stringResource(R.string.note_attach_sheet_subtitle),
        )
    }

    if (pendingDeleteMessage != null) {
        RelayDialog(
            onDismissRequest = { onPendingDeleteMessageChange(null) },
            title = stringResource(R.string.note_delete_message_title),
            content = {
                Text(
                    text = stringResource(R.string.note_delete_message_body),
                    style = RelayText.MessageBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        pendingDeleteMessage.let { viewModel.deleteMessage(it.id) }
                        onPendingDeleteMessageChange(null)
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { onPendingDeleteMessageChange(null) },
                )
            },
        )
    }

    // Round 36 phase 2 — edit a text entry (the dialog disables
    // Save while the text is blank or unchanged).
    if (pendingEditMessage != null) {
        EditNoteMessageDialog(
            current = pendingEditMessage.body,
            onDismiss = { onPendingEditMessageChange(null) },
            onConfirm = { newBody ->
                pendingEditMessage.let { viewModel.editMessage(it.id, newBody) }
                onPendingEditMessageChange(null)
            },
        )
    }

    if (pendingDeleteNote && note != null) {
        RelayDialog(
            onDismissRequest = { pendingDeleteNote = false },
            title = stringResource(R.string.note_delete_title),
            content = {
                Text(
                    text = stringResource(R.string.note_delete_body),
                    style = RelayText.MessageBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        pendingDeleteNote = false
                        viewModel.deleteNote()
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pendingDeleteNote = false },
                )
            },
        )
    }

    if (renameOpen && note != null) {
        RenameNoteDialog(
            current = note.title,
            onDismiss = { renameOpen = false },
            onConfirm = { title ->
                renameOpen = false
                viewModel.renameNote(title)
            },
        )
    }

    if (appearanceOpen && note != null) {
        AppearanceDialog(
            currentBubbleColorArgb = note.bubbleColorArgb,
            onDismiss = { appearanceOpen = false },
            onConfirm = { colorArgb ->
                appearanceOpen = false
                viewModel.setBubbleColor(colorArgb)
            },
        )
    }

    if (detailsPanelOpen && note != null) {
        NoteDetailsPanel(
            note = note,
            attachments = panelAttachments,
            resolver = viewModel.resolver,
            canExport = canExport,
            isExporting = isExporting,
            onRename = {
                onDetailsPanelOpenChange(false)
                renameOpen = true
            },
            onExportPdf = { viewModel.exportToPdf() },
            onChatColors = {
                onDetailsPanelOpenChange(false)
                appearanceOpen = true
            },
            onDelete = {
                onDetailsPanelOpenChange(false)
                pendingDeleteNote = true
            },
            onDismiss = { onDetailsPanelOpenChange(false) },
            onOpenAttachment = onOpenAttachment,
            saveCopy = { attachment, targetUri ->
                viewModel.saveAttachmentCopy(attachment, targetUri)
            },
        )
    }
}

/**
 * Notes — the bottom bar, extracted from [NoteThreadScreen] so the
 * screen stays under the Detekt long-method limit: pending chips
 * over the composer, and — when open — the emoji/GIF/sticker
 * picker panel BELOW the composer (the thread's round-14 shape, so
 * picked emojis land in the draft box the user can see).
 *
 * The keyboard lifts the whole bar as one unit (the thread's
 * round-20 rule — `imePadding` lives on the outer Column, not on
 * the composer). The navigation-bar padding lives on the inner
 * Column and drops while the panel is open — the panel carries it
 * (the thread's round-14 rule). The slot itself adds no insets,
 * so the bar is padded exactly once (round 36 — the round-34
 * floating composer was the content padding + this padding).
 *
 * A GIF or sticker tap stages the file through the note
 * attachment path (`onAttachmentPicked`, the same one the attach
 * button uses) and closes the panel — the staged chip sits in the
 * composer above, and Save stores the ORIGINAL file (design §5.3:
 * no MMS budget, no compression). An emoji tap appends to the
 * draft and leaves the panel open for more picks.
 */
@Composable
private fun NoteThreadBottomBar(
    state: NoteThreadViewModel.UiState,
    viewModel: NoteThreadViewModel,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit,
    picker: NotePickerState,
    topBarHeightPx: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var abovePanelHeightPx by remember { mutableStateOf(0) }
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val onEmojiSelected: (String) -> Unit = { emoji ->
        picker.onTabSelected(EmojiPickerTab.EMOJI)
        viewModel.updateDraft(state.draft + emoji)
    }
    val onStickerSelected: (String) -> Unit = { unicode ->
        picker.onTabSelected(EmojiPickerTab.STICKER)
        scope.launch {
            stickerSendUri(context, unicode)?.let { uri ->
                viewModel.onAttachmentPicked(uri, AttachmentKind.PHOTO)
            }
        }
        picker.onClose()
    }
    val onGifSelected: (String) -> Unit = { uri ->
        picker.onTabSelected(EmojiPickerTab.GIF)
        viewModel.onAttachmentPicked(Uri.parse(uri), AttachmentKind.PHOTO)
        picker.onClose()
    }
    Column(modifier = Modifier.imePadding()) {
        Column(
            modifier = Modifier
                .onSizeChanged { abovePanelHeightPx = it.height }
                .then(if (picker.isOpen) Modifier else Modifier.navigationBarsPadding()),
        ) {
            PendingAttachmentsBar(
                pending = state.pendingAttachments.map { staged ->
                    PendingAttachmentChipData(
                        id = staged.path,
                        file = File(staged.path),
                        mimeType = staged.mimeType,
                        displayName = staged.fileName ?: "",
                    )
                },
                onRemove = viewModel::removePendingAttachment,
            )
            val recording = state.voiceState as? NoteThreadViewModel.VoiceState.Recording
            NoteComposerBar(
                draft = state.draft,
                onDraftChanged = viewModel::updateDraft,
                hasPendingAttachments = state.pendingAttachments.isNotEmpty(),
                isSaving = state.isSaving,
                isRecording = recording != null,
                recordingElapsedMs = recording?.elapsedMs ?: 0L,
                permissionGranted = permissionGranted,
                onRequestPermission = onRequestPermission,
                onSave = viewModel::save,
                onMicClick = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onCancelRecording = viewModel::cancelRecording,
                onAttachClick = onAttachClick,
                onCameraClick = onCameraClick,
                emojiPickerButton = EmojiPickerButton(
                    iconRes = picker.lastTab.iconRes,
                    onClick = picker.onToggle,
                ),
                onTextFieldFocused = picker.onClose,
            )
        }
        if (picker.isOpen) {
            EmojiPickerPanel(
                selectedTab = picker.tab,
                onTabSelected = picker.onTabSelected,
                onClose = picker.onClose,
                onEmojiSelected = onEmojiSelected,
                onStickerSelected = onStickerSelected,
                onGifSelected = onGifSelected,
                reservedTopPx = abovePanelHeightPx.toFloat(),
                reservedHeaderPx = topBarHeightPx.toFloat(),
                reservedBottomPx = imeBottomPx.toFloat(),
            )
        }
    }
}

/**
 * Notes — the emoji/GIF/sticker picker's state, bundled so the
 * screen stays under the Detekt long-method limit: the last-used
 * tab (persisted across restarts in the shared
 * [EmojiPickerTabStore] — the same store the thread uses, so the
 * tab follows the user between screens), the open/tab state, and
 * the toggle/select/close actions (the thread's round 13–14
 * shape).
 */
private class NotePickerState(
    val lastTab: EmojiPickerTab,
    val isOpen: Boolean,
    val tab: EmojiPickerTab,
    val onTabSelected: (EmojiPickerTab) -> Unit,
    val onToggle: () -> Unit,
    val onClose: () -> Unit,
)

/** Creates the picker's state (see [NotePickerState]). */
@Composable
private fun rememberNotePickerState(): NotePickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val pickerTabStore = remember(context) { EmojiPickerTabStore(context) }
    val lastPickerTab by pickerTabStore.tab.collectAsStateWithLifecycle(
        initialValue = EmojiPickerTab.EMOJI,
    )
    var emojiPickerOpen by remember { mutableStateOf(false) }
    var emojiPickerTab by remember { mutableStateOf(EmojiPickerTab.EMOJI) }
    val onPickerTabSelected: (EmojiPickerTab) -> Unit = { tab ->
        emojiPickerTab = tab
        scope.launch { pickerTabStore.setTab(tab) }
    }
    // The composer icon toggles the panel (the thread's round 14
    // shape): opening hides the keyboard and opens on the
    // last-used tab; a second tap closes it again.
    val onToggle: () -> Unit = {
        if (emojiPickerOpen) {
            emojiPickerOpen = false
        } else {
            emojiPickerTab = lastPickerTab
            keyboardController?.hide()
            focusManager.clearFocus()
            emojiPickerOpen = true
        }
    }
    val onClose: () -> Unit = { emojiPickerOpen = false }
    return NotePickerState(
        lastTab = lastPickerTab,
        isOpen = emojiPickerOpen,
        tab = emojiPickerTab,
        onTabSelected = onPickerTabSelected,
        onToggle = onToggle,
        onClose = onClose,
    )
}

/** Auto-load older entries when the first visible item is within this many of the top. */
private const val NOTE_AUTO_LOAD_TOP_THRESHOLD: Int = 5

/**
 * Notes — the thread header, mirroring `ThreadHeader` (64dp, back
 * arrow, 37dp avatar + title tappable → panel, ⋮ → panel) with NO
 * call icon and NO search magnifier in v1.
 */
@Composable
private fun NoteThreadHeader(
    title: String,
    onBack: () -> Unit,
    onDetails: () -> Unit,
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
                    size = 37.dp,
                    isNote = true,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = title,
                    style = RelayText.ThreadTitle,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDetails)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // The thread's ⋮ (`ic_more_vert_tall`, 19.4dp tall —
                    // Material MoreVert drew at 16dp).
                    painter = painterResource(R.drawable.ic_more_vert_tall),
                    contentDescription = stringResource(R.string.action_more),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
