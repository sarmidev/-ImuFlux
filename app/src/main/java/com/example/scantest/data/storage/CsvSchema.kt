package com.example.scantest.data.storage

/**
 * Fuente única de verdad del formato CSV wide. Tanto [com.example.scantest.data.sensors.FrameAssembler]
 * como [CsvChunkWriter] leen/escriben en estos índices. Cambiar el orden o añadir
 * columnas requiere actualizar ambas clases y validar con el script
 * `tools/validate_session.py`.
 */
object CsvSchema {
    // ---- Índices de slots en FloatArray ----
    // NOTA: los slots existen para mantener la lógica interna intacta y poder
    // recuperar fácilmente cualquier columna en el futuro. Solo los índices
    // listados en CSV_SLOT_INDICES se escriben en el CSV.

    // -- Acelerómetro raw — desactivado del CSV (comentar para reactivar) --
    // const val IDX_ACC_X: Int = 0
    // const val IDX_ACC_Y: Int = 1
    // const val IDX_ACC_Z: Int = 2
    const val IDX_ACC_X: Int = 0  // retenido para master-clock en FrameAssembler
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

    // -- Rotación (yaw/pitch/roll) — desactivado del CSV --
    const val IDX_ROT_YAW: Int = 12
    const val IDX_ROT_PITCH: Int = 13
    const val IDX_ROT_ROLL: Int = 14

    // -- Magnetómetro heading — desactivado del CSV --
    const val IDX_MAG_HEADING: Int = 15

    // -- Magnitudes derivadas — desactivadas del CSV --
    /** Magnitud de la aceleración lineal — `sqrt(lin_x²+lin_y²+lin_z²)`. */
    const val IDX_ACC_MAGNITUDE: Int = 16
    /** Magnitud de la velocidad angular — `sqrt(gyro_x²+gyro_y²+gyro_z²)`. */
    const val IDX_GYRO_MAGNITUDE: Int = 17

    /** Número total de slots en el FloatArray interno (incluyendo los no exportados). */
    const val SLOT_COUNT: Int = 18

    /**
     * Índices de slots que se exportan al CSV, en el mismo orden que [COLUMNS]
     * (excluyendo `timestamp_ns` que se escribe aparte).
     *
     * Para reactivar una columna basta con añadir su IDX aquí y en [COLUMNS].
     * Columnas que estaban activas antes y ahora están desactivadas:
     *   IDX_ACC_X, IDX_ACC_Y, IDX_ACC_Z  → acc_x, acc_y, acc_z
     *   IDX_ROT_YAW, IDX_ROT_PITCH, IDX_ROT_ROLL → rot_yaw, rot_pitch, rot_roll
     *   IDX_MAG_HEADING → mag_heading
     *   IDX_ACC_MAGNITUDE → acc_magnitude
     *   IDX_GYRO_MAGNITUDE → gyro_magnitude
     */
    val CSV_SLOT_INDICES: IntArray = intArrayOf(
        IDX_LIN_X, IDX_LIN_Y, IDX_LIN_Z,
        IDX_GRAV_X, IDX_GRAV_Y, IDX_GRAV_Z,
        IDX_GYRO_X, IDX_GYRO_Y, IDX_GYRO_Z,
        // IDX_ACC_X, IDX_ACC_Y, IDX_ACC_Z,          // acc_x, acc_y, acc_z
        // IDX_ROT_YAW, IDX_ROT_PITCH, IDX_ROT_ROLL, // rot_yaw, rot_pitch, rot_roll
        // IDX_MAG_HEADING,                           // mag_heading
        // IDX_ACC_MAGNITUDE,                         // acc_magnitude
        // IDX_GYRO_MAGNITUDE,                        // gyro_magnitude
    )

    /**
     * Columnas en orden final del CSV.
     *
     * `forklift_model`, `warehouse` y `device_model` se han eliminado del CSV;
     * se envían directamente a la API al subir la sesión. Siguen disponibles
     * en `metadata.json` para cualquier uso local.
     * Para reactivarlas en el CSV: añadirlas aquí y restaurar el rowSuffix
     * en CsvChunkWriter.
     */
    val COLUMNS: List<String> = listOf(
        "timestamp_ns",
        "lin_x", "lin_y", "lin_z",
        "grav_x", "grav_y", "grav_z",
        "gyro_x", "gyro_y", "gyro_z",
        // "acc_x", "acc_y", "acc_z",
        // "rot_yaw", "rot_pitch", "rot_roll",
        // "mag_heading",
        // "acc_magnitude",
        // "gyro_magnitude",
        // "forklift_model", "warehouse", "device_model",
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
