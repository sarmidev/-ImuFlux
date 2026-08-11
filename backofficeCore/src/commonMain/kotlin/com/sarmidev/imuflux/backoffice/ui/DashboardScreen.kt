package com.sarmidev.imuflux.backoffice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sarmidev.imuflux.backoffice.state.DashboardUiState
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus

@Composable
fun DashboardScreen(
    adminEmail: String,
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onWarehouseFilter: (String?) -> Unit,
    onForkliftFilter: (String?) -> Unit,
    onHealthFilter: (DiagnosticsHealthStatus?) -> Unit,
    onTextQuery: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Diagnostics — Dispositivos", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.filtered.size} de ${state.allDevices.size} dispositivos · $adminEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRefresh, enabled = !state.isLoading) { Text("Refrescar") }
            Box(Modifier.width(8.dp))
            OutlinedButton(onClick = onLogout) { Text("Salir") }
        }

        FilterBar(
            state = state,
            onWarehouseFilter = onWarehouseFilter,
            onForkliftFilter = onForkliftFilter,
            onHealthFilter = onHealthFilter,
            onTextQuery = onTextQuery,
            onClearFilters = onClearFilters,
        )

        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (state.error != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (!state.isLoading && state.error == null && state.filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No hay dispositivos que coincidan.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(state.filtered, key = { it.deviceId }) { device ->
                    DeviceRow(device, onClick = { onSelectDevice(device.deviceId) })
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    state: DashboardUiState,
    onWarehouseFilter: (String?) -> Unit,
    onForkliftFilter: (String?) -> Unit,
    onHealthFilter: (DiagnosticsHealthStatus?) -> Unit,
    onTextQuery: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.textQuery,
            onValueChange = onTextQuery,
            label = { Text("Buscar (deviceId / modelo)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        FilterDropdown(
            label = "Almacén",
            options = state.warehouseOptions,
            selected = state.filters.warehouseId,
            onSelect = onWarehouseFilter,
        )
        FilterDropdown(
            label = "Carretilla",
            options = state.forkliftOptions,
            selected = state.filters.forkliftId,
            onSelect = onForkliftFilter,
        )
        HealthDropdown(selected = state.filters.healthStatus, onSelect = onHealthFilter)
        TextButton(onClick = onClearFilters) { Text("Limpiar") }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (selected.isNullOrBlank()) label else "$label: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Todos") }, onClick = { onSelect(null); expanded = false })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun HealthDropdown(
    selected: DiagnosticsHealthStatus?,
    onSelect: (DiagnosticsHealthStatus?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (selected == null) "Estado" else "Estado: ${DiagnosticsFormat.statusLabel(selected)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Todos") }, onClick = { onSelect(null); expanded = false })
            DiagnosticsHealthStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(DiagnosticsFormat.statusLabel(status)) },
                    onClick = { onSelect(status); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceHealthSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.deviceId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val assignment = listOfNotNull(
                        device.warehouseName.ifBlank { device.warehouseId.ifBlank { null } },
                        device.forkliftModel.ifBlank { device.forkliftId.ifBlank { null } },
                    ).joinToString(" · ").ifBlank { "Sin asignación" }
                    Text(
                        assignment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecordingBadge(device.isRecording)
                if (device.hasRemoteControl) RemoteAvailabilityBadge(available = true)
                StatusBadge(device.currentHealthStatus)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val hw = listOf(device.manufacturer, device.deviceModel).filter { it.isNotBlank() }
                    .joinToString(" ").ifBlank { "—" }
                Text(
                    "$hw · v${device.appVersion.ifBlank { "?" }} (${device.buildNumber})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MetricCell("Hz", DiagnosticsFormat.hz(device.lastMeasuredSampleRateHz))
                MetricCell("Jitter p95", DiagnosticsFormat.ms(device.lastJitterP95Ms))
                MetricCell("Frames perdidos", device.lastFramesDropped.toString())
                MetricCell("Subidas pendientes", device.pendingUploadChunks.toString())
                MetricCell("Última vez", DiagnosticsFormat.relative(device.lastSeenAt))
                MetricCell(
                    "Última grabación",
                    DiagnosticsFormat.relative(device.lastRecordingAt.takeIf { it > 0 }),
                )
            }
        }
    }
}
