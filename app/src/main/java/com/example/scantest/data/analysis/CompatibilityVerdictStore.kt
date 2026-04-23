package com.example.scantest.data.analysis

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persiste el resultado del último "Test de compatibilidad" ejecutado en este
 * dispositivo, para:
 *  - Mostrar un banner en la pantalla principal cuando el veredicto es
 *    WARN / FAIL (advertir antes de iniciar una sesión real).
 *  - Saber si el dispositivo nunca ha sido cualificado (banner suave
 *    recomendando ejecutar el test).
 *
 * Se invalida automáticamente si cambia el "device fingerprint"
 * (`Build.MANUFACTURER|Build.MODEL|Build.VERSION.SDK_INT`): si el usuario
 * cambia de móvil, un veredicto guardado previamente no tiene sentido.
 *
 * La persistencia vive en `SharedPreferences` con prefijo `compat_`; no se
 * mezcla con otros stores para facilitar reset en debug / tests.
 */
@Singleton
class CompatibilityVerdictStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** Identificador estable del dispositivo para invalidar el veredicto al cambiar de móvil. */
    private val currentFingerprint: String
        get() = "${Build.MANUFACTURER ?: "?"}|${Build.MODEL ?: "?"}|${Build.VERSION.SDK_INT}"

    fun saveVerdict(report: QualityReport) {
        prefs.edit()
            .putString(KEY_FINGERPRINT, currentFingerprint)
            .putLong(KEY_TIMESTAMP_MS, System.currentTimeMillis())
            .putString(KEY_VERDICT, report.verdict.name)
            .putString(KEY_RAW_VERDICT, report.rawVerdict.name)
            .putString(
                KEY_MFR_RELIABILITY,
                report.manufacturerInfo?.reliability?.name ?: "",
            )
            .putLong(KEY_TOTAL_ROWS, report.totalRows)
            .putLong(KEY_DURATION_MS, (report.durationS * 1000.0).toLong())
            .putLong(KEY_MEDIAN_DT_NS, report.medianDtNs)
            .putLong(KEY_JITTER_P95_NS, report.jitterP95Ns)
            .putInt(KEY_GAPS, report.gaps)
            .putLong(KEY_MAX_GAP_NS, report.maxGapNs)
            .putLong(KEY_COMPLETENESS_X10000, (report.completeness * 10_000.0).toLong())
            .putBoolean(KEY_DISMISSED, false)
            .apply()
    }

    /**
     * Devuelve el veredicto guardado si (a) existe y (b) corresponde a este
     * mismo dispositivo. En cualquier otro caso, `null`.
     */
    fun loadVerdict(): StoredVerdict? {
        val fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: return null
        if (fingerprint != currentFingerprint) return null
        val verdictStr = prefs.getString(KEY_VERDICT, null) ?: return null
        val verdict = runCatching { Verdict.valueOf(verdictStr) }.getOrNull() ?: return null
        val rawVerdict = prefs.getString(KEY_RAW_VERDICT, null)
            ?.let { s -> runCatching { Verdict.valueOf(s) }.getOrNull() }
            ?: verdict
        val reliability = prefs.getString(KEY_MFR_RELIABILITY, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { s -> runCatching { ManufacturerReliability.valueOf(s) }.getOrNull() }
        return StoredVerdict(
            verdict = verdict,
            rawVerdict = rawVerdict,
            manufacturerReliability = reliability,
            timestampMs = prefs.getLong(KEY_TIMESTAMP_MS, 0L),
            totalRows = prefs.getLong(KEY_TOTAL_ROWS, 0L),
            durationMs = prefs.getLong(KEY_DURATION_MS, 0L),
            medianDtNs = prefs.getLong(KEY_MEDIAN_DT_NS, 0L),
            jitterP95Ns = prefs.getLong(KEY_JITTER_P95_NS, 0L),
            gaps = prefs.getInt(KEY_GAPS, 0),
            maxGapNs = prefs.getLong(KEY_MAX_GAP_NS, 0L),
            completeness = prefs.getLong(KEY_COMPLETENESS_X10000, 0L) / 10_000.0,
            dismissed = prefs.getBoolean(KEY_DISMISSED, false),
        )
    }

    /** Marca el banner como cerrado por el usuario. El veredicto persiste. */
    fun markBannerDismissed() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    /** Borra todo. Útil en debug y al rehacer la cualificación. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    data class StoredVerdict(
        val verdict: Verdict,
        val rawVerdict: Verdict,
        val manufacturerReliability: ManufacturerReliability?,
        val timestampMs: Long,
        val totalRows: Long,
        val durationMs: Long,
        val medianDtNs: Long,
        val jitterP95Ns: Long,
        val gaps: Int,
        val maxGapNs: Long,
        val completeness: Double,
        val dismissed: Boolean,
    ) {
        val wasCappedByManufacturer: Boolean
            get() = rawVerdict != verdict
    }

    companion object {
        private const val PREF_FILE = "imuflux_compat_verdict"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_TIMESTAMP_MS = "timestamp_ms"
        private const val KEY_VERDICT = "verdict"
        private const val KEY_RAW_VERDICT = "raw_verdict"
        private const val KEY_MFR_RELIABILITY = "mfr_reliability"
        private const val KEY_TOTAL_ROWS = "total_rows"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_MEDIAN_DT_NS = "median_dt_ns"
        private const val KEY_JITTER_P95_NS = "jitter_p95_ns"
        private const val KEY_GAPS = "gaps"
        private const val KEY_MAX_GAP_NS = "max_gap_ns"
        private const val KEY_COMPLETENESS_X10000 = "completeness_x10000"
        private const val KEY_DISMISSED = "dismissed"
    }
}
