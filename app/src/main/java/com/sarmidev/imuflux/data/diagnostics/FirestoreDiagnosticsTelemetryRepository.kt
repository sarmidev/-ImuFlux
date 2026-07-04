package com.sarmidev.imuflux.data.diagnostics

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firestore-backed [DiagnosticsTelemetryRepository].
 *
 * Design constraints honored:
 *  - Only aggregated documents are written (never raw IMU samples).
 *  - Writes never throw: any failure (offline, no `google-services.json`, rules
 *    rejection) is caught and logged so recording/upload are never affected.
 *  - Anonymous auth is ensured before any access, matching the existing
 *    [com.sarmidev.imuflux.data.repository.AnalysisRemoteRepositoryImpl].
 */
@Singleton
class FirestoreDiagnosticsTelemetryRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val logger: DiagnosticsLogger,
) : DiagnosticsTelemetryRepository {

    private fun deviceDoc(deviceId: String) =
        firestore.collection(DiagnosticsConfig.DEVICES_COLLECTION).document(deviceId)

    override suspend fun upsertDeviceSummary(summary: DeviceHealthSummary) {
        Log.d(TAG, "upsertDeviceSummary → Hz=${summary.lastMeasuredSampleRateHz} " +
            "jit=${summary.lastJitterP95Ms} drop=${summary.lastFramesDropped} " +
            "bytes=${summary.lastBytesWritten} recording=${summary.isRecording} " +
            "device=${summary.deviceId}")
        safeWrite("upsertDeviceSummary(${summary.deviceId})") {
            deviceDoc(summary.deviceId)
                .set(DiagnosticsDocuments.deviceToMap(summary), SetOptions.merge())
                .awaitTask()
        }
    }

    override suspend fun writeSessionDiagnostics(session: ImuSessionDiagnostics) {
        safeWrite("writeSessionDiagnostics(${session.sessionId})") {
            deviceDoc(session.deviceId)
                .collection(DiagnosticsConfig.SESSIONS_SUBCOLLECTION)
                .document(session.sessionId)
                .set(DiagnosticsDocuments.sessionToMap(session), SetOptions.merge())
                .awaitTask()
        }
    }

    override suspend fun writeHealthWindow(window: ImuHealthWindow) {
        safeWrite("writeHealthWindow(${window.windowId})") {
            deviceDoc(window.deviceId)
                .collection(DiagnosticsConfig.HEALTH_WINDOWS_SUBCOLLECTION)
                .document(window.windowId)
                .set(DiagnosticsDocuments.windowToMap(window))
                .awaitTask()
        }
    }

    override suspend fun fetchDevices(limit: Long): List<DeviceHealthSummary> = safeRead("fetchDevices") {
        ensureSignedIn()
        firestore.collection(DiagnosticsConfig.DEVICES_COLLECTION)
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .awaitTask()
            ?.documents
            ?.mapNotNull { it.toDeviceSummary() }
            ?: emptyList()
    } ?: emptyList()

    override suspend fun fetchDevice(deviceId: String): DeviceHealthSummary? = safeRead("fetchDevice") {
        ensureSignedIn()
        deviceDoc(deviceId).get().awaitTask()?.toDeviceSummary()
    }

    override suspend fun fetchRecentSessions(deviceId: String, limit: Long): List<ImuSessionDiagnostics> =
        safeRead("fetchRecentSessions") {
            ensureSignedIn()
            deviceDoc(deviceId)
                .collection(DiagnosticsConfig.SESSIONS_SUBCOLLECTION)
                .orderBy("startedAtWallMs", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .awaitTask()
                ?.documents
                ?.mapNotNull { it.toSessionDiagnostics() }
                ?: emptyList()
        } ?: emptyList()

    override suspend fun fetchRecentHealthWindows(deviceId: String, limit: Long): List<ImuHealthWindow> =
        safeRead("fetchRecentHealthWindows") {
            ensureSignedIn()
            deviceDoc(deviceId)
                .collection(DiagnosticsConfig.HEALTH_WINDOWS_SUBCOLLECTION)
                .orderBy("endedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .awaitTask()
                ?.documents
                ?.mapNotNull { it.toHealthWindow() }
                ?: emptyList()
        } ?: emptyList()

    // ── Internals ─────────────────────────────────────────────────────────

    private suspend fun safeWrite(label: String, block: suspend () -> Unit) {
        runCatching {
            ensureSignedIn()
            logger.traceSuspend(DiagnosticsLogger.TRACE_FIRESTORE_WRITE) { block() }
        }
            .onSuccess { Log.d(TAG, "diagnostics write OK: $label") }
            .onFailure { Log.w(TAG, "diagnostics write FAILED: $label (${it.message})", it) }
    }

    private suspend fun <T> safeRead(label: String, block: suspend () -> T): T? =
        runCatching { block() }
            .onFailure { Log.w(TAG, "diagnostics read failed: $label (${it.message})") }
            .getOrNull()

    private suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().awaitTask()
        }
    }

    private fun DocumentSnapshot.toDeviceSummary(): DeviceHealthSummary? {
        val id = getString("deviceId") ?: id.takeIf { it.isNotEmpty() } ?: return null
        return DeviceHealthSummary(
            deviceId = id,
            warehouseId = getString("warehouseId") ?: "",
            warehouseName = getString("warehouseName") ?: "",
            forkliftId = getString("forkliftId") ?: "",
            forkliftModel = getString("forkliftModel") ?: "",
            appVersion = getString("appVersion") ?: "",
            buildNumber = getLong("buildNumber") ?: 0L,
            androidVersion = getLong("androidVersion")?.toInt() ?: 0,
            deviceModel = getString("deviceModel") ?: "",
            manufacturer = getString("manufacturer") ?: "",
            lastSeenAt = getLong("lastSeenAt") ?: 0L,
            lastRecordingStartedAt = getLong("lastRecordingStartedAt"),
            lastRecordingEndedAt = getLong("lastRecordingEndedAt"),
            lastSessionId = getString("lastSessionId"),
            isRecording = getBoolean("isRecording") ?: false,
            currentHealthStatus = DiagnosticsHealthStatus.fromWire(getString("currentHealthStatus")),
            currentHealthReasons = stringList("currentHealthReasons"),
            lastMeasuredSampleRateHz = getDouble("lastMeasuredSampleRateHz") ?: 0.0,
            lastJitterP95Ms = getDouble("lastJitterP95Ms") ?: 0.0,
            lastFramesDropped = getLong("lastFramesDropped") ?: 0L,
            lastChunkIndex = getLong("lastChunkIndex")?.toInt() ?: 0,
            lastBytesWritten = getLong("lastBytesWritten") ?: 0L,
            lastUploadSuccessAt = getLong("lastUploadSuccessAt"),
            lastUploadFailureAt = getLong("lastUploadFailureAt"),
            pendingUploadChunks = getLong("pendingUploadChunks")?.toInt() ?: 0,
            nonFatalErrorCount = getLong("nonFatalErrorCount") ?: 0L,
            lastErrorMessageSafe = getString("lastErrorMessageSafe"),
        )
    }

    private fun DocumentSnapshot.toSessionDiagnostics(): ImuSessionDiagnostics? {
        val sessionId = getString("sessionId") ?: id.takeIf { it.isNotEmpty() } ?: return null
        return ImuSessionDiagnostics(
            sessionId = sessionId,
            resumeOf = getString("resumeOf"),
            deviceId = getString("deviceId") ?: "",
            warehouseId = getString("warehouseId") ?: "",
            warehouseName = getString("warehouseName") ?: "",
            forkliftId = getString("forkliftId") ?: "",
            forkliftModel = getString("forkliftModel") ?: "",
            startedAtWallMs = getLong("startedAtWallMs") ?: 0L,
            endedAtWallMs = getLong("endedAtWallMs"),
            startedAtBootNs = getLong("startedAtBootNs") ?: 0L,
            durationMs = getLong("durationMs") ?: 0L,
            expectedSampleRateHz = getDouble("expectedSampleRateHz") ?: 0.0,
            measuredSampleRateHz = getDouble("measuredSampleRateHz") ?: 0.0,
            samplesRecordedEstimate = getLong("samplesRecordedEstimate") ?: 0L,
            framesDropped = getLong("framesDropped") ?: 0L,
            jitterP95Ms = getDouble("jitterP95Ms") ?: 0.0,
            gapsAbove50Ms = getLong("gapsAbove50Ms") ?: 0L,
            maxGapMs = getDouble("maxGapMs") ?: 0.0,
            bytesWritten = getLong("bytesWritten") ?: 0L,
            chunkCount = getLong("chunkCount")?.toInt() ?: 0,
            uploadedChunkCount = getLong("uploadedChunkCount")?.toInt() ?: 0,
            pendingChunkCount = getLong("pendingChunkCount")?.toInt() ?: 0,
            uploadFailures = getLong("uploadFailures")?.toInt() ?: 0,
            recordingStopReason = RecordingStopReason.fromWire(getString("recordingStopReason")),
            healthStatus = DiagnosticsHealthStatus.fromWire(getString("healthStatus")),
            healthReasons = stringList("healthReasons"),
            createdAt = getLong("createdAt") ?: 0L,
            updatedAt = getLong("updatedAt") ?: 0L,
        )
    }

    private fun DocumentSnapshot.toHealthWindow(): ImuHealthWindow? {
        val windowId = getString("windowId") ?: id.takeIf { it.isNotEmpty() } ?: return null
        return ImuHealthWindow(
            windowId = windowId,
            sessionId = getString("sessionId") ?: "",
            deviceId = getString("deviceId") ?: "",
            warehouseId = getString("warehouseId") ?: "",
            warehouseName = getString("warehouseName") ?: "",
            forkliftId = getString("forkliftId") ?: "",
            forkliftModel = getString("forkliftModel") ?: "",
            startedAt = getLong("startedAt") ?: 0L,
            endedAt = getLong("endedAt") ?: 0L,
            durationMs = getLong("durationMs") ?: 0L,
            samplesPerSecond = getDouble("samplesPerSecond") ?: 0.0,
            jitterP95Ms = getDouble("jitterP95Ms") ?: 0.0,
            framesQueued = getLong("framesQueued")?.toInt() ?: 0,
            framesDropped = getLong("framesDropped") ?: 0L,
            bytesWritten = getLong("bytesWritten") ?: 0L,
            currentChunkIndex = getLong("currentChunkIndex")?.toInt() ?: 0,
            uploadPendingCount = getLong("uploadPendingCount")?.toInt() ?: 0,
            healthStatus = DiagnosticsHealthStatus.fromWire(getString("healthStatus")),
            healthReasons = stringList("healthReasons"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result)
            else cont.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
        }
    }

    private companion object {
        const val TAG = "FsDiagnosticsRepo"
    }
}
