package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Round 33 — the schedule presets (the desktop popup's four quick
 * times, same semantics on both sides): in 1 hour, the next 8 PM
 * (tonight, or tomorrow once 8 PM has passed), the next 9 AM, the
 * next noon — 15-minute dedupe with relative backfill, always four,
 * always in the future, always sorted.
 *
 * All "now" values are built with [Calendar] in the default time
 * zone, the same clock [schedulePresets] reads, so the tests hold in
 * any zone.
 */
class SchedulePresetTest {

    @Test
    fun `four presets, sorted, all in the future`() {
        val now = todayAt(10, 0)
        val presets = schedulePresets(now)
        assertThat(presets).hasSize(4)
        assertThat(presets.map { it.epochMillis }).isInOrder()
        assertThat(presets.all { it.epochMillis > now }).isTrue()
    }

    @Test
    fun `morning offers 11 AM, noon, 8 PM and 9 AM tomorrow`() {
        val now = todayAt(10, 0)
        val presets = schedulePresets(now)
        // In 1 hour first, then noon, then 8 PM, then tomorrow 9 AM.
        assertThat(presets[0].epochMillis).isEqualTo(now + ONE_HOUR_MS)
        assertThat(presets[0].isInOneHour).isTrue()
        assertThat(hourOf(presets[1].epochMillis)).isEqualTo(12)
        assertThat(hourOf(presets[2].epochMillis)).isEqualTo(20)
        assertThat(isSameDay(presets[2].epochMillis, now)).isTrue()
        assertThat(hourOf(presets[3].epochMillis)).isEqualTo(9)
        assertThat(isSameDay(presets[3].epochMillis, now)).isFalse()
    }

    @Test
    fun `tonight 8 PM rolls to tomorrow once 8 PM has passed`() {
        val now = todayAt(21, 30)
        val presets = schedulePresets(now)
        val eightPm = presets.first { hourOf(it.epochMillis) == 20 }
        assertThat(isSameDay(eightPm.epochMillis, now)).isFalse()
        assertThat(eightPm.epochMillis).isGreaterThan(now)
    }

    @Test
    fun `tonight 8 PM stays today while it is still ahead`() {
        val now = todayAt(18, 0)
        val presets = schedulePresets(now)
        val eightPm = presets.first { hourOf(it.epochMillis) == 20 }
        assertThat(isSameDay(eightPm.epochMillis, now)).isTrue()
    }

    @Test
    fun `9 AM and noon roll to tomorrow after they pass`() {
        val now = todayAt(13, 0)
        val presets = schedulePresets(now)
        val nineAm = presets.first { hourOf(it.epochMillis) == 9 }
        val noon = presets.first { hourOf(it.epochMillis) == 12 }
        assertThat(isSameDay(nineAm.epochMillis, now)).isFalse()
        assertThat(isSameDay(noon.epochMillis, now)).isFalse()
    }

    @Test
    fun `close presets dedupe and backfill keeps four`() {
        // At 7:05 PM, "+1 hour" (8:05 PM) and "next 8 PM" (8:00 PM)
        // are five minutes apart: the later one drops, and a +2h
        // backfill (9:05 PM) keeps the list at four.
        val now = todayAt(19, 5)
        val presets = schedulePresets(now)
        assertThat(presets).hasSize(4)
        assertThat(presets.map { it.epochMillis }).contains(todayAt(20, 0))
        assertThat(presets.map { it.epochMillis }).doesNotContain(todayAt(20, 5))
        assertThat(presets.map { it.epochMillis }).contains(now + 2 * ONE_HOUR_MS)
    }

    @Test
    fun `every preset is inside the next 24 hours`() {
        val now = todayAt(23, 45)
        val presets = schedulePresets(now)
        assertThat(presets.all { it.epochMillis - now <= 24 * ONE_HOUR_MS }).isTrue()
    }

    private fun hourOf(epochMillis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = epochMillis }.get(Calendar.HOUR_OF_DAY)

    /** Today at [hour]:[minute] local time, seconds and millis zeroed. */
    private fun todayAt(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private companion object {
        const val ONE_HOUR_MS = 3_600_000L
    }
}
