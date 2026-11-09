package com.filestech.sms.ui.components

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.otp.OtpCodeDetector
import com.filestech.sms.ui.security.copyToClipboardSensitive
import com.filestech.sms.ui.theme.BrandBlue

/**
 * Position of a message inside a burst (consecutive messages from the same sender, < 60 s apart).
 * Used to draw distinctive bubble shapes — only [Solo] and [First] carry the classic "tail"
 * corner; middle / last bubbles flatten the joining edge so the burst reads as a single block.
 *
 * The caller (ThreadScreen) computes this; the bubble stays stateless.
 */
enum class BurstPosition { Solo, First, Middle, Last }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    showTimestamp: Boolean,
    // Feedback round 4 — statut découplé de l'heure : il s'affiche sur le
    // dernier message sortant d'une rafale, même si son heure est masquée.
    showStatus: Boolean = false,
    burstPosition: BurstPosition = BurstPosition.Solo,
    onTap: () -> Unit = {},
    /** Emitted when the user picks "Supprimer" in the bubble's overflow menu. */
    onDelete: () -> Unit = {},
    onReply: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    /**
     * v1.3.11 (F3) — copy the bubble's text body to the clipboard, from the overflow menu.
     * `null` for bubbles without a text payload.
     */
    onCopy: (() -> Unit)? = null,
    /** v1.3.11 (F5) — forward the bubble's text body to another conversation. */
    onForward: (() -> Unit)? = null,
    /**
     * Feedback round 5 — bascules « favori » / « épingler »
     * ([BubbleMenuToggles]) ; l'état épinglé est lu sur la conversation
     * (un seul message épinglé par fil), pas sur [message]. Un seul
     * paramètre pour les deux bascules : la bulle texte est à la limite
     * Detekt de 20 paramètres.
     */
    toggles: BubbleMenuToggles? = null,
    /**
     * v1.3.11 (F4) — invoked when the user taps a phone number rendered inside the
     * bubble body. Forwarded to [MessageTextWithLinks]; the host (`ThreadScreen`)
     * surfaces a [PhoneActionsDialog] in response.
     */
    onPhoneClick: (String) -> Unit = {},
    /**
     * Feedback round 5 — thread search highlight for this bubble's body: every
     * occurrence of the query, and whether the search is currently sitting on
     * this message. Empty by default: no search, no highlight.
     */
    searchHighlight: SearchHighlight = SearchHighlight(),
    repliedToPreview: ReplyQuotePreview? = null,
    /**
     * v1.3.3 #7 — étiquette d'expéditeur intégrée dans la bulle ("Vous" pour les
     * messages sortants, nom du contact pour les messages reçus). `null` = pas
     * d'étiquette (cas des bulles "Middle" et "Last" dans un burst). Le caller
     * ([com.filestech.sms.ui.screens.thread.ThreadScreen]) ne passe l'étiquette
     * que sur la 1ʳᵉ bulle d'un burst pour ne pas surcharger visuellement.
     */
    senderLabel: String? = null,
    /**
     * v1.11.0 — Sujet 5 apparence : couleur ARGB personnalisée pour la bulle
     * SORTANTE de cette conversation. `null` = utilise [com.filestech.sms.ui
     * .theme.BrandBlue] fixe (bleu de marque foncé). Sélectionnée par l'user dans `AppearanceDialog`
     * parmi [com.filestech.sms.ui.theme.BubbleColorPalette.OPTIONS]
     * (palette WCAG-safe). Aucun effet sur les bulles entrantes.
     */
    customBubbleColorArgb: Int? = null,
    /**
     * v1.11.0 — Sujet 3 anti-smishing : raisons détectées sur le corps de ce
     * message. Si non-vide ET message entrant, affiche un
     * [com.filestech.sms.ui.components.SmishingBanner] rouge sous la bulle.
     * Calculé en amont (ThreadScreen via [com.filestech.sms.domain.smishing
     * .SmishingDetector]) — MessageBubble ne fait que le rendu.
     */
    smishingReasons: List<com.filestech.sms.domain.smishing.SmishingReason> = emptyList(),
) {
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme

    // Feedback round 5 — login/authentication code in an incoming message,
    // for the copy pill below. Pure regexes on the body, remembered per body
    // so a busy thread does not re-scan on every recomposition. Outgoing
    // messages never carry a pill — a code the user sent needs no copying.
    // Feedback round 6 — the scan itself is cached per message id
    // ([OtpCodeCache]): the `remember` only survives while the bubble is
    // composed, so scrolling back re-ran the OTP regexes on every first
    // composition.
    val otpCode: String? = remember(message.body, isOut) {
        if (isOut) null else OtpCodeCache.detectFor(message.id, message.body)
    }

    // Outgoing bubbles use a subtle vertical gradient on top of the primary color — gives the
    // bubble depth without committing to a hard shadow or a custom drawable. Incoming bubbles
    // stay flat surfaceContainerHigh so reading them remains restful.
    // v1.11.0 — Sujet 5 apparence : couleur de bulle sortante personnalisée par
    // conversation. v1.19.0 — le défaut (`null`) rend désormais [BrandBlue] FIXE
    // au lieu de `cs.primary` : sous Material You / One UI, `cs.primary` suit le
    // fond d'écran et virait au bleu clair. La bulle de marque doit rester le
    // bleu foncé stable (cohérent avec la bulle entrante, elle aussi fixe).
    val outgoingBaseColor = customBubbleColorArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: BrandBlue
    // Feedback round 6 — remembered: building the gradient (and its colour
    // list) on every composition allocated per bubble, per recomposition,
    // for a value that only depends on the outgoing colour.
    val outgoingBrush = remember(outgoingBaseColor) {
        Brush.linearGradient(
            colors = listOf(outgoingBaseColor, outgoingBaseColor.copy(alpha = 0.88f)),
            start = Offset(0f, 0f),
            end = Offset(0f, Float.POSITIVE_INFINITY),
        )
    }
    // Texte blanc forcé sur toute bulle sortante : BrandBlue et chaque couleur de
    // [BubbleColorPalette] sont calibrées WCAG AA ≥ 4.5:1 contre le blanc. On ne
    // dépend plus de `cs.onPrimary` (qui pouvait dériver avec le scheme dynamique).
    val textColor = if (isOut) androidx.compose.ui.graphics.Color.White else cs.onSurface

    // Feedback round 6 — remembered like the gradient above: a new
    // `RoundedCornerShape` per composition for a value that only depends on
    // direction + burst position.
    val shape = remember(isOut, burstPosition) { bubbleShape(isOut, burstPosition) }

    // Relay — plus de bouton ⋮ à côté de la bulle : un tap sur la bulle ouvre
    // le menu ([BubbleMenu]), un appui long ouvre le menu aussi (la sélection
    // libre est supprimée, round 6), et un tap sur un message FAILED garde
    // le retry ([onTap]).
    var menuExpanded by remember { mutableStateOf(false) }

    // Feedback round 6 — press-down feedback: while the finger is down the
    // bubble sinks 2dp, so a long-press feels like pressing the bubble itself.
    // The ripple stays (LocalIndication); the translation rides on top of it.
    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val density = LocalDensity.current
    val pressOffsetY by animateFloatAsState(
        targetValue = if (pressed) with(density) { 2.dp.toPx() } else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "bubblePressOffsetY",
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .padding(bubbleVerticalPadding(burstPosition)),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // Stack the optional reply quote ABOVE the bubble — same horizontal alignment so it
        // visually anchors to the same side as the bubble. Inline column to keep the row
        // layout intact.
        // `weight(1f, fill = false)` : la bulle prend la largeur qu'il lui faut (plafonnée
        // à 320 dp) sans s'étirer sur toute la ligne ; une bulle courte garde sa largeur
        // naturelle, et l'alignement d'entrée ou de sortie est inchangé.
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
        ) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            // v1.3.0 — wrap dans BubbleReactionOverlay : no-op si pas de réaction (early
            // return interne), sinon affiche le cercle emoji en chevauchement.
            BubbleReactionOverlay(
                reactionEmoji = message.reactionEmoji,
                isOutgoing = isOut,
                bubbleColor = if (isOut) {
                    outgoingBaseColor
                } else {
                    com.filestech.sms.ui.theme.bubbleIncomingColor(cs)
                },
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 32.dp, max = 320.dp)
                        // Feedback round 7 — the press translation must wrap the
                        // WHOLE bubble: it used to sit after the background in
                        // the chain, so only the text inside the layer moved and
                        // the fill stayed put. First in the chain, the fill,
                        // the clip and the content all sink 2dp together.
                        .graphicsLayer { translationY = pressOffsetY }
                        .clip(shape)
                        .then(
                            if (isOut) Modifier.drawBehind { drawRect(outgoingBrush) }
                            else Modifier.background(com.filestech.sms.ui.theme.bubbleIncomingColor(cs)),
                        )
                        // Relay — tap opens the bubble menu ([BubbleMenu]) ; tap on a
                        // FAILED message still retries ([onTap]). Feedback round 6 —
                        // long-press opens the menu too (the free text selection is
                        // gone); FAILED keeps its tap-to-retry on tap only.
                        .combinedClickable(
                            interactionSource = pressSource,
                            indication = LocalIndication.current,
                            onClick = {
                                if (
                                    message.status ==
                                    com.filestech.sms.domain.model.Message.Status.FAILED
                                ) {
                                    onTap()
                                } else {
                                    menuExpanded = true
                                }
                            },
                            onLongClick = { menuExpanded = true },
                        )
                        // Feedback round 6 — the bubble sinks 2dp while pressed
                        // (see pressOffsetY above). Feedback round 7 — the
                        // graphicsLayer moved to the START of the chain (before
                        // the clip/background): it used to sit here, after the
                        // background, so only the text moved and the fill stayed.
                        .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
                ) {
                    // v1.3.2 — URLs détectées et rendues cliquables (ouvre le navigateur
                    // système via UriHandler). Fallback texte brut si pas d'URL.
                    //
                    // Y6 audit : sur message FAILED, le tap sur la bulle entière déclenche
                    // un retry ([onTap] ligne ci-dessus). Linkifier les URLs créerait un
                    // conflit de gestes : un tap sur une URL au milieu du body pourrait
                    // soit ouvrir le browser, soit déclencher le retry, voire les deux
                    // selon le device. On désactive la linkification dans ce cas — l'UX
                    // "tap pour réessayer" reste sans ambiguïté. L'URL reste visible en
                    // texte brut ; l'utilisateur peut copier-coller ou retry puis cliquer.
                    Column {
                        if (!senderLabel.isNullOrBlank()) {
                            // v1.3.3 #7 — étiquette en gras + couleur de contraste ; padding-bottom
                            // 2dp pour aérer sans agrandir trop la bulle.
                            Text(
                                text = senderLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = textColor,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        if (message.status == com.filestech.sms.domain.model.Message.Status.FAILED) {
                            Text(
                                text = message.body,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                            )
                        } else if (message.estUnMmsSansContenu()) {
                            // v1.28.4 — un MMS restauré d'une sauvegarde n'a plus ses pièces
                            // jointes : la bulle le DIT, plutôt que de rester vide.
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    com.filestech.sms.R.string.bubble_mms_parts_missing,
                                ),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                ),
                                color = textColor,
                            )
                        } else {
                            MessageTextWithLinks(
                                text = message.body,
                                messageId = message.id,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                onPhoneClick = onPhoneClick,
                                searchHighlight = searchHighlight,
                            )
                        }
                        // Feedback round 5 — detected login code: a copy pill under
                        // the text, incoming messages only (see [OtpCodePill]).
                        // The pill copies by itself (no extra parameter: the bubble
                        // is already at the parameter limit) — clipboard, haptic
                        // and toast all happen inside it.
                        if (otpCode != null) {
                            OtpCodePill(
                                code = otpCode,
                                modifier = Modifier.padding(top = 8.dp).align(Alignment.Start),
                            )
                        }
                        // Relay — heure (+ statut sortant) en petit, en bas à droite,
                        // À L'INTÉRIEUR de la bulle, alignée sur la dernière ligne de texte.
                        // Feedback round 4 — la ligne ne s'étire plus sur toute la
                        // largeur : elle se cale à droite dans la bulle (`align(End)`),
                        // donc elle ne peut plus sortir du fond sur une bulle étroite.
                        // Feedback round 7 — the timestamp is right-aligned again,
                        // like every other text bubble: the round-6 left alignment
                        // (to match the Figma copy-code mockup) is dropped with the
                        // pill's surface fill, so text, pill and time all read as
                        // one bubble again.
                        BubbleTimestamp(
                            message = message,
                            showTimestamp = showTimestamp,
                            showStatus = showStatus,
                            textColor = textColor,
                            // Feedback round 5 — 12dp above the timestamp (was 2dp):
                            // the time sat too close under the text.
                            modifier = Modifier.padding(top = 12.dp).align(Alignment.End),
                        )
                    }
                }
            }
            BubbleMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                onCopy = onCopy,
                onForward = onForward,
                onReply = onReply,
                onTranslate = onTranslate,
                onReact = onReact,
                starred = message.starred,
                toggles = toggles,
                onDelete = onDelete,
            )
            // v1.7.1 — inline TranslationBlock removed (was rendering an in-app
            // translation result from ML Kit). Translation now happens out-of-process
            // via ACTION_PROCESS_TEXT — the user's chosen translation app shows the
            // result in its own UI, so no in-bubble overlay is needed anymore.
        }
    }
    // v1.11.0 — Sujet 3 anti-smishing : bandeau rouge sous la bulle entrante
    // si le SmishingDetector a flaggé le contenu. Tap → dialog "Pourquoi".
    // N'affecte JAMAIS les bulles sortantes (l'user fait confiance à ce
    // qu'il écrit). Full-width pour maximiser la visibilité.
    if (!isOut && smishingReasons.isNotEmpty()) {
        SmishingBanner(reasons = smishingReasons)
    }
}

/**
 * Feedback round 5 — one-tap copy for a detected login/authentication code:
 * the code as a rounded pill under the message text, with a copy glyph.
 *
 * Feedback round 6 — the pill matches the Figma design
 * (02___Thread_copy_code) pixel for pixel: a SURFACE fill with a thin
 * 1dp on-surface border (not the round-5 solid primaryContainer fill),
 * on-surface code text in the regular font (not monospace), and the copy
 * glyph in the same colour. The timestamp sits bottom-left under the pill
 * (see the call site), so text, pill and time all align left.
 *
 * Feedback round 7 — border only: the surface fill is dropped, so the pill
 * is transparent with just its 1dp border, and the timestamp goes back to
 * right-aligned like every other text bubble. Padding grows to 15dp
 * horizontal / 9dp vertical.
 *
 * The pill copies by itself — no callback parameter, the bubble is already at
 * the parameter limit: the code goes to the clipboard marked sensitive (no
 * system preview of it, cf. [copyToClipboardSensitive]), with a haptic tick
 * and a toast as confirmation. The pill carries its own `clickable`: it sits
 * inside the bubble's `combinedClickable` (tap opens the menu), and the inner
 * clickable wins, so tapping the pill copies instead of opening the menu.
 */
@Composable
private fun OtpCodePill(
    code: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Hoisted: stringResource is @Composable and cannot run inside .semantics {}.
    val copyCodeLabel = stringResource(R.string.otp_copy_code)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, cs.onSurface.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(
                onClick = {
                    context.copyToClipboardSensitive(
                        label = "verification code",
                        text = code,
                    )
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    Toast.makeText(
                        context,
                        R.string.toast_code_copied,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            .semantics {
                contentDescription = copyCodeLabel
                role = Role.Button
            }
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = null,
            tint = cs.onSurface,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Feedback round 6 — per-message cache for the OTP regex scan
 * ([OtpCodeDetector.detect] runs the keyword + digit regexes over the body).
 * Same shape as [LinkHitCache]: the `remember` in [MessageBubble] only
 * survives while the bubble is composed, so scrolling back re-ran the scan
 * on every first composition. Keyed by message id, validated against the
 * body. Bounded (256 entries, eldest evicted).
 */
private object OtpCodeCache {
    private const val MAX_ENTRIES: Int = 256
    private val entries = object : LinkedHashMap<Long, Pair<String, String?>>(MAX_ENTRIES) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, Pair<String, String?>>,
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun detectFor(messageId: Long, body: String): String? {
        val cached = entries[messageId]
        if (cached != null && cached.first == body) return cached.second
        val code = OtpCodeDetector.detect(body)
        entries[messageId] = body to code
        return code
    }
}

/**
 * Builds the rounded-corner shape that matches the bubble's position in a burst. Solo and first
 * bubbles keep the "tail" corner (4 dp) on the speaker side; mid / last bubbles flatten the
 * joining edge so the burst reads as one connected stack.
 */
private fun bubbleShape(isOut: Boolean, position: BurstPosition): RoundedCornerShape {
    val tail = 4.dp
    val full = 20.dp
    return when (position) {
        BurstPosition.Solo -> RoundedCornerShape(
            topStart = full,
            topEnd = full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.First -> RoundedCornerShape(
            topStart = full,
            topEnd = full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.Middle -> RoundedCornerShape(
            topStart = if (isOut) full else tail,
            topEnd = if (isOut) tail else full,
            bottomStart = if (isOut) full else tail,
            bottomEnd = if (isOut) tail else full,
        )
        BurstPosition.Last -> RoundedCornerShape(
            topStart = if (isOut) full else tail,
            topEnd = if (isOut) tail else full,
            bottomStart = full,
            bottomEnd = full,
        )
    }
}

/**
 * v1.3.3 #7 — espacement vertical entre bulles. Feedback round 6 — asymétrique :
 * le haut et le bas se partagent selon la position, pour que deux bulles
 * consécutives du même expéditeur se touchent presque (4dp au total) sans
 * resserrer les frontières de rafale (16dp) :
 *  - Solo : 8dp en haut et en bas (message isolé, de l'air des deux côtés).
 *  - First : 8dp en haut (frontière), 2dp en bas (serré sur la suivante).
 *  - Middle : 2dp des deux côtés.
 *  - Last : 2dp en haut, 8dp en bas (frontière).
 * Avant : une seule valeur symétrique (8dp aux frontières) — une rafale de deux
 * messages avait 8+8 = 16dp entre ses bulles.
 */
private fun bubbleVerticalPadding(position: BurstPosition): PaddingValues = when (position) {
    BurstPosition.Solo -> PaddingValues(vertical = 8.dp)
    BurstPosition.First -> PaddingValues(top = 8.dp, bottom = 2.dp)
    BurstPosition.Middle -> PaddingValues(vertical = 2.dp)
    BurstPosition.Last -> PaddingValues(top = 2.dp, bottom = 8.dp)
}

/** v1.28.4 — un MMS sans texte ni pièce jointe : le cas d'une restauration, jamais d'une réception. */
private fun com.filestech.sms.domain.model.Message.estUnMmsSansContenu(): Boolean =
    type == com.filestech.sms.domain.model.Message.Type.MMS && body.isBlank() && attachments.isEmpty()
