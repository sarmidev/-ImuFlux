package com.example.scantest.domain

data class ScanData(
    val data: String,
    val symbology: String,
    val timestamp: Long,
    val distance: Float ? = null
)