package com.example.scantest.ui.model

import androidx.compose.ui.graphics.Color

data class SensorsUiState(
    val isRecording: Boolean = false,
    val showSaveDialog: Boolean = false,
    val showOverlay: Boolean = false,
    val overlayColor: Color = Color.Green
)