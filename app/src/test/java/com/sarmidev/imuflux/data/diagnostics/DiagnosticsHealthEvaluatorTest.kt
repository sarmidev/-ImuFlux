package com.sarmidev.imuflux.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule coverage for [DiagnosticsHealthEvaluator] against the default
 * [DiagnosticsConfig] thresholds (expected 100 Hz).
 */
class DiagnosticsHealthEvaluatorTest {

    private val evaluator = DiagnosticsHealthEvaluator(DiagnosticsConfig.DEFAULT)

    private fun input(
        isRecording: Boolean = true,
        rate: Double = 100.0,
        jitter: Double = 1.0,
        drops: Long = 0L,
        samples: Long = 6_000L,
        elapsedMs: Long = 60_000L,
        consecutiveUploadFailures: Int = 0,
        recovered: Boolean = false,
        lastSeenAgeMs: Long = 0L,
        failedToStart: Boolean = false,
        orphaned: Boolean = false,
    ) = DiagnosticsHealthEvaluator.Input(
        isRecording = isRecording,
        measuredSampleRateHz = rate,
        jitterP95Ms = jitter,
        framesDropped = drops,
        samplesRecordedEstimate = samples,
        recordingElapsedMs = elapsedMs,
        consecutiveUploadFailures = consecutiveUploadFailures,
        hadUploadFailureButRecovered = recovered,
        lastSeenAgeMs = lastSeenAgeMs,
        recordingFailedToStart = failedToStart,
        orphaned = orphaned,
    )

    @Test
    fun healthyRecording_isOk() {
        val r = evaluator.evaluate(input())
        assertEquals(DiagnosticsHealthStatus.OK, r.status)
        assertEquals(listOf(DiagnosticsReasons.ALL_CHECKS_PASSED), r.reasons)
    }

    @Test
    fun sampleRateBetween80And95_isWarning() {
        val r = evaluator.evaluate(input(rate = 85.0))
        assertEquals(DiagnosticsHealthStatus.WARNING, r.status)
        assertTrue(DiagnosticsReasons.SAMPLE_RATE_LOW in r.reasons)
    }

    @Test
    fun sampleRateBelow80_isError() {
        val r = evaluator.evaluate(input(rate = 70.0))
        assertEquals(DiagnosticsHealthStatus.ERROR, r.status)
        assertTrue(DiagnosticsReasons.SAMPLE_RATE_CRITICAL in r.reasons)
    }

    @Test
    fun noSamplesAfterGrace_isError() {
        val r = evaluator.evaluate(input(rate = 0.0, samples = 0L, elapsedMs = 20_000L))
        assertEquals(DiagnosticsHealthStatus.ERROR, r.status)
        assertTrue(DiagnosticsReasons.NO_SAMPLES_WHILE_RECORDING in r.reasons)
    }

    @Test
    fun noSamplesWithinGrace_isOk() {
        val r = evaluator.evaluate(input(rate = 0.0, samples = 0L, elapsedMs = 5_000L))
        assertEquals(DiagnosticsHealthStatus.OK, r.status)
    }

    @Test
    fun jitterAbove5_isWarning() {
        val r = evaluator.evaluate(input(jitter = 8.0))
        assertEquals(DiagnosticsHealthStatus.WARNING, r.status)
        assertTrue(DiagnosticsReasons.JITTER_HIGH in r.reasons)
    }

    @Test
    fun smallFrameDrops_isWarning_repeatedDrops_isError() {
        assertEquals(DiagnosticsHealthStatus.WARNING, evaluator.evaluate(input(drops = 5L)).status)
        val err = evaluator.evaluate(input(drops = 100L))
        assertEquals(DiagnosticsHealthStatus.ERROR, err.status)
        assertTrue(DiagnosticsReasons.REPEATED_FRAME_DROPS in err.reasons)
    }

    @Test
    fun uploadFailures_mapToWarningThenError() {
        assertEquals(DiagnosticsHealthStatus.WARNING, evaluator.evaluate(input(consecutiveUploadFailures = 1)).status)
        val err = evaluator.evaluate(input(consecutiveUploadFailures = 3))
        assertEquals(DiagnosticsHealthStatus.ERROR, err.status)
        assertTrue(DiagnosticsReasons.REPEATED_UPLOAD_FAILURES in err.reasons)
    }

    @Test
    fun uploadFailureRecovered_isWarning() {
        val r = evaluator.evaluate(input(recovered = true))
        assertEquals(DiagnosticsHealthStatus.WARNING, r.status)
        assertTrue(DiagnosticsReasons.UPLOAD_FAILURE_RECOVERED in r.reasons)
    }

    @Test
    fun delayedCheckin_isWarning_staleTooLong_isError() {
        val warn = evaluator.evaluate(input(isRecording = false, lastSeenAgeMs = 20L * 60_000L))
        assertEquals(DiagnosticsHealthStatus.WARNING, warn.status)
        assertTrue(DiagnosticsReasons.DEVICE_CHECKIN_DELAYED in warn.reasons)

        val err = evaluator.evaluate(input(isRecording = false, lastSeenAgeMs = 90L * 60_000L))
        assertEquals(DiagnosticsHealthStatus.ERROR, err.status)
        assertTrue(DiagnosticsReasons.DEVICE_STALE in err.reasons)
    }

    @Test
    fun failedToStart_andOrphaned_areErrors() {
        assertEquals(DiagnosticsHealthStatus.ERROR, evaluator.evaluate(input(failedToStart = true)).status)
        assertEquals(DiagnosticsHealthStatus.ERROR, evaluator.evaluate(input(orphaned = true)).status)
    }

    @Test
    fun errorWins_overWarning() {
        // both a warning (jitter) and an error (rate) present → ERROR
        val r = evaluator.evaluate(input(rate = 70.0, jitter = 9.0))
        assertEquals(DiagnosticsHealthStatus.ERROR, r.status)
    }

    @Test
    fun escalateForStaleness_promotesOkToError_whenVeryStale() {
        val r = evaluator.escalateForStaleness(DiagnosticsHealthStatus.OK, 90L * 60_000L)
        assertEquals(DiagnosticsHealthStatus.ERROR, r.status)
    }

    @Test
    fun escalateForStaleness_keepsStoredWhenFresh() {
        val r = evaluator.escalateForStaleness(DiagnosticsHealthStatus.OK, 1_000L)
        assertEquals(DiagnosticsHealthStatus.OK, r.status)
        assertTrue(r.reasons.isEmpty())
    }
}
