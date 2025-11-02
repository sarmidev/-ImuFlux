package com.example.scantest.domain

import java.sql.Timestamp

data class SensorData(
    val timestamp: Long,
    val name: String,
    val value: Float,
)