// The file is named for the panel composable; its actions data class lives
// here too, so the file-name rule is suppressed for the whole file.
@file:Suppress("MatchingDeclarationName")

package com.filestech.sms.ui.screens.details

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.sync.client.ClientDownloadState
import com.filestech.sms.ui.components.GroupMemberAvatar
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.RelayGroupAvatar
import com.filestech.sms.ui.components.RelaySettingRow
import com.filestech.sms.ui.components.RelaySettingsGroup
import com.filestech.sms.ui.components.RelaySwitchRow
import com.filestech.sms.ui.components.rememberAttachmentOpener
import com.filestech.sms.ui.screens.media.MediaGalleryViewModel
import com.filestech.sms.ui.screens.media.MediaTile
import com.filestech.sms.ui.screens.notes.NoteMediaTile
import com.filestech.sms.ui.theme.RelayText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The actions the details side panel can trigger. Everything is owned by
 * the thread screen — the panel only renders rows and forwards taps.
 * A `null` action hides its row: delete and rename have no frame on a
 * linked device, and the vault row must not exist in PanicDecoy (the
 * vault must not be named there).
 */
data class ConversationDetailsActions(
    val onSchedule: () -> Unit,
    val onExportPdf: () -> Unit,
    val onChatColors: () -> Unit,
    val onToggleMute: () -> Unit,
    val onBlock: () -> Unit,
    val onDelete: (() -> Unit)?,
    val onRename: (() -> Unit)?,
    val onCreateContact: () -> Unit,
    val onAddContact: () -> Unit,
    /**
     * Round 42 — "Edit contact": opens the system editor on the
     * thread's saved contact (`ACTION_EDIT` + contact URI). Null
     * falls back to [onAddContact] (the old selector flow) — the
     * row never goes dead.
     */
    val onEditContact: (() -> Unit)? = null,
    /**
     * Group member tap — the ViewModel looks the number up, then
     * opens the contact to edit it if saved, or a blank form
     * prefilled with the number if not.
     */
    val onMemberClick: (String) -> Unit,
    val onMoveVault: (() -> Unit)?,
    /** Sets the conversation avatar (`null` = back to the default). */
    val onSetAvatar: (String?) -> Unit,
    /**
     * Round 45 — E2E manual probe retry via regular SMS: the
     * fallback handshake for a peer whose data SMS never arrives.
     * Null hides the "Retry via SMS" row in the encryption box's
     * expanded area. (The data-SMS retry stays a panel param —
     * the bundle only carries the new callback because the panel
     * is at Detekt's 20-parameter ceiling.)
     */
    val onRetryE2eProbeViaSms: (() -> Unit)? = null,
)

private enum class DetailsMediaTab { Photos, Videos, Files }

/**
 * The conversation details side panel — the desktop detail pane, on
 * mobile. Slides in from the right over the thread (300ms, the app's
 * animation language) with a scrim; back, the scrim, or the panel's
 * own back arrow closes it with the same slide back out.
 *
 * Phone: full width. Tablet (screen width 600dp and up): a fixed
 * 315dp panel like the desktop pane — a full-width panel would
 * stretch the tablet layout and look wrong.
 *
 * Content, per Kristin's mockup: big avatar with a camera badge
 * (tap = change the conversation photo; 1:1 only — a group has no
 * single photo to change), name and number, the action rows
 * (Schedule / Export to PDF / Chat colors / Mute / Block / Delete,
 * plus Add to contacts on a 1:1 without a saved contact, Rename on
 * a group, and Move to/out of the vault), the group members, and
 * the Photos / Videos / Files tabs grouped by date, like the
 * desktop pane.
 *
 * The panel is a [Dialog] (full-screen window, no platform width),
 * not a re-layout of the thread: the thread keeps its Scaffold and
 * the panel overlays it, so opening it is one state flip in the
 * thread screen. The exit animation is managed here — [onDismiss]
 * fires only after the 300ms slide-out, so the dialog stays mounted
 * long enough for [AnimatedVisibility] to play it.
 *
 * Media comes from [MediaGalleryViewModel] (same ViewModel as the
 * gallery screen, scoped to this thread's back-stack entry — the
 * entry's SavedStateHandle carries `conversationId`, like
 * ThreadViewModel's). Photos and videos reuse its [MediaTile];
 * files get their own rows below.
 *
 * Notes — the note variant ([NoteDetailsPanel]) shares the shell
 * ([DetailsPanelShell]) and the media-tab helpers, with its own
 * content: the note avatar (no camera badge), the note actions
 * (Rename / Export to PDF / Chat colors / Delete — no Schedule,
 * Mute, Block, contacts, Vault, or Members), and the note's
 * attachments through [NoteMediaTile].
 */
@Composable
fun ConversationDetailsPanel(
    conversation: Conversation,
    title: String,
    subtitleNumber: String?,
    contactPhotoUri: String?,
    isUnknownContact: Boolean,
    memberNames: Map<String, String>,
    /** Group member contact photos by raw address (round 33). */
    memberPhotos: Map<String, String>,
    /** 1:1 thread, no saved contact, hub mode — the Add to contacts row. */
    showAddToContacts: Boolean,
    /** 1:1 thread, saved contact, hub mode — the Edit contact row. */
    showEditContact: Boolean,
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
    onDismiss: () -> Unit,
    mediaViewModel: MediaGalleryViewModel = hiltViewModel(),
    /**
     * E2E — this 1:1 peer's encryption state; null = no encryption
     * row (groups, and client mode — a linked device holds no
     * identity keys, so it never probes or shows its own state).
     */
    e2ePeerState: E2ePeerState? = null,
    /** E2E — the peer is marked verified (local flag). */
    e2eVerified: Boolean = false,
    /** E2E — opens the safety-number sheet for this thread's peer. */
    onOpenE2eVerification: (() -> Unit)? = null,
    /** E2E — manual probe retry (round 41): sends one probe now. */
    onRetryE2eProbe: (() -> Unit)? = null,
) {
    DetailsPanelShell(onDismiss = onDismiss) { onClose ->
        DetailsPanelContent(
            conversation = conversation,
            title = title,
            subtitleNumber = subtitleNumber,
            contactPhotoUri = contactPhotoUri,
            isUnknownContact = isUnknownContact,
            memberNames = memberNames,
            memberPhotos = memberPhotos,
            showAddToContacts = showAddToContacts,
            showEditContact = showEditContact,
            canSchedule = canSchedule,
            canExport = canExport,
            isExporting = isExporting,
            actions = actions,
            onClose = onClose,
            mediaViewModel = mediaViewModel,
            e2ePeerState = e2ePeerState,
            e2eVerified = e2eVerified,
            onOpenE2eVerification = onOpenE2eVerification,
            onRetryE2eProbe = onRetryE2eProbe,
        )
    }
}

/**
 * Notes — the note details side panel: the conversation panel's
 * shell and media tabs, with the note's content (see
 * [NoteDetailsPanelContent]). The attachments come from the note
 * thread's ViewModel (already paired with their message dates), and
 * opens + save-a-copy go through the note opener and the
 * decrypt-to-stream save — the panel never touches ciphertext.
 */
@Composable
fun NoteDetailsPanel(
    note: Note,
    attachments: List<Attachment>,
    resolver: NoteAttachmentResolver,
    canExport: Boolean,
    isExporting: Boolean,
    onRename: () -> Unit,
    onExportPdf: () -> Unit,
    onChatColors: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    /** Decrypts a copy of [Attachment] to a user-picked SAF location. */
    saveCopy: suspend (Attachment, Uri) -> Boolean,
) {
    DetailsPanelShell(onDismiss = onDismiss) { onClose ->
        NoteDetailsPanelContent(
            note = note,
            attachments = attachments,
            resolver = resolver,
            canExport = canExport,
            isExporting = isExporting,
            onRename = onRename,
            onExportPdf = onExportPdf,
            onChatColors = onChatColors,
            onDelete = onDelete,
            onClose = onClose,
            onOpenAttachment = onOpenAttachment,
            saveCopy = saveCopy,
        )
    }
}

/**
 * The shared panel shell: the [Dialog], the scrim, and the 300ms
 * slide-in from the right / slide-out. The dialog unmounts only
 * after the slide-out finishes ([onDismiss] fires after the delay),
 * so the exit animation always plays. The content gets [onClose]
 * (back arrow, scrim tap, or any in-panel close).
 */
@Composable
private fun DetailsPanelShell(
    onDismiss: () -> Unit,
    content: @Composable (onClose: () -> Unit) -> Unit,
) {
    // AnimatedVisibility skips its enter animation when it starts
    // visible, so the panel mounts hidden and flips on the next frame.
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    fun requestClose() {
        if (!closing) closing = true
    }
    // The dialog unmounts only after the slide-out finishes.
    LaunchedEffect(closing) {
        if (closing) {
            delay(PANEL_ANIMATION_MS.toLong())
            onDismiss()
        }
    }
    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The dialog owns its own window, so the activity's bar
        // treatment (Theme.kt) never reaches it — without this the
        // status and navigation bars stay dark in light mode. Same
        // treatment as the activity window: transparent bars, dark
        // icons in light mode, light icons in dark mode (judged by
        // the surface luminance, like the group background does).
        val dialogView = LocalView.current
        val dialogDark = with(MaterialTheme.colorScheme.surface) {
            0.2126f * red + 0.7152f * green + 0.0722f * blue
        } < 0.5f
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
                ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, dialogView)
            controller.isAppearanceLightStatusBars = !dialogDark
            controller.isAppearanceLightNavigationBars = !dialogDark
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedVisibility(
                visible = shown && !closing,
                enter = fadeIn(tween(PANEL_ANIMATION_MS)),
                exit = fadeOut(tween(PANEL_ANIMATION_MS)),
            ) {
                // Round 42 — the scrim stops at the bar areas (the
                // panel's surface covers them now); taps there belong
                // to the system bars, not to the close handler.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                        .clickable(onClick = { requestClose() }),
                )
            }
            AnimatedVisibility(
                visible = shown && !closing,
                enter = slideInHorizontally(tween(PANEL_ANIMATION_MS)) { it },
                exit = slideOutHorizontally(tween(PANEL_ANIMATION_MS)) { it },
            ) {
                content { requestClose() }
            }
        }
    }
}

@Composable
private fun DetailsPanelContent(
    conversation: Conversation,
    title: String,
    subtitleNumber: String?,
    contactPhotoUri: String?,
    isUnknownContact: Boolean,
    memberNames: Map<String, String>,
    memberPhotos: Map<String, String>,
    showAddToContacts: Boolean,
    showEditContact: Boolean,
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
    onClose: () -> Unit,
    mediaViewModel: MediaGalleryViewModel,
    /** E2E — this 1:1 peer's encryption state; null = no encryption row. */
    e2ePeerState: E2ePeerState? = null,
    /** E2E — the peer is marked verified (local flag). */
    e2eVerified: Boolean = false,
    /** E2E — opens the safety-number sheet for this thread's peer. */
    onOpenE2eVerification: (() -> Unit)? = null,
    /** E2E — manual probe retry (round 41): sends one probe now. */
    onRetryE2eProbe: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isWide = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val media by mediaViewModel.media.collectAsStateWithLifecycle()
    val files by mediaViewModel.files.collectAsStateWithLifecycle()
    val downloadStates by mediaViewModel.downloadStates.collectAsStateWithLifecycle()
    var mediaTab by remember { mutableStateOf(DetailsMediaTab.Photos) }
    // Round 33 — Relay's own viewer picker: media opens resolve the
    // remembered viewer or raise the picker.
    val openAttachment = rememberAttachmentOpener()
    // Round 33 — the "Save as" flow behind the download icon on
    // tiles and audio rows (hub mode: the file is local, so
    // "download" saves a copy out of Relay; client mode fetches
    // from the hub instead). One launcher for the whole panel; the
    // pending attachment is cleared on result, like the avatar
    // picker's grant flow. A toast confirms the save — the panel
    // has no snackbar host.
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    // Hoisted (not `context.getString` in the launcher callback):
    // `LocalContext.current` reads are not configuration-aware, and
    // lint fails the build on them (LocalContextGetResourceValueCall).
    val fileSavedText = stringResource(R.string.details_file_saved)
    val fileSaveFailedText = stringResource(R.string.details_file_save_failed)
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val target = pendingSave
        pendingSave = null
        if (uri != null && target != null) {
            scope.launch {
                val saved = saveAttachmentCopy(context, target, uri)
                Toast.makeText(
                    context,
                    if (saved) fileSavedText else fileSaveFailedText,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    fun requestSaveCopy(attachment: Attachment) {
        pendingSave = attachment
        saveLauncher.launch(saveFileNameFor(attachment))
    }
    // Round 42 — the background sits OUTSIDE the bar paddings: the
    // surface extends full-height behind the transparent system bars,
    // while the paddings keep the header and rows clear of them. With
    // the background after the paddings (as before), the surface
    // stopped at the bars and the bar areas showed the black scrim.
    Column(
        modifier = Modifier
            .background(cs.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .then(
                if (isWide) Modifier.width(PANEL_WIDTH_DP.dp) else Modifier.fillMaxWidth(),
            )
            .fillMaxHeight(),
    ) {
        // Header — back arrow + title, fixed while the rest scrolls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
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
            Text(
                text = stringResource(R.string.details_panel_title),
                style = RelayText.ThreadTitle,
                color = cs.onSurface,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            item(key = "avatar") {
                DetailsAvatarBlock(
                    conversation = conversation,
                    title = title,
                    subtitleNumber = subtitleNumber,
                    contactPhotoUri = contactPhotoUri,
                    isUnknownContact = isUnknownContact,
                    memberNames = memberNames,
                    memberPhotos = memberPhotos,
                    onSetAvatar = actions.onSetAvatar,
                )
            }
            item(key = "actions") {
                DetailsActionRows(
                    conversation = conversation,
                    showAddToContacts = showAddToContacts,
                    showEditContact = showEditContact,
                    canSchedule = canSchedule,
                    canExport = canExport,
                    isExporting = isExporting,
                    actions = actions,
                    e2ePeerState = e2ePeerState,
                    e2eVerified = e2eVerified,
                    onOpenE2eVerification = onOpenE2eVerification,
                    onRetryE2eProbe = onRetryE2eProbe,
                )
            }
            if (conversation.isGroup) {
                item(key = "members") {
                    DetailsMembersSection(
                        conversation = conversation,
                        memberNames = memberNames,
                        memberPhotos = memberPhotos,
                        onMemberClick = actions.onMemberClick,
                    )
                }
            }
            item(key = "tabs") {
                DetailsMediaTabs(
                    selected = mediaTab,
                    onSelect = { mediaTab = it },
                )
            }
            when (mediaTab) {
                DetailsMediaTab.Photos -> {
                    val photos = media?.filter { it.isPhotosTabItem() }
                    when {
                        photos == null -> Unit
                        photos.isEmpty() -> item(key = "empty-photos") {
                            DetailsEmptyMedia(
                                iconRes = R.drawable.ic_images,
                                text = stringResource(R.string.details_empty_photos),
                            )
                        }
                        else -> detailsGroupedTiles(
                            tabKey = "photos",
                            items = photos,
                            downloadStates = downloadStates,
                            onOpen = openAttachment,
                            onRequestDownload = { id ->
                                mediaViewModel.requestDownload(id)
                            },
                            onSaveCopy = { requestSaveCopy(it) },
                        )
                    }
                }
                DetailsMediaTab.Videos -> {
                    val videos = media?.filter { it.isVideo }
                    when {
                        videos == null -> Unit
                        videos.isEmpty() -> item(key = "empty-videos") {
                            DetailsEmptyMedia(
                                iconRes = R.drawable.ic_videocam,
                                text = stringResource(R.string.details_empty_videos),
                            )
                        }
                        else -> detailsGroupedTiles(
                            tabKey = "videos",
                            items = videos,
                            downloadStates = downloadStates,
                            onOpen = openAttachment,
                            onRequestDownload = { id ->
                                mediaViewModel.requestDownload(id)
                            },
                            onSaveCopy = { requestSaveCopy(it) },
                        )
                    }
                }
                DetailsMediaTab.Files -> {
                    // A 0-byte file has no bytes to open — hidden,
                    // like the desktop Files tab.
                    val fileRows = files?.filter { it.sizeBytes > 0 }
                    when {
                        fileRows == null -> Unit
                        fileRows.isEmpty() -> item(key = "empty-files") {
                            DetailsEmptyMedia(
                                iconRes = R.drawable.ic_file_tray_full,
                                text = stringResource(R.string.details_empty_files),
                            )
                        }
                        else -> detailsGroupedFiles(
                            files = fileRows,
                            downloadStates = downloadStates,
                            onOpen = openAttachment,
                            onRequestDownload = { id ->
                                mediaViewModel.requestDownload(id)
                            },
                            onSaveCopy = { requestSaveCopy(it) },
                        )
                    }
                }
            }
            item(key = "bottom-spacing") {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Notes — the note panel's content, mirroring
 * [DetailsPanelContent]: the same 64dp header (back + title), the
 * note avatar block, the note action rows, and the Photos / Videos
 * / Files tabs grouped by date. No Members section (a note has no
 * members), no download states (a note's attachments are local and
 * hub-only in phase 1 — `hubAttachmentId` is null, so no download
 * UI ever renders), and the tiles are [NoteMediaTile]s (decrypted
 * models, never the ciphertext URI).
 *
 * The "Save as" flow is the conversation panel's, behind the same
 * download icons: one launcher for the whole panel, a toast
 * confirming the save (the panel has no snackbar host), and the
 * toast strings hoisted via [stringResource] — never
 * `context.getString` in the callback (lint fails the build on it).
 */
@Composable
private fun NoteDetailsPanelContent(
    note: Note,
    attachments: List<Attachment>,
    resolver: NoteAttachmentResolver,
    canExport: Boolean,
    isExporting: Boolean,
    onRename: () -> Unit,
    onExportPdf: () -> Unit,
    onChatColors: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    saveCopy: suspend (Attachment, Uri) -> Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isWide = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    var mediaTab by remember { mutableStateOf(DetailsMediaTab.Photos) }
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    val fileSavedText = stringResource(R.string.details_file_saved)
    val fileSaveFailedText = stringResource(R.string.details_file_save_failed)
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val target = pendingSave
        pendingSave = null
        if (uri != null && target != null) {
            scope.launch {
                val saved = saveCopy(target, uri)
                Toast.makeText(
                    context,
                    if (saved) fileSavedText else fileSaveFailedText,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    fun requestSaveCopy(attachment: Attachment) {
        pendingSave = attachment
        saveLauncher.launch(saveFileNameFor(attachment))
    }
    // Round 42 — the background sits OUTSIDE the bar paddings: the
    // surface extends full-height behind the transparent system bars,
    // while the paddings keep the header and rows clear of them. With
    // the background after the paddings (as before), the surface
    // stopped at the bars and the bar areas showed the black scrim.
    Column(
        modifier = Modifier
            .background(cs.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .then(
                if (isWide) Modifier.width(PANEL_WIDTH_DP.dp) else Modifier.fillMaxWidth(),
            )
            .fillMaxHeight(),
    ) {
        // Header — back arrow + title, fixed while the rest scrolls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
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
            Text(
                text = stringResource(R.string.details_panel_title),
                style = RelayText.ThreadTitle,
                color = cs.onSurface,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            item(key = "avatar") {
                NoteDetailsAvatarBlock(note = note)
            }
            item(key = "actions") {
                NoteDetailsActionRows(
                    canExport = canExport,
                    isExporting = isExporting,
                    onRename = onRename,
                    onExportPdf = onExportPdf,
                    onChatColors = onChatColors,
                    onDelete = onDelete,
                )
            }
            item(key = "tabs") {
                DetailsMediaTabs(
                    selected = mediaTab,
                    onSelect = { mediaTab = it },
                )
            }
            when (mediaTab) {
                DetailsMediaTab.Photos -> {
                    val photos = attachments.filter { it.isPhotosTabItem() }
                    if (photos.isEmpty()) {
                        item(key = "empty-photos") {
                            DetailsEmptyMedia(
                                iconRes = R.drawable.ic_images,
                                text = stringResource(R.string.note_empty_photos),
                            )
                        }
                    } else {
                        detailsGroupedTiles(
                            tabKey = "photos",
                            items = photos,
                            downloadStates = emptyMap(),
                            onOpen = onOpenAttachment,
                            onRequestDownload = {},
                            onSaveCopy = { requestSaveCopy(it) },
                            tile = { attachment ->
                                NoteMediaTile(
                                    attachment = attachment,
                                    resolver = resolver,
                                    onClick = { onOpenAttachment(attachment) },
                                    onSaveCopy = { requestSaveCopy(it) },
                                )
                            },
                        )
                    }
                }
                DetailsMediaTab.Videos -> {
                    val videos = attachments.filter { it.isVideo }
                    if (videos.isEmpty()) {
                        item(key = "empty-videos") {
                            DetailsEmptyMedia(
                                iconRes = R.drawable.ic_videocam,
                                text = stringResource(R.string.note_empty_videos),
                            )
                        }
                    } else {
                        detailsGroupedTiles(
                            tabKey = "videos",
                            items = videos,
                            downloadStates = emptyMap(),
                            onOpen = onOpenAttachment,
                            onRequestDownload = {},
                            onSaveCopy = { requestSaveCopy(it) },
                            tile = { attachment ->
                                NoteMediaTile(
                                    attachment = attachment,
                                    resolver = resolver,
                                    onClick = { onOpenAttachment(attachment) },
                                    onSaveCopy = { requestSaveCopy(it) },
                                )
                            },
                        )
                    }
                }
                DetailsMediaTab.Files -> {
                    // A 0-byte file has no bytes to open — hidden,
                    // like the desktop Files tab. Files are the
                    // attachments that are neither image nor video
                    // (the `observeFilesForConversation` rule).
                    val fileRows = attachments.filter {
                        !it.isImage && !it.isVideo && it.sizeBytes > 0
                    }
                    if (fileRows.isEmpty()) {
                        item(key = "empty-files") {
                            DetailsEmptyMedia(
                                iconRes = R.drawable.ic_file_tray_full,
                                text = stringResource(R.string.note_empty_files),
                            )
                        }
                    } else {
                        detailsGroupedFiles(
                            files = fileRows,
                            downloadStates = emptyMap(),
                            onOpen = onOpenAttachment,
                            onRequestDownload = {},
                            onSaveCopy = { requestSaveCopy(it) },
                        )
                    }
                }
            }
            item(key = "bottom-spacing") {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Notes — the note avatar block: the 96dp note avatar (soft-green
 * tint + note glyph, the round-33 panel avatar size) and the title.
 * NO camera badge (a note has no photo to change) and NO subtitle
 * number (a note has no number).
 */
@Composable
private fun NoteDetailsAvatarBlock(note: Note) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RelayAvatar(
            label = note.title,
            size = HEADER_AVATAR_SIZE,
            isNote = true,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = note.title,
            style = RelayText.ThreadTitle,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * Notes — the note action rows: Rename note, Export to PDF, Chat
 * colors, Delete note. EXCLUDED, per design §5.4: Schedule (notes
 * have no send), Mute/Unmute (a note never notifies), Block, Add to
 * contacts, and Move to Vault. Same row component
 * ([DetailsActionRow]) and icons as the conversation rows.
 */
@Composable
private fun NoteDetailsActionRows(
    canExport: Boolean,
    isExporting: Boolean,
    onRename: () -> Unit,
    onExportPdf: () -> Unit,
    onChatColors: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 16.dp)) {
        DetailsActionRow(
            label = stringResource(R.string.note_rename_title),
            icon = {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            onClick = onRename,
        )
        DetailsActionRow(
            label = stringResource(R.string.action_export_pdf),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_export_pdf),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            enabled = canExport && !isExporting,
            onClick = onExportPdf,
        )
        DetailsActionRow(
            label = stringResource(R.string.appearance_dialog_title),
            icon = {
                Icon(
                    Icons.Outlined.Palette,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            onClick = onChatColors,
        )
        DetailsActionRow(
            label = stringResource(R.string.action_delete),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            onClick = onDelete,
        )
    }
}

/**
 * Big avatar (96dp, measured ~99px on Kristin's mockup) with the
 * camera badge at its bottom-right, the name, and the number — or
 * the member count for a group. The badge changes the conversation
 * photo: with no custom avatar yet it opens the gallery picker
 * straight away; with one set, a small menu offers picking a new
 * one or going back to the default. 1:1 only — a group has no
 * single contact photo to change (the desktop pane's rule); a group
 * with a custom avatar still shows it, it just can't be changed
 * from here.
 *
 * A group without a custom avatar shows the first two members as
 * overlapping minis ([RelayGroupAvatar], the same geometry as the
 * main list and the desktop pane header), not the single Group-icon
 * disc. Round 33 — the minis show the members' real contact photos
 * ([memberPhotos]), falling back to initials like the main list.
 */
@Composable
private fun DetailsAvatarBlock(
    conversation: Conversation,
    title: String,
    subtitleNumber: String?,
    contactPhotoUri: String?,
    isUnknownContact: Boolean,
    memberNames: Map<String, String>,
    memberPhotos: Map<String, String>,
    onSetAvatar: (String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var badgeMenuOpen by remember { mutableStateOf(false) }
    // Photo picker — the same persistable-URI flow the old
    // AppearanceDialog avatar section used: persist the READ grant
    // before storing anything, and release the previous URI's grant
    // once the new one holds (no grant accumulation). A failed take
    // keeps the previous avatar instead of storing a dead URI.
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val newUriStr = uri.toString()
        val takeResult = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        if (takeResult.isFailure) {
            Timber.w(
                takeResult.exceptionOrNull(),
                "DetailsPanel: takePersistableUriPermission failed — keeping previous avatar",
            )
            return@rememberLauncherForActivityResult
        }
        conversation.avatarUri?.let { oldStr ->
            if (oldStr != newUriStr) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(oldStr),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        onSetAvatar(newUriStr)
    }
    fun resetAvatar() {
        conversation.avatarUri?.let { uriStr ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriStr),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        onSetAvatar(null)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (conversation.isGroup && conversation.avatarUri == null) {
                RelayGroupAvatar(
                    members = groupMemberAvatars(conversation, memberNames, memberPhotos),
                    size = HEADER_AVATAR_SIZE,
                )
            } else {
                RelayAvatar(
                    label = title,
                    size = HEADER_AVATAR_SIZE,
                    customUri = conversation.avatarUri ?: contactPhotoUri,
                    isGroup = conversation.isGroup,
                    isUnknownContact = isUnknownContact,
                )
            }
            if (!conversation.isGroup) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(cs.surface)
                        .clickable {
                            if (conversation.avatarUri == null) {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            } else {
                                badgeMenuOpen = true
                            }
                        }
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.details_change_photo),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = badgeMenuOpen,
                onDismissRequest = { badgeMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.appearance_pick_avatar)) },
                    onClick = {
                        badgeMenuOpen = false
                        avatarPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.appearance_reset_avatar)) },
                    onClick = {
                        badgeMenuOpen = false
                        resetAvatar()
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = RelayText.ThreadTitle,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        val subtitle = if (conversation.isGroup) {
            stringResource(
                R.string.details_member_count,
                conversation.addresses.size,
            )
        } else {
            subtitleNumber
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = RelayText.Timestamp,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}

/**
 * The first two members of a group, for the header's overlapping
 * minis: the thread's resolved name when known, else the raw number
 * (an unknown number renders the grey glyph, like the main list).
 * Round 33 — the member's contact photo when the thread resolved
 * one ([memberPhotos]); a member without a photo renders initials,
 * like the main list.
 */
private fun groupMemberAvatars(
    conversation: Conversation,
    memberNames: Map<String, String>,
    memberPhotos: Map<String, String>,
): List<GroupMemberAvatar> {
    return conversation.addresses.take(2).map { address ->
        val name = memberNames[address.raw]
        GroupMemberAvatar(
            label = name ?: address.raw,
            isUnknownContact = name == null,
            photoUri = memberPhotos[address.raw],
        )
    }
}

/**
 * Round 45 — the actions the encryption box's expanded area can
 * show. Non-encrypted states offer the two manual retries (the
 * data-SMS probe first, then the regular-SMS handshake); ENCRYPTED
 * offers the safety-number sheet instead — a retry is meaningless
 * once a session is live.
 */
internal enum class E2eDetailsExpandedAction {
    RetryProbe,
    RetryViaSms,
    ViewSafetyNumber,
}

/**
 * Round 45 — which expanded actions show for this peer state. An
 * action only appears when its handler exists, so a row never
 * promises a tap that does nothing. Pure, so the visibility table
 * is unit-testable without a Compose harness.
 *
 * Round 47 — a verified peer has NO expanded actions: the
 * dropdown is redundant once the safety number is verified (the
 * row opens the sheet directly instead — see
 * [e2eDetailsRowTap]), so ENCRYPTED + verified shows nothing
 * here and the box renders with no chevron and no expand.
 */
internal fun e2eDetailsExpandedActions(
    peerState: E2ePeerState,
    verified: Boolean = false,
    hasRetryProbe: Boolean,
    hasRetryViaSms: Boolean,
    hasOpenVerification: Boolean,
): List<E2eDetailsExpandedAction> = when (peerState) {
    E2ePeerState.ENCRYPTED -> if (verified || !hasOpenVerification) {
        emptyList()
    } else {
        listOf(E2eDetailsExpandedAction.ViewSafetyNumber)
    }
    E2ePeerState.PROBING, E2ePeerState.UNKNOWN, E2ePeerState.NOT_RELAY -> buildList {
        if (hasRetryProbe) add(E2eDetailsExpandedAction.RetryProbe)
        if (hasRetryViaSms) add(E2eDetailsExpandedAction.RetryViaSms)
    }
}

/**
 * Round 47 — what a tap on the encryption box does. A verified
 * peer opens the safety-number sheet directly: once the number is
 * verified the dropdown is a redundant step, so the box has no
 * chevron and no expand — tapping it is the "View safety number"
 * action, without the dropdown in between. Any other state with
 * expanded actions toggles the dropdown; with none, the box is
 * plain status. Pure, so the tap table is unit-testable without a
 * Compose harness.
 */
internal enum class E2eDetailsRowTap {
    /** Tap opens the safety-number sheet directly. */
    OpenVerification,
    /** Tap expands / collapses the dropdown. */
    Expand,
    /** No tap — the box is plain status. */
    None,
}

internal fun e2eDetailsRowTap(
    peerState: E2ePeerState,
    verified: Boolean,
    hasOpenVerification: Boolean,
    hasExpandedActions: Boolean,
): E2eDetailsRowTap = when {
    peerState == E2ePeerState.ENCRYPTED && verified && hasOpenVerification ->
        E2eDetailsRowTap.OpenVerification
    hasExpandedActions -> E2eDetailsRowTap.Expand
    else -> E2eDetailsRowTap.None
}

/**
 * Round 45 — the mute row's icon shows the CURRENT state: the bell
 * while unmuted, the muted bell once muted — the same pair the
 * category rows use for muted categories. The toggle carries the
 * state too; the icon is the at-a-glance read.
 */
internal fun muteIconFor(muted: Boolean): ImageVector =
    if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications

/**
 * E2E — the encryption row, first in the action rows (design §7.2).
 * 1:1 only — the caller passes no state for groups and client
 * mode, so this never renders there.
 *
 * ENCRYPTED shows the closed lock glyph; PROBING reads "Encryption /
 * Checking if NAME uses Relay…"; UNKNOWN reads "Not encrypted /
 * Encryption starts automatically when NAME uses Relay"; NOT_RELAY
 * reads "Not encrypted" with no subtitle.
 *
 * Round 41 — the row mirrors the 'Encryption' row at the bottom of
 * Security & privacy (a [RelaySettingRow]: the lock in a tonal
 * badge, a 14sp title, the state as subtitle, a chevron when the
 * row is tappable). The encrypted state keeps its own lock glyph
 * (the E2E mark, as on the avatar badge and in the bubbles).
 *
 * Round 42 — the rounded box is real now: the row renders inside
 * a group segment (single segment, all four corners 24dp), the same
 * source of truth as Settings — round 41 called [RelaySettingRow]
 * directly, and the row carries no background of its own, so the
 * box never showed. The panel adds no horizontal padding above this
 * row, so the group's own 16dp inset is the only one — the box lands
 * exactly where the Settings row's does.
 *
 * Round 45 — the box expands: tapping it opens a dropdown (the
 * "Verified contacts" pattern from the Encryption screen — a
 * [RelaySettingsGroup] whose guarded items register only while
 * expanded), and the chevron shows in every state. The expanded
 * area holds the manual actions as rows in the same group:
 * non-encrypted states show "Retry encryption" (the data-SMS probe
 * retry) and "Retry via SMS" (the regular-SMS handshake); ENCRYPTED
 * shows "View safety number" (the sheet, unchanged). The expanded
 * area stays open after a tap, so the state flip the retry causes
 * (PROBING, then ENCRYPTED) shows in the box's own subtitle. The
 * old refresh icon and the "Tap to retry encryption setup"
 * subtitle are gone — the retries live in the expanded area now.
 * No expanded actions (no handlers at all) means no chevron and no
 * tap: the box is plain status. (Round 47: a verified peer has no
 * expanded actions either — the row opens the sheet directly.)
 *
 * Round 46 — tap feedback + anti-hammering: tapping a retry
 * flips that button to "Retrying…" and disables it (greyed, no
 * click) until the peer state moves or 30 seconds pass, whichever
 * comes first. Each button has its own flag, so one retrying
 * leaves the other tappable. The flags live in remembered state
 * here — the panel is at the 20-parameter ceiling, so no new
 * param — and reset if the panel closes and reopens.
 *
 * Round 47 — a verified peer has no dropdown: ENCRYPTED +
 * verified renders "End-to-end encrypted / Verified ✓" as a
 * static row (no chevron, no expand), and tapping it opens the
 * safety-number sheet directly — the same onOpenVerification
 * callback the dropdown's "View safety number" row used to call,
 * so viewing the number and clearing verification stay one tap
 * away. An unverified ENCRYPTED peer keeps the chevron +
 * dropdown, and the non-encrypted states keep their two retries.
 */
@Composable
private fun E2eDetailsEncryptionRow(
    peerState: E2ePeerState,
    verified: Boolean,
    contactName: String,
    onOpenVerification: (() -> Unit)?,
    onRetryProbe: (() -> Unit)?,
    onRetryProbeViaSms: (() -> Unit)? = null,
) {
    val encrypted = peerState == E2ePeerState.ENCRYPTED
    val title = when (peerState) {
        E2ePeerState.ENCRYPTED -> stringResource(R.string.e2e_details_encrypted)
        E2ePeerState.PROBING -> stringResource(R.string.e2e_encryption)
        E2ePeerState.UNKNOWN, E2ePeerState.NOT_RELAY ->
            stringResource(R.string.e2e_details_not_encrypted)
    }
    val subtitle = when (peerState) {
        E2ePeerState.ENCRYPTED -> if (verified) {
            stringResource(R.string.e2e_details_verified)
        } else {
            stringResource(R.string.e2e_details_verify_tap, contactName)
        }
        E2ePeerState.PROBING -> stringResource(R.string.e2e_details_setting_up, contactName)
        E2ePeerState.UNKNOWN -> stringResource(R.string.e2e_details_auto_start, contactName)
        // Round 45 — no subtitle: the retry moved into the expanded
        // area, and the old "Tap to retry encryption setup" line is
        // gone with it.
        E2ePeerState.NOT_RELAY -> null
    }
    val expandedActions = e2eDetailsExpandedActions(
        peerState = peerState,
        verified = verified,
        hasRetryProbe = onRetryProbe != null,
        hasRetryViaSms = onRetryProbeViaSms != null,
        hasOpenVerification = onOpenVerification != null,
    )
    // Round 47 — what a tap on the box does: a verified peer
    // opens the safety-number sheet directly (the dropdown is
    // gone, so the row IS the "View safety number" action);
    // anything else with expanded actions toggles the dropdown.
    // The chevron follows the dropdown, never the sheet — a
    // verified row has none.
    val rowTap = e2eDetailsRowTap(
        peerState = peerState,
        verified = verified,
        hasOpenVerification = onOpenVerification != null,
        hasExpandedActions = expandedActions.isNotEmpty(),
    )
    var expanded by remember { mutableStateOf(false) }
    // Round 46 — per-button retry-in-progress flags (two booleans,
    // not one shared state: tapping one retry must leave the other
    // tappable, and each has its own 30-second timer).
    var retryingProbe by remember { mutableStateOf(false) }
    var retryingViaSms by remember { mutableStateOf(false) }
    // Each flag clears on its own after 30 seconds.
    LaunchedEffect(retryingProbe) {
        if (retryingProbe) {
            delay(E2E_RETRYING_TIMEOUT_MS)
            retryingProbe = false
        }
    }
    LaunchedEffect(retryingViaSms) {
        if (retryingViaSms) {
            delay(E2E_RETRYING_TIMEOUT_MS)
            retryingViaSms = false
        }
    }
    // ...or the moment the peer state moves (PROBING once the
    // retry lands, ENCRYPTED once the session is up): the button
    // is live again either way. Clearing on first composition is
    // harmless — both flags start false.
    LaunchedEffect(peerState) {
        retryingProbe = false
        retryingViaSms = false
    }
    // Hoisted above the group: the group content lambda is plain
    // (not @Composable), so stringResource calls cannot live inside
    // it — same rule as the Encryption screen's verified list.
    val retryLabel = stringResource(R.string.e2e_details_retry_encryption)
    val retryViaSmsLabel = stringResource(R.string.e2e_details_retry_via_sms)
    val retryingLabel = stringResource(R.string.e2e_details_retrying)
    val viewSafetyNumberLabel = stringResource(R.string.e2e_details_view_safety_number)
    // Assigned via a when statement (not an expression): the branches
    // (a nullable callback, a lambda, null) do not unify in a when
    // expression — Kotlin infers Any? — but each assignment checks
    // independently against the declared type.
    var onClick: (() -> Unit)? = null
    when (rowTap) {
        E2eDetailsRowTap.OpenVerification -> onClick = onOpenVerification
        E2eDetailsRowTap.Expand -> onClick = { expanded = !expanded }
        E2eDetailsRowTap.None -> onClick = null
    }
    RelaySettingsGroup {
        item(onClick = onClick) {
            RelaySettingRow(
                icon = if (encrypted) null else Icons.Outlined.LockOpen,
                iconPainter = if (encrypted) {
                    painterResource(R.drawable.ic_lock_encrypted)
                } else {
                    null
                },
                title = title,
                subtitle = subtitle,
                chevron = rowTap == E2eDetailsRowTap.Expand,
            )
        }
        if (expanded) {
            for (action in expandedActions) {
                when (action) {
                    E2eDetailsExpandedAction.RetryProbe -> item(
                        onClick = if (retryingProbe) {
                            null
                        } else {
                            {
                                retryingProbe = true
                                onRetryProbe?.invoke()
                            }
                        },
                    ) {
                        RelaySettingRow(
                            title = if (retryingProbe) retryingLabel else retryLabel,
                            modifier = Modifier.alpha(if (retryingProbe) DISABLED_ALPHA else 1f),
                        )
                    }
                    E2eDetailsExpandedAction.RetryViaSms -> item(
                        onClick = if (retryingViaSms) {
                            null
                        } else {
                            {
                                retryingViaSms = true
                                onRetryProbeViaSms?.invoke()
                            }
                        },
                    ) {
                        RelaySettingRow(
                            title = if (retryingViaSms) retryingLabel else retryViaSmsLabel,
                            modifier = Modifier.alpha(if (retryingViaSms) DISABLED_ALPHA else 1f),
                        )
                    }
                    // The sheet is navigation, like a verified
                    // contact's row — it keeps its chevron.
                    E2eDetailsExpandedAction.ViewSafetyNumber -> item(onClick = onOpenVerification) {
                        RelaySettingRow(title = viewSafetyNumberLabel, chevron = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsActionRows(
    conversation: Conversation,
    showAddToContacts: Boolean,
    showEditContact: Boolean,
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
    /** E2E — this 1:1 peer's encryption state; null = no encryption row. */
    e2ePeerState: E2ePeerState? = null,
    /** E2E — the peer is marked verified (local flag). */
    e2eVerified: Boolean = false,
    /** E2E — opens the safety-number sheet for this thread's peer. */
    onOpenE2eVerification: (() -> Unit)? = null,
    /** E2E — manual probe retry (round 41): sends one probe now. */
    onRetryE2eProbe: (() -> Unit)? = null,
) {
    // Round 47 — the gap above the encryption box is the 24dp
    // Settings section gap, measured in the Encryption screen:
    // its auto-discover group carries a 24dp Spacer above it, and
    // RelaySectionLabel puts 24dp above the groups it heads — the
    // same 24dp the panel uses above the media tabs. The 16dp here
    // read tighter than Settings. The menu group keeps its 16dp
    // when there is no encryption box (groups, client mode).
    Column(
        modifier = Modifier.padding(
            top = if (e2ePeerState != null) 24.dp else 16.dp,
        ),
    ) {
        // E2E — the encryption row, first in the list, under the
        // header: the thread's encryption state at a glance.
        // Round 45 — the box sits just above the grouped menu
        // items with a MINOR gap (8dp, the panel's minor spacing
        // — the same 8dp as the row and tab spacing): Part 6
        // supersedes Part 4's 24dp here. The 24dp matched the
        // media-tabs gap, but the box and the menu group belong
        // together, so the gap is minor, not the 24dp section gap.
        // The 24dp above the media tabs is unchanged.
        val e2eState = e2ePeerState
        if (e2eState != null) {
            E2eDetailsEncryptionRow(
                peerState = e2eState,
                verified = e2eVerified,
                contactName = conversation.title,
                onOpenVerification = onOpenE2eVerification,
                onRetryProbe = onRetryE2eProbe,
                onRetryProbeViaSms = actions.onRetryE2eProbeViaSms,
            )
            Spacer(Modifier.height(8.dp))
        }
        DetailsMenuGroup(
            conversation = conversation,
            showAddToContacts = showAddToContacts,
            showEditContact = showEditContact,
            canSchedule = canSchedule,
            canExport = canExport,
            isExporting = isExporting,
            actions = actions,
        )
    }
}

/**
 * Round 45 — the menu rows as ONE Settings-style group (the same
 * [RelaySettingsGroup] / [RelaySettingRow] pair Settings uses, so
 * the panel and Settings match): Schedule, Export to PDF, Chat
 * colors, Mute, Add to contacts, Edit contact, Rename, Block,
 * Delete, Move to/out of the vault — the same rows, in the same
 * order, with the same conditions as before, only grouped now.
 * One group, not split: Settings keeps even its destructive rows
 * (Advanced's Reset all / Erase data) in the same group as the
 * rest, so the panel does the same. The E2E encryption box stays
 * its own group above this one, separated by the minor gap.
 *
 * A disabled row (Schedule, Export) keeps its greyed look
 * ([DISABLED_ALPHA], as before) and registers no click, so it
 * reads as unavailable rather than dead. The group can never be
 * empty — Schedule, Export, Chat colors, Mute and Block are
 * unconditional — and an empty group renders nothing anyway (the
 * background lives on each segment, not on the group).
 */
@Composable
private fun DetailsMenuGroup(
    conversation: Conversation,
    showAddToContacts: Boolean,
    showEditContact: Boolean,
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
) {
    var addContactMenuOpen by remember { mutableStateOf(false) }
    val canExportNow = canExport && !isExporting
    RelaySettingsGroup {
        item(onClick = if (canSchedule) actions.onSchedule else null) {
            RelaySettingRow(
                iconPainter = painterResource(R.drawable.ic_schedule),
                title = stringResource(R.string.details_schedule),
                modifier = Modifier.alpha(if (canSchedule) 1f else DISABLED_ALPHA),
            )
        }
        item(onClick = if (canExportNow) actions.onExportPdf else null) {
            RelaySettingRow(
                iconPainter = painterResource(R.drawable.ic_export_pdf),
                title = stringResource(R.string.action_export_pdf),
                modifier = Modifier.alpha(if (canExportNow) 1f else DISABLED_ALPHA),
            )
        }
        item(onClick = actions.onChatColors) {
            RelaySettingRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.appearance_dialog_title),
            )
        }
        // The mute row is a toggle now (the Settings switch row):
        // the label names the feature ("Mute") and the toggle shows
        // the state; the icon shows the CURRENT state too (the bell
        // while unmuted, the muted bell once muted). No onClick on
        // the item — the switch row owns the tap (its whole row is
        // the control, like the Encryption screen's auto-discover
        // row), so one tap flips it exactly once.
        item {
            RelaySwitchRow(
                icon = muteIconFor(conversation.muted),
                title = stringResource(R.string.action_mute),
                checked = conversation.muted,
                onCheckedChange = { actions.onToggleMute() },
            )
        }
        if (showAddToContacts) {
            // The menu anchors to this Box, so it opens under the
            // row instead of at the top of the actions column.
            item(onClick = { addContactMenuOpen = true }) {
                Box {
                    RelaySettingRow(
                        iconPainter = painterResource(R.drawable.ic_add_contact),
                        title = stringResource(R.string.details_add_to_contacts),
                    )
                    DetailsAddContactMenu(
                        expanded = addContactMenuOpen,
                        onDismissRequest = { addContactMenuOpen = false },
                        onCreateContact = {
                            addContactMenuOpen = false
                            actions.onCreateContact()
                        },
                        onAddContact = {
                            addContactMenuOpen = false
                            actions.onAddContact()
                        },
                    )
                }
            }
        }
        if (showEditContact) {
            // Round 42 — the mirror of Add to contacts: the thread's
            // number is already saved, so this opens the system
            // editor on THAT person's card (ACTION_EDIT + the
            // contact URI, via onEditContact). The old wiring called
            // onAddContact (ACTION_INSERT_OR_EDIT with the number
            // and no URI), which opened the whole contacts list.
            item(onClick = actions.onEditContact ?: actions.onAddContact) {
                RelaySettingRow(
                    icon = Icons.Outlined.Edit,
                    title = stringResource(R.string.details_edit_contact),
                )
            }
        }
        val renameAction = actions.onRename
        if (conversation.isGroup && renameAction != null) {
            item(onClick = renameAction) {
                RelaySettingRow(
                    icon = Icons.Outlined.Edit,
                    title = stringResource(R.string.action_rename_group),
                )
            }
        }
        item(onClick = actions.onBlock) {
            RelaySettingRow(
                iconPainter = painterResource(R.drawable.ic_block),
                title = stringResource(R.string.action_block),
            )
        }
        val deleteAction = actions.onDelete
        if (deleteAction != null) {
            item(onClick = deleteAction) {
                RelaySettingRow(
                    iconPainter = painterResource(R.drawable.ic_trash),
                    title = stringResource(R.string.action_delete),
                )
            }
        }
        val moveVaultAction = actions.onMoveVault
        if (moveVaultAction != null) {
            item(onClick = moveVaultAction) {
                RelaySettingRow(
                    icon = if (conversation.inVault) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    title = stringResource(
                        if (conversation.inVault) R.string.vault_move_out else R.string.vault_move_in,
                    ),
                )
            }
        }
    }
}

/**
 * Round 45 — the Add-to-contacts dropdown (Create new contact /
 * Add to existing contact), extracted so the menu group stays
 * flat. Each tap closes the menu first, then runs its action.
 */
@Composable
private fun DetailsAddContactMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onCreateContact: () -> Unit,
    onAddContact: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_create_contact)) },
            onClick = onCreateContact,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_update_contact)) },
            onClick = onAddContact,
        )
    }
}

/**
 * One action row. Round 33 — every row is rounded (16dp, the tiles'
 * and file cards' corner) with the ripple clipped to it: the mute
 * row used to be the only rounded one, and only because it was a
 * blue pill. The pill is gone; the rounding stays, on all rows.
 * The 8dp outer + 8dp inner padding keeps the text aligned exactly
 * where the old full-width rows put it (16dp from the panel edge).
 */
@Composable
private fun DetailsActionRow(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** E2E — a second line under the label (the encryption row's status). */
    subtitle: String? = null,
    /**
     * E2E — `false` renders the row as plain status: full alpha, no
     * ripple, no tap. Unlike [enabled] = false, the row does not look
     * greyed out — it is information, not an unavailable action.
     */
    isClickable: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(ACTION_ROW_CORNER))
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(
                if (isClickable) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(16.dp))
        if (subtitle != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
            )
        }
    }
}

@Composable
private fun DetailsMembersSection(
    conversation: Conversation,
    memberNames: Map<String, String>,
    memberPhotos: Map<String, String>,
    onMemberClick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 16.dp)) {
        // Round 33 — the home screen's date-header style, exactly
        // (RelayText.SectionHeader + onSurfaceVariant): the Members
        // header must read like the home list's Today / Yesterday.
        Text(
            text = stringResource(R.string.compose_group_members),
            style = RelayText.SectionHeader,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        conversation.addresses.forEach { address ->
            val name = memberNames[address.raw]
                ?: address.raw.formatPhoneNumberForDisplay()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onMemberClick(address.raw) })
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Round 33 — the member's real contact photo when
                // the thread resolved one; initials when not.
                RelayAvatar(
                    label = name,
                    size = 32.dp,
                    customUri = memberPhotos[address.raw],
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Round 45 — the Photos / Videos / Files tabs, restyled to match
 * the home category tabs (CategoryTabRow's pills): 48dp row,
 * 24dp corners, selected = `primaryContainer` /
 * `onPrimaryContainer`, unselected = `surfaceContainerHigh` /
 * `onSurface`, colours animated. A plain [Row], not a LazyRow —
 * three fixed tabs never scroll. No unread counts or mute icons
 * (the Details tabs have none).
 */
@Composable
private fun DetailsMediaTabs(
    selected: DetailsMediaTab,
    onSelect: (DetailsMediaTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)
            .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DetailsMediaTab.entries.forEach { tab ->
            DetailsMediaTabPill(
                tab = tab,
                selected = tab == selected,
                onSelect = { onSelect(tab) },
            )
        }
    }
}

/** One Details media pill — the home category pill's colours and size. */
@Composable
private fun DetailsMediaTabPill(
    tab: DetailsMediaTab,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        label = "detailsMediaTabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) cs.onPrimaryContainer else cs.onSurface,
        label = "detailsMediaTabContent",
    )
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                when (tab) {
                    DetailsMediaTab.Photos -> R.string.details_tab_photos
                    DetailsMediaTab.Videos -> R.string.details_tab_videos
                    DetailsMediaTab.Files -> R.string.details_tab_files
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/**
 * An empty media tab, like the desktop pane: the tab's icon in the
 * mockup's mid grey (56dp) above the same line of text, centred.
 * Round 33 — the space BELOW the text doubles (8dp → 16dp); the
 * 32dp above the icon stays as-is.
 */
@Composable
private fun DetailsEmptyMedia(@DrawableRes iconRes: Int, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = EMPTY_STATE_ICON_GREY,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 210.dp),
        )
    }
}

/**
 * A date-group header inside a media tab. Round 33 — the home
 * screen's date-header style, exactly (RelayText.SectionHeader +
 * onSurfaceVariant: RelayFont, 13sp semibold, 16sp line height,
 * 0.4sp letter spacing): round 32 used the desktop style (13sp
 * semibold, no font family, no letter spacing), which read
 * different next to the home list's Today / Yesterday.
 */
@Composable
private fun DetailsMediaGroupHeader(group: DetailsDateGroup) {
    Text(
        text = stringResource(group.labelRes),
        style = RelayText.SectionHeader,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
    )
}

/**
 * One media tab's tiles, grouped by date (Today / Yesterday / …):
 * a header per group, then its rows. The Photos and Videos tabs
 * share this; Files has its own rows below.
 *
 * Notes — [tile] replaces the [MediaTile] render per attachment
 * (the note panel passes [NoteMediaTile], whose model is
 * decrypted). Null keeps the [MediaTile] render.
 */
private fun LazyListScope.detailsGroupedTiles(
    tabKey: String,
    items: List<Attachment>,
    downloadStates: Map<Long, ClientDownloadState>,
    onOpen: (Attachment) -> Unit,
    onRequestDownload: (Long) -> Unit,
    onSaveCopy: (Attachment) -> Unit,
    tile: (@Composable (Attachment) -> Unit)? = null,
) {
    items.groupByDetailsDate().forEach { (group, groupItems) ->
        item(key = "$tabKey-header-${group.name}") {
            DetailsMediaGroupHeader(group)
        }
        items(
            groupItems.chunked(MEDIA_COLUMNS),
            key = { row -> row.first().id },
        ) { row ->
            DetailsMediaRow(
                row = row,
                downloadStates = downloadStates,
                onOpen = onOpen,
                onRequestDownload = onRequestDownload,
                onSaveCopy = onSaveCopy,
                tile = tile,
            )
        }
    }
}

/** The Files tab's rows, grouped by date like the tiles above. */
private fun LazyListScope.detailsGroupedFiles(
    files: List<Attachment>,
    downloadStates: Map<Long, ClientDownloadState>,
    onOpen: (Attachment) -> Unit,
    onRequestDownload: (Long) -> Unit,
    onSaveCopy: (Attachment) -> Unit,
) {
    files.groupByDetailsDate().forEach { (group, groupFiles) ->
        item(key = "files-header-${group.name}") {
            DetailsMediaGroupHeader(group)
        }
        items(groupFiles, key = { it.id }) { attachment ->
            DetailsFileRow(
                attachment = attachment,
                downloadState = downloadStates[attachment.id],
                onRequestDownload = onRequestDownload,
                onOpen = onOpen,
                onSaveCopy = onSaveCopy,
            )
        }
    }
}

/** One row of up to [MEDIA_COLUMNS] gallery tiles (3-column grid, like the gallery screen). */
@Composable
private fun DetailsMediaRow(
    row: List<Attachment>,
    downloadStates: Map<Long, ClientDownloadState>,
    onOpen: (Attachment) -> Unit,
    onRequestDownload: (Long) -> Unit,
    onSaveCopy: (Attachment) -> Unit,
    /**
     * Notes — replaces the [MediaTile] render per attachment (the
     * note panel passes [NoteMediaTile]). Null keeps [MediaTile].
     */
    tile: (@Composable (Attachment) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        row.forEach { attachment ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
            ) {
                if (tile != null) {
                    tile(attachment)
                } else {
                    MediaTile(
                        attachment,
                        onClick = { onOpen(attachment) },
                        downloadState = downloadStates[attachment.id],
                        onRequestDownload = onRequestDownload,
                        onSaveCopy = onSaveCopy,
                    )
                }
            }
        }
        // A short last row keeps its tiles full-size.
        repeat(MEDIA_COLUMNS - row.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * One file row: icon, name + size, and — on a linked device whose
 * bytes have not landed yet — the download affordance, with the same
 * tap-to-download / tap-to-retry rules as [MediaTile]. A hub-mode
 * row opens the file directly.
 *
 * The icon follows the desktop Files tab's four categories: a vCard
 * gets the person-in-a-circle, text and application MIME types the
 * document, audio the mic, anything else the photos mark.
 *
 * Round 33 — geometry from Kristin's mockup (390px wide, 1px =
 * 1dp): the card is 67dp tall with 26dp side margins, a 10dp gap
 * between cards, a 16dp corner, the icon 22dp in from the left
 * edge, and the download icon 29dp in from the right edge. The
 * 16dp top + 15dp bottom padding plus the two text lines (20sp +
 * 16sp) make exactly 67dp. Audio rows always carry the download
 * icon at the far right: client mode fetches the bytes from the
 * hub, hub mode saves a copy out of Relay ([onSaveCopy]).
 */
@Composable
private fun DetailsFileRow(
    attachment: Attachment,
    downloadState: ClientDownloadState?,
    onRequestDownload: ((Long) -> Unit)?,
    onOpen: (Attachment) -> Unit,
    onSaveCopy: (Attachment) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val needsDownload = attachment.localUri.isBlank() && attachment.hubAttachmentId != null &&
        onRequestDownload != null && downloadState != ClientDownloadState.Downloaded
    if (needsDownload) {
        LaunchedEffect(attachment.id) { onRequestDownload(attachment.id) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FILE_CARD_MARGIN, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = {
                if (needsDownload) {
                    if (downloadState == null || downloadState is ClientDownloadState.NotDownloaded ||
                        downloadState is ClientDownloadState.Failed
                    ) {
                        onRequestDownload(attachment.id)
                    }
                } else {
                    onOpen(attachment)
                }
            })
            .padding(start = 22.dp, top = 12.dp, bottom = 13.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 32dp box: the document vector's ink fills 0.625 x 0.8125 of
        // its box, so 32dp renders a ~20x26 glyph — small enough to
        // sit next to the file name without dominating the row. The
        // other type icons share the box so every row lines up.
        Icon(
            painter = painterResource(fileIconFor(attachment)),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName ?: stringResource(R.string.attachment_file_label),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachment.sizeBytes > 0) {
                Text(
                    text = Formatter.formatShortFileSize(context, attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        // The far-right slot: the fetch state on a linked device
        // whose bytes have not landed, the download icon on every
        // audio row (fetch in client mode, save-a-copy in hub mode).
        // The 40dp touch target stays; the 24dp glyph matches the
        // action-row icons (Schedule, Block — Kristin, round-33
        // testing).
        if (needsDownload || attachment.isAudio) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = {
                        if (needsDownload) {
                            if (downloadState == null ||
                                downloadState is ClientDownloadState.NotDownloaded ||
                                downloadState is ClientDownloadState.Failed
                            ) {
                                onRequestDownload(attachment.id)
                            }
                        } else {
                            onSaveCopy(attachment)
                        }
                    }),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    needsDownload && downloadState is ClientDownloadState.Downloading ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    needsDownload && downloadState is ClientDownloadState.Failed -> Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.client_attachment_failed),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    needsDownload && downloadState is ClientDownloadState.Empty -> Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.client_attachment_empty),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    else -> Icon(
                        painter = painterResource(R.drawable.ic_download_outline),
                        contentDescription = stringResource(
                            if (needsDownload) {
                                R.string.client_attachment_tap_download
                            } else {
                                R.string.details_save_copy
                            },
                        ),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * The file-row icon, by the desktop Files tab's categories: a vCard
 * (`.vcf` name or vCard MIME type) gets the person-in-a-circle,
 * text and application MIME types the document, audio the mic,
 * anything else the photos mark.
 */
private fun fileIconFor(attachment: Attachment): Int {
    val lowerName = (attachment.fileName ?: "").lowercase()
    val isVcf = lowerName.endsWith(".vcf") ||
        attachment.mimeType.equals("text/vcard", ignoreCase = true) ||
        attachment.mimeType.equals("text/x-vcard", ignoreCase = true) ||
        attachment.mimeType.equals("application/vcard", ignoreCase = true)
    val isTextOrApp = attachment.mimeType.startsWith("text/", ignoreCase = true) ||
        attachment.mimeType.startsWith("application/", ignoreCase = true)
    val isDoc = !isVcf && isTextOrApp
    // One `when`, one return (Detekt ReturnCount).
    return when {
        isVcf -> R.drawable.ic_person_circle
        isDoc -> R.drawable.ic_document
        attachment.isAudio -> R.drawable.ic_mic_outline
        else -> R.drawable.ic_images
    }
}

/** Slide + fade duration — the app's 300ms animation language. */
private const val PANEL_ANIMATION_MS = 300

/** Empty-state icon grey — the desktop pane's fixed mid grey, both themes. */
private val EMPTY_STATE_ICON_GREY = Color(0xFF696969)

/** Scrim darkness — the Material 3 drawer scrim. */
private const val SCRIM_ALPHA = 0.32f

/** Screen width at which the panel stops being full-width (standard sw600dp tablet break). */
private const val TABLET_MIN_WIDTH_DP = 600

/** Fixed panel width on tablets — the desktop pane's width. */
private const val PANEL_WIDTH_DP = 315

/** Gallery tiles per row, like the gallery screen. */
private const val MEDIA_COLUMNS = 3

/** Action-row corner — 16dp, the tiles' and file cards' corner. */
private val ACTION_ROW_CORNER = 16.dp

/** Header avatar diameter — 96dp (the mockup measures ~99px). */
private val HEADER_AVATAR_SIZE = 96.dp

/** File-card side margin — 16dp, aligned with the date-group headers (Kristin, round-33 testing). */
private val FILE_CARD_MARGIN = 16.dp

/** Disabled-row opacity (Material disabled content). */
private const val DISABLED_ALPHA = 0.38f

/** Round 46 — how long a retry button stays "Retrying…" before it re-enables on its own. */
private const val E2E_RETRYING_TIMEOUT_MS = 30_000L
