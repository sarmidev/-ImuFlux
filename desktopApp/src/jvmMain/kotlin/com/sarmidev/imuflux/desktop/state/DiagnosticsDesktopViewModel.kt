package com.sarmidev.imuflux.desktop.state

import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsConfig
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsFilter
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsFilters
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthEvaluator
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics
import com.sarmidev.imuflux.desktop.auth.FirebaseAuthClient
import com.sarmidev.imuflux.desktop.config.DesktopFirebaseConfigLoader
import com.sarmidev.imuflux.desktop.data.FirestoreDesktopDiagnosticsRepository
import com.sarmidev.imuflux.desktop.data.RemoteCommandClient
import com.sarmidev.imuflux.desktop.data.RemoteCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val configError: String? = null,
    val isAuthenticated: Boolean = false,
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val adminEmail: String = "",
)

data class DashboardUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val allDevices: List<DeviceHealthSummary> = emptyList(),
    val filtered: List<DeviceHealthSummary> = emptyList(),
    val filters: DiagnosticsFilters = DiagnosticsFilters(),
    val textQuery: String = "",
    val warehouseOptions: List<String> = emptyList(),
    val forkliftOptions: List<String> = emptyList(),
)

data class DetailUiState(
    val deviceId: String,
    val isLoading: Boolean = false,
    val error: String? = null,
    val summary: DeviceHealthSummary? = null,
    val recentSessions: List<ImuSessionDiagnostics> = emptyList(),
    val recentWindows: List<ImuHealthWindow> = emptyList(),
    /** A remote Start/Stop command is in flight for this device. */
    val commandInProgress: Boolean = false,
    /** Last remote-command feedback (success hint or error message). */
    val commandFeedback: String? = null,
    val commandFeedbackIsError: Boolean = false,
) {
    /** Remote control is offered only when the device published a usable FCM token. */
    val remoteControlAvailable: Boolean get() = summary?.hasRemoteControl == true
}

/**
 * Plain (non-Android) state holder for the desktop diagnostics app.
 *
 * Uses a private [CoroutineScope] + [MutableStateFlow]s instead of Android
 * ViewModel/Hilt. Reuses the pure `:shared` logic ([DiagnosticsFilter],
 * [DiagnosticsHealthEvaluator]) so staleness escalation and filtering behave
 * identically to the mobile dashboard.
 */
class DiagnosticsDesktopViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val configResult = DesktopFirebaseConfigLoader.load()
    private val config = (configResult as? DesktopFirebaseConfigLoader.Result.Loaded)?.config

    private val sessionManager = config?.let { SessionManager(FirebaseAuthClient(it)) }
    private val repository = if (config != null && sessionManager != null) {
        FirestoreDesktopDiagnosticsRepository(config, sessionManager::validToken)
    } else {
        null
    }

    private val remoteCommandClient = if (config != null && sessionManager != null) {
        RemoteCommandClient(config, sessionManager::validToken)
    } else {
        null
    }

    private val evaluator = DiagnosticsHealthEvaluator(DiagnosticsConfig.DEFAULT)

    private val _appState = MutableStateFlow(
        AppUiState(
            configError = (configResult as? DesktopFirebaseConfigLoader.Result.Missing)?.message,
        ),
    )
    val appState: StateFlow<AppUiState> = _appState.asStateFlow()

    private val _dashboard = MutableStateFlow(DashboardUiState())
    val dashboard: StateFlow<DashboardUiState> = _dashboard.asStateFlow()

    private val _detail = MutableStateFlow<DetailUiState?>(null)
    val detail: StateFlow<DetailUiState?> = _detail.asStateFlow()

    /** Where the Firebase config was resolved from (for a subtle UI hint). */
    val configSource: String? get() = config?.source

    // ── Auth ────────────────────────────────────────────────────────────────

    fun login(email: String, password: String) {
        val manager = sessionManager ?: return
        if (_appState.value.isLoggingIn) return
        if (email.isBlank() || password.isBlank()) {
            _appState.update { it.copy(loginError = "Introduce email y contraseña.") }
            return
        }
        _appState.update { it.copy(isLoggingIn = true, loginError = null) }
        scope.launch {
            runCatching { manager.signIn(email, password) }
                .onSuccess { session ->
                    _appState.update {
                        it.copy(isAuthenticated = true, isLoggingIn = false, adminEmail = session.email)
                    }
                    refreshDevices()
                }
                .onFailure { t ->
                    _appState.update {
                        it.copy(isLoggingIn = false, loginError = t.message ?: "Fallo de autenticación.")
                    }
                }
        }
    }

    fun logout() {
        sessionManager?.signOut()
        _dashboard.value = DashboardUiState()
        _detail.value = null
        _appState.update {
            it.copy(isAuthenticated = false, adminEmail = "", loginError = null)
        }
    }

    // ── Dashboard ─────────────────────────────────────────────────────────

    fun refreshDevices() {
        val repo = repository ?: return
        if (_dashboard.value.isLoading) return
        _dashboard.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            runCatching { repo.fetchDevices() }
                .onSuccess { devices ->
                    val escalated = devices.map(::escalateStaleness)
                    _dashboard.update { state ->
                        state.copy(
                            isLoading = false,
                            allDevices = escalated,
                            filtered = applyFilters(escalated, state.filters, state.textQuery),
                            warehouseOptions = escalated.mapNotNull { it.warehouseId.ifBlank { null } }
                                .distinct().sorted(),
                            forkliftOptions = escalated.mapNotNull { it.forkliftId.ifBlank { null } }
                                .distinct().sorted(),
                        )
                    }
                }
                .onFailure { t ->
                    _dashboard.update {
                        it.copy(isLoading = false, error = t.message ?: "Error al cargar dispositivos.")
                    }
                }
        }
    }

    fun setWarehouseFilter(value: String?) = updateFilters { it.copy(warehouseId = value) }
    fun setForkliftFilter(value: String?) = updateFilters { it.copy(forkliftId = value) }
    fun setHealthStatusFilter(value: DiagnosticsHealthStatus?) = updateFilters { it.copy(healthStatus = value) }

    fun setTextQuery(query: String) {
        _dashboard.update { state ->
            state.copy(textQuery = query, filtered = applyFilters(state.allDevices, state.filters, query))
        }
    }

    fun clearFilters() {
        _dashboard.update { state ->
            state.copy(
                filters = DiagnosticsFilters(),
                textQuery = "",
                filtered = applyFilters(state.allDevices, DiagnosticsFilters(), ""),
            )
        }
    }

    private fun updateFilters(transform: (DiagnosticsFilters) -> DiagnosticsFilters) {
        _dashboard.update { state ->
            val newFilters = transform(state.filters)
            state.copy(filters = newFilters, filtered = applyFilters(state.allDevices, newFilters, state.textQuery))
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────

    fun selectDevice(deviceId: String) {
        _detail.value = DetailUiState(deviceId = deviceId, isLoading = true)
        refreshDetail()
    }

    fun clearSelection() {
        _detail.value = null
    }

    fun refreshDetail() {
        val repo = repository ?: return
        val deviceId = _detail.value?.deviceId ?: return
        _detail.update { it?.copy(isLoading = true, error = null) }
        scope.launch {
            runCatching {
                val summary = async { repo.fetchDevice(deviceId) }
                val sessions = async { repo.fetchRecentSessions(deviceId) }
                val windows = async { repo.fetchRecentHealthWindows(deviceId) }
                awaitAll(summary, sessions, windows)
                Triple(summary.await(), sessions.await(), windows.await())
            }.onSuccess { (summary, sessions, windows) ->
                _detail.update {
                    it?.copy(
                        isLoading = false,
                        summary = summary?.let(::escalateStaleness),
                        recentSessions = sessions,
                        recentWindows = windows,
                    )
                }
            }.onFailure { t ->
                _detail.update { it?.copy(isLoading = false, error = t.message ?: "Error al cargar el detalle.") }
            }
        }
    }

    // ── Remote control ──────────────────────────────────────────────────────

    fun startRecording() = sendRemoteCommand { client, deviceId -> client.startRecording(deviceId) }

    fun stopRecording() = sendRemoteCommand { client, deviceId -> client.stopRecording(deviceId) }

    /**
     * Sends a remote command, then re-reads the device so the operator sees the
     * **real** `isRecording` from Firestore rather than assuming success from the
     * FCM response. The Cloud Function only confirms delivery, not execution.
     */
    private fun sendRemoteCommand(action: suspend (RemoteCommandClient, String) -> RemoteCommandResult) {
        val client = remoteCommandClient ?: return
        val current = _detail.value ?: return
        if (current.commandInProgress) return
        _detail.update { it?.copy(commandInProgress = true, commandFeedback = null, commandFeedbackIsError = false) }
        scope.launch {
            runCatching { action(client, current.deviceId) }
                .onSuccess { result ->
                    _detail.update {
                        it?.copy(
                            commandInProgress = false,
                            commandFeedback = if (result.success) {
                                "Comando ${result.command.wire} enviado. Confirmando estado…"
                            } else {
                                result.error ?: "El backend rechazó el comando."
                            },
                            commandFeedbackIsError = !result.success,
                        )
                    }
                    if (result.success) {
                        // Give the device + Firestore a moment to reflect the change.
                        delay(REFRESH_AFTER_COMMAND_MS)
                        refreshDetail()
                    }
                }
                .onFailure { t ->
                    _detail.update {
                        it?.copy(
                            commandInProgress = false,
                            commandFeedback = t.message ?: "Error al enviar el comando.",
                            commandFeedbackIsError = true,
                        )
                    }
                }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Re-derives health status from staleness: a device that stopped writing
     * `lastSeenAt` degrades to WARNING/ERROR even when `isRecording == true`,
     * because a live recorder flushes a health window roughly every 60 s.
     */
    private fun escalateStaleness(device: DeviceHealthSummary): DeviceHealthSummary {
        val ageMs = nowMs() - device.lastSeenAt
        val result = evaluator.escalateForStaleness(device.currentHealthStatus, ageMs)
        if (result.status == device.currentHealthStatus && result.reasons.isEmpty()) return device
        val reasons = if (result.reasons.isEmpty()) device.currentHealthReasons else result.reasons
        return device.copy(currentHealthStatus = result.status, currentHealthReasons = reasons)
    }

    private fun applyFilters(
        devices: List<DeviceHealthSummary>,
        filters: DiagnosticsFilters,
        textQuery: String,
    ): List<DeviceHealthSummary> {
        val query = textQuery.trim()
        return DiagnosticsFilter.apply(devices, filters)
            .filter { device -> query.isEmpty() || device.matchesText(query) }
            .sortedWith(
                compareByDescending<DeviceHealthSummary> { it.currentHealthStatus.ordinal }
                    .thenByDescending { it.lastSeenAt },
            )
    }

    private fun DeviceHealthSummary.matchesText(query: String): Boolean =
        deviceId.contains(query, ignoreCase = true) ||
            deviceModel.contains(query, ignoreCase = true) ||
            manufacturer.contains(query, ignoreCase = true) ||
            forkliftModel.contains(query, ignoreCase = true)

    private companion object {
        /** Delay before re-reading a device after a successful command dispatch. */
        const val REFRESH_AFTER_COMMAND_MS = 4_000L
    }
}
