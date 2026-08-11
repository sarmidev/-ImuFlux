package com.sarmidev.imuflux.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sarmidev.imuflux.domain.model.SensorType
import com.sarmidev.imuflux.ui.viewmodel.SensorsViewModel
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Calibration screen — 2D level only
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CalibrationScreen(
    viewModel: SensorsViewModel = viewModel(),
    onBack: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
) {
    val c        = LocalImuFluxColors.current
    val snapshot by viewModel.sensorSnapshotState.collectAsState()

    val ax = snapshot.values[SensorType.GRAVITY_X] ?: 0f
    val ay = snapshot.values[SensorType.GRAVITY_Y] ?: 0f
    val az = snapshot.values[SensorType.GRAVITY_Z] ?: 0f

    val g      = 9.81f
    val normX  = (ax / g).coerceIn(-1f, 1f)
    val normZ  = (az / g).coerceIn(-1f, 1f)
    val dist   = sqrt(normX * normX + normZ * normZ).coerceIn(0f, 1f)

    val isLevel = dist < 0.05f
    val isClose = dist < 0.18f

    val statusColor = when {
        isLevel -> c.accentGreen
        isClose -> c.accentAmber
        else    -> c.accentRed
    }
    val statusText = when {
        isLevel -> "NIVELADO  ✓"
        isClose -> "CASI NIVELADO"
        else    -> "INCLINADO"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to c.bgDeep, 1f to c.bgSurface)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            // ── Header ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CalHeaderBtn(label = "←", onClick = onBack, c = c)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "CALIBRACIÓN",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                            color = c.textPrimary,
                        )
                        Text(
                            text = "Nivel X / Z del dispositivo",
                            fontSize = 10.sp,
                            color = c.textSecondary,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
                CalHeaderBtn(label = if (c.isDark) "☀" else "☽", onClick = onToggleTheme, c = c)
            }

            Spacer(Modifier.height(18.dp))

            // ── 2D Bubble Level ───────────────────────────────────────────────
            // Status strip above the level
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(3.dp, 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c.accentCyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "NIVEL 2D  ·  EJE X / Z",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = c.textSecondary,
                )
            }

            Spacer(Modifier.height(10.dp))

            // The level — square, fills width
            BubbleLevel(
                normX      = normX,
                normZ      = normZ,
                statusColor = statusColor,
                c          = c,
                modifier   = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, c.bgCardBorder, RoundedCornerShape(20.dp)),
            )

            Spacer(Modifier.height(10.dp))

            // X / Z values below the level
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                AxisValueLabel(axis = "X", value = ax, c = c)
                AxisValueLabel(axis = "Z", value = az, c = c)
            }

            Spacer(Modifier.height(16.dp))

            // ── Raw XYZ bars ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bgCard)
                    .border(1.dp, c.bgCardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(3.dp, 13.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(c.accentCyan),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "GRAVEDAD",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp,
                                color = c.textSecondary,
                            )
                        }
                        Text(text = "m/s²", fontSize = 9.sp, color = c.textDim, fontFamily = FontFamily.Monospace)
                    }
                    RawAxisBar(axis = "X", value = ax, g = g, color = c.accentCyan,  c = c)
                    RawAxisBar(axis = "Y", value = ay, g = g, color = c.accentGreen, c = c)
                    RawAxisBar(axis = "Z", value = az, g = g, color = c.sensorPitch, c = c)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Status badge ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(statusColor.copy(alpha = 0.13f))
                    .border(1.dp, statusColor.copy(alpha = 0.40f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2D Bubble Level canvas
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun BubbleLevel(
    normX: Float,
    normZ: Float,
    statusColor: Color,
    c: ImuFluxColors,
    modifier: Modifier = Modifier,
) {
    val dist = sqrt(normX * normX + normZ * normZ).coerceIn(0f, 1f)

    val bgC   = c.bgCard
    val dim   = c.textDim
    val green = c.accentGreen
    val amber = c.accentAmber
    val red   = c.accentRed

    Canvas(modifier = modifier.background(bgC)) {
        val cx   = size.width  / 2f
        val cy   = size.height / 2f
        val maxR = (size.minDimension / 2f - 8.dp.toPx()).coerceAtLeast(1f)

        // ── Tolerance zones ───────────────────────────────────────────────────
        // Outer circle border (full instrument area)
        drawCircle(color = dim.copy(alpha = 0.3f), radius = maxR, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

        // Red tint on whole circle (background hint)
        drawCircle(color = red.copy(alpha = 0.05f), radius = maxR, center = Offset(cx, cy))

        // Amber zone ring (30%)
        drawCircle(color = amber.copy(alpha = 0.12f), radius = maxR * 0.30f, center = Offset(cx, cy))
        drawCircle(color = amber.copy(alpha = 0.30f), radius = maxR * 0.30f, center = Offset(cx, cy), style = Stroke(0.8.dp.toPx()))

        // Green zone ring (10%)
        drawCircle(color = green.copy(alpha = 0.22f), radius = maxR * 0.10f, center = Offset(cx, cy))
        drawCircle(color = green.copy(alpha = 0.60f), radius = maxR * 0.10f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

        // ── Grid ─────────────────────────────────────────────────────────────
        drawLine(dim.copy(alpha = 0.4f), Offset(cx - maxR, cy), Offset(cx + maxR, cy), 0.8.dp.toPx())
        drawLine(dim.copy(alpha = 0.4f), Offset(cx, cy - maxR), Offset(cx, cy + maxR), 0.8.dp.toPx())
        for (f in listOf(0.20f, 0.40f, 0.60f, 0.80f)) {
            drawCircle(color = dim.copy(alpha = 0.18f), radius = maxR * f, center = Offset(cx, cy), style = Stroke(0.5.dp.toPx()))
        }

        // ── Bubble ────────────────────────────────────────────────────────────
        val bubR    = 16.dp.toPx()
        val limit   = maxR - bubR - 2.dp.toPx()
        val rawBX   = cx + normX * maxR
        val rawBY   = cy + normZ * maxR
        val rawDist = sqrt((rawBX - cx) * (rawBX - cx) + (rawBY - cy) * (rawBY - cy))
        val bx: Float
        val by_: Float
        if (rawDist > limit && rawDist > 0f) {
            val s = limit / rawDist
            bx  = cx + (rawBX - cx) * s
            by_ = cy + (rawBY - cy) * s
        } else {
            bx  = rawBX
            by_ = rawBY
        }
        val bubCenter = Offset(bx, by_)

        // Glow ring
        drawCircle(color = statusColor.copy(alpha = 0.18f), radius = bubR * 2f, center = bubCenter)
        // Fill
        drawCircle(color = statusColor, radius = bubR, center = bubCenter)
        // Specular
        drawCircle(Color.White.copy(alpha = 0.38f), radius = bubR * 0.40f, center = Offset(bx - bubR * 0.22f, by_ - bubR * 0.22f))

        // ── Center crosshair ──────────────────────────────────────────────────
        val tm = 6.dp.toPx()
        drawLine(green.copy(alpha = 0.80f), Offset(cx - tm, cy), Offset(cx + tm, cy), 1.5.dp.toPx())
        drawLine(green.copy(alpha = 0.80f), Offset(cx, cy - tm), Offset(cx, cy + tm), 1.5.dp.toPx())

        // ── Degree labels at 10%/30% ring ─────────────────────────────────────
        // Small filled dots on axes at each tolerance ring
        for (r in listOf(maxR * 0.10f, maxR * 0.30f)) {
            for (angle in listOf(0f, 90f, 180f, 270f)) {
                val rad = Math.toRadians(angle.toDouble())
                val px  = cx + r * Math.cos(rad).toFloat()
                val py  = cy + r * Math.sin(rad).toFloat()
                drawCircle(color = dim.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(px, py))
            }
        }

        // Distance fill bar at bottom edge (optional level meter arc)
        val arcSweep = (dist * 180f).coerceIn(0f, 180f)
        drawArc(
            color = statusColor.copy(alpha = 0.25f),
            startAngle = 180f,
            sweepAngle = arcSweep,
            useCenter = false,
            topLeft = Offset(cx - maxR + 4.dp.toPx(), cy - maxR + 4.dp.toPx()),
            size = Size((maxR - 4.dp.toPx()) * 2, (maxR - 4.dp.toPx()) * 2),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Raw axis bar row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RawAxisBar(
    axis: String,
    value: Float,
    g: Float,
    color: Color,
    c: ImuFluxColors,
) {
    val fraction = ((value / g + 1f) / 2f).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = axis,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(14.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(c.bgCardBorder),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.copy(alpha = 0.75f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .height(6.dp)
                    .background(c.textDim),
            )
        }
        Text(
            text = "%+.2f".format(value),
            fontSize = 10.sp,
            color = c.textPrimary,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(60.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AxisValueLabel(axis: String, value: Float, c: ImuFluxColors) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = axis, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.textSecondary, letterSpacing = 1.sp)
        Text(text = "%+.3f".format(value), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textPrimary, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CalHeaderBtn(label: String, onClick: () -> Unit, c: ImuFluxColors) {
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
