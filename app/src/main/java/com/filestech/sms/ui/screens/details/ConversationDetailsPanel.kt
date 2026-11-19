// The file is named for the panel composable; its actions data class lives
// here too, so the file-name rule is suppressed for the whole file.
@file:Suppress("MatchingDeclarationName")

package com.filestech.sms.ui.screens.details

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.sync.client.ClientDownloadState
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.ouvrirPieceJointe
import com.filestech.sms.ui.screens.media.MediaGalleryViewModel
import com.filestech.sms.ui.screens.media.MediaTile
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.onBrandBlue
import kotlinx.coroutines.delay
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
 * (tap = change the conversation photo), name and number, the action
 * rows (Schedule / Export to PDF / Chat colors / Block / Delete, plus
 * Add to contacts on a 1:1 without a saved contact, Rename on a
 * group, and Move to/out of the vault), the group members, and the
 * Photos / Videos / Files tabs. No Mute row — mute stays in the
 * conversation long-press menu.
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
                    val photos = media?.filter { it.isImage }
                    when {
                        photos == null -> Unit
                        photos.isEmpty() -> item(key = "empty-photos") {
                            DetailsEmptyMedia(
                                text = stringResource(R.string.details_empty_photos),
                            )
                        }
                        else -> items(
                            photos.chunked(MEDIA_COLUMNS),
                            key = { row -> row.first().id },
                        ) { row ->
                            DetailsMediaRow(
                                row = row,
                                downloadStates = downloadStates,
                                onOpen = { ouvrirPieceJointe(context, it) },
                                onRequestDownload = { id ->
                                    mediaViewModel.requestDownload(id)
                                },
                            )
                        }
                    }
                }
                DetailsMediaTab.Videos -> {
                    val videos = media?.filter { it.isVideo }
                    when {
                        videos == null -> Unit
                        videos.isEmpty() -> item(key = "empty-videos") {
                            DetailsEmptyMedia(
                                text = stringResource(R.string.details_empty_videos),
                            )
                        }
                        else -> items(
                            videos.chunked(MEDIA_COLUMNS),
                            key = { row -> row.first().id },
                        ) { row ->
                            DetailsMediaRow(
                                row = row,
                                downloadStates = downloadStates,
                                onOpen = { ouvrirPieceJointe(context, it) },
                                onRequestDownload = { id ->
                                    mediaViewModel.requestDownload(id)
                                },
                            )
                        }
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
                                text = stringResource(R.string.details_empty_files),
                            )
                        }
                        else -> items(fileRows, key = { it.id }) { attachment ->
                            DetailsFileRow(
                                attachment = attachment,
                                downloadState = downloadStates[attachment.id],
                                onRequestDownload = { id ->
                                    mediaViewModel.requestDownload(id)
                                },
                                onOpen = { ouvrirPieceJointe(context, it) },
                            )
                        }
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
 * Big avatar (80dp, like the desktop pane) with the camera badge at
 * its bottom-right, the name, and the number — or the member count
 * for a group. The badge changes the conversation photo: with no
 * custom avatar yet it opens the gallery picker straight away; with
 * one set, a small menu offers picking a new one or going back to
 * the default. Groups get the badge too — the mobile avatar belongs
 * to the conversation, not to a single contact (unlike the desktop
 * pane, whose badge edits the contact's photo).
 */
@Composable
private fun DetailsAvatarBlock(
    conversation: Conversation,
    title: String,
    subtitleNumber: String?,
    contactPhotoUri: String?,
    isUnknownContact: Boolean,
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
            RelayAvatar(
                label = title,
                size = 80.dp,
                customUri = conversation.avatarUri ?: contactPhotoUri,
                isGroup = conversation.isGroup,
                isUnknownContact = isUnknownContact,
            )
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
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.compose_group_members),
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
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
                RelayAvatar(
                    label = name,
                    size = 32.dp,
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

@Composable
private fun DetailsEmptyMedia(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
    )
}

/** One row of up to [MEDIA_COLUMNS] gallery tiles (3-column grid, like the gallery screen). */
@Composable
private fun DetailsMediaRow(
    row: List<Attachment>,
    downloadStates: Map<Long, ClientDownloadState>,
    onOpen: (Attachment) -> Unit,
    onRequestDownload: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
 */
@Composable
private fun DetailsFileRow(
    attachment: Attachment,
    downloadState: ClientDownloadState?,
    onRequestDownload: ((Long) -> Unit)?,
    onOpen: (Attachment) -> Unit,
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
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = {
                if (needsDownload) {
                    if (downloadState == null || downloadState is ClientDownloadState.NotDownloaded ||
                        downloadState is ClientDownloadState.Failed
                    ) {
                        onRequestDownload?.invoke(attachment.id)
                    }
                } else {
                    onOpen(attachment)
                }
            })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (attachment.mimeType.startsWith("text/")) {
                Icons.Outlined.Description
            } else {
                Icons.Outlined.AttachFile
            },
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
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
        if (needsDownload) {
            Spacer(Modifier.width(8.dp))
            when (downloadState) {
                is ClientDownloadState.Downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                is ClientDownloadState.Failed -> Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.client_attachment_failed),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                is ClientDownloadState.Empty -> Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = stringResource(R.string.client_attachment_empty),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    Icons.Outlined.Download,
                    contentDescription = stringResource(R.string.client_attachment_tap_download),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Slide + fade duration — the app's 300ms animation language. */
private const val PANEL_ANIMATION_MS = 300

/** Scrim darkness — the Material 3 drawer scrim. */
private const val SCRIM_ALPHA = 0.32f

/** Screen width at which the panel stops being full-width (standard sw600dp tablet break). */
private const val TABLET_MIN_WIDTH_DP = 600

/** Fixed panel width on tablets — the desktop pane's width. */
private const val PANEL_WIDTH_DP = 315

/** Gallery tiles per row, like the gallery screen. */
private const val MEDIA_COLUMNS = 3

/** Disabled-row opacity (Material disabled content). */
private const val DISABLED_ALPHA = 0.38f
