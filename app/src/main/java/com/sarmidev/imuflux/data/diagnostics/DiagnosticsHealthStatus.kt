package com.sarmidev.imuflux.data.diagnostics

/**
 * Aggregated health verdict for a recording window / session / device.
 *
 * Ordered by severity so the dashboard and the aggregator can compute the
 * "worst" status across several windows with [maxOf].
 */
enum class DiagnosticsHealthStatus {
    /** No data yet (device never reported, or window not evaluated). */
    UNKNOWN,

    /** All measurable health rules passed. */
    OK,

    /** At least one degraded-but-tolerable signal (see [DiagnosticsReasons]). */
    WARNING,

    /** At least one critical failure that needs attention. */
    ERROR;

    companion object {
        /** Parses a Firestore string value, defaulting to [UNKNOWN]. */
        fun fromWire(value: String?): DiagnosticsHealthStatus =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * Why a recording session ended. Persisted on [ImuSessionDiagnostics] so the
 * dashboard can distinguish a clean user stop from an OEM kill or a failed start.
 */
enum class RecordingStopReason {
    /** User pressed stop (or the app stopped the service deliberately). */
    USER_STOP,

    /** The recording engine refused to start (no disk space, already recording…). */
    FAILED_TO_START,

    /** The foreground service was destroyed by the system while recording. */
    SERVICE_DESTROYED,

    /** Session was closed by the orphan-cleanup path (probable abrupt kill). */
    ORPHAN_CLEANUP,

    /** Reason unknown / not provided. */
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): RecordingStopReason =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * Machine-readable, locale-independent reason codes used in `healthReasons`.
 *
 * Centralized here so the evaluator, the dashboard and any test reference the
 * exact same strings. The dashboard maps them to localized labels for display.
 */
object DiagnosticsReasons {
    const val ALL_CHECKS_PASSED = "ALL_CHECKS_PASSED"

    // ERROR-level
    const val SAMPLE_RATE_CRITICAL = "SAMPLE_RATE_CRITICAL"
    const val NO_SAMPLES_WHILE_RECORDING = "NO_SAMPLES_WHILE_RECORDING"
    const val REPEATED_FRAME_DROPS = "REPEATED_FRAME_DROPS"
    const val REPEATED_UPLOAD_FAILURES = "REPEATED_UPLOAD_FAILURES"
    const val ORPHANED_SESSION = "ORPHANED_SESSION"
    const val RECORDING_FAILED_TO_START = "RECORDING_FAILED_TO_START"
    const val DEVICE_STALE = "DEVICE_STALE"

    // WARNING-level
    const val SAMPLE_RATE_LOW = "SAMPLE_RATE_LOW"
    const val JITTER_HIGH = "JITTER_HIGH"
    const val FRAMES_DROPPED = "FRAMES_DROPPED"
    const val UPLOAD_FAILURE = "UPLOAD_FAILURE"
    const val UPLOAD_FAILURE_RECOVERED = "UPLOAD_FAILURE_RECOVERED"
    const val DEVICE_CHECKIN_DELAYED = "DEVICE_CHECKIN_DELAYED"
}
