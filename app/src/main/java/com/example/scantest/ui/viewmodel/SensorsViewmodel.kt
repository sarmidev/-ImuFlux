package com.example.scantest.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scantest.domain.model.CustomMovement
import com.example.scantest.domain.model.RecordingHealth
import com.example.scantest.domain.model.SensorSnapshot
import com.example.scantest.domain.usecase.EvaluateMovementUseCase
import com.example.scantest.domain.usecase.GetMovementsUseCase
import com.example.scantest.domain.usecase.GetSensorDataUseCase
import com.example.scantest.recording.RecordingEngine
import com.example.scantest.service.RecordingService
import com.example.scantest.ui.model.SensorsUiState
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
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SensorsUiState())
    val uiState: StateFlow<SensorsUiState> = _uiState.asStateFlow()

    val isRecording: StateFlow<Boolean> = recordingEngine.isRecording
    val currentSessionId: StateFlow<String?> = recordingEngine.currentSessionId
    val recordingHealth: StateFlow<RecordingHealth> = recordingEngine.health

    val sensorSnapshotState: StateFlow<SensorSnapshot> = getSensorDataUseCase()

    private val _detectedMovement = MutableStateFlow<CustomMovement?>(null)
    val detectedMovement: StateFlow<CustomMovement?> = _detectedMovement.asStateFlow()

    init {
        getSensorDataUseCase.acquire()

        viewModelScope.launch {
            recordingEngine.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
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
                    _detectedMovement.value = evaluateMovementUseCase(snapshot.values, activeMovements)
                }
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
            intent.action = RecordingService.ACTION_START
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
}
