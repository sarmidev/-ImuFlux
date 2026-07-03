package com.sarmidev.imuflux.recording

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuración ajustable del muestreo de sensores (opciones de desarrollador).
 *
 * Nace del problema observado en Samsung/One UI: con la pantalla apagada el
 * sensor hub **reduce la frecuencia** (100 Hz solicitados → ~50-60 Hz
 * entregados). La contramedida es **sobre-muestrear** (pedir 200 Hz) y
 * **resamplear a una rejilla** de 100 Hz al emitir frames, de modo que
 * cualquier entrada limpia ≥ objetivo produce una salida exacta al objetivo.
 *
 * Todos los valores son overridables desde el panel de ajustes del test; si no
 * se han fijado, se usan defaults por fabricante (Samsung sobremuestrea por
 * defecto). El panel expone atajos en Hz / booleanos ([samplingHz],
 * [gridResampleEnabled], [batchingEnabled]) que se traducen a estas claves.
 */
@Singleton
class RecordingTuningStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val isSamsung: Boolean =
        (Build.MANUFACTURER ?: "").equals("samsung", ignoreCase = true)

    /** Periodo de muestreo solicitado al HW (µs). Menor = más rápido. */
    fun samplingPeriodUs(): Int =
        prefs.getInt(KEY_SAMPLING_PERIOD_US, defaultSamplingPeriodUs())

    /**
     * Frecuencia de salida objetivo tras decimar (Hz). `0` desactiva la
     * decimación (se emite un frame por cada evento del sensor maestro).
     */
    fun decimateToHz(): Int =
        prefs.getInt(KEY_DECIMATE_TO_HZ, defaultDecimateToHz())

    /** Latencia máxima de entrega para batching HW (µs). `0` desactiva batching. */
    fun maxReportLatencyUs(): Int =
        prefs.getInt(KEY_MAX_REPORT_LATENCY_US, DEFAULT_MAX_REPORT_LATENCY_US)

    /** Preferir sensores wake-up (despiertan el SoC para entregar en Doze). */
    fun preferWakeupSensors(): Boolean =
        prefs.getBoolean(KEY_PREFER_WAKEUP, true)

    // ── Atajos para el panel (Hz / booleanos) ──────────────────────────────

    /** Frecuencia de muestreo solicitada al HW (Hz), derivada del periodo. */
    fun samplingHz(): Int {
        val periodUs = samplingPeriodUs()
        if (periodUs <= 0) return NOMINAL_HZ
        return (1_000_000 / periodUs)
    }

    /** `true` si el resampleo de rejilla está activo (decimateToHz > 0). */
    fun gridResampleEnabled(): Boolean = decimateToHz() > 0

    /** `true` si el batching HW está activo (maxReportLatencyUs > 0). */
    fun batchingEnabled(): Boolean = maxReportLatencyUs() > 0

    // ── Setters (opciones de desarrollador) ────────────────────────────────

    fun setSamplingPeriodUs(value: Int) =
        prefs.edit().putInt(KEY_SAMPLING_PERIOD_US, value).apply()

    /** Fija el periodo a partir de una frecuencia en Hz (100/200/400…). */
    fun setSamplingHz(hz: Int) {
        val safe = hz.coerceIn(1, 1_000)
        setSamplingPeriodUs(1_000_000 / safe)
    }

    fun setDecimateToHz(value: Int) =
        prefs.edit().putInt(KEY_DECIMATE_TO_HZ, value).apply()

    /** Activa/desactiva el resampleo de rejilla al objetivo del proyecto (100 Hz). */
    fun setGridResampleEnabled(enabled: Boolean) =
        setDecimateToHz(if (enabled) NOMINAL_HZ else 0)

    fun setMaxReportLatencyUs(value: Int) =
        prefs.edit().putInt(KEY_MAX_REPORT_LATENCY_US, value).apply()

    /** Activa/desactiva el batching HW usando la latencia por defecto. */
    fun setBatchingEnabled(enabled: Boolean) =
        setMaxReportLatencyUs(if (enabled) DEFAULT_MAX_REPORT_LATENCY_US else 0)

    fun setPreferWakeupSensors(value: Boolean) =
        prefs.edit().putBoolean(KEY_PREFER_WAKEUP, value).apply()

    private fun defaultSamplingPeriodUs(): Int =
        if (isSamsung) OVERSAMPLE_PERIOD_US else NOMINAL_PERIOD_US

    private fun defaultDecimateToHz(): Int =
        if (isSamsung) NOMINAL_HZ else 0

    companion object {
        private const val PREFS_NAME = "imuflux_recording_tuning"
        private const val KEY_SAMPLING_PERIOD_US = "sampling_period_us"
        private const val KEY_DECIMATE_TO_HZ = "decimate_to_hz"
        private const val KEY_MAX_REPORT_LATENCY_US = "max_report_latency_us"
        private const val KEY_PREFER_WAKEUP = "prefer_wakeup"

        /** 10 000 µs = 100 Hz nominal. */
        const val NOMINAL_PERIOD_US = 10_000
        /** 5 000 µs = 200 Hz: sobre-muestreo para contrarrestar el recorte a 50 %. */
        const val OVERSAMPLE_PERIOD_US = 5_000
        /** 2 500 µs = 400 Hz: requiere permiso HIGH_SAMPLING_RATE_SENSORS (Android 12+). */
        const val HIGH_RATE_PERIOD_US = 2_500
        /** Frecuencia de salida objetivo del proyecto. */
        const val NOMINAL_HZ = 100
        /** 200 ms de batching HW (compromiso batería/latencia). */
        const val DEFAULT_MAX_REPORT_LATENCY_US = 200_000
    }
}
