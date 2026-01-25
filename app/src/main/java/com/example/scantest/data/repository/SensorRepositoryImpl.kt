package com.example.scantest.data.repository

import com.example.scantest.data.datasource.SensorDataSource
import com.example.scantest.domain.SensorSnapshot
import com.example.scantest.domain.repository.SensorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SensorRepositoryImpl @Inject constructor(
    private val sensorDataSource: SensorDataSource
) : SensorRepository {
    override fun getSensorDataFlow(): Flow<SensorSnapshot> {
        return sensorDataSource.getSensorDataFlow()
    }
}