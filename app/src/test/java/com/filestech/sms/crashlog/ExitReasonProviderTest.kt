package com.filestech.sms.crashlog

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Local-only error log — the exit-reason query: the previous
 * session's pid scopes the query, the most recent record maps
 * to its reason + timestamp, an entry older than the session
 * (a reused pid) is ignored, and an empty history or a binder
 * failure answers null (no record) instead of throwing.
 *
 * These tests drive [ActivityManagerExitReasonProvider.query]
 * directly: the production entry point
 * ([ActivityManagerExitReasonProvider.mostRecentExit]) guards on
 * `SDK_INT`, which is 0 on plain JVM, so it would always answer
 * null here. The class carries `@RequiresApi` so lint accepts
 * the direct calls.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ExitReasonProviderTest {

    private val marker = SessionMarker(
        sessionStartMillis = 1_000L,
        lastAliveMillis = 2_000L,
        pid = 1234,
        foreground = true,
    )

    private fun exitInfo(reason: Int, timestamp: Long): ApplicationExitInfo {
        val info = mockk<ApplicationExitInfo>()
        every { info.reason } returns reason
        every { info.timestamp } returns timestamp
        return info
    }

    private fun managerReturning(records: List<ApplicationExitInfo>): ActivityManager {
        val manager = mockk<ActivityManager>()
        every { manager.getHistoricalProcessExitReasons(any(), any(), any()) } returns records
        return manager
    }

    @Test
    fun `query maps the most recent exit record`() {
        val manager = managerReturning(listOf(exitInfo(6, 2_500L)))
        val provider = ActivityManagerExitReasonProvider(manager, "com.filestech.sms")
        assertThat(provider.query(marker)).isEqualTo(SessionExitInfo(SessionExitReason.ANR, 2_500L))
    }

    @Test
    fun `query asks for the previous session's pid only`() {
        val manager = managerReturning(emptyList())
        ActivityManagerExitReasonProvider(manager, "com.filestech.sms").query(marker)
        verify { manager.getHistoricalProcessExitReasons("com.filestech.sms", 1234, 1) }
    }

    @Test
    fun `query ignores an entry older than the session`() {
        val manager = managerReturning(listOf(exitInfo(6, 500L)))
        val provider = ActivityManagerExitReasonProvider(manager, "com.filestech.sms")
        assertThat(provider.query(marker)).isNull()
    }

    @Test
    fun `query returns null for an empty history`() {
        val manager = managerReturning(emptyList())
        val provider = ActivityManagerExitReasonProvider(manager, "com.filestech.sms")
        assertThat(provider.query(marker)).isNull()
    }

    @Test
    fun `query returns null when the manager throws`() {
        val manager = mockk<ActivityManager>()
        every { manager.getHistoricalProcessExitReasons(any(), any(), any()) } throws
            RuntimeException("binder died")
        val provider = ActivityManagerExitReasonProvider(manager, "com.filestech.sms")
        assertThat(provider.query(marker)).isNull()
    }
}
