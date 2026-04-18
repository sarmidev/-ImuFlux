package com.example.scantest.domain.model

enum class LogLevel { OK, WARNING, ERROR }

data class DetectionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.OK,
)
