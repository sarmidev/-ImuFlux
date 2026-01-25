package com.example.scantest.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scantest.data.manager.SensorDataManager
import com.example.scantest.domain.CustomMovement
import com.example.scantest.domain.SensorSnapshot
import com.example.scantest.domain.usecase.EvaluateMovementUseCase
import com.example.scantest.domain.usecase.ExportSensorDataUseCase
import com.example.scantest.domain.usecase.GetMovementsUseCase
import com.example.scantest.domain.usecase.GetSensorDataUseCase
import com.example.scantest.service.SensorForegroundService
import com.example.scantest.ui.model.SensorsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SensorsViewModel @Inject constructor(
    application: Application,
    getSensorDataUseCase: GetSensorDataUseCase,
    private val getMovementsUseCase: GetMovementsUseCase,
    private val evaluateMovementUseCase: EvaluateMovementUseCase,
    private val exportSensorDataUseCase: ExportSensorDataUseCase,
    private val sensorDataManager: SensorDataManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SensorsUiState())
    val uiState: StateFlow<SensorsUiState> = _uiState.asStateFlow()

    // Observamos el Manager para saber si estamos grabando (sincronización con Servicio)
    val isRecording = sensorDataManager.isRecording

    // UI: Movimiento detectado (viene del Manager si graba, o local si no)
    // Para simplificar, mientras no grabamos, calculamos localmente.
    // Mientras grabamos, usamos lo del servicio?
    // Mejor: Calculamos localmente SIEMPRE para la UI inmediata.
    // El servicio calcula por su cuenta para el registro.
    
    private val _detectedMovement = MutableStateFlow<CustomMovement?>(null)
    val detectedMovement: StateFlow<CustomMovement?> = _detectedMovement

    // Datos en tiempo real para la UI
    val sensorSnapshotState: StateFlow<SensorSnapshot> = getSensorDataUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = SensorSnapshot(emptyMap(), 0L)
        )

    init {
        // Sincronizar estado inicial de UI con el Manager (por si rotamos pantalla)
        viewModelScope.launch {
            sensorDataManager.isRecording.collect { recording ->
                 _uiState.update { it.copy(isRecording = recording) }
            }
        }
    }

    fun collectAndEvaluate() {
        viewModelScope.launch {
            combine(
                sensorSnapshotState,
                getMovementsUseCase()
            ) { snapshot, movements ->
                Pair(snapshot, movements)
            }.collect { (snapshot, movements) ->
                if (snapshot.values.isEmpty()) return@collect
                
                val activeMovements = movements.filter { it.isActive }
                val result = evaluateMovementUseCase(snapshot.values, activeMovements)
                _detectedMovement.value = result
                
                // NOTA: Ya no grabamos aquí en 'recordedSensors'. Eso lo hace el Servicio.
            }
        }
    }

    fun onStartStopClick() {
        val context = getApplication<Application>()
        val intent = Intent(context, SensorForegroundService::class.java)

        if (sensorDataManager.isRecording.value) {
            // STOP
            intent.action = SensorForegroundService.ACTION_STOP
            context.startService(intent) // startService delivers the intent to running service

            _uiState.update { it.copy(
                showSaveDialog = true,
                showOverlay = true,
                overlayColor = Color(0xFFFF8C00)
            ) }
            viewModelScope.launch {
                delay(1000)
                _uiState.update { it.copy(showOverlay = false) }
            }

        } else {
            // START
            intent.action = SensorForegroundService.ACTION_START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            _uiState.update { it.copy(
                showOverlay = true,
                overlayColor = Color.Green
            ) }
            viewModelScope.launch {
                delay(1000)
                _uiState.update { it.copy(showOverlay = false) }
            }
        }
    }

    fun onSaveSensorsData(uri: Uri) {
        // Obtenemos los datos del Manager (Singleton)
        val dataToSave = sensorDataManager.getRecordedData()

        if (dataToSave.isEmpty()) {
            Log.w("ViewModel", "No hay datos que guardar.")
            _uiState.update { it.copy(showSaveDialog = false) }
            return
        }

        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            try {
                exportSensorDataUseCase(dataToSave, uri.toString())

                Log.i("ViewModel", "Archivo guardado en: $uri")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Guardado correctamente", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e("ViewModel", "Error al guardar el archivo", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error al guardar", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _uiState.update { it.copy(showSaveDialog = false) }
                // Limpiar buffer
                sensorDataManager.clearData()
            }
        }
    }

    fun onDismissSaveDialog() {
        _uiState.update { it.copy(showSaveDialog = false) }
        sensorDataManager.clearData()
    }
}