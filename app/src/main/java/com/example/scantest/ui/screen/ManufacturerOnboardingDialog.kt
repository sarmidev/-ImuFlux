package com.example.scantest.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Muestra una única vez por instalación un diálogo con instrucciones específicas
 * del fabricante del dispositivo para garantizar que la grabación en background
 * no sea interrumpida por capas de ahorro energía propietarias.
 *
 * La detección es best-effort — si el fabricante no está reconocido se muestra
 * un mensaje genérico. El usuario puede aceptar ("Ir a ajustes") o posponer.
 */
@Composable
fun ManufacturerOnboardingDialog(
    preferenceKey: String = PREF_KEY,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("imuflux_onboarding", android.content.Context.MODE_PRIVATE)
    }
    var dismissed by rememberSaveable { mutableStateOf(prefs.getBoolean(preferenceKey, false)) }

    LaunchedEffect(dismissed) {
        if (dismissed) prefs.edit().putBoolean(preferenceKey, true).apply()
    }

    if (dismissed) return

    val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
    val instructions = instructionsFor(manufacturer)

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Preparar el dispositivo para jornadas largas") },
        text = {
            Text(
                text = instructions,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = {
                dismissed = true
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:" + context.packageName)
                    }
                    context.startActivity(intent)
                }.onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }) { Text("Ir a ajustes") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("Más tarde") }
        },
    )
}

private fun instructionsFor(manufacturer: String): String = when {
    manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
        "Para grabar sin cortes en MIUI:\n" +
            "1. Ajustes → Apps → ImuFlux → Ahorro de batería → Sin restricciones.\n" +
            "2. Ajustes → Apps → ImuFlux → Autostart (Inicio automático) → Activar.\n" +
            "3. En la pantalla Reciente, mantén pulsada la tarjeta de la app → icono candado (bloqueo MIUI).\n" +
            "4. Acepta el diálogo de Ignorar optimización de batería."

    manufacturer.contains("huawei") || manufacturer.contains("honor") ->
        "Para grabar sin cortes en EMUI/HarmonyOS:\n" +
            "1. Ajustes → Apps → Inicio de apps → ImuFlux → Gestionar manualmente.\n" +
            "2. Activa Autoarranque, Inicio secundario y Ejecutar en segundo plano.\n" +
            "3. Ajustes → Batería → Lanzamiento de apps → Desactiva optimizaciones para ImuFlux."

    manufacturer.contains("samsung") ->
        "Para grabar sin cortes en One UI:\n" +
            "1. Ajustes → Mantenimiento del dispositivo → Batería → Límites de uso en segundo plano → Apps que no se duermen → Añade ImuFlux.\n" +
            "2. Acepta el diálogo de Ignorar optimización de batería."

    manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
        "Para grabar sin cortes en ColorOS/OxygenOS:\n" +
            "1. Ajustes → Batería → Uso de batería de apps → ImuFlux → Permitir actividad en segundo plano.\n" +
            "2. Ajustes → Privacidad → Gestor de arranque → Permite ImuFlux.\n" +
            "3. Acepta el diálogo de Ignorar optimización de batería."

    manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
        "Para grabar sin cortes en FuntouchOS/OriginOS:\n" +
            "1. Ajustes → Batería → Consumo de energía en segundo plano → ImuFlux → Alto.\n" +
            "2. Ajustes → Aplicaciones → Autostart → Activa ImuFlux.\n" +
            "3. Acepta el diálogo de Ignorar optimización de batería."

    else ->
        "Tu fabricante tiene una política de batería estándar. Acepta el diálogo de " +
            "\"Ignorar optimización de batería\" para garantizar que la grabación no se interrumpa " +
            "mientras la pantalla está apagada."
}

private const val PREF_KEY: String = "manufacturer_onboarding_dismissed_v1"
