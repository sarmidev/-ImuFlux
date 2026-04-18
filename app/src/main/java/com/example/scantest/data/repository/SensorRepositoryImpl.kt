package com.example.scantest.data.repository

import com.example.scantest.data.sensors.SensorHub
import com.example.scantest.domain.model.SensorSnapshot
import com.example.scantest.domain.repository.SensorRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class SensorRepositoryImpl @Inject constructor(
    private val sensorHub: SensorHub,
) : SensorRepository {

    override val liveSnapshot: StateFlow<SensorSnapshot> = sensorHub.liveSnapshot

    override fun acquire() = sensorHub.acquire()

    override fun release() = sensorHub.release()
}
