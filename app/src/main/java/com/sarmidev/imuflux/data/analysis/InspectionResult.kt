package com.sarmidev.imuflux.data.analysis

/**
 * Resultado completo del análisis de una sesión grabada. Combina las métricas
 * de timing (equivalente a `tools/validate_session.py`) y la calidad de los
 * datos por sensor (equivalente a `tools/check_data_quality.py`).
 */
data class InspectionResult(
    // ── Timing (validate_session) ───────────────────────────────────────────
    val totalRows: Long,
    val durationS: Double,
    val dtMedianMs: Double,
    val dtMeanMs: Double,
    val jitterP95Ms: Double,
    val gaps: Int,
    val maxGapMs: Double,
    val completenessPercent: Double,
    val watchdogResurrections: Int?,
    /** `true` si la sesión supera todos los criterios de `validate_session.py`. */
    val timingPassed: Boolean,
    /** Lista de problemas detectados. Vacía cuando [timingPassed] es `true`. */
    val timingErrors: List<String>,
    // ── Data quality (check_data_quality) ───────────────────────────────────
    val sensorGroups: List<SensorGroupReport>,
    /** Número de grupos de sensores con status PARTIAL o MISSING. */
    val dataProblems: Int,
)

data class SensorGroupReport(
    val name: String,
    val status: SensorGroupStatus,
    val detail: String,
)

enum class SensorGroupStatus { OK, PARTIAL, MISSING }
