package com.sarmidev.imuflux.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sarmidev.imuflux.domain.model.CustomMovement
import com.sarmidev.imuflux.domain.model.DetectionLog
import com.sarmidev.imuflux.domain.model.LogLevel
import com.sarmidev.imuflux.domain.model.RecordingHealth
import com.sarmidev.imuflux.domain.model.SensorSnapshot
import com.sarmidev.imuflux.domain.usecase.EvaluateMovementUseCase
import com.sarmidev.imuflux.domain.usecase.GetMovementsUseCase
import com.sarmidev.imuflux.domain.usecase.GetSensorDataUseCase
import com.sarmidev.imuflux.recording.RecordingEngine
import com.sarmidev.imuflux.service.RecordingService
import com.sarmidev.imuflux.service.SessionConfigStore
import com.sarmidev.imuflux.ui.model.SensorsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la pantalla principal de monitor + grabación.
 *
 * - Observa `liveSnapshot` (10 Hz) del [GetSensorDataUseCase] para pintar en UI.
 * - Arranca/para el [SensorForegroundService] (que a su vez acciona el
 *   [RecordingEngine]) al pulsar el botón.
 * - Evalúa movimientos personalizados a 10 Hz sobre el mismo flujo throttled.
 * - Expone el estado de salud del motor de grabación.
 *
 * **Importante**: al ciclo de vida del ViewModel se liga un `acquire`/`release`
 * del repositorio de sensores — así se garantiza que la captura HW esté activa
 * mientras la pantalla está viva, sin duplicar registros con el servicio.
 */
@HiltViewModel
class SensorsViewModel @Inject constructor(
    application: Application,
    private val getSensorDataUseCase: GetSensorDataUseCase,
    private val getMovementsUseCase: GetMovementsUseCase,
    private val evaluateMovementUseCase: EvaluateMovementUseCase,
    private val recordingEngine: RecordingEngine,
    private val sessionConfigStore: SessionConfigStore,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SensorsUiState())
    val uiState: StateFlow<SensorsUiState> = _uiState.asStateFlow()

    val isRecording: StateFlow<Boolean> = recordingEngine.isRecording
    val currentSessionId: StateFlow<String?> = recordingEngine.currentSessionId
    val recordingHealth: StateFlow<RecordingHealth> = recordingEngine.health

    val sensorSnapshotState: StateFlow<SensorSnapshot> = getSensorDataUseCase()

    private val _detectedMovement = MutableStateFlow<CustomMovement?>(null)
    val detectedMovement: StateFlow<CustomMovement?> = _detectedMovement.asStateFlow()

    private val _logs = MutableStateFlow<List<DetectionLog>>(emptyList())
    val logs: StateFlow<List<DetectionLog>> = _logs.asStateFlow()

    /** Epoch ms cuando empezó la grabación actual; null si no se graba. */
    private val _recordingStartMs = MutableStateFlow<Long?>(null)
    val recordingStartMs: StateFlow<Long?> = _recordingStartMs.asStateFlow()

    // ── Configuración contextual de sesión (toro + almacén) ─────────────────
    private val _forkliftModel = MutableStateFlow(sessionConfigStore.getForklift())
    val forkliftModel: StateFlow<String> = _forkliftModel.asStateFlow()

    private val _warehouse = MutableStateFlow(sessionConfigStore.getWarehouse())
    val warehouse: StateFlow<String> = _warehouse.asStateFlow()

    private val _recentForklifts = MutableStateFlow(sessionConfigStore.getRecentForklifts())
    val recentForklifts: StateFlow<List<String>> = _recentForklifts.asStateFlow()

    private val _recentWarehouses = MutableStateFlow(sessionConfigStore.getRecentWarehouses())
    val recentWarehouses: StateFlow<List<String>> = _recentWarehouses.asStateFlow()

    private val _isSetupReady = MutableStateFlow(sessionConfigStore.isReady())
    /** `true` si el usuario ha configurado lo mínimo para poder iniciar grabación. */
    val isSetupReady: StateFlow<Boolean> = _isSetupReady.asStateFlow()

    /** Snapshot del último health publicado para detectar transiciones. */
    private var lastDrops: Long = 0L
    private var lastJitterWarned: Boolean = false
    private var lastJitterErrored: Boolean = false

    init {
        getSensorDataUseCase.acquire()

        viewModelScope.launch {
            recordingEngine.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }

        // Eventos de grabación: start / stop con sesión
        viewModelScope.launch {
            var previous = false
            recordingEngine.isRecording.collect { recording ->
                if (recording && !previous) {
                    _recordingStartMs.value = System.currentTimeMillis()
                    val sid = recordingEngine.currentSessionId.value
                    addLog("Grabación iniciada${if (sid != null) " — sesión $sid" else ""}", LogLevel.OK)
                } else if (!recording && previous) {
                    _recordingStartMs.value = null
                    val h = recordingEngine.health.value
                    val mb = "%.2f MB".format(h.bytesWritten / (1024.0 * 1024.0))
                    addLog(
                        "Grabación detenida — ${h.framesWritten} frames · $mb · chunk #${h.currentChunkIndex}",
                        LogLevel.OK,
                    )
                    lastDrops = 0L
                    lastJitterWarned = false
                    lastJitterErrored = false
                }
                previous = recording
            }
        }

        // Métricas de salud: drops y jitter
        viewModelScope.launch {
            recordingEngine.health.collect { h ->
                if (!recordingEngine.isRecording.value) return@collect
                // Drops nuevos
                if (h.framesDropped > lastDrops) {
                    val newDrops = h.framesDropped - lastDrops
                    if (h.framesDropped > 25L) {
                        val level =
                            if (h.framesDropped > 200L) LogLevel.ERROR else LogLevel.WARNING
                        addLog(
                            "Drops detectados: +$newDrops (total ${h.framesDropped}) — " +
                                "disco lento o CPU saturada",
                            level,
                        )
                    }
                    lastDrops = h.framesDropped
                }
                // Jitter alto
                val jitterMs = h.jitterP95Ns / 1_000_000.0
                if (!lastJitterErrored && jitterMs > 10.0) {
                    addLog("Jitter p95 muy alto: ${"%.1f".format(jitterMs)} ms (límite 10 ms)", LogLevel.ERROR)
                    lastJitterErrored = true
                    lastJitterWarned = true
                } else if (!lastJitterWarned && jitterMs > 5.0) {
                    addLog("Jitter p95 elevado: ${"%.1f".format(jitterMs)} ms (límite 5 ms)", LogLevel.WARNING)
                    lastJitterWarned = true
                } else if (lastJitterWarned && jitterMs <= 3.0) {
                    addLog("Jitter p95 normalizado: ${"%.1f".format(jitterMs)} ms", LogLevel.OK)
                    lastJitterWarned = false
                    lastJitterErrored = false
                }
            }
        }

        // Nuevas sesiones (auto-resume: el sessionId cambia mientras se graba)
        viewModelScope.launch {
            var prevId: String? = null
            recordingEngine.currentSessionId.collect { sid ->
                if (sid != null && prevId != null && sid != prevId) {
                    addLog("Auto-resume: nueva sesión $sid (continuación de $prevId)", LogLevel.WARNING)
                }
                prevId = sid
            }
        }

        viewModelScope.launch {
            combine(
                sensorSnapshotState,
                getMovementsUseCase(),
            ) { snapshot, movements -> snapshot to movements }
                .collect { (snapshot, movements) ->
                    if (snapshot.values.isEmpty()) return@collect
                    val activeMovements = movements.filter { it.isActive }
                    val detected = evaluateMovementUseCase(snapshot.values, activeMovements)
                    if (detected != null && detected != _detectedMovement.value) {
                        addLog("Movimiento detectado: ${detected.name}", LogLevel.OK)
                    }
                    _detectedMovement.value = detected
                }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun setForkliftModel(value: String) {
        sessionConfigStore.setForklift(value)
        sessionConfigStore.generateToroId(value)
        _forkliftModel.value = sessionConfigStore.getForklift()
        _recentForklifts.value = sessionConfigStore.getRecentForklifts()
        _isSetupReady.value = sessionConfigStore.isReady()
    }

    fun setWarehouse(value: String) {
        sessionConfigStore.setWarehouse(value)
        _warehouse.value = sessionConfigStore.getWarehouse()
        _recentWarehouses.value = sessionConfigStore.getRecentWarehouses()
        _isSetupReady.value = sessionConfigStore.isReady()
    }

    private fun addLog(message: String, level: LogLevel) {
        _logs.update { current ->
            val updated = listOf(DetectionLog(message = message, level = level)) + current
            if (updated.size > MAX_LOGS) updated.take(MAX_LOGS) else updated
        }
    }

    fun onStartStopClick() {
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java)

        if (recordingEngine.isRecording.value) {
            intent.action = RecordingService.ACTION_STOP
            context.startService(intent)
            _uiState.update {
                it.copy(showOverlay = true, overlayColor = Color(0xFFFF8C00))
            }
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                _uiState.update { it.copy(showOverlay = false) }
            }
        } else {
            if (!sessionConfigStore.isReady()) {
                addLog(
                    "Falta configurar toro y/o almacén antes de iniciar la grabación",
                    LogLevel.ERROR,
                )
                return
            }
            intent.action = RecordingService.ACTION_START
            intent.putExtra(RecordingService.EXTRA_FORKLIFT, sessionConfigStore.getForklift())
            intent.putExtra(RecordingService.EXTRA_WAREHOUSE, sessionConfigStore.getWarehouse())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _uiState.update {
                it.copy(showOverlay = true, overlayColor = Color.Green)
            }
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                _uiState.update { it.copy(showOverlay = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        getSensorDataUseCase.release()
    }

    companion object {
        private const val MAX_LOGS = 60
    }
}
