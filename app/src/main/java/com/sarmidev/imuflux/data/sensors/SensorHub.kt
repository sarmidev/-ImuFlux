package com.sarmidev.imuflux.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

import android.util.Log
import com.sarmidev.imuflux.domain.model.SensorFrame
import com.sarmidev.imuflux.domain.model.SensorSnapshot
import com.sarmidev.imuflux.domain.model.SessionMetadata
import com.sarmidev.imuflux.recording.RecordingTuningStore
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
 * Los callbacks se entregan en un **HandlerThread dedicado** de prioridad
 * elevada ([Process.THREAD_PRIORITY_URGENT_AUDIO]), no en el main looper. Esto
 * evita que la carga de Compose/UI o los SDK de terceros retrasen o coalescan
 * la entrega de eventos (el patrón de saltos de 60 ms que se veía al arrancar).
 * El trabajo por evento sigue siendo trivial (escrituras a un `FloatArray` +
 * un `trySend` no bloqueante). Como todo el hot path corre en ese único hilo,
 * no hay `synchronized` ni races sobre los slots del [FrameAssembler].
 *
 * El trabajo costoso (serializar CSV, flush a disco) NO ocurre aquí: vive en
 * [com.sarmidev.imuflux.recording.RecordingEngine] sobre
 * `Dispatchers.IO.limitedParallelism(1)`. El [Channel] entre ambos acota la
 * cola con `DROP_OLDEST` para evitar OOM si el disco va lento.
 *
 * ## Frecuencia
 *
 * - `samplingPeriodUs` viene de [RecordingTuningStore]. En Samsung, por
 *   defecto se **sobre-muestrea** a 200 Hz (`5_000` µs) y se **decima por
 *   timestamp** a 100 Hz: aunque One UI recorte la entrega a la mitad con la
 *   pantalla apagada, siguen quedando ~100 Hz reales. En el resto de
 *   fabricantes se pide 100 Hz sin decimación.
 * - **Sensores wake-up**: cuando existen, se prefieren (despiertan el SoC para
 *   entregar antes de que su FIFO desborde, evitando huecos en Doze). Fallback
 *   automático al sensor non-wakeup si no hay versión wake-up.
 * - **Batching HW** con `maxReportLatencyUs` (200 ms por defecto) cuando el
 *   sensor declara `fifoMaxEventCount > 0`. Los timestamps los genera el chip,
 *   así que la precisión temporal se preserva.
 * - Si el sensor NO soporta FIFO o el sistema rechaza la petición con
 *   batching, se hace fallback automático a la llamada sin batching.
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
    private val tuning: RecordingTuningStore,
) {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val assembler = FrameAssembler()

    /** Hilo dedicado de alta prioridad para los callbacks de sensores. */
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    /**
     * Resampleo de rejilla: downsamplea a la frecuencia objetivo emitiendo
     * frames con timestamps **sintetizados** (los puntos de la rejilla), de
     * modo que ante una entrada limpia y ≥ objetivo el `dt` escrito es exacto y
     * el jitter ~0. El periodo se fija al arrancar desde [tuning]. Ver
     * [GridResampler] para el detalle y los tests.
     */
    private val gridResampler = GridResampler()

    /** Snapshot throttled para UI (10 Hz) — siempre activo mientras hay hardware registrado. */
    private val _liveSnapshot = MutableStateFlow(SensorSnapshot(emptyMap(), 0L))
    val liveSnapshot: StateFlow<SensorSnapshot> = _liveSnapshot.asStateFlow()

    /** Contador monotónico de frames emitidos al canal de grabación desde app start. */
    private val _framesEmitted = AtomicLong(0L)
    val framesEmitted: Long get() = _framesEmitted.get()

    /**
     * Contador de eventos **crudos** del sensor maestro (antes del resampleo).
     * Permite medir la tasa real que entrega el hardware y distinguir un cap
     * de firmware (HW a ~60 Hz) de un artefacto de resampleo (HW a ~120 Hz
     * mal downsampleado).
     */
    private val _rawSamplesEmitted = AtomicLong(0L)
    val rawSamplesEmitted: Long get() = _rawSamplesEmitted.get()

    /** Descriptores de los sensores realmente registrados (con wakeup/min/max delay). */
    @Volatile
    private var registeredDescriptors: List<SessionMetadata.SensorDescriptor> = emptyList()

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
            val ts = event.timestamp
            _rawSamplesEmitted.incrementAndGet()
            // Timestamp de rejilla sintetizado (o el real si el resampleo está
            // off / se re-ancla tras un hueco), o null si hay que descartar.
            val emitTs = gridResampler.onEvent(ts)
            if (emitTs != null) {
                val activeChannel = recordingChannel
                if (activeChannel != null) {
                    val frame = assembler.buildFrame(timestampNs = emitTs)
                    activeChannel.trySend(frame)
                    _framesEmitted.incrementAndGet()
                }
            }
            maybeUpdateSnapshot(ts)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun maybeUpdateSnapshot(timestampNs: Long) {
        if (timestampNs - lastSnapshotUpdateNs >= SNAPSHOT_INTERVAL_NS) {
            lastSnapshotUpdateNs = timestampNs
            _liveSnapshot.value = assembler.snapshot(timestampNs)
        }
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
            _rawSamplesEmitted.set(0L)
            gridResampler.reset()
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

    /**
     * Describe los sensores IMU que se registrarán para auditoría en
     * `metadata.json`. Refleja la variante **resuelta** (wake-up si está
     * habilitada y existe), no la non-wakeup por defecto, e incluye
     * `isWakeUp` / `maxDelayUs` para poder diagnosticar caps de firmware.
     */
    fun describeAvailableSensors(): List<SessionMetadata.SensorDescriptor> {
        val result = ArrayList<SessionMetadata.SensorDescriptor>(TARGET_TYPES.size)
        for (type in TARGET_TYPES) {
            val (sensor, isWakeup) = resolveSensor(type)
            if (sensor == null) continue
            result += SessionMetadata.SensorDescriptor(
                type = typeName(type),
                name = sensor.name ?: "",
                vendor = sensor.vendor ?: "",
                resolution = sensor.resolution,
                fifoMaxEventCount = sensor.fifoMaxEventCount,
                minDelayUs = sensor.minDelay,
                isWakeUp = isWakeup || sensor.isWakeUpSensor,
                maxDelayUs = sensor.maxDelay,
            )
        }
        return result
    }

    /** Descriptores capturados en el último registro real de listeners. */
    fun registeredSensorDescriptors(): List<SessionMetadata.SensorDescriptor> =
        registeredDescriptors

    private fun startInternal() {
        lastSnapshotUpdateNs = 0L
        gridResampler.reset()

        val thread = HandlerThread("ImuSensorThread", Process.THREAD_PRIORITY_URGENT_AUDIO)
        thread.start()
        sensorThread = thread
        sensorHandler = Handler(thread.looper)

        registerAllSensors()
    }

    /**
     * Re-registra todos los listeners contra [SensorManager] sin recrear el
     * hilo ni el canal. Lo usa el watchdog de stall del [RecordingEngine]
     * cuando dejan de llegar frames: en algunos firmware un unregister +
     * register vuelve a "arrancar" la entrega tras un Doze light.
     */
    fun restartListeners() {
        synchronized(lifecycleLock) {
            if (refCount == 0) return
            Log.w(TAG, "restartListeners — re-registrando sensores (posible stall)")
            sensorManager.unregisterListener(listener)
            registerAllSensors()
        }
    }

    private fun registerAllSensors() {
        val samplingPeriodUs = tuning.samplingPeriodUs()
        val maxReportLatencyUs = tuning.maxReportLatencyUs()
        val decimateToHz = tuning.decimateToHz()
        gridResampler.periodNs = if (decimateToHz > 0) (1_000_000_000L / decimateToHz) else 0L
        gridResampler.reset()

        val descriptors = ArrayList<SessionMetadata.SensorDescriptor>(TARGET_TYPES.size)
        val missing = mutableListOf<String>()
        var registered = 0
        var batched = 0
        var wakeup = 0
        Log.i(TAG, "Arrancando SensorHub — enumeración de sensores del dispositivo:")
        for (type in TARGET_TYPES) {
            val name = typeName(type)
            val (sensor, isWakeup) = resolveSensor(type)
            if (sensor == null) {
                missing += name
                Log.w(TAG, "  · $name → NO EXISTE en este hardware (columna CSV quedará vacía)")
                continue
            }
            if (isWakeup) wakeup++
            val result = registerWithBatchingFallback(sensor, name, samplingPeriodUs, maxReportLatencyUs)
            val wu = if (isWakeup) " [wakeup]" else ""
            when (result) {
                RegistrationResult.BATCHED -> {
                    Log.i(
                        TAG,
                        "  · $name → OK con batching$wu (vendor='${sensor.vendor}' " +
                            "fifo=${sensor.fifoMaxEventCount} minDelay=${sensor.minDelay}us " +
                            "maxDelay=${sensor.maxDelay}us)",
                    )
                    registered++
                    batched++
                }
                RegistrationResult.UNBATCHED -> {
                    Log.i(
                        TAG,
                        "  · $name → OK sin batching$wu (vendor='${sensor.vendor}' " +
                            "fifo=${sensor.fifoMaxEventCount} minDelay=${sensor.minDelay}us " +
                            "maxDelay=${sensor.maxDelay}us)",
                    )
                    registered++
                }
                RegistrationResult.FAILED -> {
                    Log.e(TAG, "  · $name → el sistema rechazó el registro (columna CSV quedará vacía)")
                }
            }
            if (result != RegistrationResult.FAILED) {
                descriptors += SessionMetadata.SensorDescriptor(
                    type = name,
                    name = sensor.name ?: "",
                    vendor = sensor.vendor ?: "",
                    resolution = sensor.resolution,
                    fifoMaxEventCount = sensor.fifoMaxEventCount,
                    minDelayUs = sensor.minDelay,
                    isWakeUp = isWakeup || sensor.isWakeUpSensor,
                    maxDelayUs = sensor.maxDelay,
                )
            }
        }
        registeredDescriptors = descriptors
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Sensores ausentes en hardware: ${missing.joinToString()}")
        }
        val gridPeriodNs = gridResampler.periodNs
        val gridInfo = if (gridPeriodNs > 0L) {
            "rejilla a $decimateToHz Hz (${gridPeriodNs / 1000}us, ts sintetizado)"
        } else {
            "sin resampleo"
        }
        Log.i(
            TAG,
            "SensorHub listo: $registered/${TARGET_TYPES.size} sensores activos " +
                "($batched con batching HW ${maxReportLatencyUs / 1000}ms, $wakeup wakeup, " +
                "samplingPeriod=${samplingPeriodUs}us, $gridInfo, hilo dedicado)",
        )
    }

    /**
     * Resuelve el sensor por tipo, prefiriendo la variante **wake-up** cuando
     * existe y está habilitada en [tuning]. Devuelve el sensor y si es wakeup.
     */
    private fun resolveSensor(type: Int): Pair<Sensor?, Boolean> {
        if (tuning.preferWakeupSensors()) {
            val wake = runCatching { sensorManager.getDefaultSensor(type, /* wakeUp = */ true) }
                .getOrNull()
            if (wake != null) return wake to true
        }
        return sensorManager.getDefaultSensor(type) to false
    }

    /**
     * Intenta registrar [sensor] con batching HW si su FIFO lo soporta; si
     * el sistema lo rechaza, vuelve a intentar sin batching. Si también falla,
     * devuelve [RegistrationResult.FAILED]. Los callbacks se entregan en el
     * [sensorHandler] (hilo dedicado), no en el main looper.
     */
    private fun registerWithBatchingFallback(
        sensor: Sensor,
        sensorName: String,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int,
    ): RegistrationResult {
        val handler = sensorHandler
        val supportsBatching = sensor.fifoMaxEventCount > 0 && maxReportLatencyUs > 0
        if (supportsBatching) {
            val ok = runCatching {
                if (handler != null) {
                    sensorManager.registerListener(
                        listener,
                        sensor,
                        samplingPeriodUs,
                        maxReportLatencyUs,
                        handler,
                    )
                } else {
                    sensorManager.registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs)
                }
            }.getOrDefault(false)
            if (ok) return RegistrationResult.BATCHED
            Log.w(TAG, "$sensorName: registro con batching falló; reintentando sin batching")
        }
        val okPlain = runCatching {
            if (handler != null) {
                sensorManager.registerListener(listener, sensor, samplingPeriodUs, handler)
            } else {
                sensorManager.registerListener(listener, sensor, samplingPeriodUs)
            }
        }.getOrDefault(false)
        return if (okPlain) RegistrationResult.UNBATCHED else RegistrationResult.FAILED
    }

    private enum class RegistrationResult { BATCHED, UNBATCHED, FAILED }

    private fun stopInternal() {
        sensorManager.unregisterListener(listener)
        recordingChannel?.close()
        recordingChannel = null
        runCatching { sensorThread?.quitSafely() }
        sensorThread = null
        sensorHandler = null
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
