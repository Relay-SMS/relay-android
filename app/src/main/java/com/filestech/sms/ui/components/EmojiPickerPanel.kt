package com.filestech.sms.ui.components

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.filestech.sms.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Round 13 — the composer emoji/GIF/sticker picker: a bottom panel that does NOT
 * overlay the messages. It lives in the thread screen's bottom bar in place of the
 * composer, so the message list shrinks and the latest messages stay visible, like
 * a keyboard — and the keyboard stays hidden while it is open. This is NOT the
 * reaction picker ([EmojiReactionPickerSheet]): that one reacts to a message, this
 * one inserts emoji/GIF/sticker into the draft.
 *
 * The grids themselves ([EmojiGrid], [GifGrid], [StickerGrid]) are owned by the
 * other round-13 subagents; this file is only the shell: drag handle, search bar,
 * sliding tab indicator, and the content area that hosts them.
 */
enum class EmojiPickerTab(
    @DrawableRes val iconRes: Int,
    /** Search placeholder for the tab — also its TalkBack label. */
    @StringRes val labelRes: Int,
) {
    EMOJI(R.drawable.ic_emoji_happy_outline, R.string.emoji_picker_search_emoji),
    GIF(R.drawable.ic_gif, R.string.emoji_picker_search_gif),
    STICKER(R.drawable.ic_sticker, R.string.emoji_picker_search_sticker),
}

/**
 * Round 13 — the composer picker button: the last-used tab's icon, plus the tap
 * that opens the panel to that tab. Bundled like [ScheduleMenuActions] so the
 * composer stays within the parameter-count limit.
 */
data class EmojiPickerButton(val iconRes: Int, val onClick: () -> Unit)

/**
 * Round 13 — the last-used picker tab, persisted across restarts so the composer
 * icon and the panel reopen on the tab the user last used. This is UI state, not
 * a user-facing setting, so it lives in its own small DataStore file instead of
 * [com.filestech.sms.domain.settings.AppSettings] — the same `preferencesDataStore`
 * pattern as [com.filestech.sms.data.local.datastore.RecentReactionsStore], no new
 * storage mechanism, no Settings screen row.
 */
class EmojiPickerTabStore(context: Context) {
    private val dataStore: DataStore<Preferences> = context.emojiPickerDataStore

    /** The stored tab; [EmojiPickerTab.EMOJI] until the user has picked a tab. */
    val tab: Flow<EmojiPickerTab> = dataStore.data
        .map { prefs ->
            prefs[KEY_TAB]?.let { runCatching { EmojiPickerTab.valueOf(it) }.getOrNull() }
                ?: EmojiPickerTab.EMOJI
        }
        .catch { emit(EmojiPickerTab.EMOJI) }

    suspend fun setTab(tab: EmojiPickerTab) {
        runCatching {
            dataStore.edit { prefs -> prefs[KEY_TAB] = tab.name }
        }
    }

    companion object {
        private val KEY_TAB = stringPreferencesKey("picker.lastTab")
    }
}

private val Context.emojiPickerDataStore by preferencesDataStore(name = "emoji_picker")

/**
 * The picker panel shell. Enters by growing from 0 to the midway height; the back
 * arrow ([onClose] is only called after the shrink animation finishes, so the
 * composer slides back in underneath instead of popping).
 *
 * Drag is FREE: the handle can be dragged to any height between [MIN_HEIGHT_DP]
 * and full; on release the panel snaps to the nearest of the two resting heights
 * (midway = half the screen, as in Kristin's screenshot; full = 85% of the
 * screen). Dragging never closes the panel — only the back arrow does.
 */
@Composable
fun EmojiPickerPanel(
    selectedTab: EmojiPickerTab,
    onTabSelected: (EmojiPickerTab) -> Unit,
    onClose: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onStickerSelected: (String) -> Unit,
    onGifSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val midwayPx = screenHeightPx * MIDWAY_FRACTION
    val fullPx = screenHeightPx * FULL_FRACTION
    val minPx = with(density) { MIN_HEIGHT_DP.dp.toPx() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var panelAnimation by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    // Search query, typed in the bar above the grid. It is SUBMIT-only: typing
    // never searches — the user submits with the keyboard's search action (or
    // enter), and only then does the GIF grid search, via [GifSearchQuery.submit].
    // One submit costs one API call against the beta key's shared rate limit, so
    // searching on every keystroke is out. The emoji/sticker grids take no
    // query. The query is cleared whenever the tab changes or the panel closes,
    // so reopening always starts from trending. The field never takes focus on
    // its own — opening the panel never summons the keyboard; only an explicit
    // tap on the field does, to type a GIF search.
    var searchQuery by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun submitSearch() {
        GifSearchQuery.submit(searchQuery)
        keyboardController?.hide()
    }

    fun clearSearchQuery() {
        searchQuery = ""
        GifSearchQuery.clear()
    }

    // One in-flight height animation at a time: starting a new one cancels the
    // previous, so a drag mid-spring (or a back tap mid-enter) never fights it.
    fun animateHeightTo(target: Float, durationMillis: Int, onDone: (() -> Unit)? = null) {
        panelAnimation?.cancel()
        panelAnimation = scope.launch {
            animate(heightPx, target, animationSpec = tween(durationMillis)) { value, _ ->
                heightPx = value
            }
            onDone?.invoke()
        }
    }

    fun snapToNearest() {
        if (closing) return
        val target = if (heightPx < (midwayPx + fullPx) / 2f) midwayPx else fullPx
        animateHeightTo(target, SNAP_DURATION_MS)
    }

    fun closePanel() {
        if (closing) return
        closing = true
        clearSearchQuery()
        animateHeightTo(0f, CLOSE_DURATION_MS) { onClose() }
    }

    // Enter: grow from 0 to midway.
    LaunchedEffect(Unit) {
        animateHeightTo(midwayPx, ENTER_DURATION_MS)
    }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onVerticalDrag = { _, dragAmount ->
                panelAnimation?.cancel()
                heightPx = (heightPx - dragAmount).coerceIn(minPx, fullPx)
            },
            onDragEnd = { snapToNearest() },
            onDragCancel = { snapToNearest() },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = PANEL_SIDE_MARGIN_DP.dp,
                end = PANEL_SIDE_MARGIN_DP.dp,
                bottom = PANEL_BOTTOM_MARGIN_DP.dp,
            )
            .height(with(density) { heightPx.toDp() })
            .clip(RoundedCornerShape(PANEL_CORNER_DP.dp))
            .background(cs.surfaceContainerHigh),
    ) {
        PickerDragHandle(dragModifier)
        PickerSearchBar(
            selectedTab = selectedTab,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearchSubmit = ::submitSearch,
            onTabSelected = { tab ->
                // A fresh tab starts from a blank search (trending GIFs).
                clearSearchQuery()
                onTabSelected(tab)
            },
            onCloseClick = ::closePanel,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        PickerContent(
            selectedTab = selectedTab,
            onEmojiSelected = onEmojiSelected,
            onStickerSelected = onStickerSelected,
            onGifSelected = onGifSelected,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Drag handle — the SAME 32x4dp pill as the app's other sheets (`RelaySheet`'s
 * `RelayDragHandle`), centred in a 24dp-tall hit area (10dp above and below the
 * pill, matching the sheet's 10dp top padding). The drag gesture lives on the
 * whole hit area, not just the 4dp pill.
 */
@Composable
private fun PickerDragHandle(dragModifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = dragModifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(cs.outline, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * Search bar: back arrow on the left (closes the panel), an editable search
 * field whose placeholder changes with the tab, and the three tab icons on the
 * right. Typing only edits the local text — the GIF grid searches only when the
 * user submits (keyboard search action / enter), via [GifSearchQuery.submit];
 * the emoji and sticker grids take no query, so the text is inert on those
 * tabs. The field never requests focus by itself, so opening the panel never
 * summons the keyboard — only an explicit tap on the field does.
 */
@Composable
private fun PickerSearchBar(
    selectedTab: EmojiPickerTab,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onTabSelected: (EmojiPickerTab) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(cs.surfaceContainerLowest, RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onCloseClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(selectedTab.labelRes),
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        PickerTabs(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
        )
    }
}

/**
 * The three tab icons, with a rounded box behind the active one that SLIDES to
 * whichever tab is tapped (animated offset, one tab width per step).
 */
@Composable
private fun PickerTabs(
    selectedTab: EmojiPickerTab,
    onTabSelected: (EmojiPickerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val indicatorOffset by animateDpAsState(
        targetValue = (selectedTab.ordinal * TAB_SIZE_DP + 2).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pickerTabIndicator",
    )
    Box(
        modifier = modifier
            .width((EmojiPickerTab.entries.size * TAB_SIZE_DP).dp)
            .height(TAB_SIZE_DP.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .size((TAB_SIZE_DP - 4).dp)
                .background(cs.surfaceContainerHighest, RoundedCornerShape(10.dp)),
        )
        Row {
            EmojiPickerTab.entries.forEach { tab ->
                PickerTabButton(
                    tab = tab,
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                )
            }
        }
    }
}

@Composable
private fun PickerTabButton(
    tab: EmojiPickerTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(TAB_SIZE_DP.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(tab.iconRes),
            contentDescription = stringResource(tab.labelRes),
            tint = if (selected) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Content area: the grid for the active tab, filling the rest of the panel. */
@Composable
private fun PickerContent(
    selectedTab: EmojiPickerTab,
    onEmojiSelected: (String) -> Unit,
    onStickerSelected: (String) -> Unit,
    onGifSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    ) {
        when (selectedTab) {
            EmojiPickerTab.EMOJI -> EmojiGrid(
                modifier = Modifier.fillMaxSize(),
                onEmojiSelected = onEmojiSelected,
            )
            EmojiPickerTab.GIF -> GifGrid(
                modifier = Modifier.fillMaxSize(),
                onGifSelected = onGifSelected,
            )
            EmojiPickerTab.STICKER -> StickerGrid(
                modifier = Modifier.fillMaxSize(),
                onStickerSelected = onStickerSelected,
            )
        }
    }
}

/** One tab button, square. */
private const val TAB_SIZE_DP = 40

/** Panel corner radius — fully rounded, like the rest of the app. */
private const val PANEL_CORNER_DP = 28

/** Side and bottom margins of the floating panel, as in Kristin's screenshot. */
private const val PANEL_SIDE_MARGIN_DP = 8
private const val PANEL_BOTTOM_MARGIN_DP = 8

/** Shortest the panel can be dragged (a search bar plus a sliver of grid). */
private const val MIN_HEIGHT_DP = 240

/** Resting heights: midway is half the screen, full leaves the header visible. */
private const val MIDWAY_FRACTION = 0.5f
private const val FULL_FRACTION = 0.85f

/** Panel grow / snap / shrink durations. */
private const val ENTER_DURATION_MS = 300
private const val SNAP_DURATION_MS = 300
private const val CLOSE_DURATION_MS = 250
