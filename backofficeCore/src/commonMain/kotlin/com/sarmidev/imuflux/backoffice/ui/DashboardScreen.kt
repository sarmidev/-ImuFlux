package com.sarmidev.imuflux.backoffice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sarmidev.imuflux.backoffice.state.DashboardUiState
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus

@OptIn(ExperimentalLayoutApi::class)
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
    val windowSize = currentWindowSize()
    val isCompact = windowSize.isCompact
    val contentPadding = contentPaddingFor(windowSize.widthClass)

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isCompact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Diagnostics — Dispositivos",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${state.filtered.size} de ${state.allDevices.size} dispositivos · $adminEmail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRefresh,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    ) { Text("Refrescar") }
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    ) { Text("Salir") }
                }
            }
        } else {
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
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onLogout) { Text("Salir") }
            }
        }

        if (!isCompact) {
            FilterBar(
                state = state,
                isCompact = false,
                onWarehouseFilter = onWarehouseFilter,
                onForkliftFilter = onForkliftFilter,
                onHealthFilter = onHealthFilter,
                onTextQuery = onTextQuery,
                onClearFilters = onClearFilters,
            )
        }

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

        val showEmpty = !state.isLoading && state.error == null && state.filtered.isEmpty()
        if (!isCompact && showEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No hay dispositivos que coincidan.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isCompact) {
                    item(key = "filters") {
                        FilterBar(
                            state = state,
                            isCompact = true,
                            onWarehouseFilter = onWarehouseFilter,
                            onForkliftFilter = onForkliftFilter,
                            onHealthFilter = onHealthFilter,
                            onTextQuery = onTextQuery,
                            onClearFilters = onClearFilters,
                        )
                    }
                }
                if (showEmpty) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No hay dispositivos que coincidan.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(state.filtered, key = { it.deviceId }) { device ->
                        DeviceRow(device, isCompact = isCompact, onClick = { onSelectDevice(device.deviceId) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(
    state: DashboardUiState,
    isCompact: Boolean,
    onWarehouseFilter: (String?) -> Unit,
    onForkliftFilter: (String?) -> Unit,
    onHealthFilter: (DiagnosticsHealthStatus?) -> Unit,
    onTextQuery: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    if (isCompact) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.textQuery,
                onValueChange = onTextQuery,
                label = { Text("Buscar (deviceId / modelo)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterDropdown(
                    label = "Almacén",
                    options = state.warehouseOptions,
                    selected = state.filters.warehouseId,
                    onSelect = onWarehouseFilter,
                    compact = true,
                )
                FilterDropdown(
                    label = "Carretilla",
                    options = state.forkliftOptions,
                    selected = state.filters.forkliftId,
                    onSelect = onForkliftFilter,
                    compact = true,
                )
                HealthDropdown(selected = state.filters.healthStatus, onSelect = onHealthFilter, compact = true)
                TextButton(
                    onClick = onClearFilters,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("Limpiar") }
            }
        }
    } else {
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
}

@Composable
private fun FilterDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = if (compact) Modifier.heightIn(min = 44.dp) else Modifier,
        ) {
            Text(
                if (selected.isNullOrBlank()) label else "$label: $selected",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = if (compact) Modifier.heightIn(min = 44.dp) else Modifier,
        ) {
            Text(
                if (selected == null) "Estado" else "Estado: ${DiagnosticsFormat.statusLabel(selected)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(device: DeviceHealthSummary, isCompact: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.deviceId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val assignment = listOfNotNull(
                        device.warehouseName.ifBlank { device.warehouseId.ifBlank { null } },
                        device.forkliftModel.ifBlank { device.forkliftId.ifBlank { null } },
                    ).joinToString(" · ").ifBlank { "Sin asignación" }
                    Text(
                        assignment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCompact) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatusBadge(device.currentHealthStatus)
                        RecordingBadge(device.isRecording)
                        if (device.hasRemoteControl) RemoteAvailabilityBadge(available = true)
                    }
                } else {
                    RecordingBadge(device.isRecording)
                    if (device.hasRemoteControl) RemoteAvailabilityBadge(available = true)
                    StatusBadge(device.currentHealthStatus)
                }
            }

            Text(
                run {
                    val hw = listOf(device.manufacturer, device.deviceModel).filter { it.isNotBlank() }
                        .joinToString(" ").ifBlank { "—" }
                    "$hw · v${device.appVersion.ifBlank { "?" }} (${device.buildNumber})"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (isCompact) {
                MetricsFlow {
                    MetricCell("Hz", DiagnosticsFormat.hz(device.lastMeasuredSampleRateHz))
                    MetricCell("Jitter p95", DiagnosticsFormat.ms(device.lastJitterP95Ms))
                    MetricCell("Última vez", DiagnosticsFormat.relative(device.lastSeenAt))
                    MetricCell(
                        "Última grabación",
                        DiagnosticsFormat.relative(device.lastRecordingAt.takeIf { it > 0 }),
                    )
                }
            } else {
                MetricsFlow {
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
}
