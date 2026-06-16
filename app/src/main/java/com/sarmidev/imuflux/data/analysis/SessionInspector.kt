package com.sarmidev.imuflux.data.analysis

import com.sarmidev.imuflux.data.storage.SessionFileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Análisis en un único paso de lectura sobre todos los chunks CSV de una sesión.
 * Equivale a ejecutar `validate_session.py` + `check_data_quality.py` sin
 * necesitar exportar ni copiar datos al PC.
 *
 * Estrategia:
 *  - Por cada fila se extrae `timestamp_ns` (col 0) para el análisis de timing
 *    usando el mismo histograma de resolución 0.1 ms que [SessionQualityAnalyzer].
 *  - Cada 100 filas (muestreo al 1 %) se parsean todas las columnas para
 *    acumular estadísticas de calidad por sensor. El 1 % es suficiente para
 *    detectar columnas vacías o sensores muertos con alta fiabilidad.
 *
 * Se declara `@Singleton` porque es stateless entre llamadas.
 */
@Singleton
class SessionInspector @Inject constructor(
    private val sfm: SessionFileManager,
) {
    // ── Timing constants ─────────────────────────────────────────────────────
    private val nominalDtNs = 10_000_000L   // 100 Hz
    private val gapThresholdNs = 50_000_000L // 50 ms
    private val bucketStepNs = 100_000L     // 0.1 ms
    private val bucketCount = (gapThresholdNs / bucketStepNs).toInt() + 1

    // ── Sensor groups (mirrors check_data_quality.py SENSOR_GROUPS) ─────────
    private val sensorGroupDefs = linkedMapOf(
        // "Acelerómetro (raw)"   to listOf("acc_x", "acc_y", "acc_z"),    // desactivado del CSV
        "Aceleración lineal"    to listOf("lin_x", "lin_y", "lin_z"),
        "Gravedad"              to listOf("grav_x", "grav_y", "grav_z"),
        "Giroscopio"            to listOf("gyro_x", "gyro_y", "gyro_z"),
        // "Rotación"             to listOf("rot_yaw", "rot_pitch", "rot_roll"), // desactivado
        // "Magnetómetro"         to listOf("mag_heading"),                      // desactivado
        // "Magnitudes derivadas" to listOf("acc_magnitude", "gyro_magnitude"),  // desactivado
    )

    // ── Column accumulator ────────────────────────────────────────────────────
    private class ColAccum {
        var total = 0L
        var empty = 0L
        var nonEmpty = 0L
        var nonZero = 0L

        fun update(raw: String) {
            total++
            if (raw.isEmpty()) { empty++; return }
            val v = raw.toDoubleOrNull()
            if (v == null || v.isNaN()) { empty++; return }
            nonEmpty++
            if (v != 0.0) nonZero++
        }

        val emptyRatio: Double get() = if (total > 0) empty.toDouble() / total else 0.0
        val isAllZero: Boolean get() = nonEmpty > 0 && nonZero == 0L
        val isMissing: Boolean get() = total == 0L || emptyRatio >= 0.99
        val isPartial: Boolean get() = !isMissing && (emptyRatio >= 0.05 || isAllZero)
    }

    // ── Main entry point ─────────────────────────────────────────────────────

    /**
     * Ejecuta el análisis completo sobre la sesión indicada.
     * Debe llamarse desde un dispatcher IO (no bloquea Main).
     */
    fun inspect(sessionId: String): InspectionResult {
        val chunks = sfm.listChunks(sessionId)
        require(chunks.isNotEmpty()) { "La sesión «$sessionId» no tiene chunks grabados" }

        // ── Timing state ──────────────────────────────────────────────────────
        val dtHistogram = IntArray(bucketCount)
        var firstTsNs = Long.MIN_VALUE
        var lastTsNs = Long.MIN_VALUE
        var prevTsNs = Long.MIN_VALUE
        var totalRows = 0L
        var totalDeltas = 0L
        var sumDtNs = 0L
        var gaps = 0
        var maxGapNs = 0L

        // ── Data quality state ────────────────────────────────────────────────
        var header: List<String>? = null
        val colAccum = mutableMapOf<String, ColAccum>()
        var rowIndex = 0L   // for 1% sampling

        // ── Single-pass read ──────────────────────────────────────────────────
        for (chunk in chunks) {
            chunk.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var firstLine = true
                for (line in lines) {
                    if (line.isBlank()) continue
                    if (firstLine) {
                        firstLine = false
                        // Initialize column map once from the first chunk header.
                        if (header == null) {
                            header = line.split(',').map { it.trim() }
                            for (name in header!!) colAccum[name] = ColAccum()
                        }
                        continue
                    }

                    // ── Timing: fast path using only column 0 ─────────────────
                    val commaIdx = line.indexOf(',')
                    val tsStr = if (commaIdx >= 0) line.substring(0, commaIdx) else line
                    val ts = tsStr.toLongOrNull() ?: continue

                    totalRows++
                    if (firstTsNs == Long.MIN_VALUE) firstTsNs = ts
                    lastTsNs = ts

                    if (prevTsNs != Long.MIN_VALUE) {
                        val dt = ts - prevTsNs
                        if (dt > 0) {
                            totalDeltas++
                            sumDtNs += dt
                            if (dt > gapThresholdNs) {
                                gaps++
                                if (dt > maxGapNs) maxGapNs = dt
                            } else {
                                dtHistogram[(dt / bucketStepNs).toInt().coerceIn(0, bucketCount - 1)]++
                            }
                        }
                    }
                    prevTsNs = ts

                    // ── Data quality: 1% sample (every 100th data row) ─────────
                    rowIndex++
                    if (rowIndex % 100L == 0L) {
                        val hdr = header ?: continue
                        val parts = line.split(',')
                        hdr.forEachIndexed { i, name ->
                            colAccum[name]?.update(parts.getOrElse(i) { "" })
                        }
                    }
                }
            }
        }

        // ── Watchdog resurrections (from metadata.json) ───────────────────────
        val resurrections: Int? = runCatching { sfm.readResurrectionCount(sessionId) }.getOrNull()

        // ── Timing metrics ────────────────────────────────────────────────────
        val nonGapDeltas = totalDeltas - gaps
        val durationS = if (firstTsNs != Long.MIN_VALUE && lastTsNs != Long.MIN_VALUE)
            (lastTsNs - firstTsNs) / 1e9 else 0.0
        val medianDtNs = histPercentile(dtHistogram, 0.50, nonGapDeltas)
        val jitterP95Ns = histJitterPercentile(dtHistogram, 0.95, nonGapDeltas)
        val expectedRows = (durationS * 100.0).toLong()
        val completeness = if (expectedRows > 0L) totalRows.toDouble() / expectedRows else 0.0

        // Validation criteria (mirrors validate_session.py passes_spec)
        val timingErrors = mutableListOf<String>()
        val medianMs = medianDtNs / 1e6
        if (medianMs !in 8.5..10.5)
            timingErrors += "dt mediana = %.4f ms (esperado 8.5–10.5)".format(medianMs)
        if (gaps > 0)
            timingErrors += "Huecos: $gaps detectados (mayor = %.2f ms)".format(maxGapNs / 1e6)
        val jitterMs = jitterP95Ns / 1e6
        if (jitterMs > 5.0)
            timingErrors += "Jitter p95 = %.4f ms (esperado < 5)".format(jitterMs)
        if (completeness < 0.99)
            timingErrors += "Completitud = %.2f %% (esperado ≥ 99 %%)".format(completeness * 100)
        if (resurrections != null && resurrections > 0)
            timingErrors += "Watchdog resurrecciones = $resurrections (el sistema mató la grabación)"

        // ── Sensor group classification ────────────────────────────────────────
        val sensorGroupReports = sensorGroupDefs.mapNotNull { (groupName, cols) ->
            val accums = cols.mapNotNull { colAccum[it] }
            if (accums.isEmpty()) return@mapNotNull null
            val status = when {
                accums.all { it.isMissing } -> SensorGroupStatus.MISSING
                accums.any { it.isMissing || it.isPartial } -> SensorGroupStatus.PARTIAL
                else -> SensorGroupStatus.OK
            }
            val detail = when (status) {
                SensorGroupStatus.MISSING -> "Sin datos — sensor no disponible en este dispositivo"
                SensorGroupStatus.PARTIAL -> {
                    val maxEmpty = accums.maxOf { it.emptyRatio }
                    val hasAllZero = accums.any { it.isAllZero }
                    when {
                        hasAllZero -> "Todos los valores son 0.0 — sensor posiblemente bloqueado"
                        else -> "Datos parciales — %.1f %% de filas vacías".format(maxEmpty * 100)
                    }
                }
                SensorGroupStatus.OK -> "Completo"
            }
            SensorGroupReport(groupName, status, detail)
        }

        return InspectionResult(
            totalRows = totalRows,
            durationS = durationS,
            dtMedianMs = medianDtNs / 1e6,
            dtMeanMs = if (totalDeltas > 0) sumDtNs.toDouble() / totalDeltas / 1e6 else 0.0,
            jitterP95Ms = jitterP95Ns / 1e6,
            gaps = gaps,
            maxGapMs = maxGapNs / 1e6,
            completenessPercent = completeness * 100.0,
            watchdogResurrections = resurrections,
            timingPassed = timingErrors.isEmpty(),
            timingErrors = timingErrors,
            sensorGroups = sensorGroupReports,
            dataProblems = sensorGroupReports.count { it.status != SensorGroupStatus.OK },
        )
    }

    // ── Histogram helpers (same algorithm as SessionQualityAnalyzer) ──────────

    private fun histPercentile(histogram: IntArray, p: Double, total: Long): Long {
        if (total <= 0L) return 0L
        val target = (total * p).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= target) return i * bucketStepNs + bucketStepNs / 2L
        }
        return (bucketCount.toLong() - 1L) * bucketStepNs
    }

    private fun histJitterPercentile(histogram: IntArray, p: Double, total: Long): Long {
        if (total <= 0L) return 0L
        val sorted = histogram.indices.sortedBy { i ->
            abs(i * bucketStepNs + bucketStepNs / 2L - nominalDtNs)
        }
        val target = (total * p).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in sorted) {
            cumulative += histogram[i]
            if (cumulative >= target) {
                return abs(i * bucketStepNs + bucketStepNs / 2L - nominalDtNs)
            }
        }
        return gapThresholdNs
    }
}
