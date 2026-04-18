package com.example.scantest.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scantest.domain.model.SessionSummary
import com.example.scantest.domain.usecase.DeleteSessionUseCase
import com.example.scantest.domain.usecase.ExportSessionUseCase
import com.example.scantest.domain.usecase.ListSessionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val pendingExportSessionId: String? = null,
    val pendingExportFormat: ExportSessionUseCase.Format = ExportSessionUseCase.Format.ZIP,
    val errorMessage: String? = null,
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    application: Application,
    private val listSessionsUseCase: ListSessionsUseCase,
    private val exportSessionUseCase: ExportSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
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

    private fun formatMB(bytes: Long): String =
        "%.2f MB".format(bytes / (1024.0 * 1024.0))

    companion object {
        private const val TAG = "SessionsViewModel"
    }
}
