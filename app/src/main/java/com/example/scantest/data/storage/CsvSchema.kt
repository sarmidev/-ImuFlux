package com.example.scantest.data.storage

/**
 * Fuente única de verdad del formato CSV wide. Tanto [com.example.scantest.data.sensors.FrameAssembler]
 * como [CsvChunkWriter] leen/escriben en estos índices. Cambiar el orden o añadir
 * columnas requiere actualizar ambas clases y validar con el script
 * `tools/validate_session.py`.
 */
object CsvSchema {
    // ---- Índices de slots en FloatArray ----
    const val IDX_ACC_X: Int = 0
    const val IDX_ACC_Y: Int = 1
    const val IDX_ACC_Z: Int = 2

    const val IDX_LIN_X: Int = 3
    const val IDX_LIN_Y: Int = 4
    const val IDX_LIN_Z: Int = 5

    const val IDX_GRAV_X: Int = 6
    const val IDX_GRAV_Y: Int = 7
    const val IDX_GRAV_Z: Int = 8

    const val IDX_GYRO_X: Int = 9
    const val IDX_GYRO_Y: Int = 10
    const val IDX_GYRO_Z: Int = 11

    const val IDX_ROT_YAW: Int = 12
    const val IDX_ROT_PITCH: Int = 13
    const val IDX_ROT_ROLL: Int = 14

    const val IDX_MAG_HEADING: Int = 15

    /** Número total de columnas numéricas (sin timestamps). */
    const val SLOT_COUNT: Int = 16

    /** Columnas en orden final del CSV (incluyendo timestamps). */
    val COLUMNS: List<String> = listOf(
        "timestamp_ns",
        "timestamp_ms",
        "acc_x", "acc_y", "acc_z",
        "lin_x", "lin_y", "lin_z",
        "grav_x", "grav_y", "grav_z",
        "gyro_x", "gyro_y", "gyro_z",
        "rot_yaw", "rot_pitch", "rot_roll",
        "mag_heading",
    )

    val HEADER_LINE: String = COLUMNS.joinToString(",")
}
