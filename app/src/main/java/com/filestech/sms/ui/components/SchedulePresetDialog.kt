package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.util.rememberChatFormatters

/**
 * Round 33 — the schedule flow's time step, the desktop popup's radio
 * list on mobile: the four [schedulePresets] as radio rows, plus "Pick
 * a date and time" for the existing DatePicker → TimePicker flow.
 *
 * The panel's Schedule row lands here (after the message step, when
 * the composer draft was empty); the composer's own button keeps its
 * long-press menu, which already offers the same presets. The
 * Schedule button stays disabled until a row is picked — like the
 * desktop popup, which needs a message and a future send time.
 *
 * Preset semantics are the desktop's, exactly: in 1 hour, the next
 * 8 PM (tonight, or tomorrow once 8 PM has passed), the next 9 AM,
 * the next noon — 15-minute dedupe with relative backfill, always
 * four, always in the future (see [schedulePresets]).
 */
@Composable
fun SchedulePresetDialog(
    onPresetSelected: (Long) -> Unit,
    onPickCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formatters = rememberChatFormatters()
    val todayLabel = stringResource(R.string.date_today)
    val tomorrowLabel = stringResource(R.string.date_tomorrow)
    val inOneHourFormat = stringResource(R.string.schedule_preset_in_1_hour)
    val dayTimeFormat = stringResource(R.string.schedule_preset_day_time)
    // Computed once, when the dialog opens — like the desktop popup
    // and the long-press menu, which recompute per open.
    val now = remember { System.currentTimeMillis() }
    val presets = remember {
        schedulePresets(now).map { preset ->
            preset to presetLabel(
                preset = preset,
                nowMillis = now,
                formatters = formatters,
                todayLabel = todayLabel,
                tomorrowLabel = tomorrowLabel,
                inOneHourFormat = inOneHourFormat,
                dayTimeFormat = dayTimeFormat,
            )
        }
    }
    var selectedPreset by remember { mutableStateOf<Long?>(null) }
    var customSelected by remember { mutableStateOf(false) }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.schedule_preset_title),
        content = {
            presets.forEach { (preset, label) ->
                SchedulePresetRadioRow(
                    label = label,
                    selected = selectedPreset == preset.epochMillis,
                    onClick = {
                        selectedPreset = preset.epochMillis
                        customSelected = false
                    },
                )
            }
            SchedulePresetRadioRow(
                label = stringResource(R.string.schedule_pick_date_time),
                selected = customSelected,
                onClick = {
                    selectedPreset = null
                    customSelected = true
                },
            )
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_schedule),
                enabled = selectedPreset != null || customSelected,
                onClick = {
                    if (customSelected) {
                        onPickCustom()
                    } else {
                        selectedPreset?.let(onPresetSelected)
                    }
                },
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** One radio row: the circle + label, the whole row tappable (radio role). */
@Composable
private fun SchedulePresetRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row owns the tap (selectable above); the button itself
        // is display-only, so TalkBack announces one radio, not two.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurface,
        )
    }
}
