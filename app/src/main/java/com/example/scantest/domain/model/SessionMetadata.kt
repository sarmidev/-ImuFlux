package com.example.scantest.domain.model

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
)
