package com.filestech.sms.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.theme.BrandBlue
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.RelayText

/**
 * Audio bubble for an MMS voice message. Renders a compact mini-player inside a chat bubble
 * that honours the same in/out colouring as [MessageBubble]: solid brand blue outgoing, solid
 * neutral grey incoming, with the timestamp bottom-right inside the bubble.
 *
 * Feedback round 5 — the player matches the Figma thread: a circular play button on the
 * left (Kristin's `ic_play_circle`: a disc with a play-triangle hole), a waveform in the
 * middle, and the duration at the right. The waveform IS the progress: bars before the
 * playhead are full opacity, the rest sit at 30%. There is no slider / progress bar.
 * Tapping (or dragging) anywhere on the waveform seeks to that point and starts playing
 * from there.
 *
 * Playback is delegated to the singleton [VoicePlaybackController]: the [playback] state is
 * read from the parent so we know whether THIS bubble is the active one. Tapping play/pause for
 * a different bubble automatically stops whichever clip was playing — the controller enforces
 * "only one clip at a time".
 *
 * The bubble does NOT instantiate a MediaPlayer itself; it merely emits [onTogglePlay] and
 * [onSeekTo] callbacks. This keeps the composable testable and recomposition-friendly.
 */
@Composable
fun AudioMessageBubble(
    message: Message,
    audio: Attachment,
    /**
     * Audit P-P1-2: the playback state is passed as a **lambda** rather than the raw
     * `PlaybackState` value, so the playback controller's 5 Hz progress ticks (see
     * [VoicePlaybackController.TICK_MS]) only recompose the bubble that is actually
     * active. Without this indirection, every tick invalidated every audio bubble in
     * the thread — a 500-message thread with one playing clip was burning ~2.5 k
     * recompositions/s.
     *
     * The lambda is wrapped in `derivedStateOf` below and the value field that drives
     * the waveform (`positionMs`) is only re-read while the bubble is the active one.
     */
    playbackProvider: () -> VoicePlaybackController.PlaybackState,
    onTogglePlay: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onDelete: () -> Unit = {},
    onReply: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    /**
     * v1.3.11 (F5) — forward the voice clip + sender label to another conversation. No
     * `onCopy` here: a voice MMS has no text payload to copy.
     */
    onForward: (() -> Unit)? = null,
    /**
     * Feedback round 5 — bascules « favori » / « épingler »
     * ([BubbleMenuToggles]) ; l'état épinglé est lu sur la conversation
     * (un seul message épinglé par fil), pas sur [message]. Un seul
     * paramètre pour les deux bascules : la bulle texte est à la limite
     * Detekt de 20 paramètres.
     */
    toggles: BubbleMenuToggles? = null,
    repliedToPreview: ReplyQuotePreview? = null,
    showTimestamp: Boolean = false,
    // Feedback round 4 — statut découplé de l'heure (voir [MessageBubble]).
    showStatus: Boolean = false,
    /** v1.3.3 #7 — étiquette d'expéditeur ; voir [MessageBubble] pour la sémantique. */
    senderLabel: String? = null,
) {
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme
    // Feedback round 5 — the voice bubble is a SOLID fill like the text bubbles
    // (the Figma thread shows a solid blue outgoing bubble, not the old
    // border-only one). Incoming keeps the neutral grey.
    val bgColor = if (isOut) BrandBlue else com.filestech.sms.ui.theme.bubbleIncomingColor(cs)
    // Feedback round 5 — disc + waveform colour: white on the blue outgoing
    // bubble; Kristin's dark `#353535` on the light incoming bubble, white in
    // dark mode where `#353535` would vanish on the dark grey bubble. Picked
    // from the surface luminance, like [com.filestech.sms.ui.theme.textFieldCursorColor].
    val surfaceLuma = 0.2126f * cs.surface.red + 0.7152f * cs.surface.green + 0.0722f * cs.surface.blue
    val controlColor = if (isOut) {
        Color.White
    } else if (surfaceLuma < 0.5f) {
        Color.White
    } else {
        voiceDark
    }
    // Duration + timestamp sit ON the solid fill now: white outgoing, on-surface
    // incoming (the old outgoing `onSurfaceVariant` assumed a transparent bubble).
    val labelColor = if (isOut) Color.White else cs.onSurface

    // `derivedStateOf` blocks recomposition unless the *projected* slice changes — so a tick
    // that only moves `positionMs` on a non-active bubble emits the same `LocalPlayback.None`
    // and skips the recompose. The active bubble still ticks at 5 Hz, but it's the only one.
    val key = audio.localUri
    val local by androidx.compose.runtime.remember(key) {
        androidx.compose.runtime.derivedStateOf {
            val st = playbackProvider()
            if (st.activeKey != key) LocalPlayback.Inactive
            else LocalPlayback.Active(
                isPlaying = st.isPlaying,
                positionMs = st.positionMs,
                durationMs = st.durationMs,
            )
        }
    }

    val isActiveClip = local is LocalPlayback.Active
    val isPlaying = (local as? LocalPlayback.Active)?.isPlaying == true

    // The recorded clip's encoded duration (preferred) — fall back to the slow timer captured at
    // recording time if the controller hasn't bound the source yet (durationMs == 0 until prepared).
    val totalMs = when (val s = local) {
        is LocalPlayback.Active -> if (s.durationMs > 0) s.durationMs
            else audio.durationMs?.toInt() ?: 0
        LocalPlayback.Inactive -> audio.durationMs?.toInt() ?: 0
    }
    val positionMs = (local as? LocalPlayback.Active)?.positionMs?.coerceAtMost(
        totalMs.coerceAtLeast(0),
    ) ?: 0
    val progressFraction = if (totalMs <= 0) 0f else positionMs.toFloat() / totalMs

    // Feedback round 9 — same rounder bubble language as the text bubbles
    // (24dp corners, 6dp tail; was 18dp / 4dp).
    val shape = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomStart = if (isOut) 24.dp else 6.dp,
        bottomEnd = if (isOut) 6.dp else 24.dp,
    )

    // Relay — plus de bouton ⋮ à côté de la bulle : un tap sur la bulle
    // (hors bouton lecture et waveform, qui gardent leurs gestes) ouvre le menu.
    var menuExpanded by remember { mutableStateOf(false) }

    // Feedback round 5 — tap-to-seek on a clip that has never played: the
    // controller only seeks inside a loaded clip, and loading is async
    // (`prepareAsync`), so the target is stashed until the clip is prepared
    // (duration known), then applied. A tap on an active clip seeks at once.
    var pendingSeekMs by remember(key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(isActiveClip, totalMs) {
        val pending = pendingSeekMs ?: return@LaunchedEffect
        if (isActiveClip && totalMs > 0) {
            onSeekTo(pending)
            pendingSeekMs = null
        } else if (!isActiveClip) {
            pendingSeekMs = null
        }
    }
    val seekToFraction: (Float) -> Unit = { fraction ->
        if (totalMs > 0) {
            val targetMs = (fraction * totalMs).toInt()
            if (isActiveClip) {
                onSeekTo(targetMs)
                if (!isPlaying) onTogglePlay()
            } else {
                pendingSeekMs = targetMs
                onTogglePlay()
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
        ) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // v1.3.0 — wrap dans BubbleReactionOverlay (no-op sans réaction).
            BubbleReactionOverlay(
                reactionEmoji = message.reactionEmoji,
                isOutgoing = isOut,
                bubbleColor = bgColor,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .clip(shape)
                        .background(bgColor)
                        .clickable { menuExpanded = true }
                        // Feedback round 9 — the extra left/top breathing
                        // room is gone: it looked off on renditions without
                        // a timestamp, and the bubble reads better with equal
                        // padding on all sides. End/bottom match the roomier
                        // text bubbles (16dp / 12dp) that helped overall.
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                  androidx.compose.foundation.layout.Column {
                    if (!senderLabel.isNullOrBlank()) {
                        // The label sits on the bubble fill in both directions now
                        // (the outgoing bubble is solid blue): white outgoing,
                        // on-surface incoming.
                        Text(
                            text = senderLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isOut) Color.White else cs.onSurface,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Feedback round 5 — Kristin's play disc (`ic_play_circle`):
                        // a filled disc with the play triangle as a HOLE, so the
                        // bubble colour shows through as the triangle. White disc
                        // on the blue outgoing bubble (blue triangle), dark disc
                        // on the light incoming bubble (grey triangle). While
                        // playing, the same disc carries two pause bars in the
                        // bubble colour instead. Feedback round 7 — the vector
                        // now spans the full 48dp, so the play disc and the pause
                        // disc are the same size (before, the play disc drew at
                        // 39dp and the button grew to 48dp on play).
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .then(if (isPlaying) Modifier.background(controlColor) else Modifier)
                                .clickable(onClick = onTogglePlay)
                                .semantics { role = Role.Button },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isPlaying) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = stringResource(R.string.voice_action_pause),
                                    tint = bgColor,
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play_circle),
                                    contentDescription = stringResource(R.string.voice_action_play),
                                    tint = controlColor,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        }
                        // Feedback round 10 — the gaps flanking the waveform
                        // are equal (12dp each, like Kristin's design): a
                        // spacer after the play disc and one before the
                        // duration. The waveform's own horizontal padding is
                        // gone, so the spacers are the only gaps.
                        Spacer(Modifier.width(12.dp))
                        VoiceWaveform(
                            audioId = audio.id,
                            totalMs = totalMs,
                            isActiveClip = isActiveClip,
                            isPlaying = isPlaying,
                            progressFraction = progressFraction,
                            controlColor = controlColor,
                            onSeekToFraction = { fraction -> seekToFraction(fraction) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        // Round 11 — the duration gets its own fixed-width
                        // space: an invisible copy of the total duration
                        // sizes the box, and the live time is drawn
                        // right-aligned on top of it. The total never
                        // changes for a clip, so the box never changes
                        // width — seeking or ticking can't push the
                        // waveform around. The old fixed 44dp box (round
                        // 10) left a wide empty gap; this one is exactly
                        // as wide as the time it shows. The invisible copy
                        // is hidden from accessibility (it would otherwise
                        // be read out twice).
                        val displayedMs = if (isPlaying && positionMs > 0) {
                            positionMs.toLong()
                        } else {
                            totalMs.toLong()
                        }
                        FixedTypeSize {
                            Box(modifier = Modifier.width(IntrinsicSize.Max)) {
                                Text(
                                    text = formatBubbleDuration(totalMs.toLong()),
                                    style = RelayText.BubbleMeta,
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .clearAndSetSemantics {},
                                )
                                Text(
                                    text = formatBubbleDuration(displayedMs),
                                    style = RelayText.BubbleMeta,
                                    color = labelColor,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 4.dp),
                                )
                            }
                        }
                    }
                    // Relay — heure (+ statut sortant) en petit, en bas à droite,
                    // À L'INTÉRIEUR de la bulle. Feedback round 4 — la ligne ne
                    // s'étire plus sur toute la largeur : elle se cale à droite
                    // dans la bulle (`align(End)`).
                    BubbleTimestamp(
                        message = message,
                        showTimestamp = showTimestamp,
                        showStatus = showStatus,
                        textColor = labelColor,
                        // Feedback round 5 — 12dp above the timestamp (was 2dp):
                        // the time sat too close under the waveform.
                        modifier = Modifier.padding(top = 12.dp, end = 4.dp).align(Alignment.End),
                    )
                  } // ferme Column senderLabel + Row + horodatage
                }
            }
            BubbleMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                onReply = onReply,
                onReact = onReact,
                onForward = onForward,
                starred = message.starred,
                toggles = toggles,
                onDelete = onDelete,
            )
        }
    }
}

/**
 * Feedback round 5 — the waveform IS the progress and the seek control: no
 * slider. Bars before the playhead are full opacity, the rest sit at 30%. A tap
 * (or a drag) anywhere on it seeks to that point and starts playing from there.
 * The bar heights are seeded by [audioId] so the same voice clip always renders
 * the same wave silhouette across recompositions.
 *
 * Kept out of [AudioMessageBubble] so that bubble stays under the complexity
 * limit: the gesture loop and the bar drawing carry their own branches.
 */
@Composable
private fun VoiceWaveform(
    audioId: Long,
    totalMs: Int,
    isActiveClip: Boolean,
    isPlaying: Boolean,
    progressFraction: Float,
    controlColor: Color,
    onSeekToFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tap-to-seek uses the same gesture shape as the push-to-talk mic button
    // (`awaitEachGesture` + `awaitFirstDown`, then a drag loop until the finger
    // lifts): `awaitPointerEvent(PointerEventType.Press)` does not exist —
    // `awaitPointerEvent` takes a `PointerEventPass`, not a type.
    val seekGesture = Modifier.pointerInput(totalMs, isActiveClip, isPlaying) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onSeekToFraction(
                (down.position.x / size.width).coerceIn(0f, 1f),
            )
            down.consume()
            // One flag instead of two `break`s: the drag loop ends
            // when the finger lifts (or the change vanishes).
            var dragging = true
            while (dragging) {
                val event = awaitPointerEvent()
                val move = event.changes.firstOrNull()
                if (move == null || !move.pressed) {
                    dragging = false
                } else {
                    onSeekToFraction(
                        (move.position.x / size.width).coerceIn(0f, 1f),
                    )
                    move.consume()
                }
            }
        }
    }
    Box(
        modifier = modifier
            // Feedback round 10 — no horizontal padding here: the 12dp
            // spacers flanking the waveform in the row are the gaps
            // (was 8dp inside, which made the play-side gap smaller
            // than the duration-side one).
            .then(seekGesture),
        contentAlignment = Alignment.Center,
    ) {
        val barHeights = androidx.compose.runtime.remember(audioId) {
            val rng = kotlin.random.Random(audioId.takeIf { it != 0L } ?: 1L)
            IntArray(WAVE_BAR_COUNT) { rng.nextInt(4, 31) }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
        ) {
            val n = barHeights.size
            val barWidthPx = 1.5.dp.toPx()
            val spacing = size.width / n
            val cy = size.height / 2f
            for (i in 0 until n) {
                val cx = spacing * i + spacing / 2f
                val halfH = barHeights[i].dp.toPx() / 2f
                val played = (i + 0.5f) / n <= progressFraction
                drawLine(
                    color = controlColor.copy(
                        alpha = if (played) 1f else 0.3f,
                    ),
                    start = androidx.compose.ui.geometry.Offset(
                        cx,
                        cy - halfH,
                    ),
                    end = androidx.compose.ui.geometry.Offset(
                        cx,
                        cy + halfH,
                    ),
                    strokeWidth = barWidthPx,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Builds a `content://` (FileProvider) or `file://` Uri usable by [VoicePlaybackController]
 * from the persisted [Attachment.localUri]. Strings stored in the DB might be either form
 * depending on origin (recording cache file vs. MMSC-downloaded payload).
 */
fun Attachment.toPlaybackUri(): Uri {
    val raw = localUri
    return if (raw.startsWith("content://") || raw.startsWith("file://")) {
        Uri.parse(raw)
    } else {
        Uri.fromFile(java.io.File(raw))
    }
}

private fun formatBubbleDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * Feedback round 5 — dark colour of the play disc and the waveform bars on an incoming
 * bubble (Kristin's `ic_play_circle` fill). White in dark mode instead — see the
 * `controlColor` computation above.
 */
private val voiceDark: Color = Color(0xFF353535)

/**
 * v1.4.0 — number of waveform bars. Feedback round 6 — 56 thin bars
 * (1.5dp stroke, ~3dp pitch in the ~180-dp waveform): the Figma voice
 * message shows a dense row of fine bars, not the 28 thick spaced-out
 * bars of round 5. Still cheap to draw (single Canvas pass, no
 * recomposition from the bars themselves — only the 5 Hz progress tick
 * re-reads `positionMs`).
 */
private const val WAVE_BAR_COUNT: Int = 56

/**
 * Per-bubble projection of [VoicePlaybackController.PlaybackState]. The bubble derives its
 * value via [androidx.compose.runtime.derivedStateOf], which suppresses recomposition unless
 * the projection actually changes — non-active bubbles always emit [Inactive] and stay frozen
 * even while the controller ticks for the active clip.
 */
private sealed interface LocalPlayback {
    data object Inactive : LocalPlayback
    data class Active(
        val isPlaying: Boolean,
        val positionMs: Int,
        val durationMs: Int,
    ) : LocalPlayback
}
