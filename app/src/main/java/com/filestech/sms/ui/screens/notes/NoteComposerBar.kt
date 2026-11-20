package com.filestech.sms.ui.screens.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.theme.BrandDanger
import com.filestech.sms.ui.theme.MessageTextSize
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.brandBluePressed
import com.filestech.sms.ui.theme.onBrandBlue
import java.util.Locale

/**
 * Notes — the note composer's bar: a NEW component mirroring the
 * thread's `ComposerBar`/`ComposingRow` (same pill geometry: 48dp,
 * 24dp radius, max 5 lines, 12/6dp content padding; same 36dp icon
 * boxes with 24dp glyphs; same 48dp send/mic circles) instead of
 * extracting the composer's row out of the 4,096-line ThreadScreen.
 *
 * Differences from the thread composer, all deliberate (design
 * §5.3/§5.5):
 *
 *   - the leading smiley focuses the text field (the system
 *     keyboard's emoji key), instead of opening a picker panel —
 *     there is no emoji/GIF/sticker panel in v1;
 *   - the mic is tap-to-start / tap-to-stop (the thread's is
 *     press-and-hold with slide-to-cancel): a finished clip stages
 *     as a pending audio chip, with no pre-save playback review;
 *   - NO schedule entry (no long-press on send, no preset menu).
 */
@Composable
fun NoteComposerBar(
    draft: String,
    onDraftChanged: (String) -> Unit,
    hasPendingAttachments: Boolean,
    isSaving: Boolean,
    isRecording: Boolean,
    recordingElapsedMs: Long,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onSave: () -> Unit,
    onMicClick: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The thread's rule: the mic shows only while there is nothing
    // to send (blank draft, no staged attachments); otherwise the
    // send circle takes its place. While recording the mic spot
    // becomes the stop button.
    val showMic = isRecording || (draft.isBlank() && !hasPendingAttachments)
    val composerFocus = remember { FocusRequester() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (isRecording) {
                NoteRecordingStrip(
                    elapsedMs = recordingElapsedMs,
                    onCancel = onCancelRecording,
                )
            } else {
                MessageTextSize {
                    RelayTextField(
                        value = draft,
                        onValueChange = onDraftChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        placeholder = stringResource(R.string.note_compose_placeholder),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        cornerRadius = 24.dp,
                        // 6+36+6 = 48dp, so the 48dp mic/send circles
                        // centre on the pill (the thread's round-7 fix).
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        focusRequester = composerFocus,
                        leadingIcon = {
                            // No picker panel in v1: the smiley focuses
                            // the field, so the keyboard pops up and its
                            // own emoji key is one tap away.
                            NoteComposerIconButton(
                                painter = painterResource(R.drawable.ic_emoji_happy_outline),
                                contentDescription = stringResource(R.string.thread_compose_emoji),
                                onClick = { runCatching { composerFocus.requestFocus() } },
                            )
                        },
                        trailingIcon = {
                            // Camera then +, in Kristin's capture order.
                            // The camera slides away as soon as the user
                            // types (driven by `showMic`, like the mic ->
                            // send mutation, so the two stay in sync).
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AnimatedVisibility(
                                    visible = showMic,
                                    enter = slideInHorizontally { it } + fadeIn() +
                                        expandHorizontally(expandFrom = Alignment.End),
                                    exit = slideOutHorizontally { it } + fadeOut() +
                                        shrinkHorizontally(shrinkTowards = Alignment.End),
                                ) {
                                    NoteComposerIconButton(
                                        painter = painterResource(R.drawable.ic_camera_outline),
                                        contentDescription = stringResource(R.string.attach_photo),
                                        onClick = onCameraClick,
                                    )
                                }
                                NoteComposerIconButton(
                                    painter = painterResource(R.drawable.ic_add_circle_outline),
                                    contentDescription = stringResource(R.string.action_attach),
                                    onClick = onAttachClick,
                                )
                            }
                        },
                    )
                }
            }
        }
        when {
            isRecording -> NoteStopButton(onClick = onStopRecording)
            showMic -> NoteMicButton(
                permissionGranted = permissionGranted,
                onRequestPermission = onRequestPermission,
                onMicClick = onMicClick,
            )

            else -> NoteSendCircleButton(
                enabled = !isSaving,
                onClick = onSave,
            )
        }
    }
}

/** The thread's `ComposerIconButton`: a 36dp hit area for a 24dp glyph inside the pill. */
@Composable
private fun NoteComposerIconButton(
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

/** The thread's `SendCircleButton`, without the schedule long-press (notes have no schedule). */
@Composable
private fun NoteSendCircleButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .scale(if (pressed) 0.90f else 1f)
            .clip(CircleShape)
            .background(if (pressed) brandBluePressed() else brandBlue())
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = stringResource(R.string.action_send),
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Send,
            contentDescription = stringResource(R.string.action_send),
            tint = onBrandBlue(),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The mic button: tap to start (tap again — the stop button — to
 * finish). Without the permission it asks for it instead; the
 * screen's launcher retries the start on grant.
 */
@Composable
private fun NoteMicButton(
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onMicClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(brandBlue())
            .clickable(onClick = { if (permissionGranted) onMicClick() else onRequestPermission() })
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic_outline),
            contentDescription = stringResource(R.string.voice_action_record),
            tint = onBrandBlue(),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The stop button, in the mic's spot while recording: a red circle with a stop square. */
@Composable
private fun NoteStopButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(BrandDanger)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.voice_action_stop),
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The recording strip, in the pill's place while recording: a
 * pulsing red dot, the elapsed time, and a cancel X. (The thread's
 * strip adds a live waveform and a slide-to-cancel hint; the note
 * strip is tap-to-stop, so the X is the whole cancel path.)
 */
@Composable
private fun NoteRecordingStrip(
    elapsedMs: Long,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "note-rec-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 700),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "note-rec-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(pulse)
                    .clip(CircleShape)
                    .background(BrandDanger),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatNoteElapsed(elapsedMs),
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.voice_action_cancel),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Elapsed time as `m:ss` (US digits: a timer is a technical readout, not prose). */
private fun formatNoteElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
