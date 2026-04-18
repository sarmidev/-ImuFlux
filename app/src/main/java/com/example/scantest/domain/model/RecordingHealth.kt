package com.example.scantest.domain.model

/**
 * Métricas de salud del pipeline de grabación en tiempo real.
 * Expuesto por `RecordingHealthTracker` como `StateFlow<RecordingHealth>`.
 */
data class RecordingHealth(
    /** Muestras/s efectivas, calculadas sobre ventana móvil de los últimos 10 s. */
    val samplesPerSecond: Float = 0f,
    /** Percentil 95 del intervalo entre muestras, en nanosegundos. Nominal = 10_000_000 ns. */
    val jitterP95Ns: Long = 0L,
    /** Frames esperando en el canal productor→consumidor. */
    val framesQueued: Int = 0,
    /** Frames descartados por backpressure desde el inicio de la sesión. */
    val framesDropped: Long = 0L,
    /** Bytes escritos en disco en la sesión actual. */
    val bytesWritten: Long = 0L,
    /** Índice del chunk que se está escribiendo ahora mismo. */
    val currentChunkIndex: Int = 0,
    /** Total de frames escritos en la sesión actual. */
    val framesWritten: Long = 0L,
) {
    val isHealthy: Boolean
        get() = framesDropped == 0L && jitterP95Ns < 5_000_000L
}
