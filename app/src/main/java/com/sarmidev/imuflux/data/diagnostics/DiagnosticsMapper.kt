package com.sarmidev.imuflux.data.diagnostics

import com.sarmidev.imuflux.domain.model.RecordingHealth

/**
 * Pure mapping between the live recording state and the diagnostics documents.
 *
 * Kept free of Android, Firebase and time side-effects (callers pass `now`) so
 * health-window aggregation and session start/stop mapping are fully
 * unit-testable. [ImuDiagnosticsAggregator] is a thin, Android-aware wrapper
 * around these functions.
 */
object DiagnosticsMapper {

    /** Normalized snapshot of the already-aggregated [RecordingHealth]. */
    data class Metrics(
        val measuredSampleRateHz: Double,
        val jitterP95Ms: Double,
        val framesQueued: Int,
        val framesDropped: Long,
        val samplesRecordedEstimate: Long,
        val bytesWritten: Long,
        val currentChunkIndex: Int,
    ) {
        val chunkCount: Int get() = (currentChunkIndex + 1).coerceAtLeast(0)
    }

    /** Running upload counters for the active session. */
    data class UploadStats(
        val uploadedChunkCount: Int = 0,
        val uploadFailures: Int = 0,
        val consecutiveUploadFailures: Int = 0,
        val hadUploadFailureButRecovered: Boolean = false,
    )

    fun metricsOf(health: RecordingHealth): Metrics = Metrics(
        measuredSampleRateHz = health.samplesPerSecond.toDouble(),
        jitterP95Ms = health.jitterP95Ns / 1_000_000.0,
        framesQueued = health.framesQueued,
        framesDropped = health.framesDropped,
        samplesRecordedEstimate = health.framesWritten,
        bytesWritten = health.bytesWritten,
        currentChunkIndex = health.currentChunkIndex,
    )

    fun pendingChunkCount(metrics: Metrics, uploadedChunkCount: Int): Int =
        (metrics.chunkCount - uploadedChunkCount).coerceAtLeast(0)

    fun evaluatorInput(
        metrics: Metrics,
        isRecording: Boolean,
        recordingElapsedMs: Long,
        upload: UploadStats,
    ): DiagnosticsHealthEvaluator.Input = DiagnosticsHealthEvaluator.Input(
        isRecording = isRecording,
        measuredSampleRateHz = metrics.measuredSampleRateHz,
        jitterP95Ms = metrics.jitterP95Ms,
        framesDropped = metrics.framesDropped,
        samplesRecordedEstimate = metrics.samplesRecordedEstimate,
        recordingElapsedMs = recordingElapsedMs,
        consecutiveUploadFailures = upload.consecutiveUploadFailures,
        hadUploadFailureButRecovered = upload.hadUploadFailureButRecovered,
    )

    fun buildHealthWindow(
        identity: DeviceIdentity,
        assignment: DeviceAssignment,
        sessionId: String,
        windowIndex: Int,
        metrics: Metrics,
        eval: DiagnosticsHealthEvaluator.Result,
        startedAt: Long,
        endedAt: Long,
        upload: UploadStats,
    ): ImuHealthWindow = ImuHealthWindow(
        windowId = windowId(sessionId, windowIndex),
        sessionId = sessionId,
        deviceId = identity.deviceId,
        warehouseId = assignment.warehouseId,
        warehouseName = assignment.warehouseName,
        forkliftId = assignment.forkliftId,
        forkliftModel = assignment.forkliftModel,
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = (endedAt - startedAt).coerceAtLeast(0L),
        samplesPerSecond = metrics.measuredSampleRateHz,
        jitterP95Ms = metrics.jitterP95Ms,
        framesQueued = metrics.framesQueued,
        framesDropped = metrics.framesDropped,
        bytesWritten = metrics.bytesWritten,
        currentChunkIndex = metrics.currentChunkIndex,
        uploadPendingCount = pendingChunkCount(metrics, upload.uploadedChunkCount),
        healthStatus = eval.status,
        healthReasons = eval.reasons,
    )

    fun buildSessionDiagnostics(
        identity: DeviceIdentity,
        assignment: DeviceAssignment,
        sessionId: String,
        resumeOf: String?,
        startedAtWallMs: Long,
        startedAtBootNs: Long,
        metrics: Metrics,
        eval: DiagnosticsHealthEvaluator.Result,
        reason: RecordingStopReason,
        ended: Boolean,
        now: Long,
        upload: UploadStats,
        expectedSampleRateHz: Double,
    ): ImuSessionDiagnostics = ImuSessionDiagnostics(
        sessionId = sessionId,
        resumeOf = resumeOf,
        deviceId = identity.deviceId,
        warehouseId = assignment.warehouseId,
        warehouseName = assignment.warehouseName,
        forkliftId = assignment.forkliftId,
        forkliftModel = assignment.forkliftModel,
        startedAtWallMs = startedAtWallMs,
        endedAtWallMs = if (ended) now else null,
        startedAtBootNs = startedAtBootNs,
        durationMs = (now - startedAtWallMs).coerceAtLeast(0L),
        expectedSampleRateHz = expectedSampleRateHz,
        measuredSampleRateHz = metrics.measuredSampleRateHz,
        samplesRecordedEstimate = metrics.samplesRecordedEstimate,
        framesDropped = metrics.framesDropped,
        jitterP95Ms = metrics.jitterP95Ms,
        gapsAbove50Ms = 0L,
        maxGapMs = 0.0,
        bytesWritten = metrics.bytesWritten,
        chunkCount = metrics.chunkCount,
        uploadedChunkCount = upload.uploadedChunkCount,
        pendingChunkCount = pendingChunkCount(metrics, upload.uploadedChunkCount),
        uploadFailures = upload.uploadFailures,
        recordingStopReason = reason,
        healthStatus = eval.status,
        healthReasons = eval.reasons,
        createdAt = startedAtWallMs,
        updatedAt = now,
    )

    fun windowId(sessionId: String, windowIndex: Int): String =
        "${sessionId}_w${windowIndex.toString().padStart(4, '0')}"
}
