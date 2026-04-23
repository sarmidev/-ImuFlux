package com.example.scantest.ui.screen

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.scantest.domain.model.SessionSummary
import com.example.scantest.domain.usecase.ExportSessionUseCase
import com.example.scantest.ui.viewmodel.SessionAnalysisState
import com.example.scantest.ui.viewmodel.SessionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    onToggleTheme: () -> Unit = {},
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val c     = LocalImuFluxColors.current
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
        val ext  = if (state.pendingExportFormat == ExportSessionUseCase.Format.ZIP) "zip" else "csv"
        val mime = if (ext == "zip") "application/zip" else "text/csv"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, "imuflux_${pending}.$ext")
        }
        exportLauncher.launch(intent)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to c.bgDeep,
                    1f to c.bgSurface,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            // All session list content (unchanged below)
            Spacer(Modifier.height(16.dp))

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Back button
                    HeaderIconButton(label = "←", onClick = onBack, c = c)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SESIONES",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                            color = c.textPrimary,
                        )
                        Text(
                            text = "Grabaciones guardadas",
                            fontSize = 10.sp,
                            color = c.textSecondary,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
                // Right-side icon buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderIconButton(
                        label = if (c.isDark) "☀" else "☽",
                        onClick = onToggleTheme,
                        c = c,
                    )
                    HeaderIconButton(label = "↺", onClick = { viewModel.refresh() }, c = c)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Session count strip ───────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(3.dp, 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accentCyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        state.isLoading          -> "Cargando…"
                        state.sessions.isEmpty() -> "Sin grabaciones"
                        else                     -> "${state.sessions.size} sesiones"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = c.textSecondary,
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── Content ───────────────────────────────────────────────────────
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = c.accentCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Cargando...",
                                fontSize = 11.sp,
                                color = c.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                state.sessions.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "—", fontSize = 40.sp, color = c.textDim)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "No hay sesiones grabadas todavía.",
                                fontSize = 12.sp,
                                color = c.textSecondary,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.sessions) { session ->
                            val isLive = session.sessionId == state.liveSessionId
                            SessionCard(
                                session      = session,
                                isLive       = isLive,
                                c            = c,
                                onExportCsv  = {
                                    viewModel.requestExport(
                                        session.sessionId,
                                        ExportSessionUseCase.Format.SINGLE_CSV,
                                    )
                                },
                                onExportZip  = {
                                    viewModel.requestExport(
                                        session.sessionId,
                                        ExportSessionUseCase.Format.ZIP,
                                    )
                                },
                                onDelete     = { viewModel.deleteSession(session.sessionId) },
                                onAnalyze    = { viewModel.analyzeSession(session.sessionId) },
                            )
                        }
                    }
                }
            }
        }

        // ── Analysis overlay (running or done) ────────────────────────────────
        when (val as_ = state.analysisState) {
            is SessionAnalysisState.Running -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(c.bgDeep.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = c.accentCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Analizando sesión…",
                            fontSize = 12.sp,
                            color = c.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            as_.sessionId,
                            fontSize = 10.sp,
                            color = c.textDim,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            is SessionAnalysisState.Done -> {
                SessionInspectionSheet(
                    sessionId = as_.sessionId,
                    result    = as_.result,
                    onDismiss = { viewModel.dismissAnalysis() },
                    c         = c,
                )
            }
            is SessionAnalysisState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(c.bgDeep.copy(alpha = 0.92f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { viewModel.dismissAnalysis() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.bgCard)
                            .border(1.dp, c.accentRed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("✘", fontSize = 24.sp, color = c.accentRed)
                        Text(
                            "Error al analizar",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Text(
                            as_.message,
                            fontSize = 11.sp,
                            color = c.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "Toca para cerrar",
                            fontSize = 10.sp,
                            color = c.textDim,
                        )
                    }
                }
            }
            else -> { /* Idle: nothing to show */ }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable header icon button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HeaderIconButton(
    label: String,
    onClick: () -> Unit,
    c: ImuFluxColors,
) {
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
        Text(
            text = label,
            fontSize = 17.sp,
            color = c.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SessionCard(
    session: SessionSummary,
    isLive: Boolean,
    c: ImuFluxColors,
    onExportCsv: () -> Unit,
    onExportZip: () -> Unit,
    onDelete: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yy  HH:mm", Locale.getDefault()) }

    val accentColor = when {
        isLive           -> c.accentGreen
        session.isActive -> c.accentAmber
        else             -> c.bgCardBorder
    }
    val statusLabel = when {
        isLive           -> "EN VIVO"
        session.isActive -> "INCOMPLETA"
        else             -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgCard)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(12.dp)),
    ) {
        // Colored left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentColor),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
        ) {
            // ── Top row: status badge + date + delete ─────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (statusLabel != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor.copy(alpha = 0.15f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(accentColor),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = statusLabel,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                                color = accentColor,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = dateFormatter.format(Date(session.startedAtWallMs)),
                        fontSize = 10.sp,
                        color = c.textSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (!isLive) c.accentRed.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .clickable(
                            enabled = !isLive,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDelete,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Borrar",
                        tint = if (!isLive) c.accentRed.copy(alpha = 0.70f) else c.textDim,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(7.dp))

            // ── Session ID ────────────────────────────────────────────────────
            Text(
                text = session.sessionId,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.textPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // ── Continuation link ─────────────────────────────────────────────
            if (session.resumeOf != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "↳ continuación de ${session.resumeOf}",
                    fontSize = 10.sp,
                    color = c.accentCyan.copy(alpha = 0.70f),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Contexto de sesión (toro + almacén) ──────────────────────────
            val hasContext = session.forkliftModel.isNotBlank() || session.warehouse.isNotBlank()
            if (hasContext) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (session.forkliftModel.isNotBlank()) {
                        ContextTag(
                            label = "TORO",
                            value = session.forkliftModel,
                            accent = c.accentCyan,
                            c = c,
                        )
                    }
                    if (session.warehouse.isNotBlank()) {
                        ContextTag(
                            label = "ALMACÉN",
                            value = session.warehouse,
                            accent = c.accentGreen,
                            c = c,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Stats chips ───────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(label = "DURACIÓN", value = sessionFormatDuration(session.durationMs), c = c)
                StatChip(label = "CHUNKS",   value = "${session.chunkCount}", c = c)
                StatChip(
                    label = "TAMAÑO",
                    value = "%.1f MB".format(session.totalBytes / (1024.0 * 1024.0)),
                    c = c,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Action buttons ────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Analizar – amber outline
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (!isLive) c.accentAmber.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .border(
                            width = 1.dp,
                            color = if (!isLive) c.accentAmber.copy(alpha = 0.55f) else c.bgCardBorder,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable(
                            enabled = !isLive,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onAnalyze,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Analizar",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        color = if (!isLive) c.accentAmber else c.textDim,
                    )
                }
                // CSV – cyan outline
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (!isLive) c.accentCyan.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .border(
                            width = 1.dp,
                            color = if (!isLive) c.accentCyan.copy(alpha = 0.50f) else c.bgCardBorder,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable(
                            enabled = !isLive,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExportCsv,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "CSV",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        color = if (!isLive) c.accentCyan else c.textDim,
                    )
                }
                // ZIP – filled cyan
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (!isLive) c.accentCyan.copy(alpha = 0.85f) else c.bgCard,
                        )
                        .border(1.dp, c.bgCardBorder, RoundedCornerShape(8.dp))
                        .clickable(
                            enabled = !isLive,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExportZip,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ZIP",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        color = if (!isLive) c.bgDeep else c.textDim,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stat chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StatChip(label: String, value: String, c: ImuFluxColors) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(c.bgDeep)
            .border(1.dp, c.bgCardBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 7.sp,
            letterSpacing = 1.sp,
            color = c.textSecondary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = c.textPrimary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Context tag (forklift / warehouse)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ContextTag(label: String, value: String, accent: Color, c: ImuFluxColors) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            color = accent.copy(alpha = 0.85f),
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun sessionFormatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h %02dm".format(m)
        m > 0 -> "${m}m %02ds".format(s)
        else  -> "${s}s"
    }
}
