package com.example.scantest.domain.model

data class DetectionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isAlert: Boolean = false,
)
