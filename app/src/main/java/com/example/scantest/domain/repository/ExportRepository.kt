package com.example.scantest.domain.repository

import com.example.scantest.domain.SensorData

interface ExportRepository {
    suspend fun exportSensorData(data: List<SensorData>, uriString: String)
}