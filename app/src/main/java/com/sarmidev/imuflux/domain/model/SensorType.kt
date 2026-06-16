package com.sarmidev.imuflux.domain.model

/**
 * Todos los valores IMU que la app puede observar. El orden de los valores
 * en este enum no determina el orden de columnas del CSV (eso lo fija
 * [com.sarmidev.imuflux.data.storage.CsvSchema]).
 */
enum class SensorType {
    // Aceleración bruta (incluye gravedad) — útil si se quiere reconstruir señal raw
    RAW_ACCELERATION_X,
    RAW_ACCELERATION_Y,
    RAW_ACCELERATION_Z,

    // Aceleración lineal (sin gravedad) — la más usada para análisis de movimiento
    LINEAR_ACCELERATION_X,
    LINEAR_ACCELERATION_Y,
    LINEAR_ACCELERATION_Z,
    ACCELERATION_MAGNITUDE,

    // Gravedad
    GRAVITY_X,
    GRAVITY_Y,
    GRAVITY_Z,

    // Giroscopio
    ANGULAR_VELOCITY_X,
    ANGULAR_VELOCITY_Y,
    ANGULAR_VELOCITY_Z,
    ANGULAR_VELOCITY_MAGNITUDE,

    // Orientación (derivada de rotation vector)
    TILT_ANGLE_PITCH,
    TILT_ANGLE_ROLL,
    TILT_ANGLE_YAW,

    // Brújula magnética
    MAGNETIC_HEADING,

    // Campos derivados (no capturados directamente, reservados para post-proceso)
    LINEAR_VELOCITY_MAGNITUDE,
    DISTANCE_TRAVELED,
    LOCATION_ACCURACY,
}
