package com.example.scantest.data.manager

import com.example.scantest.domain.CustomMovement
import com.example.scantest.domain.SensorData
import com.example.scantest.domain.SensorSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorDataManager @Inject constructor() {

    // Estado de la grabación
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Último snapshot de sensores (para la UI en tiempo real)
    private val _currentSnapshot = MutableStateFlow(SensorSnapshot(emptyMap(), 0L))
    val currentSnapshot: StateFlow<SensorSnapshot> = _currentSnapshot.asStateFlow()

    // Movimiento detectado
    private val _detectedMovement = MutableStateFlow<CustomMovement?>(null)
    val detectedMovement: StateFlow<CustomMovement?> = _detectedMovement.asStateFlow()

    // Buffer de datos grabados
    private val _recordedData = MutableStateFlow<List<SensorData>>(emptyList())
    // Exponemos una lista inmutable o función para obtenerla para evitar overhead en Flow si crece mucho
    fun getRecordedData(): List<SensorData> = _recordedData.value

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
        if (recording) {
            // Limpiar buffer al iniciar
            _recordedData.value = emptyList()
        }
    }

    fun updateSnapshot(snapshot: SensorSnapshot) {
        _currentSnapshot.value = snapshot
        
        // Si estamos grabando, añadir al buffer
        if (_isRecording.value) {
            val newData = snapshot.values.map {
                SensorData(
                    timestamp = snapshot.timestamp,
                    name = it.key.name,
                    value = it.value
                )
            }
            // Nota: Para alto rendimiento, esto podría optimizarse no usando StateFlow para la lista masiva
            // Pero para este caso de uso funcionará.
            val currentList = _recordedData.value.toMutableList()
            currentList.addAll(newData)
            _recordedData.value = currentList
        }
    }

    fun setDetectedMovement(movement: CustomMovement?) {
        _detectedMovement.value = movement
    }
    
    fun clearData() {
        _recordedData.value = emptyList()
    }
}