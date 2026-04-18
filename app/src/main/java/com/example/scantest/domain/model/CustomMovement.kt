package com.example.scantest.domain.model

/** Tipos de condiciones lógicas que se aplican al valor de un sensor. */
enum class Condition {
    GREATER_THAN,
    LESS_THAN,
    BETWEEN,
}

/** Acciones que se ejecutan cuando un [CustomMovement] es detectado. */
enum class OutputAction {
    SOUND_NOTIFICATION,
    SEND_DATA_TO_SERVER,
    DISPLAY_WARNING,
    LOG_EVENT,
    NO_ACTION,
}

data class Criterion(
    val sensor: SensorType,
    val condition: Condition,
    val minValue: Float,
    val maxValue: Float? = null,
)

/**
 * Definición completa de un movimiento personalizado (regla). El motor de
 * detección evalúa la lista de criterios en AND.
 */
data class CustomMovement(
    val id: String,
    val name: String,
    val criteria: List<Criterion>,
    val isActive: Boolean = true,
)
