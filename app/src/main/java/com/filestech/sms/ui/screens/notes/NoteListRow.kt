package com.filestech.sms.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.R
import com.filestech.sms.domain.model.Note
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelaySheet
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.theme.BrandDanger
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.LocalMessageTextFactor
import com.filestech.sms.ui.theme.MessageTextSize
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.scaledBy
import com.filestech.sms.ui.util.relativeRowLabel
import com.filestech.sms.ui.util.rememberChatFormatters

/**
 * Notes — the list row for a note: the [ConversationRow] layout
 * (72dp, 16dp inset, 48dp avatar, 17dp avatar-text gap, name over
 * preview, relative date right) with the note avatar (soft-green
 * tint + note glyph) and NO unread badge, NO mute icon, NO "Blocked"
 * label — a note has none of those.
 *
 * The preview follows the conversation rule: the draft (with the
 * "Draft: " prefix) wins over the last entry; an attachment-only
 * entry falls back to the attachment label; an empty note shows
 * nothing.
 */
@Composable
fun NoteRow(
    note: Note,
    onClick: () -> Unit,
    showAvatars: Boolean = true,
    previewLines: Int = 1,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val formatters = rememberChatFormatters()
    val textFactor = LocalMessageTextFactor.current
    val previewStyle =
        RelayText.Preview.copy(fontSize = 14.sp, lineHeight = 18.sp).scaledBy(textFactor)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAvatars) {
                RelayAvatar(
                    label = note.title,
                    size = 48.dp,
                    isNote = true,
                )
                Spacer(Modifier.width(17.dp))
            }
            MessageTextSize {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = note.title,
                            style = RelayText.ConvoName.scaledBy(textFactor),
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        val sortKey = note.lastMessageAt ?: note.createdAt
                        val dateLabel = remember(sortKey) {
                            formatters.relativeRowLabel(sortKey)
                        }
                        FixedTypeSize {
                            Text(
                                text = dateLabel,
                                style = RelayText.Timestamp.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.size(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val draftText = note.draft
                        Text(
                            text = if (!draftText.isNullOrBlank()) {
                                stringResource(R.string.conversations_draft_prefix, draftText)
                            } else {
                                val preview = note.lastMessagePreview.orEmpty()
                                if (preview.isBlank() && note.lastMessageAt != null) {
                                    stringResource(R.string.conversations_preview_attachment)
                                } else {
                                    preview
                                }
                            },
                            style = previewStyle,
                            color = cs.onSurfaceVariant,
                            maxLines = previewLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Notes — the swipeable note row: a slim mirror of
 * `SwipeableConversationRow` (which is private to ConversationsScreen
 * and Conversation-typed — generalising it would drag the block
 * dialog, rename dialog, vault and mute paths into the merge).
 *
 *   - swipe right → delete confirmation (never a silent delete);
 *   - swipe left → open the note;
 *   - long-press → the note actions sheet (Pin / Archive / Delete
 *     only — no mute, no block, no mark-unread, no multi-select).
 *
 * The left-swipe one-shot lock is the conversation row's (v1.23.2):
 * `confirmValueChange` fires on every drag frame past the threshold,
 * so without the lock one swipe would stack N copies of the thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNoteRow(
    note: Note,
    showAvatars: Boolean,
    previewLines: Int,
    onOpenNote: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var pendingDelete by remember { mutableStateOf(false) }
    var actionsSheetOpen by remember { mutableStateOf(false) }
    var swipeOpenConsumed by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right → ask for confirmation, do not dismiss yet.
                    pendingDelete = true
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → open the note. Snap back.
                    if (!swipeOpenConsumed) {
                        swipeOpenConsumed = true
                        onOpenNote()
                    }
                    false
                }

                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    // Re-arm the lock once the row is back at rest (`currentValue`
    // never moves — the veto always wins — so `targetValue` is the
    // signal, like the conversation row).
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) {
            swipeOpenConsumed = false
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val trashPainter = painterResource(R.drawable.ic_trash)
            val openPainter = rememberVectorPainter(Icons.AutoMirrored.Outlined.ArrowForward)
            val dir = dismissState.dismissDirection
            val (bg, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd ->
                    Triple(BrandDanger, trashPainter, Alignment.CenterStart)

                SwipeToDismissBoxValue.EndToStart ->
                    Triple(cs.primary, openPainter, Alignment.CenterEnd)

                else -> Triple(cs.surface, trashPainter, Alignment.Center)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = 24.dp),
                contentAlignment = align,
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    val iconTint = if (dir == SwipeToDismissBoxValue.EndToStart) {
                        cs.onPrimary
                    } else {
                        Color.White
                    }
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = iconTint,
                    )
                }
            }
        },
    ) {
        NoteRow(
            note = note,
            onClick = onOpenNote,
            showAvatars = showAvatars,
            previewLines = previewLines,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                actionsSheetOpen = true
            },
        )
    }

    if (actionsSheetOpen) {
        NoteActionsSheet(
            note = note,
            onDismiss = { actionsSheetOpen = false },
            onTogglePin = {
                actionsSheetOpen = false
                onTogglePin()
            },
            onToggleArchive = {
                actionsSheetOpen = false
                onToggleArchive()
            },
            onDeleteRequested = {
                actionsSheetOpen = false
                pendingDelete = true
            },
        )
    }

    if (pendingDelete) {
        RelayDialog(
            onDismissRequest = { pendingDelete = false },
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
                        pendingDelete = false
                        onDelete()
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pendingDelete = false },
                )
            },
        )
    }
}

/**
 * Notes — the long-press sheet: Pin / Archive / Delete only. No
 * mute (a note never notifies), no block, no mark-unread (a note is
 * never unread), no multi-select (notes never join the bulk move).
 */
@Composable
private fun NoteActionsSheet(
    note: Note,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    RelaySheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                leadingContent = {
                    Icon(Icons.Outlined.PushPin, contentDescription = null)
                },
                headlineContent = {
                    Text(
                        stringResource(
                            if (note.pinned) R.string.action_unpin else R.string.action_pin,
                        ),
                    )
                },
                modifier = Modifier.clickable(onClick = onTogglePin),
            )
            ListItem(
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_archive_outline), contentDescription = null)
                },
                headlineContent = {
                    Text(
                        stringResource(
                            if (note.archived) R.string.action_unarchive else R.string.action_archive,
                        ),
                    )
                },
                modifier = Modifier.clickable(onClick = onToggleArchive),
            )
            ListItem(
                leadingContent = {
                    Icon(
                        painterResource(R.drawable.ic_trash),
                        contentDescription = null,
                        tint = BrandDanger,
                    )
                },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = BrandDanger,
                    )
                },
                modifier = Modifier.clickable(onClick = onDeleteRequested),
            )
        }
    }
}
