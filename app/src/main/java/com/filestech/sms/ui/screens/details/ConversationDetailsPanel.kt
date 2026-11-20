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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.sync.client.ClientDownloadState
import com.filestech.sms.ui.components.GroupMemberAvatar
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.RelayGroupAvatar
import com.filestech.sms.ui.components.rememberAttachmentOpener
import com.filestech.sms.ui.screens.media.MediaGalleryViewModel
import com.filestech.sms.ui.screens.media.MediaTile
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.onBrandBlue
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
    val onMoveVault: (() -> Unit)?,
    /** Sets the conversation avatar (`null` = back to the default). */
    val onSetAvatar: (String?) -> Unit,
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
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
    onDismiss: () -> Unit,
    mediaViewModel: MediaGalleryViewModel = hiltViewModel(),
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
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedVisibility(
                visible = shown && !closing,
                enter = fadeIn(tween(PANEL_ANIMATION_MS)),
                exit = fadeOut(tween(PANEL_ANIMATION_MS)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                        .clickable(onClick = { requestClose() }),
                )
            }
            AnimatedVisibility(
                visible = shown && !closing,
                enter = slideInHorizontally(tween(PANEL_ANIMATION_MS)) { it },
                exit = slideOutHorizontally(tween(PANEL_ANIMATION_MS)) { it },
            ) {
                DetailsPanelContent(
                    conversation = conversation,
                    title = title,
                    subtitleNumber = subtitleNumber,
                    contactPhotoUri = contactPhotoUri,
                    isUnknownContact = isUnknownContact,
                    memberNames = memberNames,
                    memberPhotos = memberPhotos,
                    showAddToContacts = showAddToContacts,
                    canSchedule = canSchedule,
                    canExport = canExport,
                    isExporting = isExporting,
                    actions = actions,
                    onClose = { requestClose() },
                    mediaViewModel = mediaViewModel,
                )
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
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
    onClose: () -> Unit,
    mediaViewModel: MediaGalleryViewModel,
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
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .then(
                if (isWide) Modifier.width(PANEL_WIDTH_DP.dp) else Modifier.fillMaxWidth(),
            )
            .fillMaxHeight()
            .background(cs.surface),
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
                    canSchedule = canSchedule,
                    canExport = canExport,
                    isExporting = isExporting,
                    actions = actions,
                )
            }
            if (conversation.isGroup) {
                item(key = "members") {
                    DetailsMembersSection(
                        conversation = conversation,
                        memberNames = memberNames,
                        memberPhotos = memberPhotos,
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
                        .background(brandBlue())
                        .border(2.dp, cs.surface, CircleShape)
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
                        tint = onBrandBlue(),
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

@Composable
private fun DetailsActionRows(
    conversation: Conversation,
    showAddToContacts: Boolean,
    canSchedule: Boolean,
    canExport: Boolean,
    isExporting: Boolean,
    actions: ConversationDetailsActions,
) {
    val cs = MaterialTheme.colorScheme
    var addContactMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        DetailsActionRow(
            label = stringResource(R.string.details_schedule),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_schedule),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            enabled = canSchedule,
            onClick = actions.onSchedule,
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
            onClick = actions.onExportPdf,
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
            onClick = actions.onChatColors,
        )
        // The icon shows the current state (the plain speaker while
        // unmuted, the muted speaker once muted) and the label names
        // the action. Round 33 — no blue pill: the mute row looks
        // like every other row; the icon alone carries the state.
        DetailsActionRow(
            label = stringResource(
                if (conversation.muted) R.string.action_unmute else R.string.action_mute,
            ),
            icon = {
                Icon(
                    painter = painterResource(
                        if (conversation.muted) {
                            R.drawable.ic_volume_mute
                        } else {
                            R.drawable.ic_volume_on
                        },
                    ),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            onClick = actions.onToggleMute,
        )
        if (showAddToContacts) {
            // The menu anchors to this Box, so it opens under the row
            // instead of at the top of the actions column.
            Box {
                DetailsActionRow(
                    label = stringResource(R.string.details_add_to_contacts),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_add_contact),
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = { addContactMenuOpen = true },
                )
                DropdownMenu(
                    expanded = addContactMenuOpen,
                    onDismissRequest = { addContactMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.thread_create_contact)) },
                        onClick = {
                            addContactMenuOpen = false
                            actions.onCreateContact()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.thread_update_contact)) },
                        onClick = {
                            addContactMenuOpen = false
                            actions.onAddContact()
                        },
                    )
                }
            }
        }
        val renameAction = actions.onRename
        if (conversation.isGroup && renameAction != null) {
            DetailsActionRow(
                label = stringResource(R.string.action_rename_group),
                icon = {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                },
                onClick = renameAction,
            )
        }
        DetailsActionRow(
            label = stringResource(R.string.action_block),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_block),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
            onClick = actions.onBlock,
        )
        val deleteAction = actions.onDelete
        if (deleteAction != null) {
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
                onClick = deleteAction,
            )
        }
        val moveVaultAction = actions.onMoveVault
        if (moveVaultAction != null) {
            val moveVaultLabelRes = if (conversation.inVault) R.string.vault_move_out else R.string.vault_move_in
            val moveVaultIcon = if (conversation.inVault) Icons.Outlined.LockOpen else Icons.Outlined.Lock
            DetailsActionRow(
                label = stringResource(moveVaultLabelRes),
                icon = {
                    Icon(
                        moveVaultIcon,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                },
                onClick = moveVaultAction,
            )
        }
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
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(ACTION_ROW_CORNER))
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, onClick = onClick)
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurface,
        )
    }
}

@Composable
private fun DetailsMembersSection(
    conversation: Conversation,
    memberNames: Map<String, String>,
    memberPhotos: Map<String, String>,
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

@Composable
private fun DetailsMediaTabs(
    selected: DetailsMediaTab,
    onSelect: (DetailsMediaTab) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DetailsMediaTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) brandBlue() else Color.Transparent)
                    .clickable(onClick = { onSelect(tab) })
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
                    color = if (isSelected) onBrandBlue() else cs.onSurface,
                )
            }
        }
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
 */
private fun LazyListScope.detailsGroupedTiles(
    tabKey: String,
    items: List<Attachment>,
    downloadStates: Map<Long, ClientDownloadState>,
    onOpen: (Attachment) -> Unit,
    onRequestDownload: (Long) -> Unit,
    onSaveCopy: (Attachment) -> Unit,
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
                MediaTile(
                    attachment,
                    onClick = { onOpen(attachment) },
                    downloadState = downloadStates[attachment.id],
                    onRequestDownload = onRequestDownload,
                    onSaveCopy = onSaveCopy,
                )
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
        // 42dp box: the document vector's ink fills 0.625 x 0.8125 of
        // its box, so 42dp renders a ~26x34 glyph — the mockup's
        // 27x34. The other type icons share the box so every row
        // lines up, and with 12/13dp of vertical padding the card is
        // 67dp tall — the mockup's height.
        Icon(
            painter = painterResource(fileIconFor(attachment)),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName ?: stringResource(R.string.attachment_file_label),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 2,
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
        // A 40dp touch target with 2dp of end padding puts the
        // download glyph 29dp in from the card's right edge (16dp row
        // padding + 2dp + 5dp centring slack + ~6dp of empty vector
        // space — the download vector's ink fills only 0.625 of its
        // box width) — the mockup's right inset. The 30dp icon
        // renders a ~19x24 glyph, the mockup's 20x24.
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
                        modifier = Modifier.size(30.dp),
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

/** File-card side margin — 26dp (the mockup measures 25/27px). */
private val FILE_CARD_MARGIN = 26.dp

/** Disabled-row opacity (Material disabled content). */
private const val DISABLED_ALPHA = 0.38f
