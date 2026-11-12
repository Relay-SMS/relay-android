package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.filestech.sms.R
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.klipy.KlipyClient
import com.filestech.sms.domain.gif.GifItem
import com.filestech.sms.domain.gif.GifProvider
import com.filestech.sms.ui.theme.RelayText
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Round 13 — the GIF tab of the emoji/GIF/sticker picker.
 *
 * The grid talks only to [GifProvider] (Klipy, the only provider) — it
 * never imports the Klipy client directly except to construct the default one
 * below; a future DI wiring can inject the provider instead. A blank query
 * shows trending GIFs.
 *
 * Search is live and debounced: the picker panel submits the typed text
 * ~500 ms after the user stops typing (or immediately on enter) via
 * [GifSearchQuery.submit] — one API call per pause, never per keystroke.
 * A blank query shows trending GIFs.
 *
 * Behaviour: every time the tab opens it calls Trending fresh, and every
 * submitted search calls Search fresh — no caching anywhere, per Klipy's
 * rules (no partner-operated media cache). When a request fails — rate-limited
 * (HTTP 429), no network, any error — the grid shows the "unavailable" message
 * above an empty grid; it never crashes and never shows a blank screen without
 * an explanation. With no API key yet (the placeholder in `data/build.gradle.kts`),
 * the provider returns nothing and the grid shows its empty state ("No results")
 * under the attribution line.
 *
 * Tapping a GIF downloads it to a local file and calls [onGifSelected] with the
 * local `content://` URI, ready to attach as an MMS attachment through the
 * existing attachment path.
 *
 * "Powered by KLIPY" is the literal English trademark attribution Klipy asks
 * for wherever its content is shown — hardcoded, never translated. The search
 * field's "Search KLIPY" placeholder is the REQUIRED attribution (see
 * `emoji_picker_search_gif`).
 */
@Composable
fun GifGrid(modifier: Modifier = Modifier, onGifSelected: (String) -> Unit) {
    val context = LocalContext.current
    val provider: GifProvider = remember(context) { KlipyClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val query by GifSearchQuery.submittedQuery.collectAsState()
    var gifs by remember { mutableStateOf<List<GifItem>>(emptyList()) }
    var showUnavailable by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val requested = query
        val result = if (requested.isBlank()) provider.trending() else provider.search(requested)
        // A newer submit restarts this effect; only the latest request may
        // paint, so a slow earlier search never overwrites fresher results.
        if (GifSearchQuery.submittedQuery.value == requested) {
            when (result) {
                is Outcome.Success -> {
                    gifs = result.value
                    showUnavailable = false
                }
                is Outcome.Failure -> {
                    gifs = emptyList()
                    showUnavailable = true
                    Timber.w("GifGrid: GIF request failed, showing the unavailable message")
                }
            }
        }
    }

    Column(modifier = modifier) {
        if (showUnavailable) {
            Text(
                text = stringResource(R.string.gif_search_unavailable),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (gifs.isEmpty()) {
            RelayEmptyState(
                icon = Icons.Outlined.Image,
                headline = stringResource(R.string.thread_search_no_results),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GIF_COLUMNS),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(GIF_GRID_GAP),
                horizontalArrangement = Arrangement.spacedBy(GIF_GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GIF_GRID_GAP),
            ) {
                items(gifs, key = { it.id }) { gif ->
                    GifTile(
                        gif = gif,
                        enabled = downloadingId == null,
                        onClick = {
                            if (downloadingId == null) {
                                downloadingId = gif.id
                                scope.launch {
                                    when (val downloaded = provider.download(gif)) {
                                        is Outcome.Success -> onGifSelected(downloaded.value.toString())
                                        is Outcome.Failure -> {
                                            Timber.w("GifGrid: GIF download failed for ${gif.id}")
                                        }
                                    }
                                    downloadingId = null
                                }
                            }
                        },
                    )
                }
            }
        }
        // Klipy brand mark: the literal English attribution, everywhere
        // the API is used. Hardcoded on purpose — it is a trademark, not copy.
        Text(
            text = "Powered by KLIPY",
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

/**
 * One grid cell: the GIF thumbnail, square-cropped, animated through
 * Coil's `GifDecoder` (`io.coil-kt:coil-gif`). The downloaded file is the
 * real animated GIF, and it stays animated when sent as an MMS attachment:
 * the send path never JPEG-compresses a GIF, and an oversized MEDIUM
 * rendition falls back to the SMALL one at download time (see the
 * provider's [GifProvider.download]).
 *
 * The thumbnail request disables Coil's disk cache on purpose: Klipy's terms
 * prohibit a partner-operated media cache, so no thumbnail is ever written
 * to disk. The single GIF the user taps is staged in `cacheDir` for the
 * outgoing MMS (see the provider), never re-served as a thumbnail.
 */
@Composable
private fun GifTile(gif: GifItem, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val gifImageLoader = rememberGifImageLoader()
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(GIF_TILE_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(gif.thumbnailUrl)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(true)
                .build(),
            imageLoader = gifImageLoader,
            contentDescription = gif.title.ifBlank { null },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val GIF_COLUMNS = 3
private val GIF_GRID_GAP = 4.dp
private val GIF_TILE_CORNER = 12.dp
