package com.filestech.sms.crashlog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local-only error log — one session's marker: the breadcrumb a
 * session leaves behind so a death WITHOUT a Java exception
 * (freeze/ANR-kill, native crash, OOM, system kill) still gets
 * recorded. The crash handler only fires on an uncaught exception;
 * a freeze-kill throws nothing, so without this the error log
 * stays empty and the death is invisible.
 *
 * The marker is written at startup and refreshed while the app
 * runs (foreground/background transitions, screen changes,
 * sync-state changes, and a heartbeat while foregrounded — see
 * [SessionMarkerTracker]). At the next startup,
 * [SessionDeathChecker] reads it: a marker present then means the
 * previous session died without a crash report (the handler
 * deletes the marker when it records one), and the exit reason
 * decides whether the death is worth an entry.
 *
 * Privacy, same rule as the crash reports (v1: metadata only):
 * timestamps, the process id, the foreground state, a screen
 * NAME (the route pattern, never a filled-in argument — no
 * conversation id, no address), and the sync phase. No message
 * content, no phone numbers, no contact names, no addresses.
 */
@Serializable
data class SessionMarker(
    /** When this session started, epoch millis. */
    val sessionStartMillis: Long,
    /** The last time the session was known alive, epoch millis. */
    val lastAliveMillis: Long,
    /** The process id, so the exit-reason query can match our process exactly. */
    val pid: Int,
    /** Whether the app was in the foreground at [lastAliveMillis]. */
    val foreground: Boolean,
    /** The current screen's name (e.g. "Thread"), or null when unknown. */
    val screen: String? = null,
    /** The client sync phase ("syncing", "paused", "idle", …), or null when unknown. */
    val syncState: String? = null,
) {
    companion object {
        /**
         * Tolerant JSON: unknown fields are ignored, so a marker
         * written by a newer build still reads on an older one.
         */
        private val json = Json { ignoreUnknownKeys = true }

        /** Encodes a marker for the file. Never throws for a valid marker. */
        fun encode(marker: SessionMarker): String = json.encodeToString(marker)

        /**
         * Decodes a marker, or null when the text is not a marker
         * (missing file content, a torn write, a hand-edited file).
         * A corrupt marker is treated as no marker — the death
         * goes unrecorded rather than crashing the startup check.
         */
        fun decode(text: String): SessionMarker? {
            return runCatching { json.decodeFromString<SessionMarker>(text) }.getOrNull()
        }

        /**
         * The short screen name for a type-safe route string, e.g.
         * `com.filestech.sms.ui.navigation.Thread/{conversationId}`
         * becomes "Thread" and
         * `com.filestech.sms.ui.navigation.Conversations?archived={archived}`
         * becomes "Conversations". The route PATTERN is used, never
         * the filled-in arguments, so no id or address can leak in.
         * Returns null for a null, blank, or unparseable route.
         */
        fun screenName(route: String?): String? {
            if (route.isNullOrBlank()) return null
            val simple = route.substringAfterLast('.')
            val cut = simple.indexOfFirst { it == '/' || it == '?' || it == '{' }
            val name = if (cut >= 0) simple.substring(0, cut) else simple
            return name.ifBlank { null }
        }
    }
}
