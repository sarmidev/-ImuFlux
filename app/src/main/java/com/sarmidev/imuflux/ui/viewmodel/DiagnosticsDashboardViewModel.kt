package com.sarmidev.imuflux.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsFilter
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsFilters
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthEvaluator
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsLogger
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsTelemetryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsDashboardUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val allDevices: List<DeviceHealthSummary> = emptyList(),
    val filtered: List<DeviceHealthSummary> = emptyList(),
    val filters: DiagnosticsFilters = DiagnosticsFilters(),
    val warehouseOptions: List<String> = emptyList(),
    val forkliftOptions: List<String> = emptyList(),
    val appVersionOptions: List<String> = emptyList(),
)

@HiltViewModel
class DiagnosticsDashboardViewModel @Inject constructor(
    private val repository: DiagnosticsTelemetryRepository,
    private val evaluator: DiagnosticsHealthEvaluator,
    private val logger: DiagnosticsLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsDashboardUiState())
    val uiState: StateFlow<DiagnosticsDashboardUiState> = _uiState.asStateFlow()

    init {
        logger.dashboardOpened()
        load()
    }

    fun refresh() = load()

    fun setWarehouse(value: String?) = updateFilters { it.copy(warehouseId = value) }
    fun setForklift(value: String?) = updateFilters { it.copy(forkliftId = value) }
    fun setDevice(value: String?) = updateFilters { it.copy(deviceId = value) }
    fun setAppVersion(value: String?) = updateFilters { it.copy(appVersion = value) }
    fun setHealthStatus(value: DiagnosticsHealthStatus?) = updateFilters { it.copy(healthStatus = value) }
    fun setSeenSince(ms: Long?) = updateFilters { it.copy(seenSinceMs = ms) }
    fun clearFilters() = updateFilters { DiagnosticsFilters() }

    private fun updateFilters(transform: (DiagnosticsFilters) -> DiagnosticsFilters) {
        _uiState.update { state ->
            val newFilters = transform(state.filters)
            state.copy(filters = newFilters, filtered = applyFilters(state.allDevices, newFilters))
        }
    }

    private fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                logger.traceSuspend(DiagnosticsLogger.TRACE_DASHBOARD_LOAD) { repository.fetchDevices() }
            }
                .onSuccess { devices ->
                    val escalated = devices.map(::escalateStaleness)
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            allDevices = escalated,
                            filtered = applyFilters(escalated, state.filters),
                            warehouseOptions = escalated.mapNotNull { it.warehouseId.ifBlank { null } }.distinct().sorted(),
                            forkliftOptions = escalated.mapNotNull { it.forkliftId.ifBlank { null } }.distinct().sorted(),
                            appVersionOptions = escalated.mapNotNull { it.appVersion.ifBlank { null } }.distinct().sorted(),
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.update { it.copy(isLoading = false, error = t.message ?: "Error loading diagnostics") }
                }
        }
    }

    /**
     * Re-derives status from staleness so a device that stopped reporting
     * degrades. Applies even when `isRecording == true`: a live recorder writes
     * a health window every ~60 s, so a "recording" device that hasn't checked
     * in for many minutes was almost certainly killed by the OS.
     */
    private fun escalateStaleness(device: DeviceHealthSummary): DeviceHealthSummary {
        val ageMs = System.currentTimeMillis() - device.lastSeenAt
        val result = evaluator.escalateForStaleness(device.currentHealthStatus, ageMs)
        if (result.status == device.currentHealthStatus && result.reasons.isEmpty()) return device
        val reasons = if (result.reasons.isEmpty()) device.currentHealthReasons else result.reasons
        return device.copy(currentHealthStatus = result.status, currentHealthReasons = reasons)
    }

    private fun applyFilters(
        devices: List<DeviceHealthSummary>,
        filters: DiagnosticsFilters,
    ): List<DeviceHealthSummary> = DiagnosticsFilter.apply(devices, filters)
        .sortedWith(
            compareByDescending<DeviceHealthSummary> { it.currentHealthStatus.ordinal }
                .thenByDescending { it.lastSeenAt },
        )
}
