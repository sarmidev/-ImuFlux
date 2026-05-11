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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.scantest.ui.screen.CalibrationScreen
import com.example.scantest.ui.screen.CompatibilityTestScreen
import com.example.scantest.ui.screen.DialogActionButton
import com.example.scantest.ui.screen.LocalImuFluxColors
import com.example.scantest.ui.screen.ManufacturerOnboardingDialog
import com.example.scantest.ui.screen.SessionsScreen
import com.example.scantest.ui.screen.SimpleMovementMonitorScreen
import com.example.scantest.ui.screen.darkColors
import com.example.scantest.ui.screen.lightColors
import com.example.scantest.ui.viewmodel.SensorsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val sensorsViewModel: SensorsViewModel = hiltViewModel()

            RequestNotificationPermission()

            val prefs = remember {
                getSharedPreferences("imuflux_prefs", Context.MODE_PRIVATE)
            }
            var isDark by rememberSaveable {
                mutableStateOf(prefs.getBoolean("dark_mode", true))
            }
            val toggleTheme: () -> Unit = {
                isDark = !isDark
                prefs.edit().putBoolean("dark_mode", isDark).apply()
            }

            var currentScreen by rememberSaveable { mutableStateOf(Screen.MONITOR) }

            CompositionLocalProvider(
                LocalImuFluxColors provides if (isDark) darkColors() else lightColors(),
            ) {
                // Dialogs inside the provider so they receive the themed colors
                CheckBatteryOptimizations()
                ManufacturerOnboardingDialog()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (currentScreen) {
                        Screen.MONITOR -> SimpleMovementMonitorScreen(
                            viewModel = sensorsViewModel,
                            onOpenSessions = { currentScreen = Screen.SESSIONS },
                            onToggleTheme = toggleTheme,
                            onOpenCalibration = { currentScreen = Screen.CALIBRATION },
                            onOpenCompatibilityTest = { currentScreen = Screen.COMPATIBILITY_TEST },
                        )
                        Screen.SESSIONS -> SessionsScreen(
                            onBack = { currentScreen = Screen.MONITOR },
                            onToggleTheme = toggleTheme,
                        )
                        Screen.CALIBRATION -> CalibrationScreen(
                            viewModel = sensorsViewModel,
                            onBack = { currentScreen = Screen.MONITOR },
                            onToggleTheme = toggleTheme,
                        )
                        Screen.COMPATIBILITY_TEST -> CompatibilityTestScreen(
                            onBack = { currentScreen = Screen.MONITOR },
                            onToggleTheme = toggleTheme,
                        )
                    }
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
        var showDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    showDialog = true
                }
            }
        }

        if (!showDialog) return

        val c = LocalImuFluxColors.current
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.bgSurface)
                    .border(1.dp, c.bgCardBorder, RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp, vertical = 22.dp),
            ) {
                Column {
                    // Badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(3.dp, 14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c.accentCyan),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "PERMISO REQUERIDO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp,
                            color = c.accentCyan,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Ignorar optimización\nde batería",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = c.textPrimary,
                        lineHeight = 23.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Para grabar IMU de forma continua con la pantalla apagada, " +
                            "el sistema debe permitir que la app se ejecute en segundo plano " +
                            "sin restricciones.",
                        fontSize = 12.sp,
                        color = c.textSecondary,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.bgCard)
                            .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "⚡", fontSize = 16.sp, color = c.accentAmber)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Sin este permiso, el sistema puede detener la\n" +
                                    "grabación a los pocos minutos.",
                                fontSize = 11.sp,
                                color = c.textPrimary,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DialogActionButton(
                            label = "Cancelar",
                            filled = false,
                            c = c,
                            onClick = { showDialog = false },
                        )
                        Spacer(Modifier.width(10.dp))
                        DialogActionButton(
                            label = "Configurar",
                            filled = true,
                            c = c,
                            onClick = {
                                showDialog = false
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                try {
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private enum class Screen { MONITOR, SESSIONS, CALIBRATION, COMPATIBILITY_TEST }
}
