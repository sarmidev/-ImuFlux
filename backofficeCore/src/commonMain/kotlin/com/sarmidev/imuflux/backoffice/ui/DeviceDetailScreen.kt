package com.sarmidev.imuflux.backoffice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sarmidev.imuflux.backoffice.state.DetailUiState
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.ImuHealthWindow
import com.sarmidev.imuflux.data.diagnostics.ImuSessionDiagnostics

@Composable
fun DeviceDetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val windowSize = currentWindowSize()
    val isCompact = windowSize.isCompact
    val contentPadding = contentPaddingFor(windowSize.widthClass)

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isCompact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.deviceId,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    ) { Text("← Volver") }
                    Button(
                        onClick = onRefresh,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    ) { Text("Refrescar") }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack) { Text("← Volver") }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    state.deviceId,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = onRefresh, enabled = !state.isLoading) { Text("Refrescar") }
            }
        }

        if (!isCompact) {
            RemoteControlBar(state, isCompact = false, onStartRecording, onStopRecording)
        }

        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (state.error != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            if (isCompact) {
                item(key = "remote") {
                    RemoteControlBar(state, isCompact = true, onStartRecording, onStopRecording)
                }
            }
            state.summary?.let { summary ->
                item { SummaryCard(summary, stackedKeys = isCompact) }
            }
            item {
                SectionTitle("Sesiones recientes (${state.recentSessions.size})")
            }
            if (state.recentSessions.isEmpty()) {
                item { EmptyHint("Sin sesiones recientes.") }
            } else {
                items(state.recentSessions, key = { it.sessionId }) {
                    SessionCard(it, stackedKeys = isCompact)
                }
            }
            item {
                SectionTitle("Health windows recientes (${state.recentWindows.size})")
            }
            if (state.recentWindows.isEmpty()) {
                item { EmptyHint("Sin health windows recientes.") }
            } else {
                items(state.recentWindows, key = { it.windowId }) {
                    WindowCard(it, stackedKeys = isCompact)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteControlBar(
    state: DetailUiState,
    isCompact: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val summary = state.summary
    val isRecording = summary?.isRecording == true
    val remoteAvailable = state.remoteControlAvailable

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Control remoto", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecordingBadge(isRecording)
                        RemoteAvailabilityBadge(remoteAvailable)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Control remoto", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    RecordingBadge(isRecording)
                    RemoteAvailabilityBadge(remoteAvailable)
                }
            }

            if (!remoteAvailable) {
                Text(
                    "Este dispositivo no ha registrado un token FCM válido. El control remoto se " +
                        "activará cuando la app móvil publique su token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStartRecording,
                        enabled = remoteAvailable && !isRecording && !state.commandInProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text("Iniciar grabación") }
                    OutlinedButton(
                        onClick = onStopRecording,
                        enabled = remoteAvailable && isRecording && !state.commandInProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text("Parar grabación") }
                    if (state.commandInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onStartRecording,
                        enabled = remoteAvailable && !isRecording && !state.commandInProgress,
                    ) { Text("Iniciar grabación") }
                    OutlinedButton(
                        onClick = onStopRecording,
                        enabled = remoteAvailable && isRecording && !state.commandInProgress,
                    ) { Text("Parar grabación") }
                    if (state.commandInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (state.commandFeedback != null) {
                Text(
                    state.commandFeedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.commandFeedbackIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            Text(
                "El estado real proviene de Firestore (isRecording). Tras enviar un comando se " +
                    "refresca automáticamente.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(summary: DeviceHealthSummary, stackedKeys: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stackedKeys) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resumen", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecordingBadge(summary.isRecording)
                        StatusBadge(summary.currentHealthStatus)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resumen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    RecordingBadge(summary.isRecording)
                    StatusBadge(summary.currentHealthStatus)
                }
            }
            KeyValueRow("Almacén", summary.warehouseName.ifBlank { summary.warehouseId.ifBlank { "—" } }, stacked = stackedKeys)
            KeyValueRow("Carretilla", summary.forkliftModel.ifBlank { summary.forkliftId.ifBlank { "—" } }, stacked = stackedKeys)
            KeyValueRow(
                "Dispositivo",
                listOf(summary.manufacturer, summary.deviceModel).filter { it.isNotBlank() }
                    .joinToString(" ").ifBlank { "—" },
                stacked = stackedKeys,
            )
            KeyValueRow(
                "App",
                "v${summary.appVersion.ifBlank { "?" }} (${summary.buildNumber}) · SDK ${summary.androidVersion}",
                stacked = stackedKeys,
            )
            KeyValueRow(
                "Última vez",
                "${DiagnosticsFormat.wallMs(summary.lastSeenAt)} (${DiagnosticsFormat.relative(summary.lastSeenAt)})",
                stacked = stackedKeys,
            )
            if (summary.currentHealthReasons.isNotEmpty()) {
                KeyValueRow("Motivos", summary.currentHealthReasons.joinToString(", "), stacked = stackedKeys)
            }
            MetricsFlow(modifier = Modifier.padding(top = 4.dp)) {
                MetricCell("Hz", DiagnosticsFormat.hz(summary.lastMeasuredSampleRateHz))
                MetricCell("Jitter p95", DiagnosticsFormat.ms(summary.lastJitterP95Ms))
                MetricCell("Frames perdidos", summary.lastFramesDropped.toString())
                MetricCell("Bytes", DiagnosticsFormat.bytes(summary.lastBytesWritten))
                MetricCell("Subidas pendientes", summary.pendingUploadChunks.toString())
            }
            if (summary.lastErrorMessageSafe != null) {
                KeyValueRow("Último error", summary.lastErrorMessageSafe!!, stacked = stackedKeys)
            }
        }
    }
}

@Composable
private fun SessionCard(session: ImuSessionDiagnostics, stackedKeys: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    session.sessionId,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(session.healthStatus)
            }
            KeyValueRow("Inicio", DiagnosticsFormat.wallMs(session.startedAtWallMs), stacked = stackedKeys)
            KeyValueRow("Duración", DiagnosticsFormat.durationMs(session.durationMs), stacked = stackedKeys)
            KeyValueRow("Motivo de parada", session.recordingStopReason.name, stacked = stackedKeys)
            MetricsFlow(modifier = Modifier.padding(top = 2.dp)) {
                MetricCell("Hz medido", DiagnosticsFormat.hz(session.measuredSampleRateHz))
                MetricCell("Jitter p95", DiagnosticsFormat.ms(session.jitterP95Ms))
                MetricCell("Frames perdidos", session.framesDropped.toString())
                MetricCell("Chunks", "${session.uploadedChunkCount}/${session.chunkCount}")
                MetricCell("Pendientes", session.pendingChunkCount.toString())
            }
            if (session.healthReasons.isNotEmpty()) {
                KeyValueRow("Motivos", session.healthReasons.joinToString(", "), stacked = stackedKeys)
            }
        }
    }
}

@Composable
private fun WindowCard(window: ImuHealthWindow, stackedKeys: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    DiagnosticsFormat.wallMs(window.endedAt),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(window.healthStatus)
            }
            MetricsFlow {
                MetricCell("Samples/s", DiagnosticsFormat.hz(window.samplesPerSecond))
                MetricCell("Jitter p95", DiagnosticsFormat.ms(window.jitterP95Ms))
                MetricCell("Frames perdidos", window.framesDropped.toString())
                MetricCell("Bytes", DiagnosticsFormat.bytes(window.bytesWritten))
                MetricCell("Pendientes", window.uploadPendingCount.toString())
            }
            if (window.healthReasons.isNotEmpty()) {
                KeyValueRow("Motivos", window.healthReasons.joinToString(", "), stacked = stackedKeys)
            }
        }
    }
}
