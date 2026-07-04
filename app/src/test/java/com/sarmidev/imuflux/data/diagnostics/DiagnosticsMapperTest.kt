package com.sarmidev.imuflux.data.diagnostics

import com.sarmidev.imuflux.domain.model.RecordingHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Health-window aggregation and session start/stop + upload mapping for
 * [DiagnosticsMapper] (pure, no Android).
 */
class DiagnosticsMapperTest {

    private val identity = DeviceIdentity(
        deviceId = "dev_abc",
        appVersion = "1.0",
        buildNumber = 1L,
        androidVersion = 34,
        deviceModel = "Pixel 7",
        manufacturer = "Google",
    )
    private val assignment = DeviceAssignment(
        forkliftId = "RX-50_7f3a2b",
        forkliftModel = "RX-50",
        warehouseId = "zar_a",
        warehouseName = "ZAR-A",
    )
    private val okEval = DiagnosticsHealthEvaluator.Result(
        DiagnosticsHealthStatus.OK,
        listOf(DiagnosticsReasons.ALL_CHECKS_PASSED),
    )

    private fun health() = RecordingHealth(
        samplesPerSecond = 98f,
        jitterP95Ns = 3_000_000L,
        framesQueued = 2,
        framesDropped = 4L,
        bytesWritten = 1_000L,
        currentChunkIndex = 3,
        framesWritten = 500L,
    )

    @Test
    fun metricsOf_convertsNsToMsAndHzCorrectly() {
        val m = DiagnosticsMapper.metricsOf(health())
        assertEquals(98.0, m.measuredSampleRateHz, 0.0001)
        assertEquals(3.0, m.jitterP95Ms, 0.0001)
        assertEquals(4L, m.framesDropped)
        assertEquals(500L, m.samplesRecordedEstimate)
        assertEquals(4, m.chunkCount) // currentChunkIndex 3 + 1
    }

    @Test
    fun pendingChunkCount_isChunkCountMinusUploaded() {
        val m = DiagnosticsMapper.metricsOf(health())
        assertEquals(3, DiagnosticsMapper.pendingChunkCount(m, uploadedChunkCount = 1))
        assertEquals(0, DiagnosticsMapper.pendingChunkCount(m, uploadedChunkCount = 10)) // never negative
    }

    @Test
    fun buildHealthWindow_mapsAllFields() {
        val m = DiagnosticsMapper.metricsOf(health())
        val w = DiagnosticsMapper.buildHealthWindow(
            identity = identity,
            assignment = assignment,
            sessionId = "S1",
            windowIndex = 2,
            metrics = m,
            eval = okEval,
            startedAt = 1_000L,
            endedAt = 61_000L,
            upload = DiagnosticsMapper.UploadStats(uploadedChunkCount = 1),
        )
        assertEquals("S1_w0002", w.windowId)
        assertEquals("dev_abc", w.deviceId)
        assertEquals("zar_a", w.warehouseId)
        assertEquals("RX-50_7f3a2b", w.forkliftId)
        assertEquals(60_000L, w.durationMs)
        assertEquals(98.0, w.samplesPerSecond, 0.0001)
        assertEquals(3, w.uploadPendingCount) // 4 - 1
        assertEquals(DiagnosticsHealthStatus.OK, w.healthStatus)
    }

    @Test
    fun buildSession_whileRecording_hasNoEndedAt() {
        val m = DiagnosticsMapper.metricsOf(health())
        val s = DiagnosticsMapper.buildSessionDiagnostics(
            identity = identity,
            assignment = assignment,
            sessionId = "S1",
            resumeOf = null,
            startedAtWallMs = 1_000L,
            startedAtBootNs = 999L,
            metrics = m,
            eval = okEval,
            reason = RecordingStopReason.UNKNOWN,
            ended = false,
            now = 61_000L,
            upload = DiagnosticsMapper.UploadStats(uploadedChunkCount = 2, uploadFailures = 0),
            expectedSampleRateHz = 100.0,
        )
        assertNull(s.endedAtWallMs)
        assertEquals(60_000L, s.durationMs)
        assertEquals(100.0, s.expectedSampleRateHz, 0.0001)
        assertEquals(4, s.chunkCount)
        assertEquals(2, s.uploadedChunkCount)
        assertEquals(2, s.pendingChunkCount)
    }

    @Test
    fun buildSession_onStop_setsEndedReasonAndUploadFailures() {
        val m = DiagnosticsMapper.metricsOf(health())
        val errEval = DiagnosticsHealthEvaluator.Result(
            DiagnosticsHealthStatus.ERROR,
            listOf(DiagnosticsReasons.REPEATED_UPLOAD_FAILURES),
        )
        val s = DiagnosticsMapper.buildSessionDiagnostics(
            identity = identity,
            assignment = assignment,
            sessionId = "S1",
            resumeOf = "S0",
            startedAtWallMs = 1_000L,
            startedAtBootNs = 999L,
            metrics = m,
            eval = errEval,
            reason = RecordingStopReason.USER_STOP,
            ended = true,
            now = 120_000L,
            upload = DiagnosticsMapper.UploadStats(uploadedChunkCount = 1, uploadFailures = 5),
            expectedSampleRateHz = 100.0,
        )
        assertEquals(120_000L, s.endedAtWallMs)
        assertEquals("S0", s.resumeOf)
        assertEquals(RecordingStopReason.USER_STOP, s.recordingStopReason)
        assertEquals(5, s.uploadFailures)
        assertEquals(DiagnosticsHealthStatus.ERROR, s.healthStatus)
    }

    @Test
    fun evaluatorInput_passesUploadCounters() {
        val m = DiagnosticsMapper.metricsOf(health())
        val input = DiagnosticsMapper.evaluatorInput(
            metrics = m,
            isRecording = true,
            recordingElapsedMs = 60_000L,
            upload = DiagnosticsMapper.UploadStats(consecutiveUploadFailures = 3, hadUploadFailureButRecovered = true),
        )
        assertEquals(3, input.consecutiveUploadFailures)
        assertEquals(true, input.hadUploadFailureButRecovered)
        assertEquals(98.0, input.measuredSampleRateHz, 0.0001)
    }
}
