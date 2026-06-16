package com.sarmidev.imuflux.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

import android.util.Log
import com.sarmidev.imuflux.domain.model.SensorFrame
import com.sarmidev.imuflux.domain.model.SensorSnapshot
import com.sarmidev.imuflux.domain.model.SessionMetadata
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
 * Captura en tiempo real de los sensores IMU disponibles.
 *
 * Es el único componente del proceso que registra listeners contra
 * [SensorManager]. Se inicia/para por **reference counting** para permitir
 * que varios clientes (UI + servicio de grabación) lo mantengan activo sin
 * duplicar registros.
 *
 * ## Modelo de ejecución
 *
 * Los callbacks se entregan en el **main looper**: el overload clásico de 3
 * argumentos de [SensorManager.registerListener] es el más simple y compatible
 * con cualquier firmware. El trabajo por evento es trivial (pocas escrituras
 * a un `FloatArray` + un `trySend` no bloqueante), unos pocos µs — a 100 Hz
 * con 6 sensores, ~0,2 % de CPU de main, imperceptible para Compose. Como
 * todo el hot path corre en un único hilo, no hay `synchronized` y no hay
 * races sobre los slots del [FrameAssembler].
 *
 * El trabajo costoso (serializar CSV, flush a disco) NO ocurre aquí: vive en
 * [com.sarmidev.imuflux.recording.RecordingEngine] sobre
 * `Dispatchers.IO.limitedParallelism(1)`. El [Channel] entre ambos acota la
 * cola con `DROP_OLDEST` para evitar OOM si el disco va lento.
 *
 * ## Frecuencia
 *
 * - `samplingPeriodUs = 10_000` (100 Hz nominal).
 * - **Batching HW** con `maxReportLatencyUs = 200 000` (200 ms) cuando el
 *   sensor declara `fifoMaxEventCount > 0`. El chip acumula ~20 muestras en
 *   su FIFO interna y las entrega en ráfaga; mientras tanto la CPU puede
 *   dormir. Ahorro típico de batería del 30-50 % en sesiones largas. Los
 *   timestamps son generados por el chip (no por la CPU), así que la
 *   precisión de 100 Hz se preserva.
 * - Si el sensor NO soporta FIFO o el sistema rechaza la petición con
 *   batching, se hace fallback automático a la llamada de 3 argumentos (sin
 *   batching). Es el comportamiento pre-batching y queda registrado en log.
 *
 * ## Sensores ausentes
 *
 * Si un sensor no existe físicamente en el dispositivo,
 * [SensorManager.getDefaultSensor] devuelve `null` → lo saltamos con un log
 * de advertencia y su columna en el CSV quedará vacía (campos `,,`). Es
 * responsabilidad del analista saber qué hardware lleva el device usado.
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

    private var refCount: Int = 0
    private val lifecycleLock = Any()
    private var lastSnapshotUpdateNs: Long = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val isMaster = assembler.onSensorEvent(event)
            if (!isMaster) return
            val activeChannel = recordingChannel
            if (activeChannel != null) {
                val frame = assembler.buildFrame(timestampNs = event.timestamp)
                activeChannel.trySend(frame)
                _framesEmitted.incrementAndGet()
            }
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
        val result = ArrayList<SessionMetadata.SensorDescriptor>(TARGET_TYPES.size)
        for (type in TARGET_TYPES) {
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
        lastSnapshotUpdateNs = 0L

        val missing = mutableListOf<String>()
        var registered = 0
        var batched = 0
        Log.i(TAG, "Arrancando SensorHub — enumeración de sensores del dispositivo:")
        for (type in TARGET_TYPES) {
            val name = typeName(type)
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor == null) {
                missing += name
                Log.w(TAG, "  · $name → NO EXISTE en este hardware (columna CSV quedará vacía)")
                continue
            }
            val result = registerWithBatchingFallback(sensor, name)
            when (result) {
                RegistrationResult.BATCHED -> {
                    Log.i(
                        TAG,
                        "  · $name → OK con batching (vendor='${sensor.vendor}' " +
                            "fifo=${sensor.fifoMaxEventCount} minDelay=${sensor.minDelay}us)",
                    )
                    registered++
                    batched++
                }
                RegistrationResult.UNBATCHED -> {
                    Log.i(
                        TAG,
                        "  · $name → OK sin batching (vendor='${sensor.vendor}' " +
                            "fifo=${sensor.fifoMaxEventCount} minDelay=${sensor.minDelay}us)",
                    )
                    registered++
                }
                RegistrationResult.FAILED -> {
                    Log.e(TAG, "  · $name → el sistema rechazó el registro (columna CSV quedará vacía)")
                }
            }
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Sensores ausentes en hardware: ${missing.joinToString()}")
        }
        Log.i(
            TAG,
            "SensorHub listo: $registered/${TARGET_TYPES.size} sensores activos " +
                "($batched con batching HW ${MAX_REPORT_LATENCY_US / 1000}ms, " +
                "samplingPeriod=${SAMPLING_PERIOD_US}us = 100 Hz, main looper)",
        )
    }

    /**
     * Intenta registrar [sensor] con batching HW si su FIFO lo soporta; si
     * el sistema lo rechaza, vuelve a intentar sin batching. Si también falla,
     * devuelve [RegistrationResult.FAILED].
     *
     * Decisión por sensor (no global): algunos chips sólo baten bien algunos
     * tipos (p.ej. el magnetómetro a veces no tiene FIFO aunque el acelerómetro
     * sí). Así aprovechamos el ahorro donde es posible sin sacrificar sensores.
     */
    private fun registerWithBatchingFallback(sensor: Sensor, sensorName: String): RegistrationResult {
        val supportsBatching = sensor.fifoMaxEventCount > 0
        if (supportsBatching) {
            val ok = runCatching {
                sensorManager.registerListener(
                    listener,
                    sensor,
                    SAMPLING_PERIOD_US,
                    MAX_REPORT_LATENCY_US,
                )
            }.getOrDefault(false)
            if (ok) return RegistrationResult.BATCHED
            Log.w(TAG, "$sensorName: registro con batching falló; reintentando sin batching")
        }
        val okPlain = runCatching {
            sensorManager.registerListener(listener, sensor, SAMPLING_PERIOD_US)
        }.getOrDefault(false)
        return if (okPlain) RegistrationResult.UNBATCHED else RegistrationResult.FAILED
    }

    private enum class RegistrationResult { BATCHED, UNBATCHED, FAILED }

    private fun stopInternal() {
        sensorManager.unregisterListener(listener)
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
        /**
         * Latencia máxima de entrega en batching HW (µs).
         * 200 000 us = 200 ms → ~20 muestras por ráfaga a 100 Hz.
         * Compromiso entre batería (-30%/-50% en sesiones largas) y
         * latencia del snapshot UI (5 refrescos/s en vez de 10).
         * Sólo se aplica si `sensor.fifoMaxEventCount > 0`; si no,
         * se cae a la llamada de 3 argumentos sin batching.
         */
        const val MAX_REPORT_LATENCY_US: Int = 200_000
        /** Capacidad del canal frames→engine. 2048 @ 100 Hz ≈ 20 s de cola. */
        const val FRAME_CHANNEL_CAPACITY: Int = 2048
        /** Throttle de snapshot a UI: 100 ms = 10 Hz. */
        private const val SNAPSHOT_INTERVAL_NS: Long = 100_000_000L

        /** Tipos de sensor IMU que la app intenta capturar — orden irrelevante. */
        private val TARGET_TYPES: IntArray = intArrayOf(
            Sensor.TYPE_ACCELEROMETER,       // reloj maestro (no se guarda en CSV)
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
            // Sensor.TYPE_MAGNETIC_FIELD,   // desactivado — mag_heading no se guarda en CSV
        )
    }
}
