package com.sarmidev.imuflux.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Shared diagnostics UI helpers (used by dashboard + detail screens)
// ─────────────────────────────────────────────────────────────────────────────

internal fun statusColor(status: DiagnosticsHealthStatus, c: ImuFluxColors): Color = when (status) {
    DiagnosticsHealthStatus.OK -> c.accentGreen
    DiagnosticsHealthStatus.WARNING -> c.accentAmber
    DiagnosticsHealthStatus.ERROR -> c.accentRed
    DiagnosticsHealthStatus.UNKNOWN -> c.textSecondary
}

private val timeFmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)

internal fun formatWallMs(ms: Long?): String {
    if (ms == null || ms <= 0L) return "—"
    return timeFmt.format(Date(ms))
}

internal fun formatRelative(ms: Long?): String {
    if (ms == null || ms <= 0L) return "nunca"
    val delta = System.currentTimeMillis() - ms
    if (delta < 0) return "ahora"
    val s = delta / 1000
    return when {
        s < 60 -> "hace ${s}s"
        s < 3600 -> "hace ${s / 60}m"
        s < 86400 -> "hace ${s / 3600}h"
        else -> "hace ${s / 86400}d"
    }
}

internal fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${ms / 1000}s"
    }
}

@Composable
internal fun DiagnosticsStatusBadge(status: DiagnosticsHealthStatus, c: ImuFluxColors) {
    val color = statusColor(status, c)
    val icon = when (status) {
        DiagnosticsHealthStatus.OK -> "✓"
        DiagnosticsHealthStatus.WARNING -> "⚠"
        DiagnosticsHealthStatus.ERROR -> "✕"
        DiagnosticsHealthStatus.UNKNOWN -> "–"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 11.sp, color = color)
            Spacer(Modifier.width(5.dp))
            Text(
                text = status.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                color = color,
            )
        }
    }
}

@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    c: ImuFluxColors,
    onClick: () -> Unit,
) {
    val tint = if (selected) c.accentCyan else c.textSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.accentCyan.copy(alpha = 0.12f) else c.bgCard)
            .border(1.dp, if (selected) c.accentCyan.copy(alpha = 0.4f) else c.bgCardBorder, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = tint,
        )
    }
}

@Composable
internal fun DiagHeaderIconButton(label: String, onClick: () -> Unit, c: ImuFluxColors) {
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
        Text(text = label, fontSize = 17.sp, color = c.textSecondary)
    }
}
