package com.sarmidev.imuflux.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsHealthStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting + label helpers shared by the desktop diagnostics screens. */
object DiagnosticsFormat {

    private val timeFmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    fun wallMs(ms: Long?): String {
        if (ms == null || ms <= 0L) return "—"
        return timeFmt.format(Date(ms))
    }

    fun relative(ms: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (ms == null || ms <= 0L) return "nunca"
        val delta = nowMs - ms
        if (delta < 0) return "ahora"
        val s = delta / 1000
        return when {
            s < 60 -> "hace ${s}s"
            s < 3600 -> "hace ${s / 60}m"
            s < 86400 -> "hace ${s / 3600}h"
            else -> "hace ${s / 86400}d"
        }
    }

    fun durationMs(ms: Long): String {
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

    fun bytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "$value B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun hz(value: Double): String = String.format(Locale.US, "%.1f Hz", value)

    fun ms(value: Double): String = String.format(Locale.US, "%.1f ms", value)

    fun recordingLabel(isRecording: Boolean): String = if (isRecording) "Grabando" else "Sin grabar"

    fun statusLabel(status: DiagnosticsHealthStatus): String = when (status) {
        DiagnosticsHealthStatus.OK -> "OK"
        DiagnosticsHealthStatus.WARNING -> "Advertencia"
        DiagnosticsHealthStatus.ERROR -> "Error"
        DiagnosticsHealthStatus.UNKNOWN -> "Desconocido"
    }
}

@Composable
fun statusColor(status: DiagnosticsHealthStatus): Color = when (status) {
    DiagnosticsHealthStatus.OK -> Color(0xFF2E7D32)
    DiagnosticsHealthStatus.WARNING -> Color(0xFFED6C02)
    DiagnosticsHealthStatus.ERROR -> Color(0xFFC62828)
    DiagnosticsHealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}
