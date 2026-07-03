package com.sarmidev.imuflux.data.diagnostics

/**
 * Pure (de)serialization between diagnostics models and Firestore document
 * maps. Kept free of Firebase types so it is fully unit-testable on the host
 * JVM (mirrors the approach of [com.sarmidev.imuflux.data.storage.SessionMetadataJson]).
 *
 * Field names are the canonical English keys from the schema.
 */
object DiagnosticsDocuments {

    fun deviceToMap(summary: DeviceHealthSummary): Map<String, Any?> = mapOf(
        "deviceId" to summary.deviceId,
        "warehouseId" to summary.warehouseId,
        "warehouseName" to summary.warehouseName,
        "forkliftId" to summary.forkliftId,
        "forkliftModel" to summary.forkliftModel,
        "appVersion" to summary.appVersion,
        "buildNumber" to summary.buildNumber,
        "androidVersion" to summary.androidVersion,
        "deviceModel" to summary.deviceModel,
        "manufacturer" to summary.manufacturer,
        "lastSeenAt" to summary.lastSeenAt,
        "lastRecordingStartedAt" to summary.lastRecordingStartedAt,
        "lastRecordingEndedAt" to summary.lastRecordingEndedAt,
        "lastSessionId" to summary.lastSessionId,
        "isRecording" to summary.isRecording,
        "currentHealthStatus" to summary.currentHealthStatus.name,
        "currentHealthReasons" to summary.currentHealthReasons,
        "lastMeasuredSampleRateHz" to summary.lastMeasuredSampleRateHz,
        "lastJitterP95Ms" to summary.lastJitterP95Ms,
        "lastFramesDropped" to summary.lastFramesDropped,
        "lastChunkIndex" to summary.lastChunkIndex,
        "lastBytesWritten" to summary.lastBytesWritten,
        "lastUploadSuccessAt" to summary.lastUploadSuccessAt,
        "lastUploadFailureAt" to summary.lastUploadFailureAt,
        "pendingUploadChunks" to summary.pendingUploadChunks,
        "nonFatalErrorCount" to summary.nonFatalErrorCount,
        "lastErrorMessageSafe" to summary.lastErrorMessageSafe,
    )

    fun sessionToMap(session: ImuSessionDiagnostics): Map<String, Any?> = mapOf(
        "sessionId" to session.sessionId,
        "resumeOf" to session.resumeOf,
        "deviceId" to session.deviceId,
        "warehouseId" to session.warehouseId,
        "warehouseName" to session.warehouseName,
        "forkliftId" to session.forkliftId,
        "forkliftModel" to session.forkliftModel,
        "startedAtWallMs" to session.startedAtWallMs,
        "endedAtWallMs" to session.endedAtWallMs,
        "startedAtBootNs" to session.startedAtBootNs,
        "durationMs" to session.durationMs,
        "expectedSampleRateHz" to session.expectedSampleRateHz,
        "measuredSampleRateHz" to session.measuredSampleRateHz,
        "samplesRecordedEstimate" to session.samplesRecordedEstimate,
        "framesDropped" to session.framesDropped,
        "jitterP95Ms" to session.jitterP95Ms,
        "gapsAbove50Ms" to session.gapsAbove50Ms,
        "maxGapMs" to session.maxGapMs,
        "bytesWritten" to session.bytesWritten,
        "chunkCount" to session.chunkCount,
        "uploadedChunkCount" to session.uploadedChunkCount,
        "pendingChunkCount" to session.pendingChunkCount,
        "uploadFailures" to session.uploadFailures,
        "recordingStopReason" to session.recordingStopReason.name,
        "healthStatus" to session.healthStatus.name,
        "healthReasons" to session.healthReasons,
        "createdAt" to session.createdAt,
        "updatedAt" to session.updatedAt,
    )

    fun windowToMap(window: ImuHealthWindow): Map<String, Any?> = mapOf(
        "windowId" to window.windowId,
        "sessionId" to window.sessionId,
        "deviceId" to window.deviceId,
        "warehouseId" to window.warehouseId,
        "warehouseName" to window.warehouseName,
        "forkliftId" to window.forkliftId,
        "forkliftModel" to window.forkliftModel,
        "startedAt" to window.startedAt,
        "endedAt" to window.endedAt,
        "durationMs" to window.durationMs,
        "samplesPerSecond" to window.samplesPerSecond,
        "jitterP95Ms" to window.jitterP95Ms,
        "framesQueued" to window.framesQueued,
        "framesDropped" to window.framesDropped,
        "bytesWritten" to window.bytesWritten,
        "currentChunkIndex" to window.currentChunkIndex,
        "uploadPendingCount" to window.uploadPendingCount,
        "healthStatus" to window.healthStatus.name,
        "healthReasons" to window.healthReasons,
    )
}
