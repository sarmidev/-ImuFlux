package com.sarmidev.imuflux.data.diagnostics

/**
 * Single source of truth for every diagnostics threshold, interval and
 * Firestore collection name. Per ARCHITECTURE.md the diagnostics layer must
 * keep all thresholds centralized — change them here, nowhere else.
 *
 * All values are plain data so this class is trivially unit-testable and can
 * be overridden in tests by constructing a custom instance.
 */
data class DiagnosticsConfig(
    // ── Sample rate (Hz) ──────────────────────────────────────────────────
    /** Nominal recording rate (100 Hz, see ARCHITECTURE.md §5). */
    val expectedSampleRateHz: Double = 100.0,
    /** >= this measured rate is OK. */
    val okMinSampleRateHz: Double = 95.0,
    /** [warnMinSampleRateHz, okMinSampleRateHz) is WARNING; below is ERROR. */
    val warnMinSampleRateHz: Double = 80.0,

    // ── Jitter (ms) ───────────────────────────────────────────────────────
    /** jitter p95 above this is WARNING (OK requires <=). */
    val warnJitterMaxMs: Double = 5.0,

    // ── Frame drops ───────────────────────────────────────────────────────
    /** OK requires framesDropped <= this. */
    val okMaxFramesDropped: Long = 0L,
    /** framesDropped >= this is a repeated-drop ERROR; in between is WARNING. */
    val errorFramesDropped: Long = 50L,

    // ── Upload health ─────────────────────────────────────────────────────
    /** consecutive upload failures >= this is ERROR; in (0, this) is WARNING. */
    val errorConsecutiveUploadFailures: Int = 3,

    // ── Device staleness (ms) ─────────────────────────────────────────────
    /** Age of lastSeenAt above which check-in is "delayed" (WARNING). */
    val deviceStaleWarnMs: Long = 15L * 60_000L,
    /** Age of lastSeenAt above which the device is "stale too long" (ERROR). */
    val deviceStaleErrorMs: Long = 60L * 60_000L,

    // ── Timing ────────────────────────────────────────────────────────────
    /** Periodic health-window flush cadence while recording (~60 s). */
    val healthWindowIntervalMs: Long = 60_000L,
    /**
     * Grace period after a session starts before "no samples while recording"
     * is treated as an ERROR (sensors + first frames need a moment to flow).
     */
    val noSamplesGraceMs: Long = 15_000L,

    // ── Safety ────────────────────────────────────────────────────────────
    /** Max length of `lastErrorMessageSafe` persisted to Firestore. */
    val maxSafeErrorLength: Int = 200,
) {
    companion object {
        /** Firestore root collection for per-device diagnostics. */
        const val DEVICES_COLLECTION = "diagnosticsDevices"
        /** Sub-collection of session diagnostics under a device. */
        const val SESSIONS_SUBCOLLECTION = "sessions"
        /** Sub-collection of periodic health windows under a device. */
        const val HEALTH_WINDOWS_SUBCOLLECTION = "healthWindows"

        /** A reasonable default instance for production wiring. */
        val DEFAULT = DiagnosticsConfig()
    }
}
