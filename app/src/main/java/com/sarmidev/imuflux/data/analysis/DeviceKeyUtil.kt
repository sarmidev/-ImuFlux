package com.sarmidev.imuflux.data.analysis

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DeviceModelStats(
    val sessionCount: Long,
    val passCount: Long,
    val warnCount: Long,
    val failCount: Long,
    val insufficientDataCount: Long,
    val avgCompleteness: Double,
    val avgJitterP95Ms: Double,
    val avgMedianDtMs: Double,
    val avgDurationS: Double,
    val totalGaps: Long,
    val totalWatchdogResurrections: Long,
    /** Cumulative sum of all session durations in seconds (not an average). */
    val totalDurationS: Double = 0.0,
)

object DeviceKeyUtil {

    fun normalizeDeviceKey(manufacturer: String, model: String, sdkInt: Int): String {
        val manufacturerKey = normalizePart(manufacturer)
        val modelKey = normalizePart(model)
        return "${manufacturerKey}__${modelKey}__sdk_$sdkInt"
    }

    fun verdictFor(result: InspectionResult): Verdict = when {
        result.durationS < MIN_RELIABLE_DURATION_S -> Verdict.INSUFFICIENT_DATA
        result.timingPassed && result.dataProblems == 0 -> Verdict.PASS
        result.timingPassed || result.dataProblems <= 2 -> Verdict.WARN
        else -> Verdict.FAIL
    }

    fun computeCompatibilityScore(stats: DeviceModelStats): Int {
        val count = stats.sessionCount.coerceAtLeast(1L).toDouble()
        val failRate = stats.failCount / count
        val warnRate = stats.warnCount / count
        val avgGaps = stats.totalGaps / count
        val watchdogPenalty = min(stats.totalWatchdogResurrections * 5.0, 15.0)
        val jitterPenalty = max(0.0, (stats.avgJitterP95Ms - 3.0) * 2.0)

        val rawScore = 100.0 -
            failRate * 45.0 -
            warnRate * 20.0 -
            min(avgGaps * 2.0, 10.0) -
            watchdogPenalty -
            jitterPenalty

        // Scale down the score proportionally until the device reaches FULL_CONFIDENCE_DURATION_S
        // of total recorded time. At that threshold the multiplier is 1.0 and has no effect.
        val confidenceFactor = min(stats.totalDurationS / FULL_CONFIDENCE_DURATION_S, 1.0)

        return (rawScore * confidenceFactor)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun computeCategory(score: Int, stats: DeviceModelStats): String = when {
        stats.sessionCount < MIN_SESSIONS_FOR_CATEGORY -> "UNKNOWN"
        score >= 90 && stats.failCount == 0L -> "EXCELLENT"
        score >= 75 -> "GOOD"
        score >= 50 -> "RISKY"
        else -> "BAD"
    }

    private fun normalizePart(raw: String): String {
        val withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        val normalized = withoutAccents
            .lowercase(Locale.US)
            .replace("[^a-z0-9]+".toRegex(), "_")
            .trim('_')
            .replace("_+".toRegex(), "_")
        return normalized.ifEmpty { "unknown" }
    }

    private const val MIN_RELIABLE_DURATION_S = 30.0
    private const val MIN_SESSIONS_FOR_CATEGORY = 3L

    /** Total recorded seconds at which the temporal confidence factor reaches 1.0 (8 hours). */
    const val FULL_CONFIDENCE_DURATION_S = 8.0 * 3600.0  // 28 800 s
}
