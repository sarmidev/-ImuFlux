package com.example.scantest.ui.model

data class ScanUiState(
    val lastScan: String = "Esperando detección...",
    val distance: Float = 0f,
    val isRecording: Boolean = false,
    val showSaveDialog: Boolean = false,
    val permissionGranted: Boolean = false
)