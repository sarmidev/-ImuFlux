package com.example.scantest.domain.usecase

import com.example.scantest.domain.SensorData
import com.example.scantest.domain.repository.ExportRepository
import javax.inject.Inject

class ExportSensorDataUseCase @Inject constructor(
    private val exportRepository: ExportRepository
) {
    suspend operator fun invoke(data: List<SensorData>, uriString: String) {
        exportRepository.exportSensorData(data, uriString)
    }
}