package com.sarmidev.imuflux.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Muestra una única vez por instalación un diálogo con instrucciones específicas
 * del fabricante del dispositivo para garantizar que la grabación en background
 * no sea interrumpida por capas de ahorro energía propietarias.
 *
 * Rediseñado para seguir la estética instrumental oscura del resto de la app.
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

    val c = LocalImuFluxColors.current
    val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
    val title = titleFor(manufacturer)
    val steps = stepsFor(manufacturer)

    Dialog(
        onDismissRequest = { dismissed = true },
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
                            .background(c.accentAmber),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "PREPARACIÓN",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color = c.accentAmber,
                    )
                }
                Spacer(Modifier.height(12.dp))

                // Main title
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = c.textPrimary,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Para grabar jornadas completas (8 h) sin que el sistema mate " +
                        "la app en segundo plano, sigue estos pasos:",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(16.dp))

                // Steps (scrollable if many)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bgCard)
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        steps.forEachIndexed { i, step ->
                            if (i > 0) Spacer(Modifier.height(10.dp))
                            StepRow(index = i + 1, text = step, c = c)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogActionButton(
                        label = "Más tarde",
                        filled = false,
                        c = c,
                        onClick = { dismissed = true },
                    )
                    Spacer(Modifier.width(10.dp))
                    DialogActionButton(
                        label = "Ir a ajustes",
                        filled = true,
                        c = c,
                        onClick = {
                            dismissed = true
                            runCatching {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:" + context.packageName)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, text: String, c: ImuFluxColors) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(c.accentCyan.copy(alpha = 0.14f))
                .border(1.dp, c.accentCyan.copy(alpha = 0.40f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = c.accentCyan,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = c.textPrimary,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun titleFor(manufacturer: String): String = when {
    manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
        "Preparar MIUI / HyperOS"
    manufacturer.contains("huawei") || manufacturer.contains("honor") ->
        "Preparar EMUI / HarmonyOS"
    manufacturer.contains("samsung") ->
        "Preparar One UI"
    manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
        "Preparar ColorOS / OxygenOS"
    manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
        "Preparar FuntouchOS / OriginOS"
    else -> "Preparar el dispositivo"
}

private fun stepsFor(manufacturer: String): List<String> = when {
    manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
        "Ajustes → Apps → ImuFlux → Ahorro de batería → Sin restricciones.",
        "Ajustes → Apps → ImuFlux → Autostart (Inicio automático) → Activar.",
        "En la pantalla Reciente, mantén pulsada la tarjeta de la app → icono candado.",
        "Acepta el diálogo de Ignorar optimización de batería.",
    )
    manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
        "Ajustes → Apps → Inicio de apps → ImuFlux → Gestionar manualmente.",
        "Activa Autoarranque, Inicio secundario y Ejecutar en segundo plano.",
        "Ajustes → Batería → Lanzamiento de apps → Desactiva optimizaciones para ImuFlux.",
    )
    manufacturer.contains("samsung") -> listOf(
        "Ajustes → Mantenimiento del dispositivo → Batería → Límites de uso en segundo plano.",
        "Apps que no se duermen → Añade ImuFlux.",
        "Acepta el diálogo de Ignorar optimización de batería.",
    )
    manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> listOf(
        "Ajustes → Batería → Uso de batería de apps → ImuFlux → Permitir actividad en segundo plano.",
        "Ajustes → Privacidad → Gestor de arranque → Permite ImuFlux.",
        "Acepta el diálogo de Ignorar optimización de batería.",
    )
    manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
        "Ajustes → Batería → Consumo en segundo plano → ImuFlux → Alto.",
        "Ajustes → Aplicaciones → Autostart → Activa ImuFlux.",
        "Acepta el diálogo de Ignorar optimización de batería.",
    )
    else -> listOf(
        "Acepta el diálogo \"Ignorar optimización de batería\" para que la grabación " +
            "no se interrumpa con la pantalla apagada.",
    )
}

private const val PREF_KEY: String = "manufacturer_onboarding_dismissed_v1"
