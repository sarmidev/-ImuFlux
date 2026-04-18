package com.example.scantest.ui.screen

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Color tokens
// ─────────────────────────────────────────────────────────────────────────────
data class ImuFluxColors(
    val bgDeep: Color,
    val bgSurface: Color,
    val bgCard: Color,
    val bgCardBorder: Color,
    val accentCyan: Color,
    val accentGreen: Color,
    val accentAmber: Color,
    val accentRed: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDim: Color,
    // Sensor instrument accent colors (adjusted per theme for readability)
    val sensorGyro: Color,
    val sensorPitch: Color,
    val sensorRoll: Color,
    val isDark: Boolean,
)

fun darkColors() = ImuFluxColors(
    bgDeep        = Color(0xFF07090F),
    bgSurface     = Color(0xFF0D1018),
    bgCard        = Color(0xFF111523),
    bgCardBorder  = Color(0xFF25304A),   // brighter than old 0xFF1A2035
    accentCyan    = Color(0xFF00C8FF),
    accentGreen   = Color(0xFF00E676),
    accentAmber   = Color(0xFFFFAB00),
    accentRed     = Color(0xFFFF1744),
    textPrimary   = Color(0xFFE8EDF8),
    textSecondary = Color(0xFF7A85A8),   // brighter than old 0xFF4A5270
    textDim       = Color(0xFF333A58),   // brighter than old 0xFF252A40
    sensorGyro    = Color(0xFFCE93D8),
    sensorPitch   = Color(0xFF4FC3F7),
    sensorRoll    = Color(0xFF80DEEA),
    isDark        = true,
)

fun lightColors() = ImuFluxColors(
    bgDeep        = Color(0xFFF2F5FC),
    bgSurface     = Color(0xFFE8EDF8),
    bgCard        = Color(0xFFFFFFFF),
    bgCardBorder  = Color(0xFFD5DCEE),
    accentCyan    = Color(0xFF0090BB),
    accentGreen   = Color(0xFF00A854),
    accentAmber   = Color(0xFFBB7000),
    accentRed     = Color(0xFFCC002A),
    textPrimary   = Color(0xFF0A1020),
    textSecondary = Color(0xFF5A6480),
    textDim       = Color(0xFFA8B4CC),
    sensorGyro    = Color(0xFF8E24AA),
    sensorPitch   = Color(0xFF0277BD),
    sensorRoll    = Color(0xFF00838F),
    isDark        = false,
)

val LocalImuFluxColors = compositionLocalOf { darkColors() }
