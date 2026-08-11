package com.sarmidev.imuflux.backoffice

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.sarmidev.imuflux.backoffice.state.DiagnosticsBackofficeViewModel
import com.sarmidev.imuflux.backoffice.ui.ConfigErrorScreen
import com.sarmidev.imuflux.backoffice.ui.DashboardScreen
import com.sarmidev.imuflux.backoffice.ui.DeviceDetailScreen
import com.sarmidev.imuflux.backoffice.ui.LoginScreen

@Composable
fun BackofficeApp() {
    val viewModel = remember { DiagnosticsBackofficeViewModel() }
    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    val appState by viewModel.appState.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    val detail by viewModel.detail.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                appState.configError != null -> ConfigErrorScreen(appState.configError!!)

                !appState.isAuthenticated -> LoginScreen(
                    isLoggingIn = appState.isLoggingIn,
                    error = appState.loginError,
                    configSource = viewModel.configSource,
                    onLogin = viewModel::login,
                )

                detail != null -> DeviceDetailScreen(
                    state = detail!!,
                    onBack = viewModel::clearSelection,
                    onRefresh = viewModel::refreshDetail,
                    onStartRecording = viewModel::startRecording,
                    onStopRecording = viewModel::stopRecording,
                )

                else -> DashboardScreen(
                    adminEmail = appState.adminEmail,
                    state = dashboard,
                    onRefresh = viewModel::refreshDevices,
                    onLogout = viewModel::logout,
                    onSelectDevice = viewModel::selectDevice,
                    onWarehouseFilter = viewModel::setWarehouseFilter,
                    onForkliftFilter = viewModel::setForkliftFilter,
                    onHealthFilter = viewModel::setHealthStatusFilter,
                    onTextQuery = viewModel::setTextQuery,
                    onClearFilters = viewModel::clearFilters,
                )
            }
        }
    }
}
