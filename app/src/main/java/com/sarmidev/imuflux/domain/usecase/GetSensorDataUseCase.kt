package com.sarmidev.imuflux.domain.usecase

import com.sarmidev.imuflux.domain.model.SensorSnapshot
import com.sarmidev.imuflux.domain.repository.SensorRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Observa el snapshot throttled de sensores (10 Hz). Para consumir este
 * flujo hay que llamar a [SensorRepository.acquire] primero y a
 * [SensorRepository.release] al terminar (p.ej. ligado al lifecycle del
 * ViewModel).
 */
class GetSensorDataUseCase @Inject constructor(
    private val sensorRepository: SensorRepository,
) {
    operator fun invoke(): StateFlow<SensorSnapshot> = sensorRepository.liveSnapshot

    fun acquire() = sensorRepository.acquire()
    fun release() = sensorRepository.release()
}
