package com.example.scantest.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.scantest.domain.model.SensorFrame
import com.example.scantest.domain.model.SensorSnapshot
import com.example.scantest.domain.model.SessionMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captura en tiempo real de todos los sensores IMU relevantes.
 *
 * Es el único componente del proceso que registra listeners contra
 * [SensorManager]. Se inicia/para por **reference counting** para permitir
 * que varios clientes (UI para preview + servicio para grabación) lo
 * mantengan activo sin duplicar registros.
 *
 * Contrato de frecuencia:
 * - `samplingPeriodUs = 10_000` (100 Hz nominal).
 * - `maxReportLatencyUs = 200_000` (batching hardware 200 ms) → el SoC puede
 *   dormir entre lotes. Los timestamps dentro de un lote siguen siendo
 *   exactos (los marca el sensor).
 *
 * Hot path en un `HandlerThread` con `THREAD_PRIORITY_URGENT_AUDIO` para
 * minimizar jitter. El `FrameAssembler` vive en ese mismo hilo → cero
 * `synchronized` en el callback del sensor.
 */
@Singleton
class SensorHub @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val assembler = FrameAssembler()

    /** Snapshot throttled para UI (10 Hz) — siempre activo mientras hay hardware registrado. */
    private val _liveSnapshot = MutableStateFlow(SensorSnapshot(emptyMap(), 0L))
    val liveSnapshot: StateFlow<SensorSnapshot> = _liveSnapshot.asStateFlow()

    /** Contador monotónico de frames emitidos al canal de grabación desde app start. */
    private val _framesEmitted = AtomicLong(0L)
    val framesEmitted: Long get() = _framesEmitted.get()

    /** Canal activo de la sesión de grabación en curso, o `null` si no se está grabando. */
    @Volatile
    private var recordingChannel: Channel<SensorFrame>? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var refCount: Int = 0
    private val lifecycleLock = Any()
    private var lastSnapshotUpdateNs: Long = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val isMaster = assembler.onSensorEvent(event)
            if (!isMaster) return
            // Sólo materializamos SensorFrame si hay una sesión de grabación activa.
            val activeChannel = recordingChannel
            if (activeChannel != null) {
                val frame = assembler.buildFrame(
                    timestampNs = event.timestamp,
                    timestampBootMs = SystemClock.elapsedRealtime(),
                )
                activeChannel.trySend(frame)
                _framesEmitted.incrementAndGet()
            }
            // Snapshot para UI: throttled a ~10 Hz para no recomponer Compose a 100 Hz.
            if (event.timestamp - lastSnapshotUpdateNs >= SNAPSHOT_INTERVAL_NS) {
                lastSnapshotUpdateNs = event.timestamp
                _liveSnapshot.value = assembler.snapshot(event.timestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Incrementa ref-count y arranca captura HW si era el primero. */
    fun acquire() {
        synchronized(lifecycleLock) {
            refCount += 1
            if (refCount == 1) startInternal()
        }
    }

    /** Decrementa ref-count y para captura HW si no quedan referencias. */
    fun release() {
        synchronized(lifecycleLock) {
            if (refCount == 0) return
            refCount -= 1
            if (refCount == 0) stopInternal()
        }
    }

    /**
     * Abre un canal fresco de frames para una sesión de grabación. Cierra
     * cualquier canal anterior. Devuelve un [ReceiveChannel] que el consumidor
     * (RecordingEngine) leerá en un hilo IO.
     *
     * Requiere que [acquire] haya sido llamado antes (el servicio es responsable).
     */
    fun openRecordingChannel(): ReceiveChannel<SensorFrame> {
        synchronized(lifecycleLock) {
            recordingChannel?.close()
            val ch = Channel<SensorFrame>(
                capacity = FRAME_CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            recordingChannel = ch
            _framesEmitted.set(0L)
            return ch
        }
    }

    /** Cierra el canal de grabación activo. El consumidor verá `null` al llamar `receive`. */
    fun closeRecordingChannel() {
        synchronized(lifecycleLock) {
            recordingChannel?.close()
            recordingChannel = null
        }
    }

    /** Describe los sensores IMU disponibles para auditoría en `metadata.json`. */
    fun describeAvailableSensors(): List<SessionMetadata.SensorDescriptor> {
        val types = intArrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD,
        )
        val result = ArrayList<SessionMetadata.SensorDescriptor>(types.size)
        for (type in types) {
            val sensor = sensorManager.getDefaultSensor(type) ?: continue
            result += SessionMetadata.SensorDescriptor(
                type = typeName(type),
                name = sensor.name ?: "",
                vendor = sensor.vendor ?: "",
                resolution = sensor.resolution,
                fifoMaxEventCount = sensor.fifoMaxEventCount,
                minDelayUs = sensor.minDelay,
            )
        }
        return result
    }

    private fun startInternal() {
        val thread = HandlerThread("ImuSensorThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler
        lastSnapshotUpdateNs = 0L

        val targets = intArrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD,
        )
        var registeredCount = 0
        for (type in targets) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor == null) {
                Log.w(TAG, "Sensor ${typeName(type)} no disponible en este dispositivo")
                continue
            }
            val ok = sensorManager.registerListener(
                listener,
                sensor,
                SAMPLING_PERIOD_US,
                MAX_REPORT_LATENCY_US,
                handler,
            )
            if (ok) registeredCount++ else Log.w(TAG, "registerListener falló para ${typeName(type)}")
        }
        Log.i(
            TAG,
            "SensorHub iniciado con $registeredCount sensores " +
                "(sampling=${SAMPLING_PERIOD_US}us, latency=${MAX_REPORT_LATENCY_US}us)",
        )
    }

    private fun stopInternal() {
        sensorManager.unregisterListener(listener)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        recordingChannel?.close()
        recordingChannel = null
        Log.i(TAG, "SensorHub parado")
    }

    private fun typeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "TYPE_ACCELEROMETER"
        Sensor.TYPE_LINEAR_ACCELERATION -> "TYPE_LINEAR_ACCELERATION"
        Sensor.TYPE_GRAVITY -> "TYPE_GRAVITY"
        Sensor.TYPE_GYROSCOPE -> "TYPE_GYROSCOPE"
        Sensor.TYPE_ROTATION_VECTOR -> "TYPE_ROTATION_VECTOR"
        Sensor.TYPE_MAGNETIC_FIELD -> "TYPE_MAGNETIC_FIELD"
        else -> "TYPE_$type"
    }

    companion object {
        private const val TAG = "SensorHub"
        /** 10 000 us = 100 Hz nominal. */
        const val SAMPLING_PERIOD_US: Int = 10_000
        /** 200 ms de batching hardware → el SoC puede dormir entre lotes. */
        const val MAX_REPORT_LATENCY_US: Int = 200_000
        /** Capacidad del canal frames→engine. 2048 @ 100 Hz ≈ 20 s de cola. */
        const val FRAME_CHANNEL_CAPACITY: Int = 2048
        /** Throttle de snapshot a UI: 100 ms = 10 Hz. */
        private const val SNAPSHOT_INTERVAL_NS: Long = 100_000_000L
    }
}
