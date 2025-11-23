package com.example.scantest.ui.viewmodel

// Asumiendo que MovementConfigViewModel es donde se guardan las reglas
import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scantest.domain.SensorMonitor
import com.example.scantest.domain.Condition
import com.example.scantest.domain.CustomMovement
import com.example.scantest.domain.SensorData
import com.example.scantest.domain.SensorSnapshot
import com.example.scantest.domain.SensorType
import com.example.scantest.ui.model.SensorsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SensorsViewModel(
    application: Application,
    sensorMonitor: SensorMonitor,
    private val movementConfigViewModel: MovementConfigViewModel
) : AndroidViewModel(application) {
    private val recordedSensors = mutableListOf<SensorData>()

    private val _detectedMovement = MutableStateFlow<CustomMovement?>(null)
    val detectedMovement: StateFlow<CustomMovement?> = _detectedMovement
    private val _uiState = MutableStateFlow(SensorsUiState())
    val uiState: StateFlow<SensorsUiState> = _uiState.asStateFlow()

    val sensorSnapshotState: StateFlow<SensorSnapshot> = sensorMonitor.getSensorDataFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L), // Se inicia cuando la UI está visible
            initialValue = SensorSnapshot(emptyMap(), 0L) // Valor inicial
        )


    fun collectAndEvaluate() {
        viewModelScope.launch {
            sensorSnapshotState.collect { snapshot ->
                if (snapshot.values.isEmpty()) return@collect

                val allValues = snapshot.values
                val activeMovements =
                    movementConfigViewModel.movements.value.filter { it.isActive }
                val result = evaluateMovement(allValues, activeMovements)
                _detectedMovement.value = result
                if (uiState.value.isRecording) {
                    synchronized(recordedSensors) {
                        val registeredSensors = snapshot.values.map {
                            SensorData(
                                name = it.key.name,
                                value = it.value,
                                timestamp = snapshot.timestamp
                            )
                        }
                        recordedSensors.addAll(registeredSensors)
                    }
                }
            }
        }
    }

    fun onStartStopClick() {
        val isCurrentlyRecording = _uiState.value.isRecording
        if (isCurrentlyRecording) {
            _uiState.update { it.copy(isRecording = false, showSaveDialog = true) }
        } else {
            synchronized(recordedSensors) {
                recordedSensors.clear()
            }
            _uiState.update { it.copy(isRecording = true) }
        }
    }


    private fun evaluateMovement(
        sensorValues: Map<SensorType, Float>,
        movements: List<CustomMovement>
    ): CustomMovement? {

        for (movement in movements) {
            var allCriteriaMet = true

            // Comprueba cada Criterio dentro de la regla actual
            for (criterion in movement.criteria) {

                // 1. Obtener el valor actual del sensor que la regla está evaluando
                val currentValue = sensorValues[criterion.sensor]

                // Si el valor del sensor no está disponible, esta regla no se puede cumplir.
                // Esto puede ocurrir si un sensor específico (ej: magnetómetro) falla.
                if (currentValue == null) {
                    allCriteriaMet = false
                    break // Pasa a la siguiente regla de movimiento
                }

                // 2. Aplicar la condición lógica definida por el usuario
                val conditionIsMet = when (criterion.condition) {
                    Condition.GREATER_THAN -> currentValue > criterion.minValue
                    Condition.LESS_THAN -> currentValue < criterion.minValue
                    Condition.BETWEEN -> {
                        val maxValue = criterion.maxValue
                        // Se requiere que maxValue exista para la condición BETWEEN
                        if (maxValue != null) {
                            currentValue >= criterion.minValue && currentValue <= maxValue
                        } else {
                            // Si es BETWEEN y no hay maxValue, la regla está mal definida
                            false
                        }
                    }
                }

                // Si un solo criterio no se cumple, la regla completa falla
                if (!conditionIsMet) {
                    allCriteriaMet = false
                    break // Pasa a la siguiente regla de movimiento
                }
            }

            // 3. Si se llegó hasta aquí y la bandera es true, ¡el movimiento fue detectado!
            if (allCriteriaMet) {
                return movement
            }
        }

        // Si se completó el bucle sin devolver nada, ningún movimiento coincide
        return null
    }

    fun onSaveSensorsData(filename: String) {
        if (recordedSensors.isEmpty()) {
            Log.w("ViewModel", "No hay datos que guardar.")
            _uiState.update { it.copy(showSaveDialog = false) }
            return
        }

        val sensorsToSave = synchronized(recordedSensors) {
            recordedSensors.toList()
        }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            try {
                val safeFilename = filename.ifBlank { "sensors_${System.currentTimeMillis()}.csv" }
                val file = File(context.filesDir, safeFilename)

                file.bufferedWriter().use { out ->
                    // 1. Get ordered list of all sensor names for the header
                    val sensorNames = SensorType.entries.map { it.name }

                    // Write header
                    out.write("timestamp,${sensorNames.joinToString(",")}")
                    out.newLine()

                    // 2. Group recorded data by timestamp
                    val dataByTimestamp = sensorsToSave.groupBy { it.timestamp }

                    // 3. Iterate over each timestamp entry and write a row
                    for (timestamp in dataByTimestamp.keys.sorted()) {
                        val sensorValuesForTimestamp = dataByTimestamp[timestamp]
                        val valueMap = sensorValuesForTimestamp?.associate { it.name to it.value.toString() }

                        // Map each header column to its value, or an empty string if not present
                        val rowValues = sensorNames.map { name ->
                            valueMap?.getOrDefault(name, "")
                        }

                        // Join all values to form the CSV row
                        out.write("$timestamp,${rowValues.joinToString(",")}")
                        out.newLine()
                    }
                }

                Log.i("ViewModel", "Archivo guardado en: ${file.absolutePath}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Guardado: $safeFilename", Toast.LENGTH_LONG).show()
                }

            } catch (e: IOException) {
                Log.e("ViewModel", "Error al guardar el archivo", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error al guardar", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _uiState.update { it.copy(showSaveDialog = false) }
                synchronized(recordedSensors) {
                    recordedSensors.clear()
                }
            }
        }
    }

    fun onDismissSaveDialog() {
        _uiState.update { it.copy(showSaveDialog = false) }
        synchronized(recordedSensors) {
            recordedSensors.clear()
        }
    }

}
