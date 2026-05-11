package com.example.scantest.data.analysis

import com.example.scantest.data.storage.SessionFileManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Calcula métricas de calidad equivalentes a `tools/validate_session.py` sobre
 * una sesión ya escrita a disco, sin dependencias externas.
 *
 * Diseño:
 *  - Escaneo single-pass de todos los `chunk_*.csv` de la sesión. Sólo se
 *    conserva el `timestamp_ns` (columna 0) de cada fila y el intervalo
 *    `dt = t[i] - t[i-1]`. No se guarda la lista completa para evitar OOM
 *    en sesiones de millones de filas.
 *  - Para medianas y percentiles, los `dt` se bucketean en un histograma de
 *    resolución 0.1 ms. Con ello se obtiene una aproximación excelente
 *    (< 0.05 ms de error) usando O(N_buckets) memoria en lugar de O(N_rows).
 *  - Los gaps (`dt > GAP_THRESHOLD_NS`) se cuentan aparte y se registra el
 *    máximo exacto.
 *
 * Uso típico:
 * ```
 * val report = analyzer.analyze(sessionId)
 * when (val v = report.verdict) {
 *     Verdict.PASS -> ...
 *     Verdict.WARN -> ...
 *     Verdict.FAIL -> ...
 *     Verdict.INSUFFICIENT_DATA -> ...
 * }
 * ```
 *
 * Se declara `@Singleton` para que Hilt pueda inyectarlo en pantallas y
 * ViewModels sin recrearlo, aunque es stateless — la API analiza sesiones
 * por parámetro y no guarda nada entre llamadas.
 */
@Singleton
class SessionQualityAnalyzer @Inject constructor(
    private val sessionFileManager: SessionFileManager,
) {

    /** Nominal a 100 Hz. */
    private val nominalDtNs: Long = 10_000_000L

    /** Umbral de gap (ms → ns). Coherente con `validate_session.py`. */
    private val gapThresholdNs: Long = 50_000_000L

    /** Resolución de los buckets para histograma de dt (0.1 ms). */
    private val bucketStepNs: Long = 100_000L

    /**
     * Tope del histograma: 50 ms. Cualquier `dt` mayor ya es un gap y se
     * contabiliza aparte con su valor exacto; no necesitamos resolución
     * estadística para la cola de gaps (se reporta `max_gap` exacto).
     */
    private val bucketCount: Int = (gapThresholdNs / bucketStepNs).toInt() + 1

    /**
     * Analiza una sesión por id buscándola en el directorio gestionado por
     * [SessionFileManager]. Lanza `IllegalArgumentException` si no existe.
     *
     * @param manufacturer valor de `Build.MANUFACTURER`; si se omite, el
     *   veredicto final **no** aplica cap por fabricante (útil para tests
     *   unitarios o cuando el análisis lo hace un script externo).
     */
    fun analyze(sessionId: String, manufacturer: String? = null): QualityReport {
        val chunks = sessionFileManager.listChunks(sessionId)
        require(chunks.isNotEmpty()) { "Sesión $sessionId no tiene chunks" }
        return analyzeChunks(chunks, manufacturer)
    }

    /**
     * Analiza directamente una lista de archivos CSV (chunks). Útil para
     * tests o para aplicar el analizador a un único CSV exportado.
     *
     * @param manufacturer valor de `Build.MANUFACTURER`; si se omite, el
     *   veredicto final **no** aplica cap por fabricante.
     */
    fun analyzeChunks(chunks: List<File>, manufacturer: String? = null): QualityReport {
        val dtHistogram = IntArray(bucketCount)
        var firstTsNs: Long = Long.MIN_VALUE
        var lastTsNs: Long = Long.MIN_VALUE
        var prevTsNs: Long = Long.MIN_VALUE
        var totalRows: Long = 0L
        var totalDeltas: Long = 0L
        var sumDtNs: Long = 0L
        var minDtNs: Long = Long.MAX_VALUE
        var maxDtNs: Long = Long.MIN_VALUE
        var gaps: Int = 0

        for (chunk in chunks) {
            chunk.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var isFirstLineOfChunk = true
                for (line in lines) {
                    if (line.isEmpty()) continue
                    if (isFirstLineOfChunk) {
                        // Cada chunk lleva su propia cabecera al principio.
                        isFirstLineOfChunk = false
                        continue
                    }
                    val commaIdx = line.indexOf(',')
                    val tsField = if (commaIdx >= 0) line.substring(0, commaIdx) else line
                    val ts = tsField.toLongOrNull() ?: continue

                    totalRows += 1L
                    if (firstTsNs == Long.MIN_VALUE) firstTsNs = ts
                    lastTsNs = ts

                    if (prevTsNs != Long.MIN_VALUE) {
                        val dt = ts - prevTsNs
                        // Un dt <= 0 sería un reloj no monotónico (raro); lo ignoramos
                        // del histograma pero no rompemos.
                        if (dt > 0) {
                            totalDeltas += 1L
                            sumDtNs += dt
                            if (dt < minDtNs) minDtNs = dt
                            if (dt > maxDtNs) maxDtNs = dt
                            if (dt > gapThresholdNs) {
                                gaps += 1
                            } else {
                                val bucket = (dt / bucketStepNs).toInt().coerceIn(0, bucketCount - 1)
                                dtHistogram[bucket] += 1
                            }
                        }
                    }
                    prevTsNs = ts
                }
            }
        }

        val mfrInfo = manufacturer?.let(::manufacturerInfoFor)

        if (totalDeltas < 2L || firstTsNs == Long.MIN_VALUE) {
            return QualityReport.insufficient(totalRows, mfrInfo)
        }

        val durationNs = lastTsNs - firstTsNs
        val durationS = durationNs / 1e9
        val meanDtNs = (sumDtNs.toDouble() / totalDeltas).toLong()
        val medianDtNs = percentileFromHistogram(dtHistogram, 0.50, totalDeltas - gaps)
        val p95DtNs = percentileFromHistogram(dtHistogram, 0.95, totalDeltas - gaps)
        val jitterP95Ns = jitterPercentileFromHistogram(dtHistogram, 0.95, totalDeltas - gaps)

        val expectedRows = (durationS * 100.0).toLong()
        val completeness = if (expectedRows > 0L) totalRows.toDouble() / expectedRows.toDouble() else 0.0

        val rawVerdict = classify(
            completeness = completeness,
            gaps = gaps,
            jitterP95Ns = jitterP95Ns,
            medianDtNs = medianDtNs,
            durationS = durationS,
        )

        val finalVerdict = mfrInfo?.let { applyManufacturerCap(rawVerdict, it.reliability) }
            ?: rawVerdict

        return QualityReport(
            totalRows = totalRows,
            durationS = durationS,
            firstTsNs = firstTsNs,
            lastTsNs = lastTsNs,
            medianDtNs = medianDtNs,
            meanDtNs = meanDtNs,
            p95DtNs = p95DtNs,
            minDtNs = if (minDtNs == Long.MAX_VALUE) 0L else minDtNs,
            maxDtNs = if (maxDtNs == Long.MIN_VALUE) 0L else maxDtNs,
            jitterP95Ns = jitterP95Ns,
            gaps = gaps,
            maxGapNs = if (maxDtNs > gapThresholdNs) maxDtNs else 0L,
            completeness = completeness,
            rawVerdict = rawVerdict,
            verdict = finalVerdict,
            manufacturerInfo = mfrInfo,
        )
    }

    private fun classify(
        completeness: Double,
        gaps: Int,
        jitterP95Ns: Long,
        medianDtNs: Long,
        durationS: Double,
    ): Verdict {
        // Sin datos estadísticamente relevantes no emitimos veredicto.
        if (durationS < 30.0) return Verdict.INSUFFICIENT_DATA

        val medianMs = medianDtNs / 1e6
        val jitterMs = jitterP95Ns / 1e6

        val medianOk = medianMs in 9.5..10.5
        val jitterOk = jitterMs < 5.0

        // Un PASS exige que el test haya corrido el tiempo suficiente para
        // cruzar el umbral de Doze (~15–20 min) y para que un killer
        // agresivo del OEM haya tenido oportunidad de dispararse. Sin ese
        // mínimo, los estadísticos "limpios" no prueban nada: un OnePlus
        // Nord 2T 5G puede grabar 10 min perfectos y empezar a morir al 11º.
        val longEnoughForPass = durationS >= MIN_PASS_DURATION_S

        // WARN es más tolerante: sirve incluso con tests cortados a mitad,
        // pero aún así exige algo de muestra (5 min) para no emitir WARN
        // basado en 30 s de grabación.
        val longEnoughForWarn = durationS >= MIN_WARN_DURATION_S

        return when {
            completeness >= 0.99 && gaps == 0 && medianOk && jitterOk && longEnoughForPass ->
                Verdict.PASS
            completeness >= 0.90 && gaps <= 2 && medianOk && jitterMs < 10.0 && longEnoughForWarn ->
                Verdict.WARN
            // Si la única razón por la que no es PASS es la duración corta,
            // degradamos a INSUFFICIENT_DATA en vez de a FAIL: el móvil no
            // ha hecho nada mal, simplemente no hemos podido juzgarlo.
            completeness >= 0.99 && gaps == 0 && medianOk && jitterOk && !longEnoughForPass ->
                Verdict.INSUFFICIENT_DATA
            else -> Verdict.FAIL
        }
    }

    private companion object {
        /** Un PASS requiere ≥ 25 min de grabación (83 % del test nominal de 30 min). */
        const val MIN_PASS_DURATION_S: Double = 25.0 * 60.0
        /** Un WARN requiere ≥ 5 min. */
        const val MIN_WARN_DURATION_S: Double = 5.0 * 60.0
    }

    private fun percentileFromHistogram(histogram: IntArray, p: Double, totalNonGap: Long): Long {
        if (totalNonGap <= 0L) return 0L
        val target = (totalNonGap * p).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in histogram.indices) {
            cumulative += histogram[i].toLong()
            if (cumulative >= target) {
                // Centro del bucket como estimación.
                return i * bucketStepNs + bucketStepNs / 2L
            }
        }
        return (bucketCount.toLong() - 1L) * bucketStepNs
    }

    /**
     * Percentil del jitter `|dt - 10 ms|` calculado a partir del mismo
     * histograma de dt. Para cada bucket se toma la desviación absoluta del
     * centro del bucket respecto a `nominalDtNs`, y se ordenan los buckets
     * por esa desviación antes de acumular.
     */
    private fun jitterPercentileFromHistogram(histogram: IntArray, p: Double, totalNonGap: Long): Long {
        if (totalNonGap <= 0L) return 0L
        val bucketsByJitter = IntArray(histogram.size) { it }
        bucketsByJitter.sortedBy { idx ->
            val center = idx * bucketStepNs + bucketStepNs / 2L
            abs(center - nominalDtNs)
        }.let { sortedIdx ->
            val target = (totalNonGap * p).toLong().coerceAtLeast(1L)
            var cumulative = 0L
            for (idx in sortedIdx) {
                cumulative += histogram[idx].toLong()
                if (cumulative >= target) {
                    val center = idx * bucketStepNs + bucketStepNs / 2L
                    return abs(center - nominalDtNs)
                }
            }
        }
        return gapThresholdNs
    }
}

/**
 * Veredicto en tres niveles + un cuarto cuando la muestra es demasiado corta.
 * Los umbrales exactos viven en [SessionQualityAnalyzer.classify]; este enum
 * se usa tanto en UI (banner) como en persistencia (SharedPreferences).
 */
enum class Verdict {
    PASS,
    WARN,
    FAIL,
    INSUFFICIENT_DATA,
}

/**
 * Snapshot inmutable del análisis de una sesión. Todos los tiempos en ns; la
 * capa de UI convierte a ms/s antes de mostrar.
 *
 * @property rawVerdict veredicto derivado únicamente de métricas, sin tener
 *   en cuenta el fabricante. Útil para mostrar transparencia en la UI y
 *   para scripts de análisis que quieran decidir con sus propios criterios.
 * @property verdict veredicto final mostrado al usuario. Es el [rawVerdict]
 *   posiblemente degradado por [ManufacturerInfo.reliability]. Si no se
 *   pasó `manufacturer` al analizar, `verdict == rawVerdict`.
 * @property manufacturerInfo null si el análisis se hizo sin manufacturer
 *   (p. ej. desde la línea de comandos). Con manufacturer presente, siempre
 *   lleva algo — como mínimo `UNKNOWN`.
 */
data class QualityReport(
    val totalRows: Long,
    val durationS: Double,
    val firstTsNs: Long,
    val lastTsNs: Long,
    val medianDtNs: Long,
    val meanDtNs: Long,
    val p95DtNs: Long,
    val minDtNs: Long,
    val maxDtNs: Long,
    val jitterP95Ns: Long,
    val gaps: Int,
    val maxGapNs: Long,
    /** Ratio `rows / (duration_s * 100)`: 1.0 = sample rate efectivo de 100 Hz. */
    val completeness: Double,
    val rawVerdict: Verdict,
    val verdict: Verdict,
    val manufacturerInfo: ManufacturerInfo? = null,
) {
    /** `true` si el cap por fabricante ha degradado el veredicto bruto. */
    val wasCappedByManufacturer: Boolean
        get() = rawVerdict != verdict

    companion object {
        fun insufficient(rows: Long, mfrInfo: ManufacturerInfo? = null): QualityReport = QualityReport(
            totalRows = rows,
            durationS = 0.0,
            firstTsNs = 0L,
            lastTsNs = 0L,
            medianDtNs = 0L,
            meanDtNs = 0L,
            p95DtNs = 0L,
            minDtNs = 0L,
            maxDtNs = 0L,
            jitterP95Ns = 0L,
            gaps = 0,
            maxGapNs = 0L,
            completeness = 0.0,
            rawVerdict = Verdict.INSUFFICIENT_DATA,
            verdict = Verdict.INSUFFICIENT_DATA,
            manufacturerInfo = mfrInfo,
        )
    }
}
