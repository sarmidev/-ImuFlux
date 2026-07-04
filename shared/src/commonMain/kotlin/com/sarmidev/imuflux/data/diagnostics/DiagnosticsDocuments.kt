package com.sarmidev.imuflux.data.diagnostics

/**
 * Pure (de)serialization between diagnostics models and Firestore document
 * maps. Kept free of Firebase types so it is fully unit-testable on the host
 * JVM and usable from the desktop module without any Firebase dependency.
 *
 * Field names are the canonical English keys from the schema.
 */
object DiagnosticsDocuments {

    /** Firestore field name for the FCM registration token. */
    const val FIELD_FCM_TOKEN = "fcmToken"
    /** Firestore field name for the last successful token-write timestamp. */
    const val FIELD_FCM_TOKEN_UPDATED_AT = "fcmTokenUpdatedAt"
    /** Firestore field name for the "token seen invalid" timestamp. */
    const val FIELD_FCM_TOKEN_INVALID_AT = "fcmTokenInvalidAt"

    /**
     * Serializes a full device summary for a merge write.
     *
     * The FCM remote-control fields are **only** emitted when non-null. This is
     * deliberate: the diagnostics aggregator builds summaries that never carry a
     * token, so a periodic health/upload merge from the aggregator must not
     * include `fcmToken`/`fcmTokenUpdatedAt` — otherwise a `merge()` write would
     * clobber the token that [fcmTokenUpdate]/[minimalDeviceWithToken] persisted.
     */
    fun deviceToMap(summary: DeviceHealthSummary): Map<String, Any?> = buildMap {
        put("deviceId", summary.deviceId)
        put("warehouseId", summary.warehouseId)
        put("warehouseName", summary.warehouseName)
        put("forkliftId", summary.forkliftId)
        put("forkliftModel", summary.forkliftModel)
        put("appVersion", summary.appVersion)
        put("buildNumber", summary.buildNumber)
        put("androidVersion", summary.androidVersion)
        put("deviceModel", summary.deviceModel)
        put("manufacturer", summary.manufacturer)
        put("lastSeenAt", summary.lastSeenAt)
        put("lastRecordingStartedAt", summary.lastRecordingStartedAt)
        put("lastRecordingEndedAt", summary.lastRecordingEndedAt)
        put("lastSessionId", summary.lastSessionId)
        put("isRecording", summary.isRecording)
        put("currentHealthStatus", summary.currentHealthStatus.name)
        put("currentHealthReasons", summary.currentHealthReasons)
        put("lastMeasuredSampleRateHz", summary.lastMeasuredSampleRateHz)
        put("lastJitterP95Ms", summary.lastJitterP95Ms)
        put("lastFramesDropped", summary.lastFramesDropped)
        put("lastChunkIndex", summary.lastChunkIndex)
        put("lastBytesWritten", summary.lastBytesWritten)
        put("lastUploadSuccessAt", summary.lastUploadSuccessAt)
        put("lastUploadFailureAt", summary.lastUploadFailureAt)
        put("pendingUploadChunks", summary.pendingUploadChunks)
        put("nonFatalErrorCount", summary.nonFatalErrorCount)
        put("lastErrorMessageSafe", summary.lastErrorMessageSafe)
        summary.fcmToken?.let { put(FIELD_FCM_TOKEN, it) }
        summary.fcmTokenUpdatedAt?.let { put(FIELD_FCM_TOKEN_UPDATED_AT, it) }
        summary.fcmTokenInvalidAt?.let { put(FIELD_FCM_TOKEN_INVALID_AT, it) }
    }

    /**
     * Fields to `update()` on an **existing** device document to (re)register an
     * FCM token without touching health status. `fcmTokenInvalidAt` is reset to
     * `null` so a fresh registration re-enables remote control. `lastSeenAt` is
     * refreshed and is required by the Firestore `validDeviceWrite` rule.
     */
    fun fcmTokenUpdate(token: String, nowMs: Long): Map<String, Any?> = mapOf(
        FIELD_FCM_TOKEN to token,
        FIELD_FCM_TOKEN_UPDATED_AT to nowMs,
        FIELD_FCM_TOKEN_INVALID_AT to null,
        "lastSeenAt" to nowMs,
    )

    /**
     * Minimal, rules-valid device document used to `set(merge)` a token when the
     * device document does not exist yet. Includes the three fields the
     * `validDeviceWrite` rule requires (`deviceId`, `lastSeenAt`,
     * `currentHealthStatus`) plus the token — never fabricated health metrics.
     */
    fun minimalDeviceWithToken(deviceId: String, token: String, nowMs: Long): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "lastSeenAt" to nowMs,
        "currentHealthStatus" to DiagnosticsHealthStatus.UNKNOWN.name,
        FIELD_FCM_TOKEN to token,
        FIELD_FCM_TOKEN_UPDATED_AT to nowMs,
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
