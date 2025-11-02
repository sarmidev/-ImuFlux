package com.example.scantest.ui.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scantest.domain.ScanData // Asegúrate que NO tenga 'distance'
import com.example.scantest.estimateDistance
import com.example.scantest.ui.model.ScanUiState // Asegúrate que tenga 'totalScans' y NO 'distance'
import com.scandit.datacapture.barcode.batch.capture.BarcodeBatch
import com.scandit.datacapture.barcode.batch.capture.BarcodeBatchListener
import com.scandit.datacapture.barcode.batch.capture.BarcodeBatchSession
import com.scandit.datacapture.barcode.batch.capture.BarcodeBatchSettings
import com.scandit.datacapture.barcode.data.Symbology
import com.scandit.datacapture.core.capture.DataCaptureContext
import com.scandit.datacapture.core.data.FrameData
import com.scandit.datacapture.core.source.Camera
import com.scandit.datacapture.core.source.FrameSourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException


// Mueve la licencia aquí

private const val LICENSE_KEY ="ArNG7hMcNA0DDUO1iOBZBcgxqBjlRGXpxQZVlBpAqtjPRYghug3I9OtJOMMDH/N2a0wwGqJ5GtcpV281FUxMx/xF0CiLWQ3NSHTtamgExnJLHSsfhh/2no0Ibor6XBs5wyB4qT9n2PesLcuU9FdP1ZBK8I5SAad3eGl3UCByqH7dU7c6dX4xW10ryMwYdnVj6h4qoPVcFJ9Jdiku8HfJ8uAg9VfoTMOpcnAs1Khaaia+Tw4yVnGO1RYpEiWYQZorYFmD+OJJaMcHBbfWxEkE23thdxajF5Rsmyh72AJunyxEZ96EGlGXHlII1nBxT+bH+EzwqFB6kOkLH27cwRQRVuVuLS05SoV7o3IRGpZYHuXodFN8I2Zjwyh2vxfHQqcZ4GGg9Hp6SMklESRDlXONx5ZiVwgxBSe7KVD8wrVqrMIGQ4+UinP5N0JtgKmoWR1kBAWlUwZlI8CNfWwJqFDRjHJ0oNYGQ4XNo153WjdzJOSKZ/uzZ32n+ytMtRwAfJ7TX23Oh0kVCmdmGF3GtFMzU9dwnPvuN11LsEyik9d917OyR9GoIXK3jrI+77dYTKSC1RRYLxlaVMGMZRXkgn8Sr4lynT2sagVrY2blHipG521zWQf+yVfPaFsNbn7USusPTVAKlatSxF1SDaD1eVkAgzlyDItUX02Y8k3Kj0hkyZMhQAAIgWN/ipIt82ONT2jhMEGw3nlg80QsS65Pi0ruQaJM6F1TUfonIz1GQMtaOluWeEQWEEf674tY2ul6e+m6xmZMyw0KheT1aI3OB01eUmo0CZEHWwvkcmOv3tl2WKIiexEgVn7dGs1KQpVfbqoMRFLIfN1/YNuEWxsVlFRwP7pq2EhkZyXZzV6g3TBN4XAucZJBgFSW8ABdX4ASfj/GlCC8FB1zBpkRRc32Xh5sEUJT2XhWZYdbHHDybI90nJZYQV6Mqwg02bgC/mnqVg1TEGqsl01E6pXDeG5rY1cOiNxytSTsQAZQ2+B+N0N6k7jhdzlUy0rOLO43H2a84vFBPzQWPc4okSH2C1KS00cIA49fzUYJjInilF8giLMVn7ce6WZI2HZalwpAlMYWl6ItqZy5Ie10dJcCBxhnMmANbjekQxzLy9ysP5bw4mtWozUWwooA4IhGPcEAKd5A1KppNnOQjkZEvz1hKCix30DB5g0oE3347tvvC0TsonC//kl6bNRkFJg3bsa7SfDCVJmEHkDww+SvTzXnt/FvOZQQ/P6Faaq1xdjzE0kogBu9kFEj7SwuXi+g3+atbKgTVuniOIEW3Ac+nW0qLTU87xS8cnLU6WOujF0WcmmIQI2hg9pzfIF8pEUJcj9ykeYQMezBJREmFoTNAYVx7hqqhnaq0n6RHXtdIF//vQc8ldnDX1nFjBpUj1tTiDsTqN5/bjt8OMNevBzF2WuenEPuJto4TBMN806owxm49a1WLVZXdrs0lykBAXfwd4RvewSVWNpqVOJ/FU98pfTGoE2TdueBjXG6e3bDkm58xzu7BgLVDjS8Gx5Hfj6jZ1EPqOXU8bS0kPOCLPEdrlypgAJT7jHF4fuRQpRS/p+YPJKoqPOa0gkw3qOzdcr9lDwzB8GzfYSsWGvWwM04L36hhjMxyiGoo5zymqVQ2gQCfsVh+IvQ2wCBc6+7sYAnt47GW95uoSbOqIuWh2Jx"

class ScanViewModel(application: Application) : AndroidViewModel(application), BarcodeBatchListener {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val recordedScans = mutableListOf<ScanData>()

    val dataCaptureContext: DataCaptureContext =
        DataCaptureContext.forLicenseKey(LICENSE_KEY)
    private var camera: Camera? = Camera.getDefaultCamera()

    // 1. Usar BarcodeBatch
    val barcodeBatch: BarcodeBatch

    init {
        // 2. Usar BarcodeBatchSettings
        val settings = BarcodeBatchSettings()
        settings.enableSymbologies(
            setOf(
                Symbology.EAN13_UPCA,
                Symbology.EAN8,
                Symbology.UPCE,
                Symbology.CODE39,
                Symbology.CODE128,
                Symbology.ARUCO,
                Symbology.QR
            )
        )

        barcodeBatch = BarcodeBatch.forDataCaptureContext(dataCaptureContext, settings)
        barcodeBatch.addListener(this)
        settings.expectsOnlyUniqueBarcodes = false
        camera?.let {
            dataCaptureContext.setFrameSource(it)
        }

        barcodeBatch.isEnabled = false
    }


    fun startCamera() {
        if (_uiState.value.permissionGranted && camera?.currentState != FrameSourceState.ON) {
            camera?.switchToDesiredState(FrameSourceState.ON)
            barcodeBatch.isEnabled = true // Habilitar el modo
        }

    }

    fun stopCamera() {
        barcodeBatch.isEnabled = false // Deshabilitar el modo
        camera?.switchToDesiredState(FrameSourceState.OFF)
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
        if (granted) {
            startCamera()
        } else {
            Log.w("ViewModel", "Permiso de cámara denegado")
        }
    }

    fun onStartStopClick() {
        val isCurrentlyRecording = _uiState.value.isRecording
        if (isCurrentlyRecording) {
            _uiState.update { it.copy(isRecording = false, showSaveDialog = true) }
        } else {
            synchronized(recordedScans) {
                recordedScans.clear()
            }
            _uiState.update { it.copy(isRecording = true, lastScan = "N/A") }
        }
    }

    fun onDismissSaveDialog() {
        _uiState.update { it.copy(showSaveDialog = false) }
        synchronized(recordedScans) {
            recordedScans.clear()
        }
    }

    fun onSaveScanData(filename: String) {
        if (recordedScans.isEmpty()) {
            Log.w("ViewModel", "No hay datos que guardar.")
            _uiState.update { it.copy(showSaveDialog = false) }
            return
        }

        val scansToSave = synchronized(recordedScans) {
            recordedScans.toList()
        }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            try {
                val safeFilename = filename.ifBlank { "scans_${System.currentTimeMillis()}.csv" }
                val file = File(context.filesDir, safeFilename)

                file.bufferedWriter().use { out ->
                    // Encabezado del CSV (sin distancia)
                    out.write("timestamp,symbology,data, distance\n")
                    scansToSave.forEach { scan ->
                        val escapedData = "\"${scan.data.replace("\"", "\"\"")}\""
                        // Escritura (sin distancia)
                        out.write("${scan.timestamp},${scan.symbology},${escapedData},${scan.distance},\n")
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
                synchronized(recordedScans) {
                    recordedScans.clear()
                }
            }
        }
    }

    // 6. Callback del BarcodeBatchListener
    override fun onSessionUpdated(
        mode: BarcodeBatch,
        session: BarcodeBatchSession,
        data: FrameData
    ) {
        super.onSessionUpdated(mode, session, data)

        val newScans = mutableListOf<ScanData>()
        var latestData: String?
        session.trackedBarcodes
        // Iterar sobre los códigos escaneados en esta sesión (lote)
        session.trackedBarcodes.forEach { scannedBarcode ->
            val barcodeData = scannedBarcode.value.barcode
            val symbology = scannedBarcode.value.barcode.symbology.name
            val timestamp = System.currentTimeMillis()

            val distance = estimateDistance( scannedBarcode.value.location, 0.1f)
            newScans.add(ScanData(barcodeData.data ?: "", symbology, timestamp, distance = distance))
            latestData = barcodeData.data
            // Actualizar la UI
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        lastScan = latestData ?: it.lastScan,
                        distance = distance
                    )
                }
            }
        }

        // Solo procesar si estamos grabando
        if (!_uiState.value.isRecording) return
        if (newScans.isNotEmpty()) {
            // Añadir los nuevos escaneos a la lista principal
            synchronized(recordedScans) {
                recordedScans.addAll(newScans)
            }

        }
    }


    override fun onCleared() {
        stopCamera()
        barcodeBatch.removeListener(this) // Limpiar el listener
        super.onCleared()
    }
}