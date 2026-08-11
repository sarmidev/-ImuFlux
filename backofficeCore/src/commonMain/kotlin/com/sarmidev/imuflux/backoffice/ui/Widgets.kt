package com.sarmidev.imuflux.backoffice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus

@Composable
fun StatusBadge(status: DiagnosticsHealthStatus) {
    val color = statusColor(status)
    Pill(text = DiagnosticsFormat.statusLabel(status), color = color)
}

@Composable
fun RecordingBadge(isRecording: Boolean) {
    val color = if (isRecording) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant
    Pill(text = DiagnosticsFormat.recordingLabel(isRecording), color = color, filled = isRecording)
}

@Composable
fun RemoteAvailabilityBadge(available: Boolean) {
    val color = if (available) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
    Pill(
        text = if (available) "Control remoto" else "Sin control remoto",
        color = color,
        filled = available,
    )
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.15f) else color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
