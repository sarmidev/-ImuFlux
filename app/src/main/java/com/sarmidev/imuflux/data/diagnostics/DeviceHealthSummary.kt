package com.sarmidev.imuflux.data.diagnostics

/**
 * Latest aggregated health snapshot for a single device.
 *
 * Maps 1:1 to the Firestore document `/diagnosticsDevices/{deviceId}`.
 * This is the lightweight, already-aggregated state — never raw IMU samples.
 */
data class DeviceHealthSummary(
    val deviceId: String,
    val warehouseId: String = "",
    val warehouseName: String = "",
    val forkliftId: String = "",
    val forkliftModel: String = "",
    val appVersion: String = "",
    val buildNumber: Long = 0L,
    val androidVersion: Int = 0,
    val deviceModel: String = "",
    val manufacturer: String = "",
    /** Wall-clock ms of the last time the device wrote any diagnostics. */
    val lastSeenAt: Long = 0L,
    val lastRecordingStartedAt: Long? = null,
    val lastRecordingEndedAt: Long? = null,
    val lastSessionId: String? = null,
    val isRecording: Boolean = false,
    val currentHealthStatus: DiagnosticsHealthStatus = DiagnosticsHealthStatus.UNKNOWN,
    val currentHealthReasons: List<String> = emptyList(),
    val lastMeasuredSampleRateHz: Double = 0.0,
    val lastJitterP95Ms: Double = 0.0,
    val lastFramesDropped: Long = 0L,
    val lastChunkIndex: Int = 0,
    val lastBytesWritten: Long = 0L,
    val lastUploadSuccessAt: Long? = null,
    val lastUploadFailureAt: Long? = null,
    val pendingUploadChunks: Int = 0,
    val nonFatalErrorCount: Long = 0L,
    val lastErrorMessageSafe: String? = null,
)
