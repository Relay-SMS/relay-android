package com.filestech.sms.ui.screens.details

import androidx.annotation.StringRes
import com.filestech.sms.R
import com.filestech.sms.domain.model.Attachment
import java.util.Calendar

/**
 * Round 32 — the date bucket a media item falls in, for the details
 * panel's Photos / Videos / Files tabs: Today / Yesterday / This
 * week / This month / Older, in that order.
 *
 * The boundaries mirror the desktop detail pane (rolling day counts
 * from the start of today), NOT the main list's calendar weeks and
 * months — the two groupings look the same but split differently
 * around week and month edges, and the panel follows the desktop.
 */
enum class DetailsDateGroup(@StringRes val labelRes: Int) {
    TODAY(R.string.date_today),
    YESTERDAY(R.string.date_yesterday),
    THIS_WEEK(R.string.date_this_week),
    THIS_MONTH(R.string.date_this_month),
    OLDER(R.string.date_older),
}

/** Buckets one timestamp. Pure — `nowMillis` exists so tests can pin "today". */
fun detailsDateGroupFor(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): DetailsDateGroup {
    val startOfToday = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // One `when`, one return (Detekt ReturnCount): the buckets are
    // checked in order, so an earlier match wins.
    return when {
        timestampMillis >= startOfToday -> DetailsDateGroup.TODAY
        timestampMillis >= startOfToday - DAY_MS -> DetailsDateGroup.YESTERDAY
        timestampMillis >= startOfToday - WEEK_MS -> DetailsDateGroup.THIS_WEEK
        timestampMillis >= startOfToday - MONTH_MS -> DetailsDateGroup.THIS_MONTH
        else -> DetailsDateGroup.OLDER
    }
}

/**
 * Groups media items by [detailsDateGroupFor], newest group first,
 * dropping empty groups. Items keep their input order inside a
 * group (the queries already return newest first). An item without
 * a message date — only possible outside the media queries — lands
 * in Older.
 */
fun List<Attachment>.groupByDetailsDate(
    nowMillis: Long = System.currentTimeMillis(),
): List<Pair<DetailsDateGroup, List<Attachment>>> {
    val grouped = groupBy { detailsDateGroupFor(it.messageDate ?: 0L, nowMillis) }
    return DetailsDateGroup.entries.mapNotNull { group ->
        grouped[group]?.let { group to it }
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val WEEK_MS = 7 * DAY_MS
private const val MONTH_MS = 30 * DAY_MS
