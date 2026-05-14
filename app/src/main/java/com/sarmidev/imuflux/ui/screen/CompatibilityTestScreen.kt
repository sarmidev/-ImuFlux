package com.sarmidev.imuflux.ui.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sarmidev.imuflux.data.analysis.CompatibilityVerdictStore
import com.sarmidev.imuflux.data.analysis.ManufacturerReliability
import com.sarmidev.imuflux.data.analysis.QualityReport
import com.sarmidev.imuflux.data.analysis.Verdict
import com.sarmidev.imuflux.ui.viewmodel.CompatibilityTestViewModel
import com.sarmidev.imuflux.ui.viewmodel.label

@Composable
fun CompatibilityTestScreen(
    onBack: () -> Unit,
    onToggleTheme: () -> Unit = {},
    viewModel: CompatibilityTestViewModel = hiltViewModel(),
) {
    val c = LocalImuFluxColors.current
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to c.bgDeep, 1f to c.bgSurface)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Header ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompatHeaderBtn(label = "←", onClick = onBack, c = c)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "TEST DE COMPATIBILIDAD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.5.sp,
                            color = c.textPrimary,
                        )
                        Text(
                            text = state.deviceLabel,
                            fontSize = 10.sp,
                            color = c.textSecondary,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
                CompatHeaderBtn(label = if (c.isDark) "☀" else "☽", onClick = onToggleTheme, c = c)
            }

            Spacer(Modifier.height(18.dp))

            when (state.phase) {
                CompatibilityTestViewModel.Phase.IDLE -> IdleContent(
                    totalDurationMs = state.totalDurationMs,
                    onStart = { viewModel.startTest() },
                    c = c,
                )
                CompatibilityTestViewModel.Phase.RUNNING -> RunningContent(
                    elapsedMs = state.elapsedMs,
                    totalDurationMs = state.totalDurationMs,
                    onCancel = { viewModel.cancelTest() },
                    c = c,
                )
                CompatibilityTestViewModel.Phase.ANALYZING -> AnalyzingContent(c = c)
                CompatibilityTestViewModel.Phase.FINISHED -> ResultContent(
                    report = state.report,
                    onAcknowledge = { viewModel.acknowledgeResult() },
                    c = c,
                )
                CompatibilityTestViewModel.Phase.ERROR -> ErrorContent(
                    message = state.errorMessage ?: "Error desconocido",
                    onReset = { viewModel.acknowledgeResult() },
                    c = c,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// IDLE: instructions + big start button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun IdleContent(
    totalDurationMs: Long,
    onStart: () -> Unit,
    c: ImuFluxColors,
) {
    val minutes = totalDurationMs / 60_000L
    val context = LocalContext.current
    val manufacturerInfo = remember(context) {
        com.sarmidev.imuflux.data.analysis.manufacturerInfoFor(android.os.Build.MANUFACTURER)
    }
    Column {
        SectionCard(title = "QUÉ MIDE ESTE TEST", c = c) {
            BulletLine(
                "Ejecuta una grabación real de $minutes min con el mismo pipeline que una sesión de producción.",
                c,
            )
            BulletLine(
                "Al terminar, analiza los intervalos entre muestras y calcula mediana, jitter, huecos y completitud.",
                c,
            )
            BulletLine(
                "Decide si el dispositivo es APTO, MARGINAL o NO APTO para jornadas de 8 h a 100 Hz.",
                c,
            )
            BulletLine(
                "Aplica un veredicto bruto basado en métricas **y** un techo basado en el fabricante (los killers agresivos no se activan en 30 min).",
                c,
            )
        }

        // Pre-aviso del cap: si el fabricante es conocido como hostil, lo decimos
        // antes de arrancar para evitar falsas expectativas.
        if (manufacturerInfo.reliability != ManufacturerReliability.RELIABLE) {
            Spacer(Modifier.height(12.dp))
            ManufacturerPreWarning(info = manufacturerInfo, c = c)
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "ANTES DE EMPEZAR", c = c) {
            BulletLine("Batería ≥ 50 % (el test consume ~3 %).", c)
            BulletLine("Cierra el resto de apps.", c)
            BulletLine(
                "Acepta el diálogo de optimización de batería y sigue la guía del fabricante.",
                c,
            )
            BulletLine(
                "Bloquea la pantalla y deja el teléfono quieto los $minutes min completos. No lo toques.",
                c,
            )
            BulletLine(
                "No lo pongas a cargar: el test debe reproducir las condiciones reales de una jornada.",
                c,
            )
        }

        Spacer(Modifier.height(22.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigActionButton(
                label = "INICIAR TEST",
                color = c.accentCyan,
                bgCard = c.bgCard,
                onClick = onStart,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RUNNING: countdown + pulsing indicator
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RunningContent(
    elapsedMs: Long,
    totalDurationMs: Long,
    onCancel: () -> Unit,
    c: ImuFluxColors,
) {
    val remainingMs = (totalDurationMs - elapsedMs).coerceAtLeast(0L)
    val progress = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

    val pulse by rememberInfiniteTransition(label = "compat_pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.bgCard)
                .border(1.dp, c.accentGreen.copy(alpha = 0.35f * pulse), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(c.accentGreen.copy(alpha = pulse)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "GRABANDO (DIAGNÓSTICO)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        color = c.accentGreen,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = formatMmSs(remainingMs),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "tiempo restante",
                    fontSize = 10.sp,
                    color = c.textSecondary,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(16.dp))
                ProgressBar(progress = progress, c = c)
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "INSTRUCCIONES", c = c) {
            BulletLine("Bloquea la pantalla ahora si aún no lo has hecho.", c)
            BulletLine("No toques el dispositivo hasta que termine.", c)
            BulletLine(
                "Si cancelas antes de 25 min no se puede emitir un PASS fiable (se marca como SIN DATOS).",
                c,
            )
        }

        Spacer(Modifier.height(22.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigActionButton(
                label = "CANCELAR",
                color = c.accentAmber,
                bgCard = c.bgCard,
                onClick = onCancel,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANALYZING: spinner-like indicator
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AnalyzingContent(c: ImuFluxColors) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "ANALIZANDO…",
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            color = c.accentCyan,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Calculando mediana, jitter y huecos.",
            fontSize = 11.sp,
            color = c.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FINISHED: verdict card + metrics
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ResultContent(
    report: QualityReport?,
    onAcknowledge: () -> Unit,
    c: ImuFluxColors,
) {
    if (report == null) {
        ErrorContent(
            message = "El análisis no devolvió resultados.",
            onReset = onAcknowledge,
            c = c,
        )
        return
    }

    val verdictColor = when (report.verdict) {
        Verdict.PASS -> c.accentGreen
        Verdict.WARN -> c.accentAmber
        Verdict.FAIL -> c.accentRed
        Verdict.INSUFFICIENT_DATA -> c.textSecondary
    }
    val verdictDescription = when (report.verdict) {
        Verdict.PASS -> "Este dispositivo puede grabar sesiones largas a 100 Hz con fiabilidad."
        Verdict.WARN -> if (report.wasCappedByManufacturer) {
            "El test salió limpio, pero el fabricante es conocido por matar servicios tras horas de reposo. Hace falta validar con una sesión real de ≥ 4 h antes de confiar."
        } else {
            "Funciona, pero con irregularidades. Úsalo sólo si no queda otra opción y repite el test periódicamente."
        }
        Verdict.FAIL -> if (report.wasCappedByManufacturer) {
            "Aunque el test de 30 min salió limpio, este fabricante mata servicios en segundo plano tras horas de reposo. Un test corto no puede detectarlo. No lo utilices para sesiones de producción."
        } else {
            "El sistema interrumpe la grabación. No utilices este dispositivo para sesiones de producción."
        }
        Verdict.INSUFFICIENT_DATA -> "El test se interrumpió demasiado pronto o no hubo muestras suficientes."
    }

    Column {
        // Verdict card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.bgCard)
                .border(1.5.dp, verdictColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(4.dp, 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(verdictColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "VEREDICTO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = report.verdict.label(),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = verdictColor,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = verdictDescription,
                    fontSize = 12.sp,
                    color = c.textPrimary,
                    lineHeight = 17.sp,
                )
            }
        }

        // Manufacturer cap explanation — sólo cuando aplica.
        if (report.wasCappedByManufacturer && report.manufacturerInfo != null) {
            Spacer(Modifier.height(12.dp))
            ManufacturerCapCard(report = report, c = c)
        }

        Spacer(Modifier.height(14.dp))

        // Raw metrics verdict (for transparency) — sólo si hay cap.
        if (report.wasCappedByManufacturer) {
            SectionCard(title = "VEREDICTO BRUTO (SÓLO MÉTRICAS)", c = c) {
                MetricRow(
                    label = "Sin cap por fabricante",
                    value = report.rawVerdict.label(),
                    c = c,
                    valueColor = when (report.rawVerdict) {
                        Verdict.PASS -> c.accentGreen
                        Verdict.WARN -> c.accentAmber
                        Verdict.FAIL -> c.accentRed
                        Verdict.INSUFFICIENT_DATA -> c.textSecondary
                    },
                )
                BulletLine(
                    "Este valor refleja sólo los números del test. El veredicto final aplica el conocimiento acumulado sobre este fabricante.",
                    c,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Metrics grid
        SectionCard(title = "MÉTRICAS", c = c) {
            MetricRow(
                label = "Completitud",
                value = "%.1f %%".format(report.completeness * 100.0),
                c = c,
                valueColor = tintForCompleteness(report.completeness, c),
            )
            MetricRow(
                label = "Mediana dt",
                value = "%.2f ms".format(report.medianDtNs / 1e6),
                c = c,
                valueColor = tintForMedian(report.medianDtNs, c),
            )
            MetricRow(
                label = "Jitter p95",
                value = "%.2f ms".format(report.jitterP95Ns / 1e6),
                c = c,
                valueColor = tintForJitter(report.jitterP95Ns, c),
            )
            MetricRow(
                label = "Huecos (> 50 ms)",
                value = report.gaps.toString(),
                c = c,
                valueColor = if (report.gaps == 0) c.accentGreen else c.accentRed,
            )
            if (report.gaps > 0) {
                MetricRow(
                    label = "Hueco máximo",
                    value = "%.1f s".format(report.maxGapNs / 1e9),
                    c = c,
                    valueColor = c.accentRed,
                )
            }
            MetricRow(
                label = "Filas",
                value = "%,d".format(report.totalRows),
                c = c,
                valueColor = c.textPrimary,
            )
            MetricRow(
                label = "Duración",
                value = formatDurationS(report.durationS),
                c = c,
                valueColor = c.textPrimary,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Next-step card
        SectionCard(title = "SIGUIENTES PASOS", c = c) {
            when (report.verdict) {
                Verdict.PASS -> {
                    BulletLine(
                        "Registra este dispositivo en DEVICE_COMPATIBILITY.md como Tier A.",
                        c,
                    )
                    BulletLine(
                        "Ejecuta una sesión real de al menos 4 h con pantalla apagada y vuelve a validar con validate_session.py.",
                        c,
                    )
                }
                Verdict.WARN -> if (report.wasCappedByManufacturer) {
                    BulletLine(
                        "Para convertir este WARN en PASS: graba una sesión real de ≥ 4 h (screen-off, sin tocar, sin cargador).",
                        c,
                    )
                    BulletLine(
                        "Pásala por tools/validate_session.py. Si da completeness ≥ 99 % y watchdog_resurrections == 0, el dispositivo es apto.",
                        c,
                    )
                    BulletLine(
                        "Si la sesión larga falla (como es probable en este fabricante), usa un dispositivo de Tier A.",
                        c,
                    )
                } else {
                    BulletLine(
                        "Revisa de nuevo los pasos del fabricante: Autostart, apps que no se duermen, candado en recientes.",
                        c,
                    )
                    BulletLine(
                        "Repite el test después de dejar el móvil unas horas sin tocar para ver si los kills se estabilizan.",
                        c,
                    )
                }
                Verdict.FAIL -> if (report.wasCappedByManufacturer) {
                    BulletLine(
                        "Este fabricante está marcado como Tier C en DEVICE_COMPATIBILITY.md. Un test de 30 min no prueba lo contrario.",
                        c,
                    )
                    BulletLine(
                        "Única forma de anular este FAIL: grabar una sesión real de ≥ 4 h con completeness ≥ 99 % y watchdog_resurrections == 0, y repetirlo en 3 sesiones distintas.",
                        c,
                    )
                    BulletLine(
                        "Recomendación: usa un Pixel, Samsung, Sony o Nokia para producción.",
                        c,
                    )
                } else {
                    BulletLine(
                        "El problema no se puede resolver desde la app: consulta DEVICE_COMPATIBILITY.md (Tier C).",
                        c,
                    )
                    BulletLine("Cambia a un dispositivo de Tier A para la grabación de producción.", c)
                }
                Verdict.INSUFFICIENT_DATA -> {
                    BulletLine(
                        "Vuelve a ejecutar el test y deja que termine completo (30 min).",
                        c,
                    )
                    BulletLine(
                        "Un PASS exige al menos 25 min de grabación real; con menos no podemos descartar que Doze o el killer del OEM intervengan más tarde.",
                        c,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigActionButton(
                label = "ACEPTAR",
                color = verdictColor,
                bgCard = c.bgCard,
                onClick = onAcknowledge,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ERROR state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ErrorContent(
    message: String,
    onReset: () -> Unit,
    c: ImuFluxColors,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.bgCard)
                .border(1.dp, c.accentRed.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Column {
                Text(
                    text = "NO SE PUDO COMPLETAR EL TEST",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = c.accentRed,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = c.textPrimary,
                    lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigActionButton(
                label = "VOLVER",
                color = c.accentAmber,
                bgCard = c.bgCard,
                onClick = onReset,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable bits
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SectionCard(
    title: String,
    c: ImuFluxColors,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
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
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.5.sp,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun BulletLine(text: String, c: ImuFluxColors) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = c.accentCyan,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = c.textPrimary,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    c: ImuFluxColors,
    valueColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = c.textSecondary,
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ProgressBar(progress: Float, c: ImuFluxColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(c.bgCardBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(c.accentGreen),
        )
    }
}

@Composable
private fun BigActionButton(
    label: String,
    color: Color,
    bgCard: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = 220.dp, height = 54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgCard)
            .border(1.5.dp, color, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = color.copy(alpha = 0.08f))
        }
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CompatHeaderBtn(label: String, onClick: () -> Unit, c: ImuFluxColors) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 15.sp, color = c.textSecondary)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun formatMmSs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%02d:%02d".format(m, s)
}

private fun formatDurationS(durationS: Double): String {
    val totalSec = durationS.toLong().coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%dm %02ds".format(m, s)
}

private fun tintForCompleteness(completeness: Double, c: ImuFluxColors): Color = when {
    completeness >= 0.99 -> c.accentGreen
    completeness >= 0.90 -> c.accentAmber
    else -> c.accentRed
}

private fun tintForMedian(medianDtNs: Long, c: ImuFluxColors): Color {
    val ms = medianDtNs / 1e6
    return if (ms in 9.5..10.5) c.accentGreen else c.accentAmber
}

private fun tintForJitter(jitterP95Ns: Long, c: ImuFluxColors): Color {
    val ms = jitterP95Ns / 1e6
    return when {
        ms < 5.0 -> c.accentGreen
        ms < 10.0 -> c.accentAmber
        else -> c.accentRed
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pre-aviso IDLE cuando el fabricante va a limitar el veredicto.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ManufacturerPreWarning(
    info: com.sarmidev.imuflux.data.analysis.ManufacturerInfo,
    c: ImuFluxColors,
) {
    val tint = when (info.reliability) {
        ManufacturerReliability.HOSTILE -> c.accentRed
        ManufacturerReliability.CONDITIONAL -> c.accentAmber
        ManufacturerReliability.UNKNOWN -> c.textSecondary
        ManufacturerReliability.RELIABLE -> c.accentGreen
    }
    val title = when (info.reliability) {
        ManufacturerReliability.HOSTILE -> "FABRICANTE HOSTIL · MÁXIMO POSIBLE: FAIL"
        ManufacturerReliability.CONDITIONAL -> "FABRICANTE CONDICIONAL · MÁXIMO POSIBLE: WARN"
        ManufacturerReliability.UNKNOWN -> "FABRICANTE DESCONOCIDO · MÁXIMO POSIBLE: WARN"
        ManufacturerReliability.RELIABLE -> "FABRICANTE FIABLE"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.5.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(3.dp, 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = tint,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = info.rationale,
                fontSize = 11.sp,
                color = c.textPrimary,
                lineHeight = 15.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Card explicando el techo por fabricante cuando el veredicto bruto es mejor
// que el final.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ManufacturerCapCard(report: QualityReport, c: ImuFluxColors) {
    val info = report.manufacturerInfo ?: return
    val tint = when (info.reliability) {
        ManufacturerReliability.HOSTILE -> c.accentRed
        ManufacturerReliability.CONDITIONAL -> c.accentAmber
        ManufacturerReliability.UNKNOWN -> c.textSecondary
        ManufacturerReliability.RELIABLE -> c.accentGreen
    }
    val reliabilityLabel = when (info.reliability) {
        ManufacturerReliability.HOSTILE -> "HOSTIL"
        ManufacturerReliability.CONDITIONAL -> "CONDICIONAL"
        ManufacturerReliability.UNKNOWN -> "DESCONOCIDO"
        ManufacturerReliability.RELIABLE -> "FIABLE"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.5.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(3.dp, 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "VEREDICTO LIMITADO POR FABRICANTE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = tint,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${info.displayName} · $reliabilityLabel",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = info.rationale,
                fontSize = 11.sp,
                color = c.textPrimary,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Por qué: un test de 30 min no puede provocar que el OS de " +
                    "${info.displayName} mate el servicio. Las políticas agresivas actúan " +
                    "cuando la app cae a App Standby Bucket RARE/RESTRICTED tras horas de reposo.",
                fontSize = 10.5.sp,
                color = c.textSecondary,
                lineHeight = 14.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Banner: mostrado en la pantalla principal cuando hay un veredicto WARN/FAIL
// pendiente de reconocimiento.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Banner persistente en la pantalla principal. Lee el último veredicto
 * guardado y lo muestra como barra superior **sólo si** el veredicto no es
 * PASS y el usuario aún no lo ha descartado.
 *
 * Clicar el banner lleva al Test de compatibilidad (para re-ejecutarlo o
 * revisar detalles). Pulsar la "×" lo oculta hasta que haya un nuevo
 * veredicto (al guardar uno nuevo, `dismissed` se resetea automáticamente).
 */
@Composable
fun CompatibilityVerdictBanner(
    onOpenCompatibilityTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) {
        CompatibilityVerdictStore(context.applicationContext)
    }
    var stored by remember { mutableStateOf(store.loadVerdict()) }
    val current = stored ?: return
    if (current.dismissed) return
    if (current.verdict == Verdict.PASS) return

    val c = LocalImuFluxColors.current
    val (tint, title) = when (current.verdict) {
        Verdict.FAIL -> c.accentRed to "DISPOSITIVO NO APTO"
        Verdict.WARN -> c.accentAmber to "DISPOSITIVO MARGINAL"
        Verdict.INSUFFICIENT_DATA -> c.textSecondary to "TEST INCOMPLETO"
        Verdict.PASS -> c.accentGreen to "DISPOSITIVO APTO"
    }
    val subtitle = when (current.verdict) {
        Verdict.FAIL -> "No uses este móvil para sesiones largas. Toca para ver el detalle."
        Verdict.WARN -> "Graba bien con ajustes estrictos. Revisa el detalle."
        Verdict.INSUFFICIENT_DATA -> "Vuelve a ejecutar el test completo."
        Verdict.PASS -> "Este dispositivo pasó el test de compatibilidad."
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.5.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenCompatibilityTest,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(3.dp, 30.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = tint,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 10.5.sp,
                    color = c.textPrimary,
                    lineHeight = 14.sp,
                )
                if (current.verdict != Verdict.INSUFFICIENT_DATA) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "completitud %.1f %% · %d huecos · jitter %.1f ms".format(
                            current.completeness * 100.0,
                            current.gaps,
                            current.jitterP95Ns / 1e6,
                        ),
                        fontSize = 9.5.sp,
                        color = c.textSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(c.bgSurface)
                    .border(1.dp, c.bgCardBorder, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            store.markBannerDismissed()
                            stored = store.loadVerdict()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", fontSize = 14.sp, color = c.textSecondary)
            }
        }
    }
}


