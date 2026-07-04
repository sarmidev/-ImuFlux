package com.sarmidev.imuflux.data.diagnostics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Firebase Crashlytics / Analytics / Performance integration for the
 * diagnostics layer.
 *
 * IMPORTANT (ARCHITECTURE.md hard constraints): nothing here may run on the
 * sensor callback, `FrameAssembler`, or the recording producer hot path. Every
 * caller of this logger is on an isolated thread (the diagnostics aggregator's
 * single IO thread, the upload pool, or a `viewModelScope`) and only ever passes
 * **already-aggregated** state. Crashlytics/Analytics/Performance calls are
 * themselves async and batched by the SDKs, so they never block recording.
 */
@Singleton
class DiagnosticsLogger @Inject constructor(
    private val crashlytics: FirebaseCrashlytics,
    private val analytics: FirebaseAnalytics,
    private val performance: FirebasePerformance,
) {

    // ── Crashlytics custom keys ───────────────────────────────────────────
    fun setRecordingContextKeys(
        identity: DeviceIdentity,
        assignment: DeviceAssignment,
        sessionId: String?,
        isRecording: Boolean,
        healthStatus: DiagnosticsHealthStatus,
        recordingResumeOf: String?,
    ) {
        runCatching {
            crashlytics.setCustomKey(KEY_DEVICE_ID, identity.deviceId)
            crashlytics.setCustomKey(KEY_WAREHOUSE_ID, assignment.warehouseId)
            crashlytics.setCustomKey(KEY_WAREHOUSE_NAME, assignment.warehouseName)
            crashlytics.setCustomKey(KEY_FORKLIFT_ID, assignment.forkliftId)
            crashlytics.setCustomKey(KEY_FORKLIFT_MODEL, assignment.forkliftModel)
            crashlytics.setCustomKey(KEY_SESSION_ID, sessionId ?: "")
            crashlytics.setCustomKey(KEY_IS_RECORDING, isRecording)
            crashlytics.setCustomKey(KEY_HEALTH_STATUS, healthStatus.name)
            crashlytics.setCustomKey(KEY_APP_VERSION, identity.appVersion)
            crashlytics.setCustomKey(KEY_RECORDING_RESUME_OF, recordingResumeOf ?: "")
        }
    }

    /** Crashlytics breadcrumb log. */
    fun log(message: String) {
        runCatching { crashlytics.log(message) }
    }

    /** Records a non-fatal exception (with a safe message) to Crashlytics. */
    fun recordNonFatal(message: String?, cause: Throwable? = null) {
        runCatching {
            val msg = message?.takeIf { it.isNotBlank() } ?: "diagnostics non-fatal"
            crashlytics.log(msg)
            crashlytics.recordException(cause ?: DiagnosticsNonFatalException(msg))
        }
    }

    // ── Analytics events ──────────────────────────────────────────────────
    fun event(name: String, params: Map<String, Any?> = emptyMap()) {
        runCatching { analytics.logEvent(name, params.toBundle()) }
    }

    fun recordingStarted(sessionId: String, resumeOf: String?) =
        event(EVENT_RECORDING_STARTED, mapOf("session_id" to sessionId, "resume_of" to resumeOf))

    fun recordingStopped(sessionId: String, reason: RecordingStopReason, durationMs: Long) =
        event(
            EVENT_RECORDING_STOPPED,
            mapOf("session_id" to sessionId, "reason" to reason.name, "duration_ms" to durationMs),
        )

    fun healthWarning(sessionId: String, reasons: List<String>) {
        log("health WARNING for $sessionId: ${reasons.joinToString(",")}")
        event(EVENT_HEALTH_WARNING, mapOf("session_id" to sessionId, "reasons" to reasons.joinToString(",")))
    }

    fun healthError(sessionId: String, reasons: List<String>) {
        val joined = reasons.joinToString(",")
        log("health ERROR for $sessionId: $joined")
        event(EVENT_HEALTH_ERROR, mapOf("session_id" to sessionId, "reasons" to joined))
        // A persistent ERROR is worth a non-fatal so it surfaces in Crashlytics.
        recordNonFatal("imu_health_error session=$sessionId reasons=$joined")
    }

    fun chunkUploadSuccess(sessionId: String, chunkIndex: Int) =
        event(EVENT_CHUNK_UPLOAD_SUCCESS, mapOf("session_id" to sessionId, "chunk_index" to chunkIndex))

    fun chunkUploadFailure(sessionId: String, chunkIndex: Int) =
        event(EVENT_CHUNK_UPLOAD_FAILURE, mapOf("session_id" to sessionId, "chunk_index" to chunkIndex))

    fun dashboardOpened() = event(EVENT_DASHBOARD_OPENED)

    fun deviceAssignmentChanged(assignment: DeviceAssignment) =
        event(
            EVENT_DEVICE_ASSIGNMENT_CHANGED,
            mapOf(
                "forklift_id" to assignment.forkliftId,
                "warehouse_id" to assignment.warehouseId,
            ),
        )

    // ── Performance traces ────────────────────────────────────────────────
    /** Runs [block] inside a Firebase Performance custom trace. */
    fun <T> trace(name: String, block: () -> T): T {
        val trace: Trace? = runCatching { performance.newTrace(name).apply { start() } }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { trace?.stop() }
        }
    }

    /** Suspend-friendly variant for traces that span suspending Firestore I/O. */
    suspend fun <T> traceSuspend(name: String, block: suspend () -> T): T {
        val trace: Trace? = runCatching { performance.newTrace(name).apply { start() } }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { trace?.stop() }
        }
    }

    private fun Map<String, Any?>.toBundle(): Bundle {
        val bundle = Bundle()
        for ((key, value) in this) {
            when (value) {
                null -> Unit
                is String -> bundle.putString(key, value)
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is Float -> bundle.putDouble(key, value.toDouble())
                is Boolean -> bundle.putString(key, value.toString())
                else -> bundle.putString(key, value.toString())
            }
        }
        return bundle
    }

    /** Marker exception so health-error non-fatals are grouped in Crashlytics. */
    private class DiagnosticsNonFatalException(message: String) : Exception(message)

    companion object {
        const val EVENT_RECORDING_STARTED = "imu_recording_started"
        const val EVENT_RECORDING_STOPPED = "imu_recording_stopped"
        const val EVENT_HEALTH_WARNING = "imu_health_warning"
        const val EVENT_HEALTH_ERROR = "imu_health_error"
        const val EVENT_CHUNK_UPLOAD_SUCCESS = "chunk_upload_success"
        const val EVENT_CHUNK_UPLOAD_FAILURE = "chunk_upload_failure"
        const val EVENT_DASHBOARD_OPENED = "diagnostics_dashboard_opened"
        const val EVENT_DEVICE_ASSIGNMENT_CHANGED = "device_assignment_changed"

        const val TRACE_SESSION_START = "imu_session_start"
        const val TRACE_CHUNK_UPLOAD = "chunk_upload"
        const val TRACE_FIRESTORE_WRITE = "diagnostics_firestore_write"
        const val TRACE_DASHBOARD_LOAD = "diagnostics_dashboard_load"

        // Crashlytics custom key names (snake_case per spec).
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_WAREHOUSE_ID = "warehouse_id"
        private const val KEY_WAREHOUSE_NAME = "warehouse_name"
        private const val KEY_FORKLIFT_ID = "forklift_id"
        private const val KEY_FORKLIFT_MODEL = "forklift_model"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_HEALTH_STATUS = "health_status"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_RECORDING_RESUME_OF = "recording_resume_of"
    }
}
