package com.sarmidev.imuflux.data.storage

/**
 * Fuente única de verdad del formato CSV wide. Tanto [com.sarmidev.imuflux.data.sensors.FrameAssembler]
 * como [CsvChunkWriter] leen/escriben en estos índices. Cambiar el orden o añadir
 * columnas requiere actualizar ambas clases y validar con el script
 * `tools/validate_session.py`.
 */
object CsvSchema {
    // ---- Índices de slots en FloatArray (SLOT_COUNT = 18 en total) ----
    //
    // Columnas ACTIVAS (se escriben en el CSV):
    //   lin_x/y/z   → 3–5
    //   grav_x/y/z  → 6–8
    //   gyro_x/y/z  → 9–11
    //   rot_yaw/pitch/roll → 12–14
    //
    // Columnas DESACTIVADAS (slots reservados; comentar/descomentar para reactivar):
    //   acc_x/y/z      → 0–2
    //   mag_heading    → 15
    //   acc_magnitude  → 16
    //   gyro_magnitude → 17

    // -- Desactivadas (reservadas para uso futuro) --
    // const val IDX_ACC_X: Int = 0
    // const val IDX_ACC_Y: Int = 1
    // const val IDX_ACC_Z: Int = 2

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

    // -- Desactivadas (reservadas para uso futuro) --
    // const val IDX_MAG_HEADING: Int = 15
    // const val IDX_ACC_MAGNITUDE: Int = 16
    // const val IDX_GYRO_MAGNITUDE: Int = 17

    /** Número total de slots del FloatArray interno (no cambia aunque se desactiven columnas). */
    const val SLOT_COUNT: Int = 18

    /**
     * Índices de slots que se escriben en el CSV, en el mismo orden que [COLUMNS].
     * Para reactivar una columna: añadir su índice aquí y en [COLUMNS].
     */
    val CSV_SLOT_INDICES: IntArray = intArrayOf(
        3, 4, 5,       // lin_x, lin_y, lin_z
        6, 7, 8,       // grav_x, grav_y, grav_z
        9, 10, 11,     // gyro_x, gyro_y, gyro_z
        12, 13, 14,    // rot_yaw, rot_pitch, rot_roll
        // 0, 1, 2,    // acc_x, acc_y, acc_z        (desactivado)
        // 15,         // mag_heading                (desactivado)
        // 16,         // acc_magnitude              (desactivado)
        // 17,         // gyro_magnitude             (desactivado)
    )

    /**
     * Columnas en orden final del CSV — debe mantenerse sincronizado con [CSV_SLOT_INDICES].
     *
     * `forklift_model`, `warehouse` y `device_model` son metadatos de sesión
     * (constantes en cada fila de un mismo chunk). Se incluyen como columnas
     * en el CSV para facilitar análisis cuando se combinan múltiples sesiones.
     */
    val COLUMNS: List<String> = listOf(
        "timestamp_ns",
        "lin_x", "lin_y", "lin_z",
        "grav_x", "grav_y", "grav_z",
        "gyro_x", "gyro_y", "gyro_z",
        "rot_yaw", "rot_pitch", "rot_roll",
        // "acc_x", "acc_y", "acc_z",      // desactivado
        // "mag_heading",                   // desactivado
        // "acc_magnitude",                 // desactivado
        // "gyro_magnitude",                // desactivado
        "forklift_model",
        "warehouse",
        "device_model",
    )

    val HEADER_LINE: String = COLUMNS.joinToString(",")

    /**
     * Sanitiza un valor de texto para que pueda incrustarse en CSV sin romperlo:
     * elimina comas, comillas, saltos de línea y retornos de carro. El trim
     * evita espacios colaterales del usuario.
     */
    fun sanitizeCsvValue(v: String): String =
        v.replace(',', ' ')
            .replace('"', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
}
