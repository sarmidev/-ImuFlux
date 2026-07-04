package com.sarmidev.imuflux.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sarmidev.imuflux.desktop.state.DiagnosticsDesktopViewModel
import com.sarmidev.imuflux.desktop.ui.ConfigErrorScreen
import com.sarmidev.imuflux.desktop.ui.DashboardScreen
import com.sarmidev.imuflux.desktop.ui.DeviceDetailScreen
import com.sarmidev.imuflux.desktop.ui.LoginScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ImuFlux Diagnostics",
        state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
    ) {
        DiagnosticsApp()
    }
}

@Composable
fun DiagnosticsApp() {
    val viewModel = remember { DiagnosticsDesktopViewModel() }
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
