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
    /** Modelo del toro/carretilla con el que se graba (definido por el usuario). */
    val forkliftModel: String = "",
    /** Almacén/ubicación donde se graba (definido por el usuario). */
    val warehouse: String = "",
    /** Identificador único para upload remoto: `{nombre_usuario}_{6hex}`. */
    val toroId: String = "",
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
) {
    /** Descripción de un sensor disponible en el dispositivo (auditoría). */
    data class SensorDescriptor(
        val type: String,
        val name: String,
        val vendor: String,
        val resolution: Float,
        val fifoMaxEventCount: Int,
        val minDelayUs: Int,
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
