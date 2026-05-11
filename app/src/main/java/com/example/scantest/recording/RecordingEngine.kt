package com.example.scantest.recording

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.scantest.data.sensors.SensorHub
import com.example.scantest.data.storage.CsvChunkWriter
import com.example.scantest.data.storage.CsvSchema
import com.example.scantest.data.storage.SessionFileManager
import com.example.scantest.domain.model.RecordingHealth
import com.example.scantest.domain.model.SessionMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta una sesión de grabación completa:
 *
 * - Crea metadata.json y el directorio de sesión.
 * - Arranca el [SensorHub] (vía `acquire`) y abre un canal de frames.
 * - Consume frames en `Dispatchers.IO.limitedParallelism(1)` y los escribe
 *   en un [CsvChunkWriter] con rotación automática.
 * - Publica métricas en [health] a través de [RecordingHealthTracker].
 * - En `stop()` cierra el writer, cierra el canal, libera el hub y marca
 *   la sesión como finalizada en `metadata.json`.
 *
 * Sólo hay **una** sesión activa a la vez. Intentar arrancar con una
 * sesión activa es no-op (log de warning).
 */
@Singleton
class RecordingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorHub: SensorHub,
    private val sessionFileManager: SessionFileManager,
    private val wakeLockHolder: RecordingWakeLockHolder,
) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val healthTracker = RecordingHealthTracker()
    val health: StateFlow<RecordingHealth> get() = healthTracker.state

    /** Dispatcher IO dedicado (1 hilo) → escritura secuencial sin locks. */
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val engineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var consumerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var writer: CsvChunkWriter? = null

    /**
     * Arranca una nueva sesión. Si hay una sesión activa, no hace nada.
     *
     * @param resumeOf opcional: id de la sesión anterior si ésta es una
     *   continuación tras un kill del sistema.
     * @return el [SessionMetadata] recién creado, o `null` si no se pudo
     *   arrancar (p.ej. disco lleno).
     */
    fun start(
        resumeOf: String? = null,
        forkliftModel: String = "",
        warehouse: String = "",
    ): SessionMetadata? {
        if (_isRecording.value) {
            Log.w(TAG, "start ignorado: ya hay una sesión activa (${_currentSessionId.value})")
            return null
        }
        if (!sessionFileManager.hasFreeSpace(MIN_FREE_BYTES)) {
            Log.e(TAG, "Almacenamiento insuficiente — se aborta la grabación")
            return null
        }

        val sessionId = sessionFileManager.newSessionId()
        sessionFileManager.beginSession(sessionId)

        // Hereda el contador de resurrecciones del padre (cadena auto-resume).
        // El watchdog / handleSystemRestart ya incrementó el contador del
        // padre antes de reanudar, así que el hijo arranca con el nuevo valor.
        val inheritedResurrections = resumeOf
            ?.let { sessionFileManager.readResurrectionCount(it) }
            ?: 0

        val (model, manufacturer, sdk) = sessionFileManager.buildDeviceInfoTriplet()
        val metadata = SessionMetadata(
            sessionId = sessionId,
            startedAtWallMs = System.currentTimeMillis(),
            startedAtBootNs = SystemClock.elapsedRealtimeNanos(),
            deviceModel = model,
            deviceManufacturer = manufacturer,
            sdkInt = sdk,
            appVersion = resolveAppVersion(),
            sensors = sensorHub.describeAvailableSensors(),
            columns = CsvSchema.COLUMNS,
            chunkDurationMs = CsvChunkWriter.DEFAULT_CHUNK_DURATION_MS,
            chunkMaxBytes = CsvChunkWriter.DEFAULT_CHUNK_MAX_BYTES,
            resumeOf = resumeOf,
            forkliftModel = forkliftModel,
            warehouse = warehouse,
            resurrectionCount = inheritedResurrections,
        )
        runCatching { sessionFileManager.writeMetadata(metadata) }
            .onFailure { Log.w(TAG, "No pude escribir metadata.json", it) }

        healthTracker.reset()

        // Abrir canal ANTES de adquirir el hub: evita perder los primeros frames.
        val channel = sensorHub.openRecordingChannel()
        sensorHub.acquire()

        val chunkWriter = CsvChunkWriter(
            sessionFileManager = sessionFileManager,
            sessionId = sessionId,
            forkliftModel = forkliftModel,
            warehouse = warehouse,
            deviceModel = buildDeviceLabel(manufacturer, model),
        )
        writer = chunkWriter

        consumerJob = engineScope.launch { consumeFrames(channel, chunkWriter) }
        heartbeatJob = engineScope.launch { runHeartbeat(sessionId) }

        _currentSessionId.value = sessionId
        _isRecording.value = true
        Log.i(TAG, "Sesión iniciada: $sessionId (heartbeat cada ${HEARTBEAT_INTERVAL_MS / 1000}s)")
        return metadata
    }

    /** Para la sesión activa. Idempotente. */
    fun stop() {
        if (!_isRecording.value) return
        val sessionId = _currentSessionId.value

        sensorHub.closeRecordingChannel()
        runCatching { consumerJob?.cancel() }
        runCatching { heartbeatJob?.cancel() }

        val chunkWriter = writer
        writer = null
        runCatching { chunkWriter?.close() }
            .onFailure { Log.w(TAG, "Error cerrando CsvChunkWriter", it) }

        sensorHub.release()

        if (sessionId != null) {
            runCatching {
                sessionFileManager.markSessionEnded(
                    sessionId = sessionId,
                    endedAtWallMs = System.currentTimeMillis(),
                    endedAtBootNs = SystemClock.elapsedRealtimeNanos(),
                )
                sessionFileManager.endSession(sessionId)
            }.onFailure { Log.w(TAG, "Error finalizando metadata/lock", it) }
        }

        _isRecording.value = false
        _currentSessionId.value = null
        if (chunkWriter != null) {
            healthTracker.flushPublish(
                bytesWritten = chunkWriter.bytesWritten,
                chunkIndex = chunkWriter.chunkIndex,
                framesQueued = 0,
            )
        }
        Log.i(TAG, "Sesión parada: $sessionId (frames=${chunkWriter?.framesWritten})")
    }

    /**
     * Escribe periódicamente `last_heartbeat_ms` en el metadata de la sesión.
     *
     * Motivación: si el proceso muere bruscamente (kill OEM, LMK, thermal),
     * el lock queda y no hay `ended_at_wall_ms`. El heartbeat deja una huella
     * precisa del último momento en que la grabación estaba viva, lo que
     * permite a [SessionFileManager.closeOrphanedSessions] cerrar la sesión
     * con una `ended_at_wall_ms` realista (no inventada ni basada en
     * `System.currentTimeMillis()` al descubrirse horas después).
     *
     * Se ejecuta en [ioDispatcher] (mismo hilo que el escritor) para no
     * competir con el consumer ni introducir concurrencia sobre `metadata.json`.
     */
    private suspend fun runHeartbeat(sessionId: String) {
        try {
            while (engineScope.isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                // Renueva el wake-lock: el servicio adquirió uno con timeout
                // acotado (ver RecordingService.WAKELOCK_TIMEOUT_MS) para
                // evitar locks zombi en caso de kill. Mientras la grabación
                // siga viva, aquí lo re-armamos.
                runCatching {
                    wakeLockHolder.acquireOrRenew(WAKELOCK_RENEW_TIMEOUT_MS)
                }.onFailure { Log.w(TAG, "renovación de wake-lock falló", it) }

                runCatching {
                    sessionFileManager.writeHeartbeat(sessionId, System.currentTimeMillis())
                }.onFailure { Log.w(TAG, "heartbeat falló (se reintentará)", it) }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "heartbeat terminado: ${t.message}")
        }
    }

    private suspend fun consumeFrames(
        channel: ReceiveChannel<com.example.scantest.domain.model.SensorFrame>,
        chunkWriter: CsvChunkWriter,
    ) {
        try {
            for (frame in channel) {
                runCatching { chunkWriter.writeFrame(frame) }
                    .onFailure {
                        Log.e(TAG, "Error escribiendo frame — se intentará continuar", it)
                    }
                healthTracker.onFrame(
                    timestampNs = frame.timestampNs,
                    framesEmittedByHub = sensorHub.framesEmitted,
                    bytesWritten = chunkWriter.bytesWritten,
                    chunkIndex = chunkWriter.chunkIndex,
                    framesQueued = 0, // Channel no expone `size`; se aproxima como 0.
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Consumer terminó por excepción", t)
        }
    }

    /**
     * Devuelve una etiqueta legible del dispositivo combinando `manufacturer`
     * y `model`. Evita duplicados cuando `model` ya empieza por `manufacturer`
     * (caso típico en Samsung: manufacturer="samsung", model="Samsung Galaxy…").
     */
    private fun buildDeviceLabel(manufacturer: String, model: String): String {
        val mfr = manufacturer.trim()
        val mdl = model.trim()
        return when {
            mdl.isEmpty() -> mfr
            mfr.isEmpty() -> mdl
            mdl.startsWith(mfr, ignoreCase = true) -> mdl
            else -> "$mfr $mdl"
        }
    }

    private fun resolveAppVersion(): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    /** Libera el scope de corutinas. Llamar sólo al destruir la app/servicio. */
    fun shutdown() {
        runCatching { engineScope.cancel() }
    }

    companion object {
        private const val TAG = "RecordingEngine"
        /** Reservamos 1 GB libres antes de iniciar grabación. */
        private const val MIN_FREE_BYTES: Long = 1L * 1024L * 1024L * 1024L
        /**
         * Intervalo de heartbeat. 30 s es un buen compromiso:
         *  - Suficientemente frecuente para que en caso de kill, `ended_at`
         *    tenga como mucho 30 s de error.
         *  - Suficientemente espaciado para no cargar el disco (una escritura
         *    atómica de ~2 KB cada 30 s es despreciable).
         */
        private const val HEARTBEAT_INTERVAL_MS: Long = 30_000L
        /**
         * Timeout que se aplica cada vez que el heartbeat re-adquiere el
         * wake-lock. Debe ser **mayor** que [HEARTBEAT_INTERVAL_MS] con
         * holgura suficiente para cubrir un retraso ocasional del scheduler
         * de coroutines, y **menor** que el timeout inicial del servicio para
         *a que el mecanismo de seguridad (liberación automátic por el OS si
         * el proceso muere) siga actuando en el peor caso. 5 min cumple.
         */
        private const val WAKELOCK_RENEW_TIMEOUT_MS: Long = 5L * 60L * 1000L
    }
}
