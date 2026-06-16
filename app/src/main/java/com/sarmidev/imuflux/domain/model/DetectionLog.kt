package com.sarmidev.imuflux.domain.model

enum class LogLevel { OK, WARNING, ERROR }

data class DetectionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.OK,
)
