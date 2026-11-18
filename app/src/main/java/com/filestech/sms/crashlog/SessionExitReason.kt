package com.filestech.sms.crashlog

/**
 * Local-only error log — why a previous process died, as the
 * system recorded it (`ApplicationExitInfo`, API 30+).
 *
 * The codes mirror `ApplicationExitInfo.REASON_*` exactly, but
 * they are spelled out here instead of referenced: the classifier
 * stays pure JVM (no Android class to load in unit tests), and
 * the mapping is explicit rather than hidden behind a framework
 * constant. An unknown code (a reason added by a future Android
 * release) maps to [UNKNOWN] — an unexplained death is reported,
 * never silently dropped.
 */
enum class SessionExitReason(
    /** The `ApplicationExitInfo.REASON_*` code. */
    val reasonCode: Int,
    /** Short human-readable name, as written in the report body. */
    val displayName: String,
) {
    /** The system could not say why the process died (or the code is unknown). */
    UNKNOWN(0, "unknown"),

    /** The process exited itself (e.g. `System.exit`). */
    EXIT_SELF(1, "exit self"),

    /** The process was killed by a signal. */
    SIGNALED(2, "signaled"),

    /** The system killed the process to reclaim memory. */
    LOW_MEMORY(3, "low memory"),

    /** An uncaught Java exception killed the process. */
    CRASH(4, "crash"),

    /** A native crash killed the process. */
    CRASH_NATIVE(5, "native crash"),

    /** The system killed the process after an ANR (app not responding). */
    ANR(6, "ANR"),

    /** The process failed to start (e.g. a library failed to load). */
    INITIALIZATION_FAILURE(7, "initialization failure"),

    /** The process was killed because a permission changed. */
    PERMISSION_CHANGE(8, "permission change"),

    /** The process was killed for using too many resources. */
    EXCESSIVE_RESOURCE_USAGE(9, "excessive resource usage"),

    /** The user asked the system to stop the app (e.g. swiped it away). */
    USER_REQUESTED(10, "user requested"),

    /** The user force-stopped the app from Settings. */
    USER_STOPPED(11, "user stopped"),

    /** A process this one depended on died. */
    DEPENDENCY_DIED(12, "dependency died"),

    /** The system killed the process for another reason. */
    OTHER(13, "other");

    companion object {
        /** Maps an `ApplicationExitInfo` reason code, or [UNKNOWN] for any other value. */
        fun fromReasonCode(code: Int): SessionExitReason {
            return entries.firstOrNull { it.reasonCode == code } ?: UNKNOWN
        }
    }
}
