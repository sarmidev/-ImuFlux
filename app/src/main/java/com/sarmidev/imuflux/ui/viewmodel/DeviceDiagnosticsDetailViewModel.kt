package com.sarmidev.imuflux.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsTelemetryRepository
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceDiagnosticsDetailUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val deviceId: String = "",
    val summary: DeviceHealthSummary? = null,
    val recentSessions: List<ImuSessionDiagnostics> = emptyList(),
    val recentWindows: List<ImuHealthWindow> = emptyList(),
)

@HiltViewModel
class DeviceDiagnosticsDetailViewModel @Inject constructor(
    private val repository: DiagnosticsTelemetryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceDiagnosticsDetailUiState())
    val uiState: StateFlow<DeviceDiagnosticsDetailUiState> = _uiState.asStateFlow()

    fun load(deviceId: String) {
        _uiState.update { it.copy(isLoading = true, error = null, deviceId = deviceId) }
        viewModelScope.launch {
            runCatching {
                val summary = async { repository.fetchDevice(deviceId) }
                val sessions = async { repository.fetchRecentSessions(deviceId) }
                val windows = async { repository.fetchRecentHealthWindows(deviceId) }
                awaitAll(summary, sessions, windows)
                Triple(summary.await(), sessions.await(), windows.await())
            }.onSuccess { (summary, sessions, windows) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        summary = summary,
                        recentSessions = sessions,
                        recentWindows = windows,
                    )
                }
            }.onFailure { t ->
                _uiState.update { it.copy(isLoading = false, error = t.message ?: "Error loading device") }
            }
        }
    }
}
