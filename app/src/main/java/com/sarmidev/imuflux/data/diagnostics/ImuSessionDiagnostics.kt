package com.sarmidev.imuflux.data.diagnostics

/**
 * Per-session aggregated diagnostics.
 *
 * Maps 1:1 to `/diagnosticsDevices/{deviceId}/sessions/{sessionId}`.
 *
 * Note: `gapsAbove50Ms` and `maxGapMs` are not measured live by
 * [com.sarmidev.imuflux.recording.RecordingHealthTracker] (they are computed by
 * the offline `tools/validate_session.py`). They are kept here for schema
 * completeness and default to 0 until a future live gap tracker fills them.
 */
data class ImuSessionDiagnostics(
    val sessionId: String,
    val resumeOf: String? = null,
    val deviceId: String,
    val warehouseId: String = "",
    val warehouseName: String = "",
    val forkliftId: String = "",
    val forkliftModel: String = "",
    val startedAtWallMs: Long = 0L,
    val endedAtWallMs: Long? = null,
    val startedAtBootNs: Long = 0L,
    val durationMs: Long = 0L,
    val expectedSampleRateHz: Double = 0.0,
    val measuredSampleRateHz: Double = 0.0,
    val samplesRecordedEstimate: Long = 0L,
    val framesDropped: Long = 0L,
    val jitterP95Ms: Double = 0.0,
    val gapsAbove50Ms: Long = 0L,
    val maxGapMs: Double = 0.0,
    val bytesWritten: Long = 0L,
    val chunkCount: Int = 0,
    val uploadedChunkCount: Int = 0,
    val pendingChunkCount: Int = 0,
    val uploadFailures: Int = 0,
    val recordingStopReason: RecordingStopReason = RecordingStopReason.UNKNOWN,
    val healthStatus: DiagnosticsHealthStatus = DiagnosticsHealthStatus.UNKNOWN,
    val healthReasons: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
