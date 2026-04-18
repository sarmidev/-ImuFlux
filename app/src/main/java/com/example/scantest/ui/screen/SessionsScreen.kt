package com.example.scantest.ui.screen

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.scantest.domain.model.SessionSummary
import com.example.scantest.domain.usecase.ExportSessionUseCase
import com.example.scantest.ui.viewmodel.SessionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { viewModel.onExportDestinationPicked(it) }
            } else {
                viewModel.cancelExport()
            }
        },
    )

    LaunchedEffect(state.pendingExportSessionId) {
        val pending = state.pendingExportSessionId ?: return@LaunchedEffect
        val ext = if (state.pendingExportFormat == ExportSessionUseCase.Format.ZIP) "zip" else "csv"
        val mime = if (ext == "zip") "application/zip" else "text/csv"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, "imuflux_${pending}.$ext")
        }
        exportLauncher.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sesiones grabadas") })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Volver") }
                OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refrescar") }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                state.isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.padding(horizontal = 8.dp))
                        Text("Cargando...")
                    }
                }
                state.sessions.isEmpty() -> {
                    Text(
                        text = "No hay sesiones grabadas.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.sessions) { session ->
                            SessionRow(
                                session = session,
                                onExportCsv = {
                                    viewModel.requestExport(session.sessionId, ExportSessionUseCase.Format.SINGLE_CSV)
                                },
                                onExportZip = {
                                    viewModel.requestExport(session.sessionId, ExportSessionUseCase.Format.ZIP)
                                },
                                onDelete = { viewModel.deleteSession(session.sessionId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    onExportCsv: () -> Unit,
    onExportZip: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.sessionId + if (session.isActive) " (activa)" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDelete, enabled = !session.isActive) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar")
                }
            }
            Text(
                text = "Inicio: ${formatter.format(Date(session.startedAtWallMs))}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Duración: ${formatDuration(session.durationMs)}" +
                    "   chunks: ${session.chunkCount}" +
                    "   tamaño: ${"%.2f MB".format(session.totalBytes / (1024.0 * 1024.0))}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportCsv, enabled = !session.isActive) { Text("Exportar CSV") }
                OutlinedButton(onClick = onExportZip, enabled = !session.isActive) { Text("Exportar ZIP") }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d h %02d min".format(h, m)
        m > 0 -> "%d min %02d s".format(m, s)
        else -> "%d s".format(s)
    }
}
