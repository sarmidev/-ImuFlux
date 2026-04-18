package com.example.scantest.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleMovementMonitorScreen(
    viewModel: SensorsViewModel = viewModel(),
    onOpenSessions: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val latestSensorSnapshot by viewModel.sensorSnapshotState.collectAsState()
    val recordingHealth by viewModel.recordingHealth.collectAsState()
    val sessionId by viewModel.currentSessionId.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ImuFlux — Monitor IMU") },
                actions = {
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.Default.List, contentDescription = "Sesiones")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                SensorValuesDisplay(latestSensorSnapshot.values)

                Spacer(modifier = Modifier.height(12.dp))

                RecordingHealthPanel(
                    isRecording = uiState.isRecording,
                    sessionId = sessionId,
                    health = recordingHealth,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.onStartStopClick() },
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .fillMaxWidth(),
                ) {
                    Text(if (uiState.isRecording) "Parar Grabación" else "Empezar a Grabar")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "LOGS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { viewModel.clearLogs() }) {
                        Text("Limpiar", style = MaterialTheme.typography.bodySmall)
                    }
                }
                RecordingLogDisplay(
                    logs = logs,
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .fillMaxWidth(),
                )
            }

            if (uiState.showOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(uiState.overlayColor.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
fun SensorValuesDisplay(sensorValues: Map<SensorType, Float>) {
    Column {
        Text(
            text = "Valores de Sensores (10 Hz)",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
            val entries = sensorValues.entries.toList()
            items(entries.size) { id ->
                val entry = entries[id]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = entry.key.name.replace('_', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%.2f", entry.value),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingHealthPanel(
    isRecording: Boolean,
    sessionId: String?,
    health: RecordingHealth,
) {
    // Verde: grabando bien. Naranja: drops > 200 o jitter p95 > 10 ms.
    // Rojo queda para casos realmente críticos (muchos drops + jitter extremo).
    val hasHighDrops = health.framesDropped > 200L
    val hasHighJitter = health.jitterP95Ns > 10_000_000L  // > 10 ms
    val containerColor = when {
        !isRecording -> MaterialTheme.colorScheme.surfaceVariant
        hasHighDrops && hasHighJitter -> MaterialTheme.colorScheme.errorContainer
        hasHighDrops || hasHighJitter -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(12.dp),
    ) {
        Column {
            Text(
                text = if (isRecording) "Grabando sesión $sessionId" else "Parado",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "samples/s: ${"%.1f".format(health.samplesPerSecond)}" +
                    "   jitter p95: ${"%.2f".format(health.jitterP95Ns / 1_000_000.0)} ms",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "chunk #${health.currentChunkIndex}   escritos: ${health.framesWritten}" +
                    "   drops: ${health.framesDropped}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "bytes: ${"%.2f MB".format(health.bytesWritten / (1024.0 * 1024.0))}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun RecordingLogDisplay(
    logs: List<DetectionLog>,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberLazyListState()

    val colorOk = Color(0xFF2E7D32)      // verde oscuro
    val colorWarning = Color(0xFFE65100) // naranja
    val colorError = Color(0xFFC62828)   // rojo

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(logs.size) { idx ->
            val log = logs[idx]
            val textColor = when (log.level) {
                LogLevel.OK -> colorOk
                LogLevel.WARNING -> colorWarning
                LogLevel.ERROR -> colorError
            }
            val prefix = when (log.level) {
                LogLevel.OK -> "✓"
                LogLevel.WARNING -> "⚠"
                LogLevel.ERROR -> "✗"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "$prefix ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "[${formatter.format(Date(log.timestamp))}]  ",
                    fontSize = 10.sp,
                    color = textColor.copy(alpha = 0.65f),
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = log.message,
                    fontSize = 11.sp,
                    color = textColor,
                    fontWeight = if (log.level == LogLevel.ERROR) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
