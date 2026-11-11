package com.filestech.sms.ui.components

import java.util.Calendar
import kotlin.math.abs

/**
 * Round 13 — one preset send time for the long-press schedule menu on the send
 * button. [epochMillis] is the absolute time to schedule for; [isInOneHour] marks
 * the "+1 hour" preset, which the menu labels "In 1 hour" instead of "Today, …".
 */
data class SchedulePreset(
    val epochMillis: Long,
    val isInOneHour: Boolean,
)

/**
 * Round 13 — four preset send times across the next 24 hours, computed from
 * [nowMillis] so they are always in the future and always inside the next 24h:
 *
 *  1. in 1 hour,
 *  2. tonight at 8 PM (the next 8 PM — today if it is still ahead, else tomorrow),
 *  3. tomorrow morning at 9 AM (the next 9 AM),
 *  4. tomorrow at noon (the next 12 PM).
 *
 * A "next clock time" is always less than 24h away, so every preset lands inside
 * the window. Two presets closer than [MIN_PRESET_GAP_MS] read as the same choice
 * (e.g. at 7:05 PM, "+1 hour" lands at 8:05 PM and "next 8 PM" at 8:00 PM — five
 * minutes apart), so the later one is dropped and a plain relative offset (+2h,
 * +6h, +12h, +18h) backfills, keeping four distinct presets. The list is sorted
 * chronologically.
 */
fun schedulePresets(nowMillis: Long): List<SchedulePreset> {
    val candidates = listOf(
        SchedulePreset(nowMillis + ONE_HOUR_MS, isInOneHour = true),
        SchedulePreset(nextClockTime(nowMillis, 20, 0), isInOneHour = false),
        SchedulePreset(nextClockTime(nowMillis, 9, 0), isInOneHour = false),
        SchedulePreset(nextClockTime(nowMillis, 12, 0), isInOneHour = false),
    ).sortedBy { it.epochMillis }
    val kept = mutableListOf<SchedulePreset>()
    for (candidate in candidates) {
        if (kept.isEmpty() || candidate.epochMillis - kept.last().epochMillis >= MIN_PRESET_GAP_MS) {
            kept.add(candidate)
        }
    }
    if (kept.size < PRESET_COUNT) {
        for (offsetHours in BACKFILL_OFFSET_HOURS) {
            if (kept.size >= PRESET_COUNT) break
            val epoch = nowMillis + offsetHours * ONE_HOUR_MS
            if (kept.all { abs(epoch - it.epochMillis) >= MIN_PRESET_GAP_MS }) {
                kept.add(SchedulePreset(epoch, isInOneHour = false))
            }
        }
        kept.sortBy { it.epochMillis }
    }
    return kept.take(PRESET_COUNT)
}

/** The next occurrence of [hour]:[minute] after [nowMillis] (today, else tomorrow). */
private fun nextClockTime(nowMillis: Long, hour: Int, minute: Int): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
}

private const val PRESET_COUNT = 4
private const val ONE_HOUR_MS = 3_600_000L
private const val MIN_PRESET_GAP_MS = 15 * 60_000L
private val BACKFILL_OFFSET_HOURS = listOf(2L, 6L, 12L, 18L)
