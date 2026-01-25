package com.example.scantest.data.datasource

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.scantest.domain.SensorSnapshot
import com.example.scantest.domain.SensorType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt
import kotlin.math.atan2
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

// Renamed from SensorMonitor and injected
class SensorDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorValues: MutableMap<SensorType, Float> = mutableMapOf()

    // Assuming androidx.xr.runtime.math.toDegrees was used, but standard Math.toDegrees exists. 
    // If specific library is needed, keep import. I'll use Math.toDegrees for standard compat unless xr is required.
    // The previous file imported androidx.xr.runtime.math.toDegrees. 
    // I will replace it with Math.toDegrees to avoid dependency if not needed, or keep it if project uses XR.
    // Checking previous imports: "androidx.xr.runtime.math.toDegrees"
    // I'll stick to standard Kotlin/Java math if possible to reduce dependencies, or re-add the import.
    // Given "androidx.xr.runtime:runtime:1.0.0-alpha06" in toml, it seems intentional.
    // But Math.toDegrees returns Double, float needed? 
    
    private fun toDegrees(radians: Float): Float {
        return Math.toDegrees(radians.toDouble()).toFloat()
    }

    fun getSensorDataFlow(): Flow<SensorSnapshot> = callbackFlow {

        val rawAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val sensorsToRegister = listOfNotNull(
            rawAccelerometer,
            linearAccelerometer,
            gravitySensor,
            rotationVector,
            gyroscope,
            magneticField
        )

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> processRawAccelerometerData(event.values)
                    Sensor.TYPE_LINEAR_ACCELERATION -> processLinearAccelerationData(event.values)
                    Sensor.TYPE_GRAVITY -> processGravityData(event.values)
                    Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(event.values)
                    Sensor.TYPE_GYROSCOPE -> processGyroscopeData(event.values)
                    Sensor.TYPE_MAGNETIC_FIELD -> processMagneticField(event.values)
                }

                val snapshot = SensorSnapshot(
                    values = sensorValues.toMap(),
                    timestamp = System.currentTimeMillis()
                )
                trySend(snapshot)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val desiredFrequencyHz = 100
        val desiredPeriodUs = 1_000_000 / desiredFrequencyHz

        sensorsToRegister.forEach { sensor ->
            val samplingPeriod = if (desiredPeriodUs < sensor.minDelay) {
                Log.w(
                    "SensorDataSource",
                    "La frecuencia de ${desiredFrequencyHz}Hz es demasiado rápida para ${sensor.name}. " +
                            "Usando la frecuencia máxima soportada."
                )
                kotlin.math.max(desiredPeriodUs, sensor.minDelay)
            } else {
                desiredPeriodUs
            }

            sensorManager.registerListener(listener, sensor, samplingPeriod)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    private fun processRawAccelerometerData(values: FloatArray) {
        sensorValues[SensorType.RAW_ACCELERATION_X] = values[0]
        sensorValues[SensorType.RAW_ACCELERATION_Y] = values[1]
        sensorValues[SensorType.RAW_ACCELERATION_Z] = values[2]
    }

    private fun processLinearAccelerationData(values: FloatArray) {
        val ax = values[0]
        val ay = values[1]
        val az = values[2]

        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

        sensorValues[SensorType.ACCELERATION_MAGNITUDE] = magnitude
        sensorValues[SensorType.LINEAR_ACCELERATION_X] = ax
        sensorValues[SensorType.LINEAR_ACCELERATION_Y] = ay
        sensorValues[SensorType.LINEAR_ACCELERATION_Z] = az
    }

    private fun processGravityData(values: FloatArray) {
        sensorValues[SensorType.GRAVITY_X] = values[0]
        sensorValues[SensorType.GRAVITY_Y] = values[1]
        sensorValues[SensorType.GRAVITY_Z] = values[2]
    }

    private fun processGyroscopeData(values: FloatArray) {
        val wx = values[0]
        val wy = values[1]
        val wz = values[2]

        val magnitude = sqrt((wx * wx + wy * wy + wz * wz).toDouble()).toFloat()

        sensorValues[SensorType.ANGULAR_VELOCITY_MAGNITUDE] = magnitude
        sensorValues[SensorType.ANGULAR_VELOCITY_X] = wx
        sensorValues[SensorType.ANGULAR_VELOCITY_Y] = wy
        sensorValues[SensorType.ANGULAR_VELOCITY_Z] = wz
    }

    private fun processRotationVector(values: FloatArray) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)

        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val yaw = toDegrees(orientationAngles[0])
        val pitch = toDegrees(orientationAngles[1])
        val roll = toDegrees(orientationAngles[2])

        sensorValues[SensorType.TILT_ANGLE_YAW] = yaw
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