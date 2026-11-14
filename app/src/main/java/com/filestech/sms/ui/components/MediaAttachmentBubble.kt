package com.filestech.sms.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayCircleOutline
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.bubbleIncomingColor
import com.filestech.sms.ui.theme.onBrandBlue
import timber.log.Timber
import java.io.File

/**
 * v1.3.3 bug #2 — bulle dédiée aux pièces jointes **non-audio** (image / vidéo /
 * fichier). Le tap ouvre la PJ via [Intent.ACTION_VIEW] + FileProvider URI dans
 * l'app système associée au MIME type (Galerie pour image, lecteur vidéo, etc.).
 *
 * Architecture :
 *
 *   - **Image** : thumbnail rendu par Coil ([AsyncImage]) avec contentScale = Crop,
 *     max 240×320 dp pour cohérence avec [MessageBubble]. Tap → `ACTION_VIEW`.
 *   - **Vidéo** : première frame en vignette (Coil [VideoFrameDecoder],
 *     même taille/forme que l'image) avec le disque lecture des messages
 *     vocaux centré dessus. Si la frame ne se décode pas (vidéo corrompue,
 *     URI `content://` morte), repli sur l'icône play + libellé. Tap →
 *     `ACTION_VIEW` ouvre le player système.
 *   - **Autre fichier** : icône doc + nom + taille. Tap → `ACTION_VIEW` (chooser).
 *
 * Sécurité : URI émise via [FileProvider.getUriForFile] avec
 * `FLAG_GRANT_READ_URI_PERMISSION` strict — l'app cible ne peut PAS lire d'autre
 * fichier que celui partagé. Cohérent avec l'audit F1 PDF Tech (allowed roots).
 *
 * Cohérence visuelle : reuse [outgoingBubbleColor] / [bubbleIncomingColor] et le
 * [BubbleMenu] (delete/reply/react) du pattern existant — l'utilisateur a
 * les mêmes actions que sur les bulles texte/audio.
 */
@Composable
fun MediaAttachmentBubble(
    message: Message,
    attachment: Attachment,
    /**
     * v1.28.3 (audit global, X-04 — mesuré sur le S9) — **les autres pièces jointes du même
     * message**, affichées sous la première. F16 avait appris au receveur à garder toutes les
     * parties d'un MMS ; l'écran, lui, continuait de n'en montrer qu'une — trois photos reçues,
     * trois fichiers écrits, une seule bulle. Le correctif de données n'avait pas son jumeau
     * d'affichage.
     */
    autres: List<Attachment> = emptyList(),
    showTimestamp: Boolean,
    // Feedback round 4 — statut découplé de l'heure (voir [MessageBubble]).
    showStatus: Boolean = false,
    onDelete: () -> Unit,
    onReply: () -> Unit,
    onReact: (() -> Unit)?,
    /**
     * Round 22 — quick-reaction bar on long-press (incoming only, same
     * condition as [onReact]): the caller passes the same bundle
     * [MessageBubble] gets. Tap still opens the viewer, so the bar lives
     * on the long-press instead of the tap.
     */
    quickReactions: QuickReactionConfig? = null,
    /**
     * v1.3.11 (F3) — copy the caption (`message.body`) to the clipboard. Caller passes
     * `null` when the message has no caption to avoid an inert "Copier" menu entry.
     */
    onCopy: (() -> Unit)? = null,
    /** v1.3.11 (F5) — forward the attachment (+ caption if any) to another conversation. */
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
     * caption block. Forwarded to [MessageTextWithLinks]; the host (`ThreadScreen`)
     * surfaces a [PhoneActionsDialog] in response.
     */
    onPhoneClick: (String) -> Unit = {},
    repliedToPreview: com.filestech.sms.ui.components.ReplyQuotePreview? = null,
    /** v1.3.3 #7 — étiquette d'expéditeur ; voir [MessageBubble] pour la sémantique. */
    senderLabel: String? = null,
    /**
     * Feedback round 5 — thread search highlight for the caption, same
     * semantics as [MessageBubble.searchHighlight]: every occurrence of the
     * query in `message.body`, current when the search is sitting on this
     * message. Empty by default.
     */
    searchHighlight: SearchHighlight = SearchHighlight(),
) {
    val context = LocalContext.current
    val isOut = message.isOutgoing
    val cs = MaterialTheme.colorScheme
    // Outgoing : [brandBlue] (cohérent avec [MessageBubble] et
    // [AudioMessageBubble]) — bleu de marque fixe hors couleurs dynamiques,
    // `primary` du fond d'écran quand elles sont actives (round 20). Le
    // texte sortant suit avec [onBrandBlue] (voir ci-dessous).
    val bgColor = if (isOut) brandBlue() else bubbleIncomingColor(cs)
    // Feedback round 9 — same rounder bubble language as the text bubbles
    // (24dp corners; was 18dp).
    val shape = RoundedCornerShape(24.dp)

    // X-04 — une seule fonction d'ouverture, paramétrée par la pièce jointe touchée.
    val ouvrir: (Attachment) -> Unit = remember(message.id) {
        fun(cible: Attachment) = ouvrirPieceJointe(context, cible)
    }

    // Relay — plus de bouton ⋮ à côté de la bulle : un tap sur la bulle ouvre
    // le menu. Le tap sur une vignette garde l'ouverture de la pièce jointe ;
    // l'appui long sur la vignette ouvre la barre de réaction rapide
    // ([QuickReactionBar], entrant uniquement) ou le menu — avant, le
    // `clickable` interne consommait le geste et l'appui long ne faisait
    // rien sur la vignette, donc une image/GIF sans légende n'avait AUCUN
    // chemin vers le menu ni vers les réactions (round 22).
    var menuExpanded by remember { mutableStateOf(false) }
    var quickBarExpanded by remember { mutableStateOf(false) }
    // Round 22 — same choice as [MessageBubble]'s tap, on the long-press:
    // the quick bar when the caller wired reactions, the full menu
    // otherwise. Shared by the outer column (caption area) and the
    // thumbnails below.
    val onLongPress: () -> Unit = {
        if (quickReactions != null) quickBarExpanded = true else menuExpanded = true
    }

    Row(
        // Feedback round 5 — `fillMaxWidth` was missing: without it the row
        // wrapped its content and `Arrangement.End` did nothing, so a SENT
        // image landed on the left like a received one (text and audio rows
        // both fill the width). The overlaid timestamp pill is untouched.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .heightIn(min = 32.dp),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
            modifier = Modifier.combinedClickable(
                onClick = { menuExpanded = true },
                onLongClick = onLongPress,
            ),
        ) {
            if (repliedToPreview != null) {
                ReplyQuoteCard(
                    preview = repliedToPreview,
                    isOutgoingHost = isOut,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (!senderLabel.isNullOrBlank()) {
                Text(
                    text = senderLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurface,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = if (isOut) 0.dp else 4.dp,
                        end = if (isOut) 4.dp else 0.dp,
                        bottom = 2.dp,
                    ),
                )
            }
            // Feedback round 4 — l'heure (+ statut) est TOUJOURS dans la bulle :
            // sans légende, elle est en surimpression en bas à droite de la
            // dernière vignette (pastille sombre pour rester lisible sur
            // n'importe quelle image, dans les deux thèmes) ; avec une légende,
            // elle vit dans le fond de la légende (voir ci-dessous). Avant, elle
            // se plaçait sous la vignette, hors de tout fond de bulle.
            Box {
                Column {
                    BubbleReactionOverlay(
                        reactionEmoji = message.reactionEmoji,
                        isOutgoing = isOut,
                        bubbleColor = bgColor,
                        // Round 21 — badge tap removes the reaction; the
                        // callback rides [BubbleMenuToggles] (see
                        // [MessageBubble]).
                        onRemoveReaction = toggles?.onRemoveReaction ?: {},
                    ) {
                        MediaPreview(
                            attachment,
                            shape,
                            bgColor,
                            isOut,
                            onLongClick = onLongPress,
                        ) { ouvrir(attachment) }
                    }
                    // X-04 — les pièces jointes suivantes, empilées sous la première, chacune avec son
                    // propre tap-to-view. La réaction et la légende restent celles du message.
                    autres.forEach { autre ->
                        Spacer(Modifier.size(4.dp))
                        MediaPreview(
                            autre,
                            shape,
                            bgColor,
                            isOut,
                            onLongClick = onLongPress,
                        ) { ouvrir(autre) }
                    }
                }
                if (message.body.isBlank()) {
                    BubbleTimestamp(
                        message = message,
                        showTimestamp = showTimestamp,
                        showStatus = showStatus,
                        textColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // v1.3.10 — caption text affiché EN DESSOUS de l'image / icône quand
            // l'utilisateur a saisi du texte avant d'envoyer un MMS image / vidéo /
            // fichier. Avant ce fix, MediaAttachmentBubble ignorait silencieusement
            // `message.body` au rendu — le texte était bien envoyé dans le PDU MMS
            // (donc visible côté destinataire) mais invisible localement, ce qui
            // donnait l'impression à l'utilisateur que son texte avait disparu.
            // Pattern WhatsApp / Google Messages : image en haut + caption en bas
            // dans la même bulle, mêmes couleurs (cohérence visuelle).
            if (message.body.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                // v1.3.11 (F4) — linkify the caption so phone numbers + URLs inside an
                // image / file / video caption are tappable just like in a plain SMS body.
                // Feedback round 4 — la légende porte l'heure (+ statut) DANS son
                // fond, en bas à droite, comme une bulle texte.
                Column(
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .clip(shape)
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    MessageTextWithLinks(
                        text = message.body,
                        messageId = message.id,
                        style = MaterialTheme.typography.bodyLarge,
                        // Round 20 — [onBrandBlue] sur le fond sortant : blanc
                        // sur le bleu fixe, `onPrimary` du scheme dynamique
                        // quand les couleurs dynamiques sont actives.
                        color = if (isOut) onBrandBlue() else cs.onSurface,
                        onPhoneClick = onPhoneClick,
                        searchHighlight = searchHighlight,
                    )
                    BubbleTimestamp(
                        message = message,
                        showTimestamp = showTimestamp,
                        showStatus = showStatus,
                        textColor = if (isOut) onBrandBlue() else cs.onSurface,
                        // Feedback round 5 — 12dp above the timestamp (was 2dp):
                        // the time sat too close under the caption.
                        modifier = Modifier.padding(top = 12.dp).align(Alignment.End),
                    )
                }
            }
            BubbleMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                onCopy = onCopy,
                onForward = onForward,
                onReply = onReply,
                onReact = onReact,
                starred = message.starred,
                toggles = toggles,
                onDelete = onDelete,
            )
            // Round 22 — quick-reaction bar on long-press (incoming only).
            // The "+" reuses [onReact]: it opens the full emoji picker for
            // this message, exactly like the menu's "React" item.
            if (quickReactions != null) {
                QuickReactionBar(
                    expanded = quickBarExpanded,
                    onDismissRequest = { quickBarExpanded = false },
                    recentEmojis = quickReactions.recentEmojis,
                    onEmojiSelected = { emoji ->
                        quickBarExpanded = false
                        quickReactions.onSelect(emoji)
                    },
                    onOpenPicker = {
                        quickBarExpanded = false
                        onReact?.invoke()
                    },
                )
            }
        }
    }
}

/** Une pièce jointe, selon son type : vignette, icône vidéo ou icône de fichier. */
@Composable
private fun MediaPreview(
    attachment: Attachment,
    shape: androidx.compose.ui.graphics.Shape,
    bgColor: androidx.compose.ui.graphics.Color,
    isOutgoing: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    when {
        attachment.isImage -> ImagePreview(
            attachment = attachment,
            shape = shape,
            bgColor = bgColor,
            onLongClick = onLongClick,
            onClick = onClick,
        )
        attachment.mimeType.startsWith("video/", ignoreCase = true) ->
            VideoPreview(
                attachment = attachment,
                shape = shape,
                bgColor = bgColor,
                isOutgoing = isOutgoing,
                onLongClick = onLongClick,
                onClick = onClick,
            )
        else -> IconAttachment(
            attachment = attachment,
            icon = if (attachment.mimeType.startsWith("text/")) Icons.Outlined.Description else Icons.Outlined.AttachFile,
            iconLabelRes = R.string.attachment_file_label,
            shape = shape,
            bgColor = bgColor,
            isOutgoing = isOutgoing,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

/**
 * Thumbnail image via Coil. Tap = ACTION_VIEW; round 22 — long-press opens
 * the quick-reaction bar (incoming) or the full menu.
 *
 * Round 16 — the image loads through a local loader with the GIF decoder
 * (`io.coil-kt:coil-gif`), so animated GIFs play in the thread instead of
 * showing a static first frame. Same pattern as the video-frame loader in
 * [VideoPreview]. The 1dp border is gone: it read as a blue outline in
 * dark mode — the rounded clip alone shapes the bubble.
 */
@Composable
private fun ImagePreview(
    attachment: Attachment,
    shape: androidx.compose.ui.graphics.Shape,
    bgColor: androidx.compose.ui.graphics.Color,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val gifImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(GifDecoder.Factory()) }
            .build()
    }
    Box(
        modifier = Modifier
            .widthIn(min = 80.dp, max = 240.dp)
            .heightIn(min = 80.dp, max = 320.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // v1.3.10 (P5) — Coil `.size(px)` cap so multi-megapixel incoming MMS images (2048×1536
        // iPhone shots are routine) are downsampled at decode time instead of decoding the full
        // bitmap and scaling on the GPU. Without the cap, a thread with 10 photo MMS easily
        // allocates 120 MiB of Bitmap on the Java heap → OOM on Android Go (Redmi 9A, 2 GiB RAM).
        // 480 px ≈ 240 dp × 2 density factor (xxhdpi) — sharp on every device, ~230 KiB per image.
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(attachment.modeleImage())
                .size(THUMBNAIL_PX)
                .crossfade(true)
                .build(),
            imageLoader = gifImageLoader,
            contentDescription = attachment.fileName ?: stringResource(R.string.attachment_image_label),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 240.dp, height = 240.dp),
            // F-11 — les MMS importés du système stockent `content://mms/part/N` sans copie
            // locale (contrairement aux MMS temps réel copiés dans filesDir). Si le provider
            // Telephony est purgé (réinstallation, changement temporaire d'app SMS par défaut,
            // suppression cross-app), l'URI devient invalide → Coil échoue. On rend alors un
            // tile neutre (au lieu d'un trou blanc) et on logue pour diagnostic.
            error = ColorPainter(bgColor),
            fallback = ColorPainter(bgColor),
            onError = {
                Timber.w("MMS thumbnail load failed for attachment %d", attachment.id)
            },
        )
    }
}

/**
 * Round 13 — video thumbnail: the first frame, decoded by Coil's
 * [VideoFrameDecoder] (`io.coil-kt:coil-video`), with Kristin's voice-message
 * play disc ([R.drawable.ic_play_circle], 48dp) centred on top. Tap still
 * opens the video in the system player ([ouvrirPieceJointe]) — the disc is a
 * visual cue, not an in-bubble player.
 *
 * The decoder only applies when Coil reports a `video/` MIME type for the
 * source ([VideoFrameDecoder.Factory] gates on it): `content://mms/part/N`
 * parts resolve it via `ContentResolver.getType`, files via their extension.
 * The [ImageLoader] is local to this preview (remembered) so the app-wide
 * Coil setup is untouched — `RealImageLoader` appends all the default
 * fetchers/decoders on top of the added factory, so a plain
 * `.components { add(...) }` here does NOT drop them.
 *
 * Fallback: if the frame cannot be decoded (corrupt/unsupported video, dead
 * `content://` URI — the same failure mode [ImagePreview] already handles),
 * [onError] swaps in the plain [IconAttachment] below, so the bubble never
 * shows a blank hole. While the frame is loading, the outlined play icon
 * shows underneath on the bubble colour instead of an empty tile.
 *
 * Play disc: a plain white disc can wash out on a bright frame, so it sits
 * on a 56dp dark scrim halo — the triangle hole in [R.drawable.ic_play_circle]
 * shows the scrim through (dark triangle on a white disc), readable on any
 * frame, in both themes.
 */
@Composable
private fun VideoPreview(
    attachment: Attachment,
    shape: androidx.compose.ui.graphics.Shape,
    bgColor: androidx.compose.ui.graphics.Color,
    isOutgoing: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // Round 13 — local loader with the video-frame decoder, remembered for
    // the lifetime of this preview. One loader per video bubble is cheap:
    // the frame itself is ~230 KiB, same 480px cap as [ImagePreview].
    val videoImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
    // Keyed on the video itself so a different attachment resets the state
    // (a recycled list item must not keep the previous video's loaded/failed).
    var thumbLoaded by remember(attachment.localUri) { mutableStateOf(false) }
    var thumbFailed by remember(attachment.localUri) { mutableStateOf(false) }
    if (thumbFailed) {
        IconAttachment(
            attachment = attachment,
            icon = Icons.Outlined.PlayCircleOutline,
            iconLabelRes = R.string.attachment_video_label,
            shape = shape,
            bgColor = bgColor,
            isOutgoing = isOutgoing,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 240.dp)
                .heightIn(min = 80.dp, max = 320.dp)
                .clip(shape)
                .background(bgColor)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            // Underneath: the old video tile's outlined play icon, visible
            // while the frame loads (and briefly if it errors, before the
            // swap above). Same tint as the bubble text.
            Icon(
                imageVector = Icons.Outlined.PlayCircleOutline,
                contentDescription = null,
                tint = if (isOutgoing) onBrandBlue() else cs.onSurface,
                modifier = Modifier.size(48.dp),
            )
            // v1.3.10 (P5) — same 480px decode cap as [ImagePreview] so a
            // 4K video frame is downsampled at decode time, not on the GPU.
            // No error/fallback painter: a failed load must stay transparent
            // so the icon underneath (then the [IconAttachment] swap) shows.
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(attachment.modeleImage())
                    .size(THUMBNAIL_PX)
                    .crossfade(true)
                    .build(),
                imageLoader = videoImageLoader,
                contentDescription = attachment.fileName
                    ?: stringResource(R.string.attachment_video_label),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 240.dp, height = 240.dp),
                onSuccess = { thumbLoaded = true },
                onError = {
                    Timber.w(
                        "MMS video thumbnail load failed for attachment %d",
                        attachment.id,
                    )
                    thumbFailed = true
                },
            )
            if (thumbLoaded) {
                // Kristin's voice-message play disc, 48dp, centred on the
                // frame, on a 56dp dark scrim halo (see the KDoc above).
                // No own clickable: the tap falls through to the Box and
                // opens the video, exactly as before.
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_circle),
                        contentDescription = stringResource(R.string.attachment_video_label),
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}

/**
 * v1.3.3 Z1 — ce que Coil doit charger. Il accepte File OU Uri ; on évite File("content://...") qui
 * échoue en silence à l'affichage.
 */
internal fun Attachment.modeleImage(): Any =
    if (localUri.startsWith("content://")) Uri.parse(localUri) else File(localUri)

/**
 * Ouvre une pièce jointe dans l'application du système associée à son type.
 *
 * v1.3.3 Z1 audit fix — `localUri` peut être :
 *   (1) un absolute file path (cache MMS, voice_mms, vault decrypt — fichiers app)
 *   (2) une `content://mms/part/N` URI (MMS importés du système Android via
 *       TelephonyReader → ConversationMirror.bulkImportMmsFromTelephony)
 * L'ancien code wrappait aveuglément `File(localUri)` → FileProvider throw sur (2)
 * = aucun MMS image historique ne pouvait s'ouvrir. On détecte le scheme avant.
 */
internal fun ouvrirPieceJointe(context: android.content.Context, cible: Attachment) {
    runCatching {
        val targetUri = cible.toShareableUri(context)
        if (targetUri == null) {
            Timber.w("ouvrirPieceJointe: cannot resolve URI for %s", cible.localUri)
            return@runCatching
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, cible.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, cible.fileName ?: ""))
    }.onFailure { Timber.w(it, "Failed to open attachment %d", cible.id) }
}

/**
 * v1.3.3 Z1 — résout `localUri` en une [Uri] partageable via Intent. Pour les paths
 * `content://...` (MMS importés du système), on retourne l'URI tel quel ; pour les
 * fichiers locaux app (cache / files), on encapsule dans FileProvider pour ne pas
 * exposer un `file://` direct (Android 7+ refuse).
 *
 * Retourne `null` si le fichier n'existe pas (cas path stale après nettoyage cache).
 */
private fun com.filestech.sms.domain.model.Attachment.toShareableUri(
    context: android.content.Context,
): Uri? {
    if (localUri.isBlank()) return null
    if (localUri.startsWith("content://")) {
        return Uri.parse(localUri)
    }
    // v1.6.1 (audit SEC-09) — canonicalFile + whitelist filesDir/cacheDir defense-in-depth.
    // FileProvider.getUriForFile() throw IllegalArgumentException si le path est hors des
    // répertoires déclarés dans file_provider_paths.xml — c'est la protection effective —
    // mais le whitelist explicite ici garantit qu'un `localUri` traversant (`../` injecté
    // via PDU MMS corrompu ou migration DB) est rejeté AVANT même de toucher FileProvider.
    val canonicalFile = runCatching { File(localUri).canonicalFile }.getOrNull()
    if (canonicalFile == null || !canonicalFile.exists()) {
        Timber.w("Attachment.toShareableUri: file not found or unresolvable")
        return null
    }
    val allowedPrefixes = listOf(
        runCatching { context.filesDir.canonicalPath }.getOrNull(),
        runCatching { context.cacheDir.canonicalPath }.getOrNull(),
    ).filterNotNull()
    val absolutePath = canonicalFile.absolutePath
    if (allowedPrefixes.none { absolutePath.startsWith(it + File.separator) || absolutePath == it }) {
        Timber.w("Attachment.toShareableUri: path outside app sandbox, refusing")
        return null
    }
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", canonicalFile)
    }.getOrNull()
}

/** Icône + nom + taille pour vidéo / fichier. Tap = ACTION_VIEW ; round 22 — appui long = barre de réaction rapide (entrant) ou menu. */
@Composable
private fun IconAttachment(
    attachment: Attachment,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconLabelRes: Int,
    shape: androidx.compose.ui.graphics.Shape,
    bgColor: androidx.compose.ui.graphics.Color,
    isOutgoing: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Round 20 — [onBrandBlue] sur le fond sortant (voir [bgColor] plus haut) :
    // blanc sur le bleu fixe, `onPrimary` du scheme dynamique quand les
    // couleurs dynamiques sont actives.
    val textColor = if (isOutgoing) onBrandBlue() else cs.onSurface
    // v1.3.10 — fill the bubble for BOTH directions. Before, outgoing got only a 1.5 dp
    // border around a transparent interior; that pattern relied on the body placeholder text
    // ("📎 filename.pdf") underneath to fill the visual space. With the v1.3.10 body /
    // previewLabel decoupling, outgoing files without a user caption have body = "" → the
    // bordered shell looks like a hollow white blob. Filling with the brand blue
    // (text in [onBrandBlue], see above) keeps all outgoing bubble types coherent.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .clip(shape)
            .background(bgColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = stringResource(iconLabelRes), tint = textColor)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName ?: stringResource(iconLabelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachment.sizeBytes > 0) {
                // v1.28.12 — la taille passe par le formateur de la PLATEFORME, qui lit la
                // configuration du contexte : il écrit donc l'unité dans la langue de
                // l'application, et le séparateur décimal avec.
                //
                // ⚠️ Elle était écrite à la main, en français : « 1,2 Mo » s'affichait sous
                // CHAQUE pièce jointe, y compris pour un lecteur allemand, italien, espagnol
                // ou anglais. Aucun contrôle ne pouvait le voir — ce texte n'était pas dans
                // `strings.xml`, et c'est exactement le motif des SMS de sécurité partis en
                // français à tout le monde. Relevé par deux audits indépendants le
                // 2026-09-17, chacun de son côté.
                Text(
                    text = android.text.format.Formatter.formatShortFileSize(
                        LocalContext.current,
                        attachment.sizeBytes,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Coil downsample target for MMS image previews. 480 px ≈ 240 dp × 2× density factor (xxhdpi),
 * which is the actual upper bound used by [ImagePreview]'s 240 dp thumbnail box. Capping the
 * decode here keeps a 12 MiB photo at ~230 KiB of bitmap memory — critical on Android Go.
 */
private const val THUMBNAIL_PX: Int = 480
