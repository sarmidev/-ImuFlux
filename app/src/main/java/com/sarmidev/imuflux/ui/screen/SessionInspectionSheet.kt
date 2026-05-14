package com.sarmidev.imuflux.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarmidev.imuflux.data.analysis.InspectionResult
import com.sarmidev.imuflux.data.analysis.SensorGroupStatus

/**
 * Hoja de resultados que ocupa toda la pantalla cuando el análisis de una
 * sesión ha finalizado. Muestra:
 *  - Métricas de timing (equivalente a `validate_session.py`)
 *  - Calidad de datos por grupo de sensor (equivalente a `check_data_quality.py`)
 */
@Composable
fun SessionInspectionSheet(
    sessionId: String,
    result: InspectionResult,
    onDismiss: () -> Unit,
    c: ImuFluxColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(0f to c.bgDeep, 1f to c.bgSurface),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},  // Absorb touches so underlying list is not clickable
            ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "ANÁLISIS",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                            color = c.textPrimary,
                        )
                        Text(
                            text = sessionId,
                            fontSize = 10.sp,
                            color = c.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c.bgCard)
                            .border(1.dp, c.bgCardBorder, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", fontSize = 16.sp, color = c.textSecondary)
                    }
                }
            }

            // ── Overall verdict banner ─────────────────────────────────────────
            item {
                val allOk = result.timingPassed && result.dataProblems == 0
                val color = when {
                    allOk -> c.accentGreen
                    result.timingPassed -> c.accentAmber
                    else -> c.accentRed
                }
                val icon = if (allOk) "✔" else "✘"
                val label = when {
                    allOk -> "SESIÓN VÁLIDA"
                    result.timingPassed && result.dataProblems > 0 ->
                        "TIMING OK · ${result.dataProblems} SENSOR(ES) CON PROBLEMAS"
                    else -> "SESIÓN INVÁLIDA"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(color.copy(alpha = 0.12f))
                        .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(icon, fontSize = 20.sp, color = color)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            color = color,
                        )
                        Text(
                            text = sessionFormatDuration(result.durationS) +
                                "  ·  ${"%,d".format(result.totalRows)} filas",
                            fontSize = 10.sp,
                            color = c.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // ── Timing section ─────────────────────────────────────────────────
            item { SectionHeader("TIMING  (validate_session)", c = c) }

            item {
                InspectionCard(c = c) {
                    InspectionRow("Duración", sessionFormatDuration(result.durationS), c = c)
                    InspectionRow("Filas totales", "%,d".format(result.totalRows), c = c)
                    InspectionRow(
                        "Completitud",
                        "%.2f %%".format(result.completenessPercent),
                        highlight = if (result.completenessPercent >= 99.0) null else c.accentAmber,
                        c = c,
                    )
                    InspectionRow(
                        "dt mediana",
                        "%.4f ms".format(result.dtMedianMs),
                        highlight = if (result.dtMedianMs in 9.5..10.5) null else c.accentRed,
                        note = "objetivo: 9.5–10.5 ms",
                        c = c,
                    )
                    InspectionRow(
                        "dt media",
                        "%.4f ms".format(result.dtMeanMs),
                        c = c,
                    )
                    InspectionRow(
                        "Jitter p95",
                        "%.4f ms".format(result.jitterP95Ms),
                        highlight = if (result.jitterP95Ms < 5.0) null else c.accentRed,
                        note = "objetivo: < 5 ms",
                        c = c,
                    )
                    InspectionRow(
                        "Huecos (>50 ms)",
                        "${result.gaps}",
                        highlight = if (result.gaps == 0) null else c.accentRed,
                        note = if (result.gaps > 0) "mayor = %.2f ms".format(result.maxGapMs) else null,
                        c = c,
                    )
                    result.watchdogResurrections?.let { r ->
                        InspectionRow(
                            "Watchdog reinicios",
                            "$r",
                            highlight = if (r == 0) null else c.accentRed,
                            note = if (r > 0) "el sistema mató la grabación" else null,
                            c = c,
                        )
                    }
                }
            }

            // Timing errors
            if (result.timingErrors.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.accentRed.copy(alpha = 0.08f))
                            .border(1.dp, c.accentRed.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        result.timingErrors.forEach { err ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    "·",
                                    fontSize = 10.sp,
                                    color = c.accentRed,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(12.dp),
                                )
                                Text(
                                    err,
                                    fontSize = 10.sp,
                                    color = c.accentRed.copy(alpha = 0.9f),
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            // ── Data quality section ────────────────────────────────────────────
            item { SectionHeader("CALIDAD DE DATOS  (check_data_quality)", c = c) }

            items(result.sensorGroups) { group ->
                val (color, icon) = when (group.status) {
                    SensorGroupStatus.OK -> c.accentGreen to "✔"
                    SensorGroupStatus.PARTIAL -> c.accentAmber to "⚠"
                    SensorGroupStatus.MISSING -> c.accentRed to "✘"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.bgCard)
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(icon, fontSize = 12.sp, color = color, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            group.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Text(
                            group.detail,
                            fontSize = 9.sp,
                            color = if (group.status == SensorGroupStatus.OK) c.textSecondary else color,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, c: ImuFluxColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(3.dp, 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.accentCyan),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = c.textSecondary,
        )
    }
}

@Composable
private fun InspectionCard(c: ImuFluxColors, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun InspectionRow(
    label: String,
    value: String,
    highlight: Color? = null,
    note: String? = null,
    c: ImuFluxColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = highlight ?: c.textPrimary,
                fontFamily = FontFamily.Monospace,
            )
            if (note != null) {
                Text(
                    note,
                    fontSize = 9.sp,
                    color = (highlight ?: c.textSecondary).copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun sessionFormatDuration(durationS: Double): String {
    val totalSec = durationS.toLong()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h %02dm %02ds".format(m, s)
        m > 0 -> "${m}m %02ds".format(s)
        else  -> "${s}s"
    }
}
