package com.sarmidev.imuflux.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sarmidev.imuflux.data.analysis.InspectionResult
import com.sarmidev.imuflux.data.analysis.SessionInspector
import com.sarmidev.imuflux.data.storage.SessionFileManager
import com.sarmidev.imuflux.domain.model.SessionSummary
import com.sarmidev.imuflux.domain.repository.AnalysisRemoteRepository
import com.sarmidev.imuflux.domain.usecase.DeleteSessionUseCase
import com.sarmidev.imuflux.domain.usecase.ExportSessionUseCase
import com.sarmidev.imuflux.domain.usecase.ListSessionsUseCase
import com.sarmidev.imuflux.recording.RecordingEngine
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Analysis state
// ─────────────────────────────────────────────────────────────────────────────

sealed class SessionAnalysisState {
    object Idle : SessionAnalysisState()
    data class Running(val sessionId: String) : SessionAnalysisState()
    data class Done(val sessionId: String, val result: InspectionResult) : SessionAnalysisState()
    data class Error(val sessionId: String, val message: String) : SessionAnalysisState()
}

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val pendingExportSessionId: String? = null,
    val pendingExportFormat: ExportSessionUseCase.Format = ExportSessionUseCase.Format.ZIP,
    val errorMessage: String? = null,
    /** ID de la sesión que el engine está grabando en este momento, o null. */
    val liveSessionId: String? = null,
    /** Estado del análisis en curso (o del último análisis completado). */
    val analysisState: SessionAnalysisState = SessionAnalysisState.Idle,
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class SessionsViewModel @Inject constructor(
    application: Application,
    private val listSessionsUseCase: ListSessionsUseCase,
    private val exportSessionUseCase: ExportSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val recordingEngine: RecordingEngine,
    private val sessionFileManager: SessionFileManager,
    private val sessionInspector: SessionInspector,
    private val analysisRemoteRepository: Lazy<AnalysisRemoteRepository>,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
        // Observar qué sesión está grabándose en tiempo real.
        viewModelScope.launch {
            recordingEngine.currentSessionId.collect { liveId ->
                _uiState.update { it.copy(liveSessionId = liveId) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // Defensa en profundidad: si alguna sesión anterior se quedó con
            // `session.lock` abierto y el engine NO está grabando ahora,
            // la consideramos huérfana (kill OEM/LMK/thermal en sesión previa)
            // y la cerramos al vuelo para que la UI no muestre "incompleta".
            if (!recordingEngine.isRecording.value) {
                withContext(Dispatchers.IO) {
                    runCatching { sessionFileManager.closeOrphanedSessions() }
                        .onFailure { Log.w(TAG, "closeOrphanedSessions falló", it) }
                }
            }
            val sessions = runCatching { listSessionsUseCase() }
                .onFailure { Log.e(TAG, "listSessions falló", it) }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(sessions = sessions, isLoading = false) }
        }
    }

    fun requestExport(sessionId: String, format: ExportSessionUseCase.Format) {
        _uiState.update {
            it.copy(pendingExportSessionId = sessionId, pendingExportFormat = format)
        }
    }

    fun onExportDestinationPicked(uri: Uri) {
        val state = _uiState.value
        val sessionId = state.pendingExportSessionId ?: return
        viewModelScope.launch {
            val format = state.pendingExportFormat
            val ctx = getApplication<Application>().applicationContext
            runCatching { exportSessionUseCase(sessionId, uri.toString(), format) }
                .onSuccess { bytes ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ctx,
                            "Exportado ${formatMB(bytes)}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .onFailure {
                    Log.e(TAG, "export falló", it)
                    _uiState.update { s -> s.copy(errorMessage = it.message) }
                }
            _uiState.update { it.copy(pendingExportSessionId = null) }
        }
    }

    fun cancelExport() {
        _uiState.update { it.copy(pendingExportSessionId = null) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            runCatching { deleteSessionUseCase(sessionId) }
                .onFailure { Log.e(TAG, "delete falló", it) }
            refresh()
        }
    }

    // ── Session analysis ──────────────────────────────────────────────────────

    /**
     * Lanza el análisis combinado (timing + calidad de datos) sobre la sesión
     * indicada. El resultado se publica en [uiState].analysisState.
     * Llámalo desde el hilo principal; la lógica pesada corre en IO.
     */
    fun analyzeSession(sessionId: String) {
        if (_uiState.value.analysisState is SessionAnalysisState.Running) return
        _uiState.update { it.copy(analysisState = SessionAnalysisState.Running(sessionId)) }
        viewModelScope.launch {
            val analysisResult = withContext(Dispatchers.IO) {
                runCatching { sessionInspector.inspect(sessionId) }
            }
            _uiState.update { state ->
                state.copy(
                    analysisState = analysisResult.fold(
                        onSuccess = { SessionAnalysisState.Done(sessionId, it) },
                        onFailure = { SessionAnalysisState.Error(sessionId, it.message ?: "Error desconocido") },
                    ),
                )
            }
            analysisResult.onSuccess { inspectionResult ->
                uploadAnalysis(sessionId, inspectionResult)
            }
        }
    }

    private fun uploadAnalysis(sessionId: String, result: InspectionResult) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val metadata = sessionFileManager.readMetadata(sessionId)
                    ?: error("metadata.json no existe para $sessionId")
                analysisRemoteRepository.get().uploadAnalysis(metadata, result)
            }.onFailure {
                Log.w(TAG, "Upload a Firestore falló (no bloquea UI)", it)
            }
        }
    }

    /** Cierra el panel de resultados del análisis. */
    fun dismissAnalysis() {
        _uiState.update { it.copy(analysisState = SessionAnalysisState.Idle) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun formatMB(bytes: Long): String =
        "%.2f MB".format(bytes / (1024.0 * 1024.0))

    companion object {
        private const val TAG = "SessionsViewModel"
    }
}
