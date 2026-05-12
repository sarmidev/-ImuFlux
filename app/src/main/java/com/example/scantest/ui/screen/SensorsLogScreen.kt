package com.example.scantest.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scantest.domain.model.DetectionLog
import com.example.scantest.domain.model.LogLevel
import com.example.scantest.domain.model.RecordingHealth
import com.example.scantest.domain.model.SensorType
import com.example.scantest.ui.viewmodel.SensorsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Sensor instrument config
// ─────────────────────────────────────────────────────────────────────────────
private data class SensorTile(
    val type: SensorType,
    val label: String,
    val unit: String,
    val color: Color,
    val decimals: Int = 1,
)

private fun sensorTiles(c: ImuFluxColors) = listOf(
    SensorTile(SensorType.ACCELERATION_MAGNITUDE,     "ACC",   "m/s²",  c.accentCyan,  1),
    SensorTile(SensorType.ANGULAR_VELOCITY_MAGNITUDE, "GYRO",  "rad/s", c.sensorGyro,  2),
    SensorTile(SensorType.TILT_ANGLE_PITCH,           "PITCH", "°",     c.sensorPitch, 0),
    SensorTile(SensorType.TILT_ANGLE_ROLL,            "ROLL",  "°",     c.sensorRoll,  0),
    SensorTile(SensorType.MAGNETIC_HEADING,           "HDG",   "°",     c.accentAmber, 0),
)

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SimpleMovementMonitorScreen(
    viewModel: SensorsViewModel = viewModel(),
    onOpenSessions: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onOpenCompatibilityTest: () -> Unit = {},
    onOpenDeviceRanking: () -> Unit = {},
) {
    val c           = LocalImuFluxColors.current
    val uiState        by viewModel.uiState.collectAsState()
    val snapshot       by viewModel.sensorSnapshotState.collectAsState()
    val health         by viewModel.recordingHealth.collectAsState()
    val sessionId      by viewModel.currentSessionId.collectAsState()
    val logs           by viewModel.logs.collectAsState()
    val recordingStart by viewModel.recordingStartMs.collectAsState()
    val forkliftModel  by viewModel.forkliftModel.collectAsState()
    val warehouse      by viewModel.warehouse.collectAsState()
    val recentForks    by viewModel.recentForklifts.collectAsState()
    val recentWhs      by viewModel.recentWarehouses.collectAsState()
    val isSetupReady   by viewModel.isSetupReady.collectAsState()
    val isRecording    = uiState.isRecording

    // Dialog state for editing forklift / warehouse
    var editingField by remember { mutableStateOf<SetupField?>(null) }
    // Overflow menu visibility
    var showMenu by remember { mutableStateOf(false) }

    // Screen-level animations for the recording state indicator
    val screenTx = rememberInfiniteTransition(label = "screen_fx")
    val borderAlpha by screenTx.animateFloat(
        initialValue = 0.45f,
        targetValue  = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "border_alpha",
    )
    val recPulse by screenTx.animateFloat(
        initialValue = 0.60f,
        targetValue  = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(550),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec_badge_pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to c.bgDeep,
                    1f to c.bgSurface,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Header ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecording) c.accentGreen else c.accentCyan),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "IMUFLUX",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 5.sp,
                                color = c.textPrimary,
                            )
                        }
                        Text(
                            text = if (isRecording) "● grabando ahora" else "Motion Data Recorder",
                            fontSize = 10.sp,
                            color = if (isRecording) c.accentGreen.copy(alpha = 0.80f) else c.textSecondary,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                    // Blinking REC badge while recording
                    if (isRecording) {
                        Spacer(Modifier.width(12.dp))
                        RecBadge(pulse = recPulse, c = c)
                    }
                }

                // Right-side: theme toggle + overflow menu
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderIconButton(
                        label = if (c.isDark) "☀" else "☽",
                        onClick = onToggleTheme,
                        c = c,
                    )
                    HeaderIconButton(
                        label = "⋮",
                        onClick = { showMenu = true },
                        c = c,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Compatibility verdict banner (sólo si hay veredicto WARN/FAIL no descartado) ─
            CompatibilityVerdictBanner(onOpenCompatibilityTest = onOpenCompatibilityTest)

            Spacer(Modifier.height(10.dp))

            // ── Status card ───────────────────────────────────────────────────
            StatusCard(
                isRecording = isRecording,
                sessionId = sessionId,
                health = health,
                recordingStartMs = recordingStart,
                c = c,
            )

            Spacer(Modifier.height(12.dp))

            // ── Session setup (toro + almacén) ────────────────────────────────
            SessionSetupCard(
                forkliftModel = forkliftModel,
                warehouse = warehouse,
                isRecording = isRecording,
                c = c,
                onEditForklift = { editingField = SetupField.FORKLIFT },
                onEditWarehouse = { editingField = SetupField.WAREHOUSE },
            )

            Spacer(Modifier.height(18.dp))

            // ── Record button ─────────────────────────────────────────────────
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RecordButton(
                    isRecording = isRecording,
                    enabled = isRecording || isSetupReady,
                    c = c,
                    onClick = { viewModel.onStartStopClick() },
                )
            }
            if (!isRecording && !isSetupReady) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Completa toro y almacén para poder grabar",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = c.accentAmber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(22.dp))

            // ── Metric chips ──────────────────────────────────────────────────
            MetricChipsRow(health = health, isRecording = isRecording, c = c)

            Spacer(Modifier.height(16.dp))

            // ── Sensor instruments ────────────────────────────────────────────
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                items(sensorTiles(c)) { tile ->
                    SensorInstrument(tile = tile, value = snapshot.values[tile.type], c = c)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Logs header ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(3.dp, 13.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.accentCyan),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "LOGS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${logs.size}",
                        fontSize = 9.sp,
                        color = c.textDim,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = { viewModel.clearLogs() }) {
                    Text(
                        text = "LIMPIAR",
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        color = c.textSecondary,
                    )
                }
            }

            // ── Log list ──────────────────────────────────────────────────────
            RecordingLogDisplay(
                logs = logs,
                c = c,
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
        }

        // Brief flash feedback on start/stop
        if (uiState.showOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(uiState.overlayColor.copy(alpha = 0.10f)),
            )
        }

        // Pulsating green border — highly visible recording state indicator
        if (isRecording) {
            val borderColor = c.accentGreen
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = borderColor.copy(alpha = borderAlpha),
                    style = Stroke(width = 5.dp.toPx()),
                )
            }
        }

        // ── Overflow menu ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { -16 },
            exit  = fadeOut(tween(120)) + slideOutVertically(tween(140)) { -16 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showMenu = false },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 62.dp, end = 20.dp)
                        .width(220.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.bgCard)
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(14.dp))
                        .padding(vertical = 6.dp),
                ) {
                    AppMenuItem(
                        icon = "☰",
                        label = "Sesiones",
                        sublabel = "Historial de grabaciones",
                        c = c,
                        onClick = { showMenu = false; onOpenSessions() },
                    )
                    AppMenuDivider(c)
                    AppMenuItem(
                        icon = "◎",
                        label = "Test de compatibilidad",
                        sublabel = "Verificar fiabilidad del dispositivo",
                        c = c,
                        onClick = { showMenu = false; onOpenCompatibilityTest() },
                    )
                    AppMenuDivider(c)
                    AppMenuItem(
                        icon = "⊕",
                        label = "Calibración",
                        sublabel = "Alineado del dispositivo",
                        c = c,
                        onClick = { showMenu = false; onOpenCalibration() },
                    )
                    AppMenuDivider(c)
                    AppMenuItem(
                        icon = "★",
                        label = "Ranking de Dispositivos",
                        sublabel = "Mejor a peor por compatibilidad",
                        c = c,
                        onClick = { showMenu = false; onOpenDeviceRanking() },
                    )
                }
            }
        }
    }

    // ── Setup field editor dialog ────────────────────────────────────────────
    when (editingField) {
        SetupField.FORKLIFT -> SetupFieldDialog(
            title = "MODELO DE TORO",
            label = "IDENTIFICADOR / MODELO",
            currentValue = forkliftModel,
            recents = recentForks,
            hint = "Se guardará junto con cada fila del CSV",
            c = c,
            onDismiss = { editingField = null },
            onConfirm = { value ->
                viewModel.setForkliftModel(value)
                editingField = null
            },
        )
        SetupField.WAREHOUSE -> SetupFieldDialog(
            title = "ALMACÉN",
            label = "NOMBRE DEL ALMACÉN",
            currentValue = warehouse,
            recents = recentWhs,
            hint = "Se guardará junto con cada fila del CSV",
            c = c,
            onDismiss = { editingField = null },
            onConfirm = { value ->
                viewModel.setWarehouse(value)
                editingField = null
            },
        )
        null -> Unit
    }
}

private enum class SetupField { FORKLIFT, WAREHOUSE }

// ─────────────────────────────────────────────────────────────────────────────
// Reusable header icon button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HeaderIconButton(
    onClick: () -> Unit,
    c: ImuFluxColors,
    label: String? = null,
    content: (@Composable () -> Unit)? = null,
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
        if (label != null) {
            Text(text = label, fontSize = 15.sp, color = c.textSecondary)
        } else {
            content?.invoke()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Overflow menu items
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppMenuItem(
    icon: String,
    label: String,
    sublabel: String,
    c: ImuFluxColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 17.sp, color = c.accentCyan, modifier = Modifier.width(28.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.textPrimary,
            )
            Text(sublabel, fontSize = 10.sp, color = c.textSecondary)
        }
    }
}

@Composable
private fun AppMenuDivider(c: ImuFluxColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(c.bgCardBorder),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// REC badge (header)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RecBadge(pulse: Float, c: ImuFluxColors) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(c.accentRed.copy(alpha = pulse))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "REC",
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                color = Color.White,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StatusCard(
    isRecording: Boolean,
    sessionId: String?,
    health: RecordingHealth,
    recordingStartMs: Long?,
    c: ImuFluxColors,
) {
    // Tick every second while recording to update the elapsed duration
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording, recordingStartMs) {
        if (isRecording && recordingStartMs != null) {
            while (true) {
                elapsedMs = System.currentTimeMillis() - recordingStartMs
                delay(1000L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    val hasHighDrops  = health.framesDropped > 200L
    val hasHighJitter = health.jitterP95Ns > 10_000_000L
    val borderColor = when {
        !isRecording                  -> c.bgCardBorder
        hasHighDrops && hasHighJitter -> c.accentRed.copy(alpha = 0.4f)
        hasHighDrops || hasHighJitter -> c.accentAmber.copy(alpha = 0.4f)
        else                          -> c.accentGreen.copy(alpha = 0.25f)
    }
    val healthColor = when {
        !isRecording                  -> c.textSecondary
        hasHighDrops && hasHighJitter -> c.accentRed
        hasHighDrops || hasHighJitter -> c.accentAmber
        else                          -> c.accentGreen
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgCard)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) c.accentGreen else c.textSecondary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) "GRABANDO" else "EN ESPERA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        color = if (isRecording) c.accentGreen else c.textSecondary,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = sessionId ?: "—",
                    fontSize = 11.sp,
                    color = c.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isRecording) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatRecordingDuration(elapsedMs),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "%.2f MB".format(health.bytesWritten / (1024.0 * 1024.0)),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = healthColor,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "chunk #${health.currentChunkIndex}  ·  ${health.framesWritten} frames",
                        fontSize = 10.sp,
                        color = c.textSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun formatRecordingDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%dh %02dm %02ds".format(h, m, s)
        m > 0 -> "%dm %02ds".format(m, s)
        else  -> "%ds".format(s)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pulsating record button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RecordButton(
    isRecording: Boolean,
    c: ImuFluxColors,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val ring1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring1",
    )
    val ring2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseOut, delayMillis = 800),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring2",
    )

    // Capture colors before entering DrawScope
    val accentRed   = c.accentRed
    val accentCyan  = c.accentCyan
    val bgCard      = c.bgCard

    Box(
        modifier = Modifier
            .size(164.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center    = Offset(size.width / 2f, size.height / 2f)
            val btnRadius = size.minDimension * 0.375f
            val maxRadius = size.minDimension * 0.47f

            if (isRecording) {
                listOf(ring1 to 0.50f, ring2 to 0.50f).forEach { (progress, maxAlpha) ->
                    val r     = btnRadius + (maxRadius - btnRadius) * progress
                    val alpha = maxAlpha * (1f - progress)
                    drawCircle(
                        color = accentRed.copy(alpha = alpha),
                        radius = r,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                drawCircle(color = accentRed, radius = btnRadius, center = center)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.20f),
                    radius = btnRadius * 0.60f,
                    center = center,
                )
            } else {
                val strokeAlpha = if (enabled) 1f else 0.30f
                drawCircle(color = bgCard, radius = btnRadius, center = center)
                drawCircle(
                    color = accentCyan.copy(alpha = strokeAlpha),
                    radius = btnRadius,
                    center = center,
                    style = Stroke(width = 1.5f.dp.toPx()),
                )
                drawCircle(
                    color = accentCyan.copy(alpha = 0.05f * strokeAlpha),
                    radius = btnRadius * 0.75f,
                    center = center,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isRecording) {
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White),
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "PARAR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp,
                    color = Color.White,
                )
            } else {
                val dotAlpha = if (enabled) 1f else 0.30f
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(c.accentCyan.copy(alpha = dotAlpha)),
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "GRABAR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp,
                    color = c.accentCyan.copy(alpha = dotAlpha),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Metric chips
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MetricChipsRow(health: RecordingHealth, isRecording: Boolean, c: ImuFluxColors) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricChip(
            label = "HZ",
            value = if (isRecording) "%.0f".format(health.samplesPerSecond) else "—",
            valueColor = when {
                !isRecording                  -> c.textSecondary
                health.samplesPerSecond < 90f -> c.accentAmber
                else                          -> c.textPrimary
            },
            c = c,
            modifier = Modifier.weight(1f),
        )
        MetricChip(
            label = "JITTER",
            value = if (isRecording) "${"%.1f".format(health.jitterP95Ns / 1_000_000.0)}ms" else "—",
            valueColor = when {
                !isRecording                     -> c.textSecondary
                health.jitterP95Ns > 10_000_000L -> c.accentRed
                health.jitterP95Ns > 5_000_000L  -> c.accentAmber
                else                             -> c.textPrimary
            },
            c = c,
            modifier = Modifier.weight(1f),
        )
        MetricChip(
            label = "DROPS",
            value = if (isRecording) "${health.framesDropped}" else "—",
            valueColor = when {
                !isRecording               -> c.textSecondary
                health.framesDropped > 200 -> c.accentRed
                health.framesDropped > 25  -> c.accentAmber
                else                       -> c.textPrimary
            },
            c = c,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    valueColor: Color,
    c: ImuFluxColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
            color = c.textSecondary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sensor instrument card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SensorInstrument(tile: SensorTile, value: Float?, c: ImuFluxColors) {
    val hasValue   = value != null
    val displayVal = if (hasValue) "%.${tile.decimals}f".format(value) else "—"
    val valColor   = if (hasValue) tile.color else c.textDim

    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (hasValue) tile.color else c.textDim),
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = tile.label,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = displayVal,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = valColor,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = tile.unit,
            fontSize = 8.sp,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(9.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Log list
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RecordingLogDisplay(
    logs: List<DetectionLog>,
    c: ImuFluxColors,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .background(c.bgCard)
            .border(
                width = 1.dp,
                color = c.bgCardBorder,
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (logs.isEmpty()) {
            item {
                Text(
                    text = "Sin eventos registrados.",
                    fontSize = 11.sp,
                    color = c.textDim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        } else {
            items(logs.size) { idx ->
                val log = logs[idx]
                val textColor = when (log.level) {
                    LogLevel.OK      -> c.accentGreen
                    LogLevel.WARNING -> c.accentAmber
                    LogLevel.ERROR   -> c.accentRed
                }
                val prefix = when (log.level) {
                    LogLevel.OK      -> "·"
                    LogLevel.WARNING -> "▲"
                    LogLevel.ERROR   -> "✕"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = prefix,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(14.dp),
                    )
                    Text(
                        text = formatter.format(Date(log.timestamp)),
                        fontSize = 9.sp,
                        color = c.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(54.dp),
                    )
                    Text(
                        text = log.message,
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (log.level == LogLevel.ERROR) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
