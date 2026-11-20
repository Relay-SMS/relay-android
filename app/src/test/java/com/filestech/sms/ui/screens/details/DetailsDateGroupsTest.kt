package com.filestech.sms.ui.screens.details

import com.filestech.sms.domain.model.Attachment
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Round 32 — the details panel's media date buckets. Boundaries are
 * the desktop pane's rolling day counts from the start of today
 * (not the main list's calendar weeks/months), so the tests pin
 * "now" and probe each edge to the millisecond.
 */
class DetailsDateGroupsTest {

    // A fixed "now": 2026-11-19 15:30 local. The start of that day
    // is computed the same way as the code under test.
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 19, 15, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val startOfToday: Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `today starts at midnight`() {
        assertThat(detailsDateGroupFor(startOfToday, now)).isEqualTo(DetailsDateGroup.TODAY)
        assertThat(detailsDateGroupFor(now, now)).isEqualTo(DetailsDateGroup.TODAY)
        assertThat(detailsDateGroupFor(startOfToday - 1, now)).isEqualTo(DetailsDateGroup.YESTERDAY)
    }

    @Test
    fun `yesterday covers the previous 24 hours`() {
        assertThat(detailsDateGroupFor(startOfToday - DAY_MS, now)).isEqualTo(DetailsDateGroup.YESTERDAY)
        assertThat(detailsDateGroupFor(startOfToday - DAY_MS - 1, now)).isEqualTo(DetailsDateGroup.THIS_WEEK)
    }

    @Test
    fun `this week covers the previous 7 days`() {
        assertThat(detailsDateGroupFor(startOfToday - 7 * DAY_MS, now)).isEqualTo(DetailsDateGroup.THIS_WEEK)
        assertThat(detailsDateGroupFor(startOfToday - 7 * DAY_MS - 1, now)).isEqualTo(DetailsDateGroup.THIS_MONTH)
    }

    @Test
    fun `this month covers the previous 30 days`() {
        assertThat(detailsDateGroupFor(startOfToday - 30 * DAY_MS, now)).isEqualTo(DetailsDateGroup.THIS_MONTH)
        assertThat(detailsDateGroupFor(startOfToday - 30 * DAY_MS - 1, now)).isEqualTo(DetailsDateGroup.OLDER)
    }

    @Test
    fun `grouping orders newest group first and drops empty groups`() {
        val items = listOf(
            attachment(id = 1, date = startOfToday - 40 * DAY_MS),
            attachment(id = 2, date = startOfToday + 60_000L),
            attachment(id = 3, date = startOfToday - 3 * DAY_MS),
            attachment(id = 4, date = startOfToday + 120_000L),
        )
        val grouped = items.groupByDetailsDate(now)
        assertThat(grouped.map { it.first }).containsExactly(
            DetailsDateGroup.TODAY,
            DetailsDateGroup.THIS_WEEK,
            DetailsDateGroup.OLDER,
        ).inOrder()
        // Input order is kept inside a group (newest first).
        assertThat(grouped[0].second.map { it.id }).containsExactly(2L, 4L).inOrder()
        assertThat(grouped[1].second.map { it.id }).containsExactly(3L)
        assertThat(grouped[2].second.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `an item without a message date lands in Older`() {
        val items = listOf(attachment(id = 1, date = null))
        val grouped = items.groupByDetailsDate(now)
        assertThat(grouped.map { it.first }).containsExactly(DetailsDateGroup.OLDER)
    }

    private fun attachment(id: Long, date: Long?): Attachment = Attachment(
        id = id,
        messageId = id,
        mimeType = "image/jpeg",
        fileName = "a.jpg",
        sizeBytes = 3,
        localUri = "/files/a.jpg",
        width = null,
        height = null,
        durationMs = null,
        messageDate = date,
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
