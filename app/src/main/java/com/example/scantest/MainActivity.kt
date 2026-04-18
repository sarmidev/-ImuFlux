package com.example.scantest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.scantest.ui.screen.ManufacturerOnboardingDialog
import com.example.scantest.ui.screen.SessionsScreen
import com.example.scantest.ui.screen.SimpleMovementMonitorScreen
import com.example.scantest.ui.viewmodel.SensorsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val sensorsViewModel: SensorsViewModel = hiltViewModel()

            CheckBatteryOptimizations()
            ManufacturerOnboardingDialog()
            RequestNotificationPermission()

            var currentScreen by rememberSaveable { mutableStateOf(Screen.MONITOR) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (currentScreen) {
                    Screen.MONITOR -> SimpleMovementMonitorScreen(
                        viewModel = sensorsViewModel,
                        onOpenSessions = { currentScreen = Screen.SESSIONS },
                    )
                    Screen.SESSIONS -> SessionsScreen(
                        onBack = { currentScreen = Screen.MONITOR },
                    )
                }
            }
        }
    }

    @Composable
    private fun RequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { /* no-op: la notificación es opcional para el usuario */ },
        )
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Composable
    private fun CheckBatteryOptimizations() {
        val context = this
        var showDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    showDialog = true
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Configuración necesaria") },
                text = {
                    Text(
                        "Para grabar en segundo plano sin cortes durante horas, " +
                            "necesitas desactivar la optimización de batería para esta app.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showDialog = false
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Configurar") }
                },
                dismissButton = {
                    Button(onClick = { showDialog = false }) { Text("Cancelar") }
                },
            )
        }
    }

    private enum class Screen { MONITOR, SESSIONS }
}
