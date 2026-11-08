package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet of the Relay design system: 28dp top corners, flat canvas background, a
 * centred drag handle, and a light `rgba(0,0,0,0.16)` scrim. The sheet always opens fully
 * expanded — no half-expanded resting state. [contentWindowInsets] defaults to the
 * Material 3 bottom-sheet insets; pass `WindowInsets.navigationBars` when the content
 * manages the IME itself (e.g. a sheet with a search field).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = cs.surface,
        scrimColor = Color.Black.copy(alpha = 0.16f),
        dragHandle = { RelayDragHandle() },
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

@Composable
private fun RelayDragHandle() {
    val cs = MaterialTheme.colorScheme
    // Feedback round 5 — restore the 22dp of space ABOVE the handle that the
    // pre-refresh sheet had: it used ModalBottomSheet's DEFAULT drag handle,
    // whose vertical padding is 22dp (material3 1.4.0, `DragHandleVerticalPadding`,
    // same BOM then as now). The refresh's bare 4dp pill had no padding, so
    // the handle touched the sheet's top edge. Bottom padding stays off: the
    // 22dp the content carries above its title already covers the gap below
    // the handle (restored in round 4 from the same source).
    Box(
        modifier = Modifier
            .padding(top = 22.dp)
            .width(32.dp)
            .height(4.dp)
            .background(cs.outline, RoundedCornerShape(2.dp)),
    )
}
