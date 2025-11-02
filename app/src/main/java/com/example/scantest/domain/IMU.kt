package com.example.scantest.domain

enum class SensorType {
    // Aceleración


    // Velocidad y Ubicación
    LINEAR_VELOCITY_MAGNITUDE,      // Velocidad lineal
    DISTANCE_TRAVELED,              // Distancia recorrida en un corto periodo.
    LOCATION_ACCURACY,              // Precisión de la ubicación GPS (puede indicar interferencia o quietud).

    // Rotación e Inclinación (Giroscopio/Acelerómetro fusionados)


    MAGNETIC_HEADING,               // Dirección o rumbo magnético (en grados).
    ANGULAR_VELOCITY_MAGNITUDE,     // Magnitud total de la velocidad angular. Clave para medir vibración o rotación errática.
    ACCELERATION_MAGNITUDE,         // Magnitud total de la aceleración. Útil para detectar el inicio o parada brusca.
    ACCELERATION_X,                 // Aceleración en el eje X (Lateral).
    ACCELERATION_Y,                 // Aceleración en el eje Y (Movimiento del vehículo).
    ACCELERATION_Z,                 // Aceleración en el eje Z (Vertical). Clave para subida/bajada del palet.
    TILT_ANGLE_PITCH,               // Ángulo de inclinación frontal/trasera (pitch). Útil para la posición de la horquilla.
    TILT_ANGLE_ROLL,                // Ángulo de inclinación lateral (roll). Útil para la estabilidad y giros.
}

/**
 * Tipos de condiciones lógicas que se aplican al valor del sensor.
 */
enum class Condition {
    GREATER_THAN, // >
    LESS_THAN,    // <
    BETWEEN       // Requiere minValue y maxValue [Min, Max]
}

/**
 * Acciones que se ejecutan cuando una CustomMovement es detectada (se cumplen todos sus criterios).
 */
enum class OutputAction {
    SOUND_NOTIFICATION,   // Reproducir un sonido de alerta o confirmación.
    SEND_DATA_TO_SERVER,  // Enviar un evento o registro de actividad al servidor central.
    DISPLAY_WARNING,      // Mostrar un mensaje de advertencia o información en la pantalla.
    LOG_EVENT,            // Simplemente registra el evento localmente para auditoría.
    NO_ACTION             // Se usa para detectar sin generar una alerta inmediata.
}

data class Criterion(
    val sensor: SensorType,
    val condition: Condition,
    val minValue: Float,
    val maxValue: Float? = null // Es nulo a menos que Condition sea 'BETWEEN'
)

/**
 * La definición completa de un movimiento personalizado.
 * Contiene el nombre, todos los criterios de sensor y la acción a ejecutar.
 */
data class CustomMovement(
    val id: String,                    // ID único para la persistencia.
    val name: String,                  // Nombre descriptivo definido por el usuario (e.g., "Palet Quieto").
    val criteria: List<Criterion>,     // Lista de criterios de sensor que deben cumplirse a la vez.
    val isActive: Boolean = true       // Permite al usuario activar/desactivar la regla.
)

data class SensorSnapshot(
    val values: Map<SensorType, Float>,
    val timestamp: Long
)

data class DetectionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isAlert: Boolean = false
)