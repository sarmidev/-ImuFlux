package com.sarmidev.imuflux.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sarmidev.imuflux.data.diagnostics.DeviceHealthSummary
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import com.sarmidev.imuflux.ui.viewmodel.DiagnosticsDashboardViewModel
import kotlin.math.roundToInt

@Composable
fun DiagnosticsDashboardScreen(
    onBack: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onToggleTheme: () -> Unit = {},
    viewModel: DiagnosticsDashboardViewModel = hiltViewModel(),
) {
    val c = LocalImuFluxColors.current
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bgDeep)
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(3.dp, 20.dp).clip(RoundedCornerShape(2.dp)).background(c.accentCyan),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ADMIN · DIAGNÓSTICO",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color = c.textSecondary,
                    )
                    Text(
                        text = "Flota IMU",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = c.textPrimary,
                        lineHeight = 22.sp,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagHeaderIconButton(if (c.isDark) "☀" else "☽", onToggleTheme, c)
                DiagHeaderIconButton("↺", { viewModel.refresh() }, c)
                DiagHeaderIconButton("←", onBack, c)
            }
        }

        Spacer(Modifier.height(14.dp))

        // Summary counts
        val errorCount = state.allDevices.count { it.currentHealthStatus == DiagnosticsHealthStatus.ERROR }
        val warnCount = state.allDevices.count { it.currentHealthStatus == DiagnosticsHealthStatus.WARNING }
        val okCount = state.allDevices.count { it.currentHealthStatus == DiagnosticsHealthStatus.OK }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CountChip("OK", okCount, c.accentGreen, c, Modifier.weight(1f))
            CountChip("WARN", warnCount, c.accentAmber, c, Modifier.weight(1f))
            CountChip("ERROR", errorCount, c.accentRed, c, Modifier.weight(1f))
            CountChip("TOTAL", state.allDevices.size, c.textSecondary, c, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // Status filter row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("TODOS", state.filters.healthStatus == null, c) { viewModel.setHealthStatus(null) }
            DiagnosticsHealthStatus.entries.filter { it != DiagnosticsHealthStatus.UNKNOWN }.forEach { st ->
                FilterChip(st.name, state.filters.healthStatus == st, c) {
                    viewModel.setHealthStatus(if (state.filters.healthStatus == st) null else st)
                }
            }
        }

        if (state.warehouseOptions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OptionFilterRow(
                title = "WAREHOUSE",
                options = state.warehouseOptions,
                selected = state.filters.warehouseId,
                c = c,
                onSelect = { viewModel.setWarehouse(it) },
            )
        }
        if (state.forkliftOptions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            OptionFilterRow(
                title = "FORKLIFT",
                options = state.forkliftOptions,
                selected = state.filters.forkliftId,
                c = c,
                onSelect = { viewModel.setForklift(it) },
            )
        }
        if (state.appVersionOptions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            OptionFilterRow(
                title = "APP VER",
                options = state.appVersionOptions,
                selected = state.filters.appVersion,
                c = c,
                onSelect = { viewModel.setAppVersion(it) },
            )
        }

        Spacer(Modifier.height(14.dp))

        when {
            state.isLoading -> CenteredLoader(c)
            state.error != null -> CenteredMessage("✕", state.error ?: "Error", c.accentRed, c) { viewModel.refresh() }
            state.filtered.isEmpty() -> CenteredMessage("—", "Sin dispositivos para los filtros actuales", c.textDim, c, null)
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.filtered, key = { it.deviceId }) { device ->
                    DeviceCard(device = device, c = c, onClick = { onOpenDevice(device.deviceId) })
                }
            }
        }
    }
}

@Composable
private fun OptionFilterRow(
    title: String,
    options: List<String>,
    selected: String?,
    c: ImuFluxColors,
    onSelect: (String?) -> Unit,
) {
    Column {
        Text(text = title, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = c.textDim)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { opt ->
                FilterChip(opt, selected == opt, c) { onSelect(if (selected == opt) null else opt) }
            }
        }
    }
}

@Composable
private fun CountChip(label: String, count: Int, color: androidx.compose.ui.graphics.Color, c: ImuFluxColors, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "$count", fontSize = 18.sp, fontWeight = FontWeight.Black, color = color, fontFamily = FontFamily.Monospace)
            Text(text = label, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = c.textDim)
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceHealthSummary, c: ImuFluxColors, onClick: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.forkliftModel.ifBlank { "(sin forklift)" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${device.warehouseName.ifBlank { "—" }} · ${device.forkliftId.ifBlank { "—" }}",
                        fontSize = 10.sp,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = device.deviceId,
                        fontSize = 8.sp,
                        color = c.textDim,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    DiagnosticsStatusBadge(device.currentHealthStatus, c)
                    Spacer(Modifier.height(4.dp))
                    if (device.isRecording) {
                        Text(text = "● REC", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = c.accentRed)
                    } else {
                        Text(text = formatRelative(device.lastSeenAt), fontSize = 9.sp, color = c.textDim, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (device.currentHealthReasons.isNotEmpty() && device.currentHealthStatus != DiagnosticsHealthStatus.OK) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = device.currentHealthReasons.joinToString(" · "),
                    fontSize = 9.sp,
                    color = statusColor(device.currentHealthStatus, c),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MiniStat("Hz", "${device.lastMeasuredSampleRateHz.roundToInt()}", c, Modifier.weight(1f))
                MiniStat("JIT", "${"%.1f".format(device.lastJitterP95Ms)}", c, Modifier.weight(1f))
                MiniStat("DROP", "${device.lastFramesDropped}", c, Modifier.weight(1f))
                MiniStat("PEND", "${device.pendingUploadChunks}", c, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "v${device.appVersion.ifBlank { "—" }} · API ${device.androidVersion} · ${device.deviceModel}",
                    fontSize = 8.sp,
                    color = c.textDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, c: ImuFluxColors, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.bgDeep)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(6.dp))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = c.textDim)
            Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textPrimary, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CenteredLoader(c: ImuFluxColors) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = c.accentCyan, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(14.dp))
            Text(text = "Consultando Firestore...", fontSize = 11.sp, color = c.textSecondary, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CenteredMessage(
    icon: String,
    message: String,
    color: androidx.compose.ui.graphics.Color,
    c: ImuFluxColors,
    onRetry: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 70.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = icon, fontSize = 34.sp, color = color)
            Spacer(Modifier.height(10.dp))
            Text(text = message, fontSize = 12.sp, color = c.textSecondary, fontFamily = FontFamily.Monospace)
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.bgCard)
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(8.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(text = "REINTENTAR", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = c.accentCyan)
                }
            }
        }
    }
}
