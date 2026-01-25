package com.example.scantest.domain.repository

import com.example.scantest.domain.SensorSnapshot
import kotlinx.coroutines.flow.Flow

interface SensorRepository {
    fun getSensorDataFlow(): Flow<SensorSnapshot>
}