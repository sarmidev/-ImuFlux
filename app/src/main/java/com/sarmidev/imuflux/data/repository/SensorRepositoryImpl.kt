package com.sarmidev.imuflux.data.repository

import com.sarmidev.imuflux.data.sensors.SensorHub
import com.sarmidev.imuflux.domain.model.SensorSnapshot
import com.sarmidev.imuflux.domain.repository.SensorRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class SensorRepositoryImpl @Inject constructor(
    private val sensorHub: SensorHub,
) : SensorRepository {

    override val liveSnapshot: StateFlow<SensorSnapshot> = sensorHub.liveSnapshot

    override fun acquire() = sensorHub.acquire()

    override fun release() = sensorHub.release()
}
