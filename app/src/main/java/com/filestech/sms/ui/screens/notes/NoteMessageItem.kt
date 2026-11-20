package com.filestech.sms.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.model.toDisplayMessage
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.ui.components.AudioMessageBubble
import com.filestech.sms.ui.components.BurstPosition
import com.filestech.sms.ui.components.MessageBubble
import com.filestech.sms.ui.screens.thread.isLastOfSenderRun
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.RelayText

/**
 * Notes — one entry of the note thread.
 *
 * The entry maps to a display [Message] at the render boundary
 * ([NoteMessage.toDisplayMessage]: OUTGOING, RECEIVED, no status)
 * and renders through the thread's dumb bubbles:
 *
 *   - a lone audio attachment → [AudioMessageBubble] (delete only);
 *   - any other attachment → [NoteMediaAttachmentBubble] (delete +
 *     copy when there is a caption);
 *   - text only → [MessageBubble] (delete + copy + translate).
 *
 * No reply, no reactions, no forward, no pin toggles — the note
 * thread has none of those in v1 (§5.5). The mapped message never
 * leaves this renderer: it is never passed into an SMS path.
 *
 * Timestamps follow the thread's rule ([isLastOfSenderRun]: one per
 * same-sender run, on its last message — every entry here is
 * outgoing, so the run is the whole day's run) and the burst shape
 * follows the thread's ([computeNoteBurstPosition]: same-day, ≤60 s
 * apart). The day separator renders above the entry when
 * [daySeparatorLabel] is non-null.
 */
@Composable
fun NoteMessageItem(
    noteMessage: NoteMessage,
    prev: NoteMessage?,
    next: NoteMessage?,
    daySeparatorLabel: String?,
    resolver: NoteAttachmentResolver,
    playbackProvider: () -> VoicePlaybackController.PlaybackState,
    onTogglePlayback: (Attachment) -> Unit,
    onSeekPlaybackTo: (Int) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    customBubbleColorArgb: Int?,
    onDelete: () -> Unit,
    /** Null when the entry has no text (no inert Copy entry). */
    onCopy: (() -> Unit)?,
    /** Null when the entry has no text (no inert Translate entry). */
    onTranslate: (() -> Unit)?,
) {
    val display = remember(noteMessage) { noteMessage.toDisplayMessage() }
    val displayPrev = remember(prev) { prev?.toDisplayMessage() }
    val displayNext = remember(next) { next?.toDisplayMessage() }
    val showTimestamp = isLastOfSenderRun(display, displayNext)
    val burst = computeNoteBurstPosition(displayPrev, display, displayNext)
    Column {
        if (daySeparatorLabel != null) {
            NoteDateSeparator(label = daySeparatorLabel)
        }
        val audio = noteMessage.attachments.singleOrNull()?.takeIf { it.isAudio }
        when {
            audio != null -> AudioMessageBubble(
                message = display,
                audio = audio,
                playbackProvider = playbackProvider,
                onTogglePlay = { onTogglePlayback(audio) },
                onSeekTo = onSeekPlaybackTo,
                onDelete = onDelete,
                showTimestamp = showTimestamp,
                showStatus = false,
                customBubbleColorArgb = customBubbleColorArgb,
            )

            noteMessage.attachments.isNotEmpty() -> NoteMediaAttachmentBubble(
                displayMessage = display,
                noteMessage = noteMessage,
                resolver = resolver,
                onOpenAttachment = onOpenAttachment,
                showTimestamp = showTimestamp,
                customBubbleColorArgb = customBubbleColorArgb,
                onDelete = onDelete,
                onCopy = onCopy,
            )

            else -> MessageBubble(
                message = display,
                showTimestamp = showTimestamp,
                showStatus = false,
                burstPosition = burst,
                onDelete = onDelete,
                onTranslate = onTranslate,
                onCopy = onCopy,
                customBubbleColorArgb = customBubbleColorArgb,
            )
        }
    }
}

/** Max gap (ms) between two entries to count as one burst — the thread's [BURST_GAP_MS]. */
private const val NOTE_BURST_GAP_MS: Long = 60_000L

/**
 * The thread's `computeBurstPosition`, for notes: every entry is
 * outgoing, so the direction check drops out — a burst is a run of
 * entries on the same day, ≤60 s apart.
 */
private fun computeNoteBurstPosition(
    prev: Message?,
    current: Message,
    next: Message?,
): BurstPosition {
    val joinsBefore = prev != null &&
        sameDay(prev.date, current.date) &&
        current.date - prev.date <= NOTE_BURST_GAP_MS
    val joinsAfter = next != null &&
        sameDay(current.date, next.date) &&
        next.date - current.date <= NOTE_BURST_GAP_MS
    return when {
        !joinsBefore && !joinsAfter -> BurstPosition.Solo
        !joinsBefore && joinsAfter -> BurstPosition.First
        joinsBefore && joinsAfter -> BurstPosition.Middle
        else -> BurstPosition.Last
    }
}

/** True when [a] and [b] fall on the same calendar day (the thread's `sameDay`). */
internal fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * The thread's `DateSeparator` + `DatePill`, for notes (both are
 * private there): a quiet pill on the surface fill, 13sp secondary
 * text, 16dp of vertical air — no dividers above or below.
 */
@Composable
private fun NoteDateSeparator(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            FixedTypeSize {
                Text(
                    text = label,
                    style = RelayText.SystemNote,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
