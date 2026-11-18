package com.filestech.sms.crashlog

import java.io.File

/**
 * Local-only error log — the file-backed store for the session
 * marker (see [SessionMarker]).
 *
 * The marker lives next to the crash reports, in the same
 * directory, but its file name never matches the report pattern,
 * so [CrashReportStore.listReports], rotation, and Clear all all
 * ignore it: it is session state, not a report. The constructor
 * takes the directory so tests can point it at a temp dir on
 * plain JVM — no Android calls in this class.
 *
 * Every method is best-effort and never throws: marker I/O must
 * never crash the app it is diagnosing. Writes are atomic (temp
 * file + rename), so a death mid-write leaves either the old
 * marker or the new one, never a torn file.
 */
class SessionMarkerStore(private val reportsDir: File) {

    companion object {
        /** The marker file, inside the reports directory. */
        const val FILE_NAME: String = "session-marker.json"

        /** Scratch file for the atomic write; never read back. */
        private const val TEMP_FILE_NAME: String = "session-marker.json.tmp"
    }

    /**
     * Writes the marker atomically. Returns false when the write
     * failed (a stale marker, if any, is left in place).
     */
    fun write(marker: SessionMarker): Boolean {
        return runCatching {
            reportsDir.mkdirs()
            val temp = File(reportsDir, TEMP_FILE_NAME)
            temp.writeText(SessionMarker.encode(marker))
            if (temp.renameTo(File(reportsDir, FILE_NAME))) {
                true
            } else {
                temp.delete()
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Reads the marker, or null when there is none or it is
     * unreadable/corrupt (a corrupt marker is treated as no
     * marker — see [SessionMarker.decode]).
     */
    fun read(): SessionMarker? {
        val file = File(reportsDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { SessionMarker.decode(file.readText()) }.getOrNull()
    }

    /**
     * Deletes the marker. Called by the crash handler after it
     * records a report, so the next startup does not ALSO record
     * the same death as an unexpected stop. Returns false when
     * there was nothing to delete or the delete failed.
     */
    fun delete(): Boolean {
        return runCatching { File(reportsDir, FILE_NAME).delete() }.getOrDefault(false)
    }
}
