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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.filestech.sms.ui.theme.pickerPanelColor
import com.filestech.sms.ui.theme.pickerTabIndicatorColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * The picker panel shell. It sits BELOW the composer (round 14 — the message box
 * stays visible above it, so picked emojis land in a draft the user can see).
 * Enters by growing from 0 to the midway height; the back arrow ([onClose] is
 * only called after the shrink animation finishes, so the panel shrinks away
 * instead of popping).
 *
 * Drag is FREE: the handle can be dragged to any height between [MIN_HEIGHT_DP]
 * and full; on release the panel snaps to the nearest of the two resting heights
 * (midway = half the space below the composer, as in Kristin's screenshot;
 * full = up to the bottom of the top header, round 17 — the old 85% guess
 * overlapped the header on her phone). Dragging never closes the panel —
 * only the back arrow does.
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
    /**
     * Round 14 — height (px) of the composer stacked above the panel: the resting
     * heights are fractions of the space BELOW it, so composer + panel never
     * exceed the screen. `0f` keeps the old full-screen fractions.
     */
    reservedTopPx: Float = 0f,
    /**
     * Round 17 — height (px) of the top header: the FULL height stops at its
     * bottom edge, so a fully-dragged panel never overlaps the header.
     * `0f` (not measured yet) falls back to the old 85% fraction.
     */
    reservedHeaderPx: Float = 0f,
    /**
     * Round 20 — height (px) of the open keyboard: the resting heights are
     * fractions of the space above it, so opening the keyboard while the
     * panel is tall shrinks the panel instead of pushing the composer off
     * the top of the screen. `0f` when the keyboard is closed.
     */
    reservedBottomPx: Float = 0f,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val minPx = with(density) { MIN_HEIGHT_DP.dp.toPx() }
    val availablePx = (screenHeightPx - reservedTopPx - reservedBottomPx).coerceAtLeast(minPx)
    val midwayPx = (availablePx * MIDWAY_FRACTION).coerceAtLeast(minPx)
    // Round 17 — full is the space between the header's bottom edge and the
    // composer, so the dragged panel stops exactly at the header instead of
    // overlapping it; the 85% fraction is only the pre-measure fallback.
    // Round 20 — the panel's own chrome (navigation-bar padding + the 8dp
    // bottom margin) sits OUTSIDE `heightPx` but INSIDE the bottom bar, so
    // the old ceiling let the stack overlap the header by exactly that
    // much. Subtract it and the fully-dragged panel stops dead on the
    // header's bottom edge.
    val navBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val panelChromePx = navBarPx + with(density) { PANEL_BOTTOM_MARGIN_DP.dp.toPx() }
    val fullPx = if (reservedHeaderPx > 0f) {
        (availablePx - reservedHeaderPx - panelChromePx).coerceAtLeast(minPx)
    } else {
        (availablePx * FULL_FRACTION - panelChromePx).coerceAtLeast(minPx)
    }
    // Round 20 — the drag handler is a `pointerInput(Unit)` block, created
    // once: it would keep the FIRST composition's ceiling forever, so a
    // keyboard opening mid-session (or a reply chip arming) would leave the
    // drag clamping to a stale, taller ceiling. Read the live values.
    val latestFullPx by rememberUpdatedState(fullPx)
    val latestMidwayPx by rememberUpdatedState(midwayPx)
    var heightPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var panelAnimation by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    // Search query, typed in the bar above the grid. On the GIF tab it searches
    // live, debounced ([GifLiveSearchEffect]): half a second after the user
    // stops typing, the query goes to [GifSearchQuery.submit] — one API call
    // per pause, never per keystroke, so the test key's shared rate limit is
    // respected. Enter still submits immediately. The emoji/sticker grids
    // filter locally on the same text. The query is cleared whenever the tab
    // changes or the panel closes, so reopening always starts from trending.
    // The field never takes focus on its own — opening the panel never summons
    // the keyboard; only an explicit tap on the field does, to type a GIF search.
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
        val target = if (heightPx < (latestMidwayPx + latestFullPx) / 2f) {
            latestMidwayPx
        } else {
            latestFullPx
        }
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

    // Round 14 — if the space above grows while the panel is open (a reply chip
    // armed, an attachment staged), the ceiling drops: clamp the panel to it
    // instead of overflowing the screen.
    LaunchedEffect(fullPx) {
        if (heightPx > fullPx) heightPx = fullPx
    }

    // Round 15 — live GIF search, debounced (see [GifLiveSearchEffect]).
    GifLiveSearchEffect(query = searchQuery, selectedTab = selectedTab)

    val dragModifier = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onVerticalDrag = { _, dragAmount ->
                panelAnimation?.cancel()
                heightPx = (heightPx - dragAmount).coerceIn(minPx, latestFullPx)
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
            .background(pickerPanelColor(cs)),
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
            query = searchQuery,
            onEmojiSelected = onEmojiSelected,
            onStickerSelected = onStickerSelected,
            onGifSelected = onGifSelected,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Round 15 — live GIF search, debounced: typing searches once the user stops
 * for [GIF_LIVE_SEARCH_DEBOUNCE_MS], so there is no need to press enter. The
 * effect restarts on every keystroke (and tab change), so the delay only
 * completes after a pause. A query already submitted costs no extra API call;
 * a single character never searches live (it is never a useful GIF search —
 * enter still submits it immediately); clearing the field back to blank
 * returns to trending. Runs only on the GIF tab; the keyboard stays up —
 * only the explicit submit hides it.
 */
@Composable
private fun GifLiveSearchEffect(query: String, selectedTab: EmojiPickerTab) {
    LaunchedEffect(query, selectedTab) {
        if (selectedTab == EmojiPickerTab.GIF) {
            val candidate = query.trim()
            if (candidate.isEmpty() || candidate.length >= GIF_LIVE_SEARCH_MIN_CHARS) {
                delay(GIF_LIVE_SEARCH_DEBOUNCE_MS)
                if (candidate != GifSearchQuery.submittedQuery.value) {
                    GifSearchQuery.submit(candidate)
                }
            }
        }
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
 * right. Typing edits the local text and, on the GIF tab, searches live once
 * the user pauses ([GifLiveSearchEffect]); enter submits immediately. The
 * emoji and sticker grids filter locally on the same text. The field never
 * requests focus by itself, so opening the panel never summons the keyboard
 * — only an explicit tap on the field does.
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
            .height(SEARCH_BAR_HEIGHT_DP.dp)
            // Same fill as the message input box (RelayTextField): the search
            // field is an input box too, so it reads as one.
            .background(cs.surfaceVariant, RoundedCornerShape(24.dp))
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
                painter = painterResource(R.drawable.ic_close_drawer),
                contentDescription = stringResource(R.string.action_close),
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
 * The three tab icons, with a CIRCLE behind the active one that SLIDES to
 * whichever tab is tapped (animated offset, one tab width per step). Round 15
 * — the indicator is a true circle ([CircleShape]) filling the tab slot
 * ([TAB_SIZE_DP]): the 24dp icon sits inside it with 8dp of breathing room
 * on every side, tight around the icon the way Kristin's mockup shows it.
 * (The earlier rounded box was 40x44dp and read as shifted.)
 */
@Composable
private fun PickerTabs(
    selectedTab: EmojiPickerTab,
    onTabSelected: (EmojiPickerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val indicatorOffset by animateDpAsState(
        targetValue = (selectedTab.ordinal * TAB_SIZE_DP).dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pickerTabIndicator",
    )
    Box(
        modifier = modifier
            .width((EmojiPickerTab.entries.size * TAB_SIZE_DP).dp)
            .height(SEARCH_BAR_HEIGHT_DP.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = indicatorOffset)
                .size(TAB_SIZE_DP.dp)
                .background(
                    pickerTabIndicatorColor(cs),
                    CircleShape,
                ),
        )
        Row(
            modifier = Modifier.align(Alignment.Center),
        ) {
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

/**
 * Content area: the grid for the active tab, filling the rest of the panel.
 * The search query filters the emoji and sticker grids LIVE as it is typed
 * (local data, no API cost); the GIF grid searches live too, debounced, via
 * [GifSearchQuery] ([GifLiveSearchEffect]).
 */
@Composable
private fun PickerContent(
    selectedTab: EmojiPickerTab,
    query: String,
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
                query = query,
                onEmojiSelected = onEmojiSelected,
            )
            EmojiPickerTab.GIF -> GifGrid(
                modifier = Modifier.fillMaxSize(),
                onGifSelected = onGifSelected,
            )
            EmojiPickerTab.STICKER -> StickerGrid(
                modifier = Modifier.fillMaxSize(),
                query = query,
                onStickerSelected = onStickerSelected,
            )
        }
    }
}

/** One tab button, square. */
private const val TAB_SIZE_DP = 40

/** Search bar height — the tab cluster fills it so the indicator centres. */
private const val SEARCH_BAR_HEIGHT_DP = 48

/** Panel corner radius — fully rounded, like the rest of the app. */
private const val PANEL_CORNER_DP = 28

/** Side and bottom margins of the floating panel, as in Kristin's screenshot. */
private const val PANEL_SIDE_MARGIN_DP = 8
private const val PANEL_BOTTOM_MARGIN_DP = 8

/** Shortest the panel can be dragged (a search bar plus a sliver of grid). */
private const val MIN_HEIGHT_DP = 240

/** Resting heights: midway is half the space below the composer. */
private const val MIDWAY_FRACTION = 0.5f

/** Full-height fallback before the header is measured (round 17). */
private const val FULL_FRACTION = 0.85f

/** Panel grow / snap / shrink durations. */
private const val ENTER_DURATION_MS = 300
private const val SNAP_DURATION_MS = 300
private const val CLOSE_DURATION_MS = 250
private const val GIF_LIVE_SEARCH_DEBOUNCE_MS = 500L
private const val GIF_LIVE_SEARCH_MIN_CHARS = 2
