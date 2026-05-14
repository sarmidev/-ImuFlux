package com.sarmidev.imuflux.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Esta es una utilidad para generar el "Feature Graphic" (1024x500) para Google Play Console.
 * Puedes abrir este archivo en Android Studio, ver el Preview y tomar una captura de pantalla
 * o usar la herramienta de exportación de previews si está disponible.
 */
@Preview(widthDp = 1024, heightDp = 500, showBackground = true)
@Composable
fun FeatureGraphicPreview() {
    val bgDeep = Color(0xFF07090F)
    val accentCyan = Color(0xFF00C8FF)
    val accentGreen = Color(0xFF00E676)
    val sensorPitch = Color(0xFF4FC3F7)
    val textPrimary = Color(0xFFE8EDF8)

    Box(
        modifier = Modifier
            .size(width = 1024.dp, height = 500.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(bgDeep, Color(0xFF111523))
                )
            )
    ) {
        // Lineas de fondo decorativas (efecto tecnológico)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Rejilla
            for (i in 0..12) {
                val x = width * i / 12
                drawLine(
                    color = accentCyan.copy(alpha = 0.05f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1f
                )
            }
            for (i in 0..6) {
                val y = height * i / 6
                drawLine(
                    color = accentCyan.copy(alpha = 0.05f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            // Onda de datos abstracta
            val path = Path()
            path.moveTo(0f, height * 0.75f)
            val segments = 30
            for (i in 1..segments) {
                val x = width * i / segments
                val y = height * 0.75f + (if (i % 2 == 0) 60f else -60f) * (i % 4 + 1) * 0.5f
                path.quadraticTo(
                    x - (width / (segments * 2)), y + (if (i % 2 == 0) -30f else 30f),
                    x, y
                )
            }
            drawPath(
                path = path,
                color = accentCyan.copy(alpha = 0.15f),
                style = Stroke(width = 3f)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo / Icono simplificado
            Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    // Ejes
                    drawLine(accentCyan, center, Offset(center.x + 45.dp.toPx(), center.y), strokeWidth = 6f)
                    drawLine(accentGreen, center, Offset(center.x, center.y - 45.dp.toPx()), strokeWidth = 6f)
                    drawLine(sensorPitch, center, Offset(center.x - 35.dp.toPx(), center.y + 35.dp.toPx()), strokeWidth = 6f)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "ImuFlux",
                color = textPrimary,
                fontSize = 90.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )

            Text(
                text = "REGISTRO DE SENSORES EN TIEMPO REAL",
                color = accentCyan,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp
            )
        }

        // Tags inferiores
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            PromotionTag("ACELERÓMETRO")
            PromotionTag("GIROSCOPIO")
            PromotionTag("MAGNETÓMETRO")
            PromotionTag("100Hz+")
        }
    }
}

@Composable
fun PromotionTag(text: String) {
    Text(
        text = text,
        color = Color(0xFF00E676).copy(alpha = 0.8f),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}
