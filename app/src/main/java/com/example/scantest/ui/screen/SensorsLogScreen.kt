package com.example.scantest.ui.screen

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scantest.domain.DetectionLog
import com.example.scantest.domain.SensorType
import com.example.scantest.ui.viewmodel.SensorsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleMovementMonitorScreen(
    viewModel: SensorsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val latestSensorSnapshot by viewModel.sensorSnapshotState.collectAsState()

    val lastDisplayedValues = remember { mutableStateMapOf<SensorType, Float>() }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    viewModel.onSaveSensorsData(uri)
                }
            } else {
                // Ensure the dialog state is reset even if the user cancels the picker
                viewModel.onDismissSaveDialog()
            }
        }
    )

    // Launch the file picker as a side-effect when showSaveDialog becomes true
    LaunchedEffect(uiState.showSaveDialog) {
        if (uiState.showSaveDialog) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "sensors_${System.currentTimeMillis()}.csv")
            }
            createDocumentLauncher.launch(intent)
        }
    }


    LaunchedEffect(latestSensorSnapshot) {
        latestSensorSnapshot.values.forEach { (sensorType, currentValue) ->
            lastDisplayedValues[sensorType] = currentValue
        }
    }

    val detectedMovement by viewModel.detectedMovement.collectAsState()

    val logMessages = remember { mutableStateListOf<DetectionLog>() }

    LaunchedEffect(detectedMovement) {
        val movement = detectedMovement
        if (movement != null) {
            logMessages.add(0, DetectionLog(
                message = "🚨 MOVIMIENTO DETECTADO: ${movement.name}. Acción: ${movement.name.replace('_', ' ')}",
                isAlert = true
            )
            )
            // Limitar el log para no consumir demasiada memoria
            if (logMessages.size > 50) logMessages.removeAt(logMessages.lastIndex)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Monitor de Sensores y Movimientos") }) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SensorValuesDisplay(latestSensorSnapshot.values)

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Log de Detección",
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = { viewModel.onStartStopClick() },
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(if (uiState.isRecording) "Parar Grabación" else "Empezar a Grabar")
                }
                DetectionLogDisplay(logMessages)

                // The logic to launch the picker has been moved to a LaunchedEffect
                // and is no longer needed here.
            }

            // Overlay that appears when recording starts (green) or stops (orange)
            if (uiState.showOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(uiState.overlayColor.copy(alpha = 0.7f))
                )
            }
        }
    }
}

@Composable
fun SensorValuesDisplay(sensorValues: Map<SensorType, Float>) {
    Column {
        Text(
            text = "Valores de Sensores (Tiempo Real)",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
            items(sensorValues.entries.toList().size) {  id ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = sensorValues.entries.toList()[id].key.name.replace('_', ' '),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f", sensorValues.entries.toList()[id].value),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun DetectionLogDisplay(logMessages: List<DetectionLog>) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.05f))
            .padding(4.dp)
    ) {
        items(logMessages.size) { id ->
            val color = if (logMessages[id].isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = "[${formatter.format(Date(logMessages[id].timestamp))}] ",
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.7f)
                )
                Text(
                    text = logMessages[id].message,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    fontWeight = if (logMessages[id].isAlert) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
