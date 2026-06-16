package com.sarmidev.imuflux.domain.model

/**
 * Representa el estado instantáneo de todos los sensores IMU en un momento
 * concreto. Se usa únicamente para **UI en tiempo real** (valores throttled
 * a ~10 Hz) y para la evaluación de [CustomMovement].
 *
 * Para el pipeline de grabación a disco se usa [SensorFrame] (más eficiente,
 * sin allocations de mapa).
 */
data class SensorSnapshot(
    val values: Map<SensorType, Float>,
    /** Reloj monotónico en ns (`SystemClock.elapsedRealtimeNanos`). */
    val timestampNs: Long,
)
