package com.sarmidev.imuflux.data.diagnostics

/**
 * A single periodic health window (one every
 * [DiagnosticsConfig.healthWindowIntervalMs], ~60 s) emitted while recording.
 *
 * Maps 1:1 to `/diagnosticsDevices/{deviceId}/healthWindows/{windowId}`.
 *
 * Each window is a snapshot of the already-aggregated
 * [com.sarmidev.imuflux.domain.model.RecordingHealth] state — it never reads
 * raw IMU samples and never runs on the sensor callback.
 */
data class ImuHealthWindow(
    val windowId: String,
    val sessionId: String,
    val deviceId: String,
    val warehouseId: String = "",
    val warehouseName: String = "",
    val forkliftId: String = "",
    val forkliftModel: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val durationMs: Long = 0L,
    val samplesPerSecond: Double = 0.0,
    val jitterP95Ms: Double = 0.0,
    val framesQueued: Int = 0,
    val framesDropped: Long = 0L,
    val bytesWritten: Long = 0L,
    val currentChunkIndex: Int = 0,
    val uploadPendingCount: Int = 0,
    val healthStatus: DiagnosticsHealthStatus = DiagnosticsHealthStatus.UNKNOWN,
    val healthReasons: List<String> = emptyList(),
)
