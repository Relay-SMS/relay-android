package com.filestech.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText

/**
 * Dialog shell of the Relay design system: 28dp corners, flat canvas background, title in
 * 17sp/600. Buttons are the caller's responsibility — use [RelayTextButton] (confirm blue
 * 600) and [RelayDestructiveButton] so every dialog reads the same. Pass [icon] for the
 * rare dialog that leads with a status glyph, and [titleColor] when the title itself
 * carries the state (the wipe report's red title when the wipe was incomplete). Pass
 * [properties] when the dialog holds a text field the keyboard must not cover — the
 * passphrase dialogs use `decorFitsSystemWindows = false` with `imePadding` so the
 * confirm button stays reachable with the keyboard open.
 *
 * Round 13 — [shape] lets one dialog opt out of the 28dp corners: the emoji "Other"
 * dialog passes fully-rounded (48dp) corners, matching the emoji picker sheet.
 * Every other caller keeps the default.
 */
@Composable
fun RelayDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        icon = icon,
        title = {
            Text(
                text = title,
                style = RelayText.ThreadTitle,
                color = titleColor ?: cs.onSurface,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = shape,
        containerColor = cs.surface,
        titleContentColor = cs.onSurface,
        textContentColor = cs.onSurface,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/**
 * One option of a [RelayPickerDialog]: a label, an optional explanatory hint, its selected
 * state, and the callback fired when the row is tapped.
 */
data class RelayPickerOption(
    val label: String,
    val hint: String? = null,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * Single-choice picker dialog: the shared shell for every "pick one value" setting.
 * Options render as radio rows (label + optional hint); the list scrolls when it outgrows
 * the dialog. Dismissing is a Cancel text button — selecting an option fires [RelayPickerOption.onClick]
 * and the caller closes the dialog.
 */
@Composable
fun RelayPickerDialog(
    title: String,
    options: List<RelayPickerOption>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RelayDialog(
        onDismissRequest = onDismiss,
        title = title,
        modifier = modifier,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    RelayPickerOptionRow(option = option)
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun RelayPickerOptionRow(option: RelayPickerOption) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = option.onClick)
            .padding(vertical = 10.dp),
        // Feedback round 8 — CenterVertically, not Top: the radio's drawn
        // circle sits in the middle of its 48dp box, so top-aligning the
        // row put the circle ~14dp below the label's centre (measured on
        // the "Choose lock mode" screenshot: radio 27–35px under the text).
        // Centring puts the circle on the label line (and on the middle of
        // a two-line label + hint, the M3 list-item rule). Every picker
        // dialog shares this row, so the one change fixes them all.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = option.selected,
            onClick = option.onClick,
            colors = RadioButtonDefaults.colors(selectedColor = RelayColors.Blue),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = RelayText.MessageBody,
                color = cs.onSurface,
            )
            if (option.hint != null) {
                Text(
                    text = option.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
