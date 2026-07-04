package com.sarmidev.imuflux.ui.screen

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics
import com.sarmidev.imuflux.ui.viewmodel.DeviceDiagnosticsDetailViewModel
import kotlin.math.roundToInt

@Composable
fun DeviceDiagnosticsDetailScreen(
    deviceId: String,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit = {},
    viewModel: DeviceDiagnosticsDetailViewModel = hiltViewModel(),
) {
    val c = LocalImuFluxColors.current
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(deviceId) { viewModel.load(deviceId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgDeep)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "DETALLE DISPOSITIVO", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = c.textSecondary)
                Text(text = deviceId, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textPrimary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagHeaderIconButton(if (c.isDark) "☀" else "☽", onToggleTheme, c)
                DiagHeaderIconButton("↺", { viewModel.load(deviceId) }, c)
                DiagHeaderIconButton("←", onBack, c)
            }
        }

        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accentCyan, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            state.error != null -> Text(text = state.error ?: "Error", color = c.accentRed, fontSize = 12.sp)
            state.summary == null -> Text(text = "Sin datos para este dispositivo.", color = c.textSecondary, fontSize = 12.sp)
            else -> {
                val s = state.summary!!
                LatestSummarySection(s, c)
                UploadStateSection(s, c)
                SessionsSection(state.recentSessions, c)
                HealthWindowsSection(state.recentWindows, c)
                SafeErrorsSection(s, c)
            }
        }
    }
}

@Composable
private fun LatestSummarySection(s: DeviceHealthSummary, c: ImuFluxColors) {
    DetailCard(title = "RESUMEN ACTUAL", c = c) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (s.isRecording) "● GRABANDO" else "DETENIDO", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = if (s.isRecording) c.accentRed else c.textSecondary)
            DiagnosticsStatusBadge(s.currentHealthStatus, c)
        }
        if (s.currentHealthReasons.isNotEmpty()) {
            KeyValue("Motivos", s.currentHealthReasons.joinToString(", "), c, statusColor(s.currentHealthStatus, c))
        }
        KeyValue("Warehouse", "${s.warehouseName.ifBlank { "—" }} (${s.warehouseId.ifBlank { "—" }})", c)
        KeyValue("Forklift", "${s.forkliftModel.ifBlank { "—" }} (${s.forkliftId.ifBlank { "—" }})", c)
        KeyValue("Sample rate", "${s.lastMeasuredSampleRateHz.roundToInt()} Hz", c)
        KeyValue("Jitter p95", "${"%.2f".format(s.lastJitterP95Ms)} ms", c)
        KeyValue("Frames dropped", "${s.lastFramesDropped}", c)
        KeyValue("Chunk index", "${s.lastChunkIndex}", c)
        KeyValue("Bytes escritos", "${s.lastBytesWritten / 1024} KB", c)
        KeyValue("Última sesión", s.lastSessionId ?: "—", c)
        KeyValue("Visto", "${formatRelative(s.lastSeenAt)} (${formatWallMs(s.lastSeenAt)})", c)
        KeyValue("Dispositivo", "${s.manufacturer} ${s.deviceModel} · API ${s.androidVersion} · v${s.appVersion} (${s.buildNumber})", c)
    }
}

@Composable
private fun UploadStateSection(s: DeviceHealthSummary, c: ImuFluxColors) {
    DetailCard(title = "ESTADO DE SUBIDA", c = c) {
        KeyValue("Pendientes", "${s.pendingUploadChunks}", c, if (s.pendingUploadChunks > 0) c.accentAmber else c.accentGreen)
        KeyValue("Último éxito", "${formatRelative(s.lastUploadSuccessAt)} (${formatWallMs(s.lastUploadSuccessAt)})", c)
        KeyValue("Último fallo", "${formatRelative(s.lastUploadFailureAt)} (${formatWallMs(s.lastUploadFailureAt)})", c)
    }
}

@Composable
private fun SessionsSection(sessions: List<ImuSessionDiagnostics>, c: ImuFluxColors) {
    DetailCard(title = "SESIONES RECIENTES (${sessions.size})", c = c) {
        if (sessions.isEmpty()) {
            Text(text = "Sin sesiones registradas.", fontSize = 11.sp, color = c.textDim)
        } else {
            sessions.forEach { session ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = session.sessionId, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textPrimary, fontFamily = FontFamily.Monospace)
                        DiagnosticsStatusBadge(session.healthStatus, c)
                    }
                    if (session.resumeOf != null) {
                        Text(text = "↳ continuación de ${session.resumeOf}", fontSize = 9.sp, color = c.accentCyan)
                    }
                    Text(
                        text = "${formatDurationMs(session.durationMs)} · ${session.measuredSampleRateHz.roundToInt()} Hz · " +
                            "jit ${"%.1f".format(session.jitterP95Ms)} · drop ${session.framesDropped} · " +
                            "chunks ${session.uploadedChunkCount}/${session.chunkCount} · stop ${session.recordingStopReason.name}",
                        fontSize = 9.sp,
                        color = c.textSecondary,
                    )
                    if (session.healthReasons.isNotEmpty() && session.healthStatus.name != "OK") {
                        Text(text = session.healthReasons.joinToString(", "), fontSize = 9.sp, color = statusColor(session.healthStatus, c))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun HealthWindowsSection(windows: List<ImuHealthWindow>, c: ImuFluxColors) {
    DetailCard(title = "VENTANAS DE SALUD (${windows.size})", c = c) {
        if (windows.isEmpty()) {
            Text(text = "Sin ventanas registradas.", fontSize = 11.sp, color = c.textDim)
        } else {
            windows.forEach { w ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = formatWallMs(w.endedAt), fontSize = 10.sp, color = c.textPrimary, fontFamily = FontFamily.Monospace)
                        Text(
                            text = "${w.samplesPerSecond.roundToInt()} Hz · jit ${"%.1f".format(w.jitterP95Ms)} · drop ${w.framesDropped} · pend ${w.uploadPendingCount}",
                            fontSize = 9.sp,
                            color = c.textSecondary,
                        )
                    }
                    DiagnosticsStatusBadge(w.healthStatus, c)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SafeErrorsSection(s: DeviceHealthSummary, c: ImuFluxColors) {
    DetailCard(title = "ERRORES RECIENTES (SEGUROS)", c = c) {
        KeyValue("Errores no fatales", "${s.nonFatalErrorCount}", c, if (s.nonFatalErrorCount > 0) c.accentAmber else c.textSecondary)
        Text(
            text = s.lastErrorMessageSafe ?: "Sin errores registrados.",
            fontSize = 10.sp,
            color = if (s.lastErrorMessageSafe != null) c.accentRed else c.textDim,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DetailCard(title: String, c: ImuFluxColors, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = c.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.bgCard)
                .border(1.dp, c.bgCardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, c: ImuFluxColors, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(text = label, fontSize = 10.sp, color = c.textSecondary, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: c.textPrimary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.58f),
        )
    }
}
