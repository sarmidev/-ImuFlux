package com.example.scantest.domain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.xr.runtime.math.toDegrees
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt
import kotlin.math.atan2

class SensorMonitor(context: Context) {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorValues: MutableMap<SensorType, Float> = mutableMapOf()

    fun getSensorDataFlow(): Flow<SensorSnapshot> = callbackFlow {

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val sensorsToRegister = listOfNotNull(
            accelerometer,
            rotationVector,
            gyroscope,
            magneticField
        )

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> processAccelerometerData(event.values)
                    Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(event.values)
                    Sensor.TYPE_GYROSCOPE -> processGyroscopeData(event.values)
                    Sensor.TYPE_MAGNETIC_FIELD -> processMagneticField(event.values)
                }

                // 3. Emitir el snapshot de datos combinados.
                val snapshot = SensorSnapshot(
                    values = sensorValues.toMap(),
                    timestamp = System.currentTimeMillis()
                )
                trySend(snapshot)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorsToRegister.forEach { sensor ->
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // 5. Cleanup
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    // --- Funciones de Procesamiento ---

    private fun processAccelerometerData(values: FloatArray) {
        val ax = values[0]
        val ay = values[1]
        val az = values[2]

        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

        sensorValues[SensorType.ACCELERATION_MAGNITUDE] = magnitude
        sensorValues[SensorType.ACCELERATION_X] = ax
        sensorValues[SensorType.ACCELERATION_Y] = ay
        sensorValues[SensorType.ACCELERATION_Z] = az
    }

    private fun processGyroscopeData(values: FloatArray) {
        val wx = values[0]
        val wy = values[1]
        val wz = values[2]

        val magnitude = sqrt((wx * wx + wy * wy + wz * wz).toDouble()).toFloat()

        sensorValues[SensorType.ANGULAR_VELOCITY_MAGNITUDE] = magnitude
    }

    private fun processRotationVector(values: FloatArray) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)

        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val pitch = toDegrees(orientationAngles[1])// Inclinación Frontal/Trasera
        val roll = toDegrees(orientationAngles[2]) // Inclinación Lateral

        sensorValues[SensorType.TILT_ANGLE_PITCH] = pitch
        sensorValues[SensorType.TILT_ANGLE_ROLL] = roll
    }

    private fun processMagneticField(values: FloatArray) {
        val mx = values[0]
        val my = values[1]
        val heading = (toDegrees(atan2(my.toDouble(), mx.toDouble()).toFloat()) + 360) % 360

        sensorValues[SensorType.MAGNETIC_HEADING] = heading
    }
}