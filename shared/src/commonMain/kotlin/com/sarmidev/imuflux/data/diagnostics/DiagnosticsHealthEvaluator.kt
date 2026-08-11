package com.sarmidev.imuflux.data.diagnostics

/**
 * Pure, side-effect-free evaluation of recording health against the centralized
 * [DiagnosticsConfig] thresholds.
 *
 * It only observes **already aggregated** state (sample rate, jitter p95, frame
 * drops, upload counters, staleness). It never touches sensors, Firestore or the
 * network, so it is safe to call from anywhere and is fully unit-testable.
 *
 * Note: `@Inject`/`@Singleton` annotations have been intentionally omitted so
 * this class remains framework-free in `:shared`. The Android app wires it via
 * `DiagnosticsModule.provideDiagnosticsHealthEvaluator`.
 */
class DiagnosticsHealthEvaluator(
    private val config: DiagnosticsConfig,
) {

    /** Input snapshot of every signal the rules consider. */
    data class Input(
        val isRecording: Boolean,
        val measuredSampleRateHz: Double,
        val jitterP95Ms: Double,
        val framesDropped: Long,
        val samplesRecordedEstimate: Long,
        /** Elapsed wall time since the current session started (ms). */
        val recordingElapsedMs: Long,
        /** Number of upload failures in a row not yet followed by a success. */
        val consecutiveUploadFailures: Int = 0,
        /** True if an upload failed earlier in the session but later recovered. */
        val hadUploadFailureButRecovered: Boolean = false,
        /** Age of the device's lastSeenAt (ms). 0 when reporting live. */
        val lastSeenAgeMs: Long = 0L,
        val recordingFailedToStart: Boolean = false,
        val orphaned: Boolean = false,
    )

    data class Result(
        val status: DiagnosticsHealthStatus,
        val reasons: List<String>,
    )

    fun evaluate(input: Input): Result {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (input.recordingFailedToStart) errors += DiagnosticsReasons.RECORDING_FAILED_TO_START
        if (input.orphaned) errors += DiagnosticsReasons.ORPHANED_SESSION

        if (input.isRecording) {
            val beyondGrace = input.recordingElapsedMs >= config.noSamplesGraceMs
            if (beyondGrace && input.samplesRecordedEstimate <= 0L) {
                errors += DiagnosticsReasons.NO_SAMPLES_WHILE_RECORDING
            } else if (input.measuredSampleRateHz > 0.0 || beyondGrace) {
                when {
                    beyondGrace && input.measuredSampleRateHz < config.warnMinSampleRateHz ->
                        errors += DiagnosticsReasons.SAMPLE_RATE_CRITICAL
                    input.measuredSampleRateHz < config.okMinSampleRateHz ->
                        warnings += DiagnosticsReasons.SAMPLE_RATE_LOW
                }
            }
        }

        when {
            input.framesDropped >= config.errorFramesDropped ->
                errors += DiagnosticsReasons.REPEATED_FRAME_DROPS
            input.framesDropped > config.okMaxFramesDropped ->
                warnings += DiagnosticsReasons.FRAMES_DROPPED
        }

        if (input.jitterP95Ms > config.warnJitterMaxMs) {
            warnings += DiagnosticsReasons.JITTER_HIGH
        }

        when {
            input.consecutiveUploadFailures >= config.errorConsecutiveUploadFailures ->
                errors += DiagnosticsReasons.REPEATED_UPLOAD_FAILURES
            input.consecutiveUploadFailures > 0 ->
                warnings += DiagnosticsReasons.UPLOAD_FAILURE
            input.hadUploadFailureButRecovered ->
                warnings += DiagnosticsReasons.UPLOAD_FAILURE_RECOVERED
        }

        when {
            input.lastSeenAgeMs > config.deviceStaleErrorMs ->
                errors += DiagnosticsReasons.DEVICE_STALE
            input.lastSeenAgeMs > config.deviceStaleWarnMs ->
                warnings += DiagnosticsReasons.DEVICE_CHECKIN_DELAYED
        }

        return when {
            errors.isNotEmpty() -> Result(DiagnosticsHealthStatus.ERROR, errors)
            warnings.isNotEmpty() -> Result(DiagnosticsHealthStatus.WARNING, warnings)
            else -> Result(DiagnosticsHealthStatus.OK, listOf(DiagnosticsReasons.ALL_CHECKS_PASSED))
        }
    }

    /**
     * Escalates a previously stored status purely on the basis of how long ago
     * the device was last seen. Used by the dashboard so a device that stopped
     * reporting turns WARNING/ERROR even though its last stored status was OK.
     */
    fun escalateForStaleness(
        stored: DiagnosticsHealthStatus,
        lastSeenAgeMs: Long,
    ): Result {
        val stale = when {
            lastSeenAgeMs > config.deviceStaleErrorMs ->
                Result(DiagnosticsHealthStatus.ERROR, listOf(DiagnosticsReasons.DEVICE_STALE))
            lastSeenAgeMs > config.deviceStaleWarnMs ->
                Result(DiagnosticsHealthStatus.WARNING, listOf(DiagnosticsReasons.DEVICE_CHECKIN_DELAYED))
            else -> null
        } ?: return Result(stored, emptyList())
        return if (stale.status >= stored) stale else Result(stored, emptyList())
    }
}
