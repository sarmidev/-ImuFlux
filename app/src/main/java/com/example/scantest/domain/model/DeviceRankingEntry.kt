package com.example.scantest.domain.model

/**
 * Snapshot of a device model's compatibility aggregate as stored in Firestore
 * `deviceModels/{deviceKey}`, ready to be displayed in the ranking screen.
 *
 * All averages are running incremental means computed over [sessionCount] analyses.
 */
data class DeviceRankingEntry(
    val deviceKey: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    /** Compatibility score 0–100. Higher is better. */
    val score: Int,
    /** EXCELLENT | GOOD | RISKY | BAD | UNKNOWN */
    val category: String,
    val sessionCount: Long,
    val passCount: Long,
    val warnCount: Long,
    val failCount: Long,
    val insufficientDataCount: Long,
    /** Incremental average of completeness ratio (0.0–1.0). */
    val avgCompleteness: Double,
    /** Incremental average jitter p95 in milliseconds. */
    val avgJitterP95Ms: Double,
    /** Incremental average median dt in milliseconds. Nominal = 10 ms (100 Hz). */
    val avgMedianDtMs: Double,
    val lastVerdict: String,
    /** Cumulative sum of all session durations converted to minutes. */
    val totalDurationMinutes: Double,
)
