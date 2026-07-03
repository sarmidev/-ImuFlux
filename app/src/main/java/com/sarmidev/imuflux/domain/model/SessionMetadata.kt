package com.sarmidev.imuflux.domain.model

data class SessionMetadata(
    val sessionId: String,
    val startedAtWallMs: Long,
    val startedAtBootNs: Long,
    val endedAtWallMs: Long? = null,
    val endedAtBootNs: Long? = null,
    val deviceModel: String,
    val deviceManufacturer: String,
    val sdkInt: Int,
    val appVersion: String,
    val sensors: List<SensorDescriptor>,
    val columns: List<String>,
    val chunkDurationMs: Long,
    val chunkMaxBytes: Long,
    val resumeOf: String? = null,
    /** Forklift model the session is recorded with (set by the user). */
    val forkliftModel: String = "",
    /** Warehouse / location where the session is recorded (set by the user). */
    val warehouse: String = "",
    /** Stable identifier used for remote upload: `{userName}_{6hex}`. */
    val forkliftId: String = "",
    /**
     * Número acumulado de veces que el watchdog (o el propio `START_STICKY`
     * del sistema) ha tenido que relanzar la grabación en toda la cadena
     * de sesiones hasta ésta. `0` significa que la sesión vive desde que
     * el usuario pulsó "grabar"; `N > 0` implica N kills del OS durante
     * la jornada y es una **señal fuerte** de dispositivo incompatible.
     *
     * Serializado en `metadata.json` como `watchdog_resurrections`.
     */
    val resurrectionCount: Int = 0,
    /**
     * Periodo de muestreo solicitado al HW al iniciar la sesión (µs) y
     * frecuencia de salida objetivo tras decimar (Hz, `0` = sin decimación).
     * Permiten interpretar la tasa efectiva del CSV frente a lo pedido.
     */
    val requestedSamplingPeriodUs: Int = 0,
    val decimateToHz: Int = 0,
    /**
     * Estado de energía capturado al arrancar la sesión. Sirve para
     * correlacionar tasas bajas / huecos con Doze, pantalla apagada o falta
     * de exención de optimización de batería. Se serializa como el bloque
     * `power_state_at_start` en `metadata.json`.
     */
    val batteryOptimizationIgnoredAtStart: Boolean? = null,
    val deviceIdleModeAtStart: Boolean? = null,
    val screenInteractiveAtStart: Boolean? = null,
    val chargingAtStart: Boolean? = null,
) {
    /** Descripción de un sensor disponible en el dispositivo (auditoría). */
    data class SensorDescriptor(
        val type: String,
        val name: String,
        val vendor: String,
        val resolution: Float,
        val fifoMaxEventCount: Int,
        val minDelayUs: Int,
        /**
         * `true` si la variante **realmente registrada** es un sensor wake-up
         * (despierta el SoC para entregar en Doze). `maxDelayUs` es el mayor
         * periodo de muestreo soportado (`0` = desconocido). Ambos ayudan a
         * distinguir un cap de firmware de un artefacto de configuración.
         */
        val isWakeUp: Boolean = false,
        val maxDelayUs: Int = 0,
    )
}

/** Resumen ligero para la pantalla de sesiones. */
data class SessionSummary(
    val sessionId: String,
    val startedAtWallMs: Long,
    val durationMs: Long,
    val chunkCount: Int,
    val totalBytes: Long,
    val isActive: Boolean,
    /** Id de la sesión anterior si ésta es una continuación tras un kill del sistema. */
    val resumeOf: String? = null,
    val forkliftModel: String = "",
    val warehouse: String = "",
    /**
     * Número de resurrecciones por watchdog acumuladas en esta cadena de
     * sesiones. Ver [SessionMetadata.resurrectionCount]. `0` = sesión limpia.
     */
    val resurrectionCount: Int = 0,
)
