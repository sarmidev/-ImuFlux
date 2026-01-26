package com.example.scantest.data.datasource

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.example.scantest.domain.SensorSnapshot
import com.example.scantest.domain.SensorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.math.atan2
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

class SensorDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val currentValues: MutableMap<SensorType, Float> = mutableMapOf()
    private val valuesLock = Any()

    private fun toDegrees(radians: Float): Float {
        return Math.toDegrees(radians.toDouble()).toFloat()
    }

    private fun getWakeUpSensor(type: Int): Sensor? {
        val sensorList = sensorManager.getSensorList(type)
        val wakeUpSensor = sensorList.firstOrNull { it.isWakeUpSensor }
        return wakeUpSensor ?: sensorManager.getDefaultSensor(type)
    }

    fun getSensorDataFlow(): Flow<SensorSnapshot> = callbackFlow {

        val sensorThread = HandlerThread("SensorWorkerThread", Process.THREAD_PRIORITY_URGENT_AUDIO)
        sensorThread.start()
        val sensorHandler = Handler(sensorThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(valuesLock) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                             currentValues[SensorType.RAW_ACCELERATION_X] = event.values[0]
                             currentValues[SensorType.RAW_ACCELERATION_Y] = event.values[1]
                             currentValues[SensorType.RAW_ACCELERATION_Z] = event.values[2]
                        }
                        Sensor.TYPE_LINEAR_ACCELERATION -> {
                            val ax = event.values[0]
                            val ay = event.values[1]
                            val az = event.values[2]
                            currentValues[SensorType.ACCELERATION_MAGNITUDE] = sqrt((ax*ax + ay*ay + az*az).toDouble()).toFloat()
                            currentValues[SensorType.LINEAR_ACCELERATION_X] = ax
                            currentValues[SensorType.LINEAR_ACCELERATION_Y] = ay
                            currentValues[SensorType.LINEAR_ACCELERATION_Z] = az
                        }
                        Sensor.TYPE_GRAVITY -> {
                            currentValues[SensorType.GRAVITY_X] = event.values[0]
                            currentValues[SensorType.GRAVITY_Y] = event.values[1]
                            currentValues[SensorType.GRAVITY_Z] = event.values[2]
                        }
                        Sensor.TYPE_GYROSCOPE -> {
                            val wx = event.values[0]
                            val wy = event.values[1]
                            val wz = event.values[2]
                            currentValues[SensorType.ANGULAR_VELOCITY_MAGNITUDE] = sqrt((wx*wx + wy*wy + wz*wz).toDouble()).toFloat()
                            currentValues[SensorType.ANGULAR_VELOCITY_X] = wx
                            currentValues[SensorType.ANGULAR_VELOCITY_Y] = wy
                            currentValues[SensorType.ANGULAR_VELOCITY_Z] = wz
                        }
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            val rotationMatrix = FloatArray(9)
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            val orientationAngles = FloatArray(3)
                            SensorManager.getOrientation(rotationMatrix, orientationAngles)
                            currentValues[SensorType.TILT_ANGLE_YAW] = toDegrees(orientationAngles[0])
                            currentValues[SensorType.TILT_ANGLE_PITCH] = toDegrees(orientationAngles[1])
                            currentValues[SensorType.TILT_ANGLE_ROLL] = toDegrees(orientationAngles[2])
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                             val mx = event.values[0]
                             val my = event.values[1]
                             currentValues[SensorType.MAGNETIC_HEADING] = (toDegrees(atan2(my.toDouble(), mx.toDouble()).toFloat()) + 360) % 360
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Petición eficiente: 10ms (10,000 microsegundos)
        // Esto le dice al driver: "Intenta darme datos a 100Hz, no más rápido".
        // Algunos drivers lo respetan exacto, otros redondean al múltiplo más cercano (ej. 20ms o 5ms).
        val desiredPeriodUs = 10_000 

        val sensors = listOfNotNull(
            getWakeUpSensor(Sensor.TYPE_ACCELEROMETER),
            getWakeUpSensor(Sensor.TYPE_LINEAR_ACCELERATION),
            getWakeUpSensor(Sensor.TYPE_GRAVITY),
            getWakeUpSensor(Sensor.TYPE_ROTATION_VECTOR),
            getWakeUpSensor(Sensor.TYPE_GYROSCOPE),
            getWakeUpSensor(Sensor.TYPE_MAGNETIC_FIELD)
        )

        sensors.forEach { sensor ->
            // Usamos registerListener solicitando 10ms.
            // maxReportLatencyUs = 0 fuerza entrega inmediata (crucial para evitar latencia en tiempo real y doze)
            sensorManager.registerListener(listener, sensor, desiredPeriodUs, sensorHandler)
        }

        // Ticker a 10ms para regularizar la salida y garantizar la frecuencia en el CSV.
        // Esto desacopla la irregularidad del hardware de tu requisito de 100Hz.
        val tickerJob = launch(Dispatchers.Default) {
            val periodMs = 10L
            var nextEmissionTime = System.currentTimeMillis()

            while (isActive) {
                val currentTime = System.currentTimeMillis()
                
                if (currentTime >= nextEmissionTime) {
                    val snapshotData: Map<SensorType, Float>
                    synchronized(valuesLock) {
                        snapshotData = currentValues.toMap()
                    }

                    if (snapshotData.isNotEmpty()) {
                        trySend(SensorSnapshot(snapshotData, currentTime))
                    }
                    
                    nextEmissionTime += periodMs
                    
                    // Si el sistema se colgó y nos retrasamos mucho (>100ms), saltamos para alcanzar el presente
                    if (System.currentTimeMillis() > nextEmissionTime + 100) {
                         nextEmissionTime = System.currentTimeMillis() + periodMs
                    }
                }
                
                val delayTime = nextEmissionTime - System.currentTimeMillis()
                if (delayTime > 0) delay(delayTime)
            }
        }

        awaitClose {
            tickerJob.cancel()
            sensorManager.unregisterListener(listener)
            sensorThread.quitSafely()
        }
    }
}