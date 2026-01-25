package com.example.scantest.domain.usecase

import com.example.scantest.domain.SensorSnapshot
import com.example.scantest.domain.repository.SensorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSensorDataUseCase @Inject constructor(
    private val sensorRepository: SensorRepository
) {
    operator fun invoke(): Flow<SensorSnapshot> {
        return sensorRepository.getSensorDataFlow()
    }
}