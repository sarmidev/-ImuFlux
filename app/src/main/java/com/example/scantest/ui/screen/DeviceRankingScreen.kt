package com.example.scantest.ui.screen

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.scantest.domain.model.DeviceRankingEntry
import com.example.scantest.ui.viewmodel.DeviceRankingViewModel
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Thresholds
// ─────────────────────────────────────────────────────────────────────────────
//
// Nominal 100 Hz → 10 ms.
// OK (green)   : 8.5–10.5 ms → GREEN.
// Risk (amber) : 7.0–8.5 ms or 10.5–12.0 ms → AMBER.
// Fail (red)   : outside 7.0–12.0 ms → RED.
//
private val MEDIAN_OK_LO = 8.5
private val MEDIAN_OK_HI = 10.5
private val MEDIAN_RISK_LO = 7.0
private val MEDIAN_RISK_HI = 12.0

@Composable
fun DeviceRankingScreen(
    onBack: () -> Unit,
    onToggleTheme: () -> Unit = {},
    viewModel: DeviceRankingViewModel = hiltViewModel(),
) {
    val c = LocalImuFluxColors.current
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.bgDeep)
                .padding(horizontal = 18.dp, vertical = 24.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(3.dp, 20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.accentCyan),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "RANKING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp,
                            color = c.textSecondary,
                        )
                        Text(
                            text = "Dispositivos",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = c.textPrimary,
                            lineHeight = 22.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderIconButton(
                        label = if (c.isDark) "☀" else "☽",
                        onClick = onToggleTheme,
                        c = c,
                    )
                    HeaderIconButton(label = "↺", onClick = { viewModel.refresh() }, c = c)
                    HeaderIconButton(label = "←", onClick = onBack, c = c)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Subtitle strip ────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(3.dp, 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accentCyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        state.isLoading -> "Cargando…"
                        state.error != null -> "Error al cargar"
                        state.entries.isEmpty() -> "Sin datos aún"
                        else -> "${state.entries.size} modelos analizados"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = c.textSecondary,
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── Content ───────────────────────────────────────────────────────────
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = c.accentCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Consultando Firestore...",
                                fontSize = 11.sp,
                                color = c.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "✕", fontSize = 32.sp, color = c.accentRed)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = state.error ?: "Error desconocido",
                                fontSize = 12.sp,
                                color = c.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.bgCard)
                                    .border(1.dp, c.bgCardBorder, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.refresh() }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = "REINTENTAR",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 2.sp,
                                    color = c.accentCyan,
                                )
                            }
                        }
                    }
                }

                state.entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "—", fontSize = 40.sp, color = c.textDim)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Aún no hay dispositivos analizados.",
                                fontSize = 12.sp,
                                color = c.textSecondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Analiza una sesión desde la pantalla de Sesiones.",
                                fontSize = 11.sp,
                                color = c.textDim,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        itemsIndexed(state.entries) { index, entry ->
                            DeviceRankingCard(
                                rank = index + 1,
                                entry = entry,
                                c = c,
                                onClick = { viewModel.selectEntry(entry) },
                            )
                        }
                    }
                }
            }
        }

        // ── Detail overlay ────────────────────────────────────────────────────────
        state.selectedEntry?.let { entry ->
            DeviceDetailSheet(
                entry = entry,
                c = c,
                onDismiss = { viewModel.clearSelection() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device ranking card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceRankingCard(
    rank: Int,
    entry: DeviceRankingEntry,
    c: ImuFluxColors,
    onClick: () -> Unit,
) {
    val categoryColor = categoryColor(entry.category, c)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            // ── Top row: rank + device name + score badge ─────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Rank badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.bgDeep)
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "#$rank",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (rank <= 3) c.accentCyan else c.textSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Device name
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.manufacturer,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.model,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                    Text(
                        text = "Android SDK ${entry.sdkInt}",
                        fontSize = 9.sp,
                        color = c.textDim,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Score badge
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(categoryColor.copy(alpha = 0.12f))
                            .border(1.dp, categoryColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${entry.score}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = categoryColor,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.category,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = categoryColor,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Stats row ─────────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatChip(
                    label = "SESIONES",
                    value = "${entry.sessionCount}",
                    color = c.textSecondary,
                    c = c,
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    label = "COMPLETITUD",
                    value = "${(entry.avgCompleteness * 100).roundToInt()}%",
                    color = tintForCompleteness(entry.avgCompleteness, c),
                    c = c,
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    label = "MEDIANA DT",
                    value = "${"%.1f".format(entry.avgMedianDtMs)} ms",
                    color = tintForMedianDt(entry.avgMedianDtMs, c),
                    c = c,
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    label = "JITTER P95",
                    value = "${"%.1f".format(entry.avgJitterP95Ms)} ms",
                    color = tintForJitter(entry.avgJitterP95Ms, c),
                    c = c,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Status badge — uses lastVerdict from Firestore so it matches
            //    the actual analysis result, not a UI re-derivation.
            CurrentStatusBadge(verdict = entry.lastVerdict, c = c)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device detail sheet (full-screen overlay)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceDetailSheet(
    entry: DeviceRankingEntry,
    c: ImuFluxColors,
    onDismiss: () -> Unit,
) {
    val categoryColor = categoryColor(entry.category, c)
    val medianColor = tintForMedianDt(entry.avgMedianDtMs, c)
    val completenessColor = tintForCompleteness(entry.avgCompleteness, c)
    val jitterColor = tintForJitter(entry.avgJitterP95Ms, c)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(0f to c.bgDeep.copy(alpha = 0.97f), 1f to c.bgSurface.copy(alpha = 0.97f)),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DETALLE DE DISPOSITIVO",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.manufacturer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textSecondary,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = entry.model,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = c.textPrimary,
                        lineHeight = 24.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Android SDK ${entry.sdkInt}",
                        fontSize = 10.sp,
                        color = c.textDim,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    CurrentStatusBadge(verdict = entry.lastVerdict, c = c)
                }

                Spacer(Modifier.width(14.dp))

                // Score badge
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(categoryColor.copy(alpha = 0.12f))
                            .border(1.dp, categoryColor.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${entry.score}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = categoryColor,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.category,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = categoryColor,
                    )
                }
            }

            // ── Divider ───────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.bgCardBorder),
            )

            // ── Timing ────────────────────────────────────────────────────────
            DetailSection(title = "TIMING", c = c) {
                DetailRow(
                    label = "Mediana dt (media sesiones)",
                    value = "${"%.2f".format(entry.avgMedianDtMs)} ms",
                    hint = "objetivo: 8.5–10.5 ms",
                    valueColor = medianColor,
                    c = c,
                )
                val hz = if (entry.avgMedianDtMs > 0.0) 1000.0 / entry.avgMedianDtMs else 0.0
                DetailRow(
                    label = "Frecuencia equivalente",
                    value = "${"%.1f".format(hz)} Hz",
                    hint = "nominal: 100 Hz",
                    valueColor = medianColor,
                    c = c,
                )
            }

            // ── Calidad ───────────────────────────────────────────────────────
            DetailSection(title = "CALIDAD", c = c) {
                DetailRow(
                    label = "Completitud promedio",
                    value = "${"%.1f".format(entry.avgCompleteness * 100)} %",
                    hint = "OK ≥ 99 %  ·  riesgo ≥ 95 %",
                    valueColor = completenessColor,
                    c = c,
                )
                DetailRow(
                    label = "Jitter P95 promedio",
                    value = "${"%.2f".format(entry.avgJitterP95Ms)} ms",
                    hint = "OK ≤ 3 ms  ·  riesgo ≤ 5 ms",
                    valueColor = jitterColor,
                    c = c,
                )
            }

            // ── Sesiones ──────────────────────────────────────────────────────
            DetailSection(title = "SESIONES ANALIZADAS", c = c) {
                DetailRow(
                    label = "Total de sesiones",
                    value = "${entry.sessionCount}",
                    hint = null,
                    valueColor = c.textPrimary,
                    c = c,
                )
                DetailRow(
                    label = "Tiempo grabado",
                    value = formatMinutes(entry.totalDurationMinutes),
                    hint = null,
                    valueColor = c.textPrimary,
                    c = c,
                )
                DetailRow(
                    label = "Último veredicto",
                    value = entry.lastVerdict,
                    hint = null,
                    valueColor = when (entry.lastVerdict) {
                        "PASS" -> c.accentGreen
                        "WARN" -> c.accentAmber
                        "FAIL" -> c.accentRed
                        else -> c.textSecondary
                    },
                    c = c,
                )
                Spacer(Modifier.height(6.dp))
                // Verdict breakdown bar
                if (entry.sessionCount > 0L) {
                    VerdictBar(entry = entry, c = c)
                }
            }

            // ── Leyenda de colores ────────────────────────────────────────────
            DetailSection(title = "LEYENDA", c = c) {
                LegendRow(color = c.accentGreen, label = "OK — dentro del rango aceptable", c = c)
                LegendRow(color = c.accentAmber, label = "RIESGO — fuera del rango ideal pero tolerable", c = c)
                LegendRow(color = c.accentRed, label = "FALLO — valor fuera del rango aceptable", c = c)
            }

            // ── Cerrar ────────────────────────────────────────────────────────
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.bgCard)
                    .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "CERRAR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = c.accentCyan,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    c: ImuFluxColors,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            fontSize = 8.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = c.textSecondary,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.bgCard)
                .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    hint: String?,
    valueColor: Color,
    c: ImuFluxColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = c.textPrimary,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 9.sp,
                    color = c.textDim,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(valueColor.copy(alpha = 0.12f))
                .border(1.dp, valueColor.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, c: ImuFluxColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = c.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Current status badge
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CurrentStatusBadge(verdict: String, c: ImuFluxColors) {
    val color: Color
    val icon: String
    val title: String
    val subtitle: String
    when (verdict) {
        "PASS" -> {
            color = c.accentGreen
            icon = "✓"
            title = "COMPATIBLE"
            subtitle = "Todos los valores dentro del rango"
        }
        "WARN" -> {
            color = c.accentAmber
            icon = "⚠"
            title = "ZONA DE RIESGO"
            subtitle = "Algún valor fuera del rango ideal"
        }
        "FAIL" -> {
            color = c.accentRed
            icon = "✕"
            title = "NO COMPATIBLE"
            subtitle = "Uno o más valores fuera del rango"
        }
        else -> {
            color = c.textSecondary
            icon = "–"
            title = "SIN DATOS"
            subtitle = "Datos insuficientes para evaluar"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = icon,
                fontSize = 16.sp,
                color = color,
            )
            Column {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = color,
                )
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    color = color.copy(alpha = 0.75f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatChip(
    label: String,
    value: String,
    color: Color,
    c: ImuFluxColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.bgDeep)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                color = c.textDim,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun VerdictBar(entry: DeviceRankingEntry, c: ImuFluxColors) {
    val total = entry.sessionCount.toFloat().coerceAtLeast(1f)
    val passW = entry.passCount / total
    val warnW = entry.warnCount / total
    val failW = entry.failCount / total
    val insuffW = entry.insufficientDataCount / total

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (passW > 0f) {
                Box(
                    modifier = Modifier
                        .weight(passW)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accentGreen),
                )
            }
            if (warnW > 0f) {
                Box(
                    modifier = Modifier
                        .weight(warnW)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accentAmber),
                )
            }
            if (failW > 0f) {
                Box(
                    modifier = Modifier
                        .weight(failW)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accentRed),
                )
            }
            if (insuffW > 0f) {
                Box(
                    modifier = Modifier
                        .weight(insuffW)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.textDim),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.passCount > 0L) VerdictLabel("PASS ${entry.passCount}", c.accentGreen)
            if (entry.warnCount > 0L) VerdictLabel("WARN ${entry.warnCount}", c.accentAmber)
            if (entry.failCount > 0L) VerdictLabel("FAIL ${entry.failCount}", c.accentRed)
            if (entry.insufficientDataCount > 0L) VerdictLabel("INSUF ${entry.insufficientDataCount}", c.textDim)
        }
    }
}

@Composable
private fun VerdictLabel(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 8.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.5.sp,
        color = color,
        fontFamily = FontFamily.Monospace,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Color helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun categoryColor(category: String, c: ImuFluxColors): Color = when (category) {
    "EXCELLENT" -> c.accentGreen
    "GOOD" -> c.accentCyan
    "RISKY" -> c.accentAmber
    "BAD" -> c.accentRed
    else -> c.textSecondary
}

/**
 * Returns the "worst" of the given colors in priority order: red > amber > green > anything else.
 */
private fun worstColor(colors: List<Color>, c: ImuFluxColors): Color = when {
    colors.any { it == c.accentRed } -> c.accentRed
    colors.any { it == c.accentAmber } -> c.accentAmber
    else -> c.accentGreen
}

/**
 * Nominal 100 Hz = 10 ms.
 * OK (green)   : 8.5–10.5 ms.
 * Risk (amber) : 7.0–8.5 ms or 10.5–12.0 ms.
 * Fail (red)   : outside 7.0–12.0 ms.
 */
private fun tintForMedianDt(dtMs: Double, c: ImuFluxColors): Color = when {
    dtMs in MEDIAN_OK_LO..MEDIAN_OK_HI -> c.accentGreen
    dtMs in MEDIAN_RISK_LO..MEDIAN_RISK_HI -> c.accentAmber
    else -> c.accentRed
}

private fun tintForCompleteness(completeness: Double, c: ImuFluxColors): Color = when {
    completeness >= 0.99 -> c.accentGreen
    completeness >= 0.95 -> c.accentAmber
    else -> c.accentRed
}

private fun tintForJitter(jitterMs: Double, c: ImuFluxColors): Color = when {
    jitterMs <= 3.0 -> c.accentGreen
    jitterMs <= 5.0 -> c.accentAmber
    else -> c.accentRed
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatMinutes(minutes: Double): String = when {
    minutes < 1.0 -> "<1 min"
    minutes < 60.0 -> "${minutes.roundToInt()} min"
    else -> "${"%.1f".format(minutes / 60.0)} h"
}

// ─────────────────────────────────────────────────────────────────────────────
// Header icon button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeaderIconButton(
    label: String,
    onClick: () -> Unit,
    c: ImuFluxColors,
) {
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
        Text(
            text = label,
            fontSize = 17.sp,
            color = c.textSecondary,
        )
    }
}
