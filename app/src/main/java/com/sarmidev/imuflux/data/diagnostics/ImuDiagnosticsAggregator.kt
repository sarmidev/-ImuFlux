package com.sarmidev.imuflux.data.diagnostics

import android.util.Log
import com.sarmidev.imuflux.domain.model.RecordingHealth
import com.sarmidev.imuflux.domain.model.SessionMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central diagnostics orchestrator. It **observes already-aggregated** recording
 * state and writes lightweight telemetry to Firestore.
 *
 * ### Threading / safety (per ARCHITECTURE.md hard constraints)
 *  - Runs entirely on its **own** `Dispatchers.IO.limitedParallelism(1)` —
 *    completely separate from the recording consumer's IO thread and from the
 *    uploader's pool. It never touches `SensorHub`, `FrameAssembler` or the
 *    `RecordingEngine` producer.
 *  - All public entry points just `launch` onto that single-threaded scope, so
 *    every mutation of internal state is serialized without locks and nothing a
 *    caller does can block (calls return in ~µs).
 *  - The periodic flush reads `RecordingHealth` from the already-throttled
 *    `StateFlow` snapshot every [DiagnosticsConfig.healthWindowIntervalMs]
 *    (~60 s). It never writes Firestore at sensor frequency.
 *  - Every Firestore call is failure-tolerant (see repository), so diagnostics
 *    can never break 8-hour recording reliability.
 */
@Singleton
class ImuDiagnosticsAggregator @Inject constructor(
    private val identityProvider: DeviceIdentityProvider,
    private val assignmentProvider: DeviceAssignmentProvider,
    private val repository: DiagnosticsTelemetryRepository,
    private val evaluator: DiagnosticsHealthEvaluator,
    private val config: DiagnosticsConfig,
    private val logger: DiagnosticsLogger,
) {

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var windowJob: Job? = null
    private var active: ActiveSession? = null

    /** Cached full device summary; always written whole (merge) to avoid clobbering. */
    private var summary: DeviceHealthSummary? = null

    // ── Recording lifecycle ───────────────────────────────────────────────

    /**
     * Call right after [com.sarmidev.imuflux.recording.RecordingEngine.start]
     * succeeds. [healthFlow] is the engine's already-aggregated health state.
     */
    fun onRecordingStarted(metadata: SessionMetadata, healthFlow: StateFlow<RecordingHealth>) {
        scope.launch { runCatching { handleStarted(metadata, healthFlow) }.logFailure("onRecordingStarted") }
    }

    /** Call when the engine refused to start a session (disk full, etc.). */
    fun onRecordingFailedToStart(resumeOf: String?) {
        scope.launch { runCatching { handleFailedToStart(resumeOf) }.logFailure("onRecordingFailedToStart") }
    }

    /** Call right after [com.sarmidev.imuflux.recording.RecordingEngine.stop]. */
    fun onRecordingStopped(reason: RecordingStopReason) {
        scope.launch { runCatching { handleStopped(reason) }.logFailure("onRecordingStopped") }
    }

    // ── Upload lifecycle (called from the uploader's pool) ─────────────────

    fun onUploadSuccess(sessionId: String, chunkIndex: Int) {
        scope.launch { runCatching { handleUploadSuccess(sessionId, chunkIndex) }.logFailure("onUploadSuccess") }
    }

    fun onUploadFailure(sessionId: String, chunkIndex: Int, message: String?) {
        scope.launch {
            runCatching { handleUploadFailure(sessionId, chunkIndex, message) }.logFailure("onUploadFailure")
        }
    }

    /** Records a non-fatal error (safe message only) on the device summary. */
    fun recordNonFatalError(message: String?) {
        scope.launch { runCatching { handleNonFatalError(message) }.logFailure("recordNonFatalError") }
    }

    // ── Handlers (all run on the single diagnostics thread) ────────────────

    private suspend fun handleStarted(metadata: SessionMetadata, healthFlow: StateFlow<RecordingHealth>) {
        // Resolve the Firebase UID before building the identity snapshot.
        // ensureSignedIn() is idempotent and fast once auth is established.
        identityProvider.ensureSignedIn()
        val identity = identityProvider.current()
        val assignment = assignmentProvider.current()
        val now = System.currentTimeMillis()

        // Emit a one-shot assignment-changed analytics event on real changes.
        assignmentProvider.consumeAssignmentChange()?.let { logger.deviceAssignmentChanged(it) }

        val session = ActiveSession(
            identity = identity,
            assignment = assignment,
            sessionId = metadata.sessionId,
            resumeOf = metadata.resumeOf,
            startedAtWallMs = metadata.startedAtWallMs.takeIf { it > 0 } ?: now,
            startedAtBootNs = metadata.startedAtBootNs,
            healthFlow = healthFlow,
        )
        active = session

        summary = baseSummary(identity, assignment).copy(
            lastSeenAt = now,
            lastRecordingStartedAt = now,
            lastSessionId = metadata.sessionId,
            isRecording = true,
            currentHealthStatus = DiagnosticsHealthStatus.OK,
            currentHealthReasons = listOf(DiagnosticsReasons.ALL_CHECKS_PASSED),
        )

        val startEval = DiagnosticsHealthEvaluator.Result(
            DiagnosticsHealthStatus.OK,
            listOf(DiagnosticsReasons.ALL_CHECKS_PASSED),
        )
        // Performance trace around the session-start bookkeeping (Firestore I/O).
        logger.traceSuspend(DiagnosticsLogger.TRACE_SESSION_START) {
            repository.upsertDeviceSummary(summary!!)
            repository.writeSessionDiagnostics(
                buildSession(session, RecordingHealth(), startEval, RecordingStopReason.UNKNOWN, ended = false, now = now),
            )
        }

        logger.recordingStarted(metadata.sessionId, metadata.resumeOf)
        updateCrashlyticsKeys(session, DiagnosticsHealthStatus.OK)
        Log.d(TAG, "handleStarted OK — session=${metadata.sessionId} deviceId=${identity.deviceId} healthFlow=${healthFlow.value}")

        startWindowLoop(session)
    }

    private suspend fun handleFailedToStart(resumeOf: String?) {
        identityProvider.ensureSignedIn()
        val identity = identityProvider.current()
        val assignment = assignmentProvider.current()
        val now = System.currentTimeMillis()
        val eval = evaluator.evaluate(
            DiagnosticsHealthEvaluator.Input(
                isRecording = false,
                measuredSampleRateHz = 0.0,
                jitterP95Ms = 0.0,
                framesDropped = 0L,
                samplesRecordedEstimate = 0L,
                recordingElapsedMs = 0L,
                recordingFailedToStart = true,
            ),
        )
        summary = baseSummary(identity, assignment).copy(
            lastSeenAt = now,
            isRecording = false,
            currentHealthStatus = eval.status,
            currentHealthReasons = eval.reasons,
        )
        repository.upsertDeviceSummary(summary!!)
        logger.healthError("(start)", eval.reasons)
        Log.w(TAG, "recording failed to start (resumeOf=$resumeOf): ${eval.reasons}")
    }

    private suspend fun handleStopped(reason: RecordingStopReason) {
        val session = active ?: run {
            Log.w(TAG, "handleStopped — active session is NULL, skipping")
            return
        }
        windowJob?.cancel()
        windowJob = null
        val health = session.healthFlow.value
        Log.d(TAG, "handleStopped — reason=$reason health=$health")
        val now = System.currentTimeMillis()

        val eval = evaluate(session, health, isRecording = false)
        repository.writeSessionDiagnostics(
            buildSession(session, health, eval, reason, ended = true, now = now),
        )

        summary = (summary ?: baseSummary(session.identity, session.assignment)).copy(
            lastSeenAt = now,
            lastRecordingEndedAt = now,
            isRecording = false,
            currentHealthStatus = eval.status,
            currentHealthReasons = eval.reasons,
            lastMeasuredSampleRateHz = health.samplesPerSecond.toDouble(),
            lastJitterP95Ms = health.jitterP95Ns / 1_000_000.0,
            lastFramesDropped = health.framesDropped,
            lastChunkIndex = health.currentChunkIndex,
            lastBytesWritten = health.bytesWritten,
        )
        repository.upsertDeviceSummary(summary!!)

        logger.recordingStopped(session.sessionId, reason, now - session.startedAtWallMs)
        updateCrashlyticsKeys(session, eval.status, recording = false)
        active = null
    }

    private fun startWindowLoop(session: ActiveSession) {
        windowJob?.cancel()
        windowJob = scope.launch {
            // First flush after a short grace period so the device summary gets
            // real metrics quickly instead of waiting the full 60-second window.
            delay(INITIAL_FLUSH_DELAY_MS)
            if (isActive && active === session) {
                runCatching { flushWindow(session) }.logFailure("flushWindow(initial)")
            }
            while (isActive && active === session) {
                delay(config.healthWindowIntervalMs)
                runCatching { flushWindow(session) }.logFailure("flushWindow")
            }
        }
    }

    private suspend fun flushWindow(session: ActiveSession) {
        val health = session.healthFlow.value
        Log.d(TAG, "flushWindow — health=$health")
        val metrics = DiagnosticsMapper.metricsOf(health)
        val now = System.currentTimeMillis()
        val windowStart = now - config.healthWindowIntervalMs
        val eval = evaluate(session, health, isRecording = true)
        session.windowIndex += 1

        val window = DiagnosticsMapper.buildHealthWindow(
            identity = session.identity,
            assignment = session.assignment,
            sessionId = session.sessionId,
            windowIndex = session.windowIndex,
            metrics = metrics,
            eval = eval,
            startedAt = windowStart,
            endedAt = now,
            upload = session.uploadStats(),
        )
        repository.writeHealthWindow(window)

        summary = (summary ?: baseSummary(session.identity, session.assignment)).copy(
            lastSeenAt = now,
            isRecording = true,
            currentHealthStatus = eval.status,
            currentHealthReasons = eval.reasons,
            lastMeasuredSampleRateHz = metrics.measuredSampleRateHz,
            lastJitterP95Ms = metrics.jitterP95Ms,
            lastFramesDropped = metrics.framesDropped,
            lastChunkIndex = metrics.currentChunkIndex,
            lastBytesWritten = metrics.bytesWritten,
            pendingUploadChunks = session.pendingChunkCount(health),
        )
        repository.upsertDeviceSummary(summary!!)

        // Also keep the session doc fresh (updatedAt + live metrics).
        repository.writeSessionDiagnostics(
            buildSession(session, health, eval, RecordingStopReason.UNKNOWN, ended = false, now = now),
        )

        if (eval.status != session.lastStatus) {
            when (eval.status) {
                DiagnosticsHealthStatus.WARNING -> logger.healthWarning(session.sessionId, eval.reasons)
                DiagnosticsHealthStatus.ERROR -> logger.healthError(session.sessionId, eval.reasons)
                else -> Unit
            }
            updateCrashlyticsKeys(session, eval.status)
            session.lastStatus = eval.status
        }
    }

    private suspend fun handleUploadSuccess(sessionId: String, chunkIndex: Int) {
        val now = System.currentTimeMillis()
        active?.let { s ->
            if (s.consecutiveUploadFailures > 0) s.hadUploadFailureButRecovered = true
            s.consecutiveUploadFailures = 0
            s.uploadedChunkCount += 1
        }
        summary = currentSummary().copy(
            lastSeenAt = now,
            lastUploadSuccessAt = now,
            pendingUploadChunks = active?.pendingChunkCount(active?.healthFlow?.value ?: RecordingHealth())
                ?: 0,
        )
        repository.upsertDeviceSummary(summary!!)
        logger.chunkUploadSuccess(sessionId, chunkIndex)
    }

    private suspend fun handleUploadFailure(sessionId: String, chunkIndex: Int, message: String?) {
        val now = System.currentTimeMillis()
        active?.let { s ->
            s.uploadFailures += 1
            s.consecutiveUploadFailures += 1
        }
        summary = currentSummary().copy(
            lastSeenAt = now,
            lastUploadFailureAt = now,
            lastErrorMessageSafe = sanitize(message) ?: currentSummary().lastErrorMessageSafe,
        )
        repository.upsertDeviceSummary(summary!!)
        logger.chunkUploadFailure(sessionId, chunkIndex)
    }

    private suspend fun handleNonFatalError(message: String?) {
        val now = System.currentTimeMillis()
        val base = currentSummary()
        summary = base.copy(
            lastSeenAt = now,
            nonFatalErrorCount = base.nonFatalErrorCount + 1L,
            lastErrorMessageSafe = sanitize(message) ?: base.lastErrorMessageSafe,
        )
        repository.upsertDeviceSummary(summary!!)
        logger.recordNonFatal(sanitize(message))
    }

    // ── Builders / helpers ────────────────────────────────────────────────

    private fun evaluate(
        session: ActiveSession,
        health: RecordingHealth,
        isRecording: Boolean,
    ): DiagnosticsHealthEvaluator.Result = evaluator.evaluate(
        DiagnosticsMapper.evaluatorInput(
            metrics = DiagnosticsMapper.metricsOf(health),
            isRecording = isRecording,
            recordingElapsedMs = System.currentTimeMillis() - session.startedAtWallMs,
            upload = session.uploadStats(),
        ),
    )

    private fun buildSession(
        session: ActiveSession,
        health: RecordingHealth,
        eval: DiagnosticsHealthEvaluator.Result,
        reason: RecordingStopReason,
        ended: Boolean,
        now: Long,
    ): ImuSessionDiagnostics = DiagnosticsMapper.buildSessionDiagnostics(
        identity = session.identity,
        assignment = session.assignment,
        sessionId = session.sessionId,
        resumeOf = session.resumeOf,
        startedAtWallMs = session.startedAtWallMs,
        startedAtBootNs = session.startedAtBootNs,
        metrics = DiagnosticsMapper.metricsOf(health),
        eval = eval,
        reason = reason,
        ended = ended,
        now = now,
        upload = session.uploadStats(),
        expectedSampleRateHz = config.expectedSampleRateHz,
    )

    private fun baseSummary(identity: DeviceIdentity, assignment: DeviceAssignment) = DeviceHealthSummary(
        deviceId = identity.deviceId,
        warehouseId = assignment.warehouseId,
        warehouseName = assignment.warehouseName,
        forkliftId = assignment.forkliftId,
        forkliftModel = assignment.forkliftModel,
        appVersion = identity.appVersion,
        buildNumber = identity.buildNumber,
        androidVersion = identity.androidVersion,
        deviceModel = identity.deviceModel,
        manufacturer = identity.manufacturer,
    )

    private fun currentSummary(): DeviceHealthSummary =
        summary ?: baseSummary(identityProvider.current(), assignmentProvider.current())

    private fun updateCrashlyticsKeys(
        session: ActiveSession,
        status: DiagnosticsHealthStatus,
        recording: Boolean = true,
    ) {
        logger.setRecordingContextKeys(
            identity = session.identity,
            assignment = session.assignment,
            sessionId = session.sessionId,
            isRecording = recording,
            healthStatus = status,
            recordingResumeOf = session.resumeOf,
        )
    }

    private fun sanitize(message: String?): String? {
        val m = message?.trim().orEmpty()
        if (m.isEmpty()) return null
        return m.take(config.maxSafeErrorLength)
    }

    private fun <T> Result<T>.logFailure(label: String) =
        onFailure { Log.w(TAG, "diagnostics $label failed: ${it.message}") }

    /** Releases the diagnostics scope. Call only on full app/service teardown. */
    fun shutdown() {
        runCatching { scope.cancel() }
    }

    private class ActiveSession(
        val identity: DeviceIdentity,
        val assignment: DeviceAssignment,
        val sessionId: String,
        val resumeOf: String?,
        val startedAtWallMs: Long,
        val startedAtBootNs: Long,
        val healthFlow: StateFlow<RecordingHealth>,
    ) {
        var windowIndex: Int = 0
        var uploadedChunkCount: Int = 0
        var uploadFailures: Int = 0
        var consecutiveUploadFailures: Int = 0
        var hadUploadFailureButRecovered: Boolean = false
        var lastStatus: DiagnosticsHealthStatus = DiagnosticsHealthStatus.OK

        fun uploadStats(): DiagnosticsMapper.UploadStats = DiagnosticsMapper.UploadStats(
            uploadedChunkCount = uploadedChunkCount,
            uploadFailures = uploadFailures,
            consecutiveUploadFailures = consecutiveUploadFailures,
            hadUploadFailureButRecovered = hadUploadFailureButRecovered,
        )

        fun pendingChunkCount(health: RecordingHealth): Int =
            DiagnosticsMapper.pendingChunkCount(DiagnosticsMapper.metricsOf(health), uploadedChunkCount)
    }

    private companion object {
        const val TAG = "ImuDiagnosticsAgg"
        const val INITIAL_FLUSH_DELAY_MS = 10_000L
    }
}
