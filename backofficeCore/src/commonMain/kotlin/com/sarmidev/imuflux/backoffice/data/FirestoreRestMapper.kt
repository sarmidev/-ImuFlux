package com.sarmidev.imuflux.backoffice.data

import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics
import com.sarmidev.imuflux.data.diagnostics.RecordingStopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure mapping of Firestore REST document payloads to `:shared` diagnostics
 * models. Kept free of any HTTP/coroutine concerns so the mapping rules are
 * fully unit-testable on the host JVM.
 *
 * Firestore REST wraps every scalar in a typed object, e.g.
 * `{"stringValue": "x"}`, `{"integerValue": "42"}`, `{"doubleValue": 99.5}`,
 * `{"booleanValue": true}`, `{"arrayValue": {"values": [...]}}`. Numbers are
 * accepted whether stored as `integerValue` or `doubleValue` so the mapping
 * matches the Android `DocumentSnapshot.getLong/getDouble` behavior (which
 * coerces between the two). Missing fields fall back to the same defaults used
 * by the Android repository.
 */
object FirestoreRestMapper {

    // ── Public entry points ───────────────────────────────────────────────

    /** Extracts the `fields` object of a Firestore REST document, or null. */
    fun fields(document: JsonObject): JsonObject? =
        document["fields"] as? JsonObject

    /** Last path segment of a Firestore document `name`, e.g. the document id. */
    fun documentId(document: JsonObject): String? =
        (document["name"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')

    fun toDeviceSummary(document: JsonObject): DeviceHealthSummary? {
        val fields = fields(document) ?: JsonObject(emptyMap())
        val id = fields.stringValue("deviceId")
            ?: documentId(document)?.takeIf { it.isNotEmpty() }
            ?: return null
        return DeviceHealthSummary(
            deviceId = id,
            warehouseId = fields.stringValue("warehouseId") ?: "",
            warehouseName = fields.stringValue("warehouseName") ?: "",
            forkliftId = fields.stringValue("forkliftId") ?: "",
            forkliftModel = fields.stringValue("forkliftModel") ?: "",
            appVersion = fields.stringValue("appVersion") ?: "",
            buildNumber = fields.longValue("buildNumber") ?: 0L,
            androidVersion = fields.longValue("androidVersion")?.toInt() ?: 0,
            deviceModel = fields.stringValue("deviceModel") ?: "",
            manufacturer = fields.stringValue("manufacturer") ?: "",
            lastSeenAt = fields.longValue("lastSeenAt") ?: 0L,
            lastRecordingStartedAt = fields.longValue("lastRecordingStartedAt"),
            lastRecordingEndedAt = fields.longValue("lastRecordingEndedAt"),
            lastSessionId = fields.stringValue("lastSessionId"),
            isRecording = fields.booleanValue("isRecording") ?: false,
            currentHealthStatus = DiagnosticsHealthStatus.fromWire(fields.stringValue("currentHealthStatus")),
            currentHealthReasons = fields.stringList("currentHealthReasons"),
            lastMeasuredSampleRateHz = fields.doubleValue("lastMeasuredSampleRateHz") ?: 0.0,
            lastJitterP95Ms = fields.doubleValue("lastJitterP95Ms") ?: 0.0,
            lastFramesDropped = fields.longValue("lastFramesDropped") ?: 0L,
            lastChunkIndex = fields.longValue("lastChunkIndex")?.toInt() ?: 0,
            lastBytesWritten = fields.longValue("lastBytesWritten") ?: 0L,
            lastUploadSuccessAt = fields.longValue("lastUploadSuccessAt"),
            lastUploadFailureAt = fields.longValue("lastUploadFailureAt"),
            pendingUploadChunks = fields.longValue("pendingUploadChunks")?.toInt() ?: 0,
            nonFatalErrorCount = fields.longValue("nonFatalErrorCount") ?: 0L,
            lastErrorMessageSafe = fields.stringValue("lastErrorMessageSafe"),
            fcmToken = fields.stringValue("fcmToken"),
            fcmTokenUpdatedAt = fields.longValue("fcmTokenUpdatedAt"),
            fcmTokenInvalidAt = fields.longValue("fcmTokenInvalidAt"),
        )
    }

    fun toSessionDiagnostics(document: JsonObject): ImuSessionDiagnostics? {
        val fields = fields(document) ?: JsonObject(emptyMap())
        val sessionId = fields.stringValue("sessionId")
            ?: documentId(document)?.takeIf { it.isNotEmpty() }
            ?: return null
        return ImuSessionDiagnostics(
            sessionId = sessionId,
            resumeOf = fields.stringValue("resumeOf"),
            deviceId = fields.stringValue("deviceId") ?: "",
            warehouseId = fields.stringValue("warehouseId") ?: "",
            warehouseName = fields.stringValue("warehouseName") ?: "",
            forkliftId = fields.stringValue("forkliftId") ?: "",
            forkliftModel = fields.stringValue("forkliftModel") ?: "",
            startedAtWallMs = fields.longValue("startedAtWallMs") ?: 0L,
            endedAtWallMs = fields.longValue("endedAtWallMs"),
            startedAtBootNs = fields.longValue("startedAtBootNs") ?: 0L,
            durationMs = fields.longValue("durationMs") ?: 0L,
            expectedSampleRateHz = fields.doubleValue("expectedSampleRateHz") ?: 0.0,
            measuredSampleRateHz = fields.doubleValue("measuredSampleRateHz") ?: 0.0,
            samplesRecordedEstimate = fields.longValue("samplesRecordedEstimate") ?: 0L,
            framesDropped = fields.longValue("framesDropped") ?: 0L,
            jitterP95Ms = fields.doubleValue("jitterP95Ms") ?: 0.0,
            gapsAbove50Ms = fields.longValue("gapsAbove50Ms") ?: 0L,
            maxGapMs = fields.doubleValue("maxGapMs") ?: 0.0,
            bytesWritten = fields.longValue("bytesWritten") ?: 0L,
            chunkCount = fields.longValue("chunkCount")?.toInt() ?: 0,
            uploadedChunkCount = fields.longValue("uploadedChunkCount")?.toInt() ?: 0,
            pendingChunkCount = fields.longValue("pendingChunkCount")?.toInt() ?: 0,
            uploadFailures = fields.longValue("uploadFailures")?.toInt() ?: 0,
            recordingStopReason = RecordingStopReason.fromWire(fields.stringValue("recordingStopReason")),
            healthStatus = DiagnosticsHealthStatus.fromWire(fields.stringValue("healthStatus")),
            healthReasons = fields.stringList("healthReasons"),
            createdAt = fields.longValue("createdAt") ?: 0L,
            updatedAt = fields.longValue("updatedAt") ?: 0L,
        )
    }

    fun toHealthWindow(document: JsonObject): ImuHealthWindow? {
        val fields = fields(document) ?: JsonObject(emptyMap())
        val windowId = fields.stringValue("windowId")
            ?: documentId(document)?.takeIf { it.isNotEmpty() }
            ?: return null
        return ImuHealthWindow(
            windowId = windowId,
            sessionId = fields.stringValue("sessionId") ?: "",
            deviceId = fields.stringValue("deviceId") ?: "",
            warehouseId = fields.stringValue("warehouseId") ?: "",
            warehouseName = fields.stringValue("warehouseName") ?: "",
            forkliftId = fields.stringValue("forkliftId") ?: "",
            forkliftModel = fields.stringValue("forkliftModel") ?: "",
            startedAt = fields.longValue("startedAt") ?: 0L,
            endedAt = fields.longValue("endedAt") ?: 0L,
            durationMs = fields.longValue("durationMs") ?: 0L,
            samplesPerSecond = fields.doubleValue("samplesPerSecond") ?: 0.0,
            jitterP95Ms = fields.doubleValue("jitterP95Ms") ?: 0.0,
            framesQueued = fields.longValue("framesQueued")?.toInt() ?: 0,
            framesDropped = fields.longValue("framesDropped") ?: 0L,
            bytesWritten = fields.longValue("bytesWritten") ?: 0L,
            currentChunkIndex = fields.longValue("currentChunkIndex")?.toInt() ?: 0,
            uploadPendingCount = fields.longValue("uploadPendingCount")?.toInt() ?: 0,
            healthStatus = DiagnosticsHealthStatus.fromWire(fields.stringValue("healthStatus")),
            healthReasons = fields.stringList("healthReasons"),
        )
    }

    // ── Typed value accessors over a Firestore `fields` object ─────────────

    private fun JsonObject.valueOf(field: String): JsonObject? = this[field] as? JsonObject

    fun JsonObject.stringValue(field: String): String? =
        valueOf(field)?.get("stringValue")?.let { (it as? JsonPrimitive)?.contentOrNull }

    fun JsonObject.booleanValue(field: String): Boolean? =
        valueOf(field)?.get("booleanValue")?.let { (it as? JsonPrimitive)?.booleanOrNull() }

    /** Reads a whole number stored as `integerValue` (string) or `doubleValue`. */
    fun JsonObject.longValue(field: String): Long? {
        val v = valueOf(field) ?: return null
        (v["integerValue"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { return it }
        (v["doubleValue"] as? JsonPrimitive)?.numberOrNull()?.let { return it.toLong() }
        return null
    }

    /** Reads a real number stored as `doubleValue` or `integerValue`. */
    fun JsonObject.doubleValue(field: String): Double? {
        val v = valueOf(field) ?: return null
        (v["doubleValue"] as? JsonPrimitive)?.numberOrNull()?.let { return it }
        (v["integerValue"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { return it }
        return null
    }

    fun JsonObject.stringList(field: String): List<String> {
        val arr = valueOf(field)?.get("arrayValue") as? JsonObject ?: return emptyList()
        val values = arr["values"] as? JsonArray ?: return emptyList()
        return values.mapNotNull { element ->
            (element as? JsonObject)?.get("stringValue")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }
    }

    private fun JsonPrimitive.booleanOrNull(): Boolean? = content.toBooleanStrictOrNull()

    private fun JsonPrimitive.numberOrNull(): Double? = contentOrNull?.toDoubleOrNull()
}
