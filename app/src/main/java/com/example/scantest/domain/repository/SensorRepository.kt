package com.example.scantest.domain.repository

import com.example.scantest.domain.model.SensorSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstracción de la capa de dominio sobre el hardware de sensores IMU.
 * Se usa exclusivamente para **UI en tiempo real** (preview throttled a 10 Hz).
 * La escritura a disco la orquesta el `RecordingEngine`, no esta interfaz.
 */
interface SensorRepository {
    /**
     * Snapshot throttled a ~10 Hz del estado de sensores. Sólo se actualiza
     * mientras haya al menos una llamada activa a [acquire] sin su [release].
     */
    val liveSnapshot: StateFlow<SensorSnapshot>

    /** Incrementa ref-count; arranca la captura hardware si es el primero. */
    fun acquire()

    /** Decrementa ref-count; para la captura hardware si no quedan referencias. */
    fun release()
}
