package com.filestech.sms.ui.screens.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.sync.client.ClientDownloadState
import com.filestech.sms.ui.components.RelayEmptyState
import com.filestech.sms.ui.components.modeleImage
import com.filestech.sms.ui.components.rememberAttachmentOpener
import com.filestech.sms.ui.theme.RelayText
import timber.log.Timber

/** Issue #59 — la grille des photos et vidéos. Toucher une vignette ouvre le fichier comme depuis la bulle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    onBack: () -> Unit,
    viewModel: MediaGalleryViewModel = hiltViewModel(),
) {
    val media by viewModel.media.collectAsStateWithLifecycle()
    val vaultHidden by viewModel.vaultHidden.collectAsStateWithLifecycle()
    // Client mode C5 — the download states for tiles whose bytes have
    // not landed yet (empty in hub mode).
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    // Round 33 — Relay's own viewer picker: media opens resolve the
    // remembered viewer or raise the picker.
    val openAttachment = rememberAttachmentOpener()
    // `withResumed`, comme `ThreadScreen` : dépiler pendant qu'un autre écran est devant viserait le voisin.
    val navOwner = LocalLifecycleOwner.current
    LaunchedEffect(vaultHidden) {
        if (vaultHidden) navOwner.lifecycle.withResumed { onBack() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.media_gallery_title),
                        style = RelayText.ThreadTitle,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        MediaGalleryContent(
            media,
            padding,
            onOpen = openAttachment,
            downloadStates = downloadStates,
            onRequestDownload = { id -> viewModel.requestDownload(id) },
        )
    }
}

/** Sans état, pour que le test d'interface n'ait besoin ni de base ni de Hilt. */
@Composable
internal fun MediaGalleryContent(
    media: List<Attachment>?,
    padding: PaddingValues,
    onOpen: (Attachment) -> Unit,
    /** Client mode C5 — the download states, keyed by local attachment id. */
    downloadStates: Map<Long, ClientDownloadState> = emptyMap(),
    /** Client mode C5 — asks the downloader for a tile's bytes (tap-to-download/retry). */
    onRequestDownload: ((Long) -> Unit)? = null,
) {
    when {
        media == null -> Unit
        media.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            RelayEmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                headline = stringResource(R.string.media_gallery_empty),
            )
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(GALLERY_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = TILE_GAP_HORIZONTAL,
                end = TILE_GAP_HORIZONTAL,
                top = padding.calculateTopPadding() + TILE_GAP_VERTICAL,
                bottom = padding.calculateBottomPadding() + TILE_GAP_VERTICAL,
            ),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP_VERTICAL),
        ) {
            items(media, key = { it.id }) { attachment ->
                MediaTile(
                    attachment,
                    onClick = { onOpen(attachment) },
                    downloadState = downloadStates[attachment.id],
                    onRequestDownload = onRequestDownload,
                )
            }
        }
    }
}

/**
 * Shared with the details panel: the same tile, its download badge and its
 * tap-to-download behaviour, laid out in the panel's media rows.
 *
 * Round 32 — the tile follows the desktop pane: rounded 16dp, 11/10
 * aspect (not square), 14dp between columns and 12dp between rows.
 * The rounding applies here and in the panel alike — one component,
 * one look.
 *
 * Round 33 — video tiles show their first frame (the thread bubble's
 * [VideoFrameDecoder] loader), not just the play icon; and every tile
 * carries the download icon top-right ([onSaveCopy] in the panel —
 * hub mode saves a copy out of Relay, client mode fetches the bytes
 * from the hub when they have not landed yet). The gallery passes no
 * [onSaveCopy], so its hub-mode tiles show no icon and its
 * client-mode tiles show it only while bytes are missing.
 */
@Composable
internal fun MediaTile(
    attachment: Attachment,
    onClick: () -> Unit,
    downloadState: ClientDownloadState? = null,
    onRequestDownload: ((Long) -> Unit)? = null,
    /** Round 33 — saves a copy of a local file (the panel's SAF "Save as"). */
    onSaveCopy: ((Attachment) -> Unit)? = null,
) {
    val fond = MaterialTheme.colorScheme.surfaceVariant
    // Client mode C5 — a synced tile without bytes: it auto-requests
    // on render (the desktop rule), taps request/retry instead of
    // opening, and carries a small badge while the bytes are missing.
    // A hub-mode tile with a missing file (`hubAttachmentId == null`)
    // is NOT downloadable — no badge, no request, tap opens as before
    // (and fails open, as before).
    val needsDownload = attachment.localUri.isBlank() && attachment.hubAttachmentId != null &&
        onRequestDownload != null && downloadState != ClientDownloadState.Downloaded
    if (needsDownload) {
        LaunchedEffect(attachment.id) { onRequestDownload(attachment.id) }
    }
    Box(
        modifier = Modifier
            .aspectRatio(11f / 10f)
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(fond)
            .clickable(onClick = {
                if (needsDownload) {
                    // Downloading + Empty are not tappable: a
                    // download already runs, and an empty file has
                    // nothing to fetch.
                    if (downloadState == null || downloadState is ClientDownloadState.NotDownloaded ||
                        downloadState is ClientDownloadState.Failed
                    ) {
                        onRequestDownload?.invoke(attachment.id)
                    }
                } else {
                    onClick()
                }
            }),
        contentAlignment = Alignment.Center,
    ) {
        if (attachment.isImage) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(attachment.modeleImage())
                    // Plafond au décodage : une longue conversation ne charge pas ses photos en pleine taille.
                    .size(THUMBNAIL_PX)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.fileName ?: stringResource(R.string.attachment_image_label),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // Un fichier disparu laisse une case neutre, pas un trou.
                error = ColorPainter(fond),
                fallback = ColorPainter(fond),
            )
        } else {
            // Round 33 — the video's first frame, like the thread
            // bubble (the Videos tab used to show only the play icon
            // while the thread showed the frame).
            VideoTileThumbnail(attachment)
        }
        // Round 33 — the download icon, top-right on every tile in
        // the panel: client mode fetches the bytes from the hub
        // (like the tile tap); hub mode saves a copy out of Relay.
        // Its own tap target, so it never opens the attachment.
        val showDownloadIcon = onSaveCopy != null || needsDownload
        if (showDownloadIcon) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(DOWNLOAD_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = {
                        if (needsDownload) {
                            if (downloadState == null ||
                                downloadState is ClientDownloadState.NotDownloaded ||
                                downloadState is ClientDownloadState.Failed
                            ) {
                                onRequestDownload?.invoke(attachment.id)
                            }
                        } else {
                            onSaveCopy?.invoke(attachment)
                        }
                    }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download_outline),
                    contentDescription = stringResource(
                        if (needsDownload) {
                            R.string.client_attachment_tap_download
                        } else {
                            R.string.details_save_copy
                        },
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(DOWNLOAD_BADGE_ICON_SIZE),
                )
            }
        }
        if (needsDownload) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(DOWNLOAD_BADGE_SIZE)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                when (downloadState) {
                    is ClientDownloadState.Downloading -> CircularProgressIndicator(
                        modifier = Modifier.size(DOWNLOAD_BADGE_ICON_SIZE),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    is ClientDownloadState.Failed -> Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.client_attachment_failed),
                        tint = Color.White,
                        modifier = Modifier.size(DOWNLOAD_BADGE_ICON_SIZE),
                    )
                    is ClientDownloadState.Empty -> Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.client_attachment_empty),
                        tint = Color.White,
                        modifier = Modifier.size(DOWNLOAD_BADGE_ICON_SIZE),
                    )
                    else -> Icon(
                        painter = painterResource(R.drawable.ic_download_outline),
                        contentDescription = stringResource(R.string.client_attachment_tap_download),
                        tint = Color.White,
                        modifier = Modifier.size(DOWNLOAD_BADGE_ICON_SIZE),
                    )
                }
            }
        }
    }
}

private const val GALLERY_COLUMNS = 3
private val TILE_CORNER = 16.dp
private val TILE_GAP_HORIZONTAL = 14.dp
private val TILE_GAP_VERTICAL = 12.dp
private val VIDEO_ICON_SIZE = 40.dp
private val DOWNLOAD_BADGE_SIZE = 32.dp
private val DOWNLOAD_BADGE_ICON_SIZE = 18.dp
private const val THUMBNAIL_PX = 360

/** Play disc on a loaded video frame — the bubble's 48/56dp, scaled to the tile. */
private val VIDEO_PLAY_DISC_SIZE = 28.dp
private val VIDEO_PLAY_HALO_SIZE = 36.dp

/**
 * Round 33 — a video tile's first frame, the thread bubble's
 * mechanism (`VideoPreview`): a local loader with Coil's
 * [VideoFrameDecoder], the outlined play icon underneath while the
 * frame loads, and the play disc on top once it lands (so a video
 * tile never reads as a photo). A frame that cannot be decoded
 * leaves the plain icon — never a blank hole.
 */
@Composable
private fun VideoTileThumbnail(attachment: Attachment) {
    val context = LocalContext.current
    val videoImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
    var thumbLoaded by remember(attachment.localUri) { mutableStateOf(false) }
    var thumbFailed by remember(attachment.localUri) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.PlayCircleOutline,
            contentDescription = stringResource(R.string.attachment_video_label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(VIDEO_ICON_SIZE),
        )
        if (!thumbFailed) {
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
                modifier = Modifier.fillMaxSize(),
                onSuccess = { thumbLoaded = true },
                onError = {
                    Timber.w(
                        "Video tile thumbnail load failed for attachment %d",
                        attachment.id,
                    )
                    thumbFailed = true
                },
            )
        }
        if (thumbLoaded) {
            // The bubble's play disc, scaled down: a white disc on a
            // dark scrim halo, readable on any frame.
            Box(
                modifier = Modifier
                    .size(VIDEO_PLAY_HALO_SIZE)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play_circle),
                    contentDescription = stringResource(R.string.attachment_video_label),
                    tint = Color.White,
                    modifier = Modifier.size(VIDEO_PLAY_DISC_SIZE),
                )
            }
        }
    }
}
