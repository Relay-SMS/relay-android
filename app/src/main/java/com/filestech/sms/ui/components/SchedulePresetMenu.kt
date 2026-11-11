package com.filestech.sms.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.util.ChatFormatters
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Calendar
import java.util.Date

/**
 * Round 13 — the long-press schedule menu on the send button. A small popup with
 * four preset times across the next 24 hours ([schedulePresets]), then a divider
 * and "Pick date and time" at the bottom, which opens the existing DatePicker →
 * TimePicker scheduling flow. Same overlay pattern as the quick-reaction bar (a
 * [DropdownMenu] floating near the button, dismissing on an outside tap) and the
 * same 150ms fade + slight scale on appear and dismiss. Tapping a preset schedules
 * through the caller's [onPresetSelected] (the existing `scheduleSend` path) — it
 * never sends immediately.
 *
 * The presets are recomputed each time the menu opens, so a menu left open across
 * the hour never offers a time that has already passed (`scheduleSend` also
 * rejects past times, as a safety net).
 */
@Composable
fun SchedulePresetMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onPresetSelected: (Long) -> Unit,
    onPickDateTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatters = rememberChatFormatters()
    val todayLabel = stringResource(R.string.date_today)
    val tomorrowLabel = stringResource(R.string.date_tomorrow)
    val inOneHourFormat = stringResource(R.string.schedule_preset_in_1_hour)
    val dayTimeFormat = stringResource(R.string.schedule_preset_day_time)
    val presets = remember(expanded) {
        if (!expanded) {
            emptyList()
        } else {
            val now = System.currentTimeMillis()
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
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(SCHEDULE_MENU_ANIM_MS)) +
            scaleIn(
                initialScale = SCHEDULE_MENU_START_SCALE,
                animationSpec = tween(SCHEDULE_MENU_ANIM_MS),
            ),
        exit = fadeOut(animationSpec = tween(SCHEDULE_MENU_ANIM_MS)) +
            scaleOut(
                targetScale = SCHEDULE_MENU_START_SCALE,
                animationSpec = tween(SCHEDULE_MENU_ANIM_MS),
            ),
        label = "schedulePresetMenu",
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismissRequest,
            // Full pill like the quick-reaction bar (24dp), not the 18dp menu.
            shape = RoundedCornerShape(24.dp),
            modifier = modifier,
        ) {
            presets.forEach { (preset, label) ->
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                        )
                    },
                    text = { Text(label) },
                    onClick = {
                        onPresetSelected(preset.epochMillis)
                        onDismissRequest()
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.DateRange,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.schedule_pick_date_time)) },
                onClick = {
                    onPickDateTime()
                    onDismissRequest()
                },
            )
        }
    }
}

/** The menu label for one preset: "In 1 hour · 5:50 PM", or "Today, 8:00 PM" / "Tomorrow, 9:00 AM". */
private fun presetLabel(
    preset: SchedulePreset,
    nowMillis: Long,
    formatters: ChatFormatters,
    todayLabel: String,
    tomorrowLabel: String,
    inOneHourFormat: String,
    dayTimeFormat: String,
): String {
    val timeStr = formatters.time.format(Date(preset.epochMillis))
    if (preset.isInOneHour) return inOneHourFormat.format(timeStr)
    val dayLabel = if (isSameDay(preset.epochMillis, nowMillis)) todayLabel else tomorrowLabel
    return dayTimeFormat.format(dayLabel, timeStr)
}

private fun isSameDay(aMillis: Long, bMillis: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = aMillis }
    val b = Calendar.getInstance().apply { timeInMillis = bMillis }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** Enter/exit animation length: quick and subtle, so the menu feels snappy. */
private const val SCHEDULE_MENU_ANIM_MS = 150

/** Scale the menu starts (and ends) at — a slight grow/shrink, not a pop. */
private const val SCHEDULE_MENU_START_SCALE = 0.92f
