package com.example.scantest.data.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import com.example.scantest.data.storage.CsvSchema
import com.example.scantest.domain.model.SensorFrame
import com.example.scantest.domain.model.SensorSnapshot
import com.example.scantest.domain.model.SensorType
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Mantiene el "estado actual" de todos los sensores IMU en un `FloatArray`
 * fijo y, cuando se le pide, materializa un [SensorFrame] con la instantánea.
 *
 * Estrategia **hold-last-sample**: cada sensor actualiza sus slots cuando
 * llega un evento suyo; el resto de slots conservan el último valor conocido.
 * Al recibir el evento del **sensor maestro** (acelerómetro) se emite un
 * frame con el timestamp nativo del propio evento.
 *
 * No es thread-safe por sí mismo: todo el uso debe ocurrir desde el
 * mismo hilo (en la práctica, el main looper — ver `SensorHub`). Esto
 * evita cualquier `synchronized` en el hot path.
 */
class FrameAssembler {

    /** Estado vivo. Todas las posiciones empiezan en NaN → columna vacía en CSV si nunca llegan. */
    private val slots: FloatArray = FloatArray(CsvSchema.SLOT_COUNT) { Float.NaN }

    /** Reutilizables para evitar allocations en la derivación de orientación. */
    private val rotationMatrix: FloatArray = FloatArray(9)
    private val orientationAngles: FloatArray = FloatArray(3)

    /** Snapshot para la UI (a 10 Hz); sólo se rellena cuando [snapshotFor] se invoca. */
    fun snapshot(timestampNs: Long): SensorSnapshot {
        val map = HashMap<SensorType, Float>(SensorType.entries.size)
        val v = slots
        fun put(type: SensorType, value: Float) {
            if (!value.isNaN()) map[type] = value
        }
        put(SensorType.RAW_ACCELERATION_X, v[CsvSchema.IDX_ACC_X])
        put(SensorType.RAW_ACCELERATION_Y, v[CsvSchema.IDX_ACC_Y])
        put(SensorType.RAW_ACCELERATION_Z, v[CsvSchema.IDX_ACC_Z])
        put(SensorType.LINEAR_ACCELERATION_X, v[CsvSchema.IDX_LIN_X])
        put(SensorType.LINEAR_ACCELERATION_Y, v[CsvSchema.IDX_LIN_Y])
        put(SensorType.LINEAR_ACCELERATION_Z, v[CsvSchema.IDX_LIN_Z])
        val lx = v[CsvSchema.IDX_LIN_X]
        val ly = v[CsvSchema.IDX_LIN_Y]
        val lz = v[CsvSchema.IDX_LIN_Z]
        if (!lx.isNaN() && !ly.isNaN() && !lz.isNaN()) {
            map[SensorType.ACCELERATION_MAGNITUDE] = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
        }
        put(SensorType.GRAVITY_X, v[CsvSchema.IDX_GRAV_X])
        put(SensorType.GRAVITY_Y, v[CsvSchema.IDX_GRAV_Y])
        put(SensorType.GRAVITY_Z, v[CsvSchema.IDX_GRAV_Z])
        val wx = v[CsvSchema.IDX_GYRO_X]
        val wy = v[CsvSchema.IDX_GYRO_Y]
        val wz = v[CsvSchema.IDX_GYRO_Z]
        put(SensorType.ANGULAR_VELOCITY_X, wx)
        put(SensorType.ANGULAR_VELOCITY_Y, wy)
        put(SensorType.ANGULAR_VELOCITY_Z, wz)
        if (!wx.isNaN() && !wy.isNaN() && !wz.isNaN()) {
            map[SensorType.ANGULAR_VELOCITY_MAGNITUDE] = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
        }
        put(SensorType.TILT_ANGLE_YAW, v[CsvSchema.IDX_ROT_YAW])
        put(SensorType.TILT_ANGLE_PITCH, v[CsvSchema.IDX_ROT_PITCH])
        put(SensorType.TILT_ANGLE_ROLL, v[CsvSchema.IDX_ROT_ROLL])
        put(SensorType.MAGNETIC_HEADING, v[CsvSchema.IDX_MAG_HEADING])
        return SensorSnapshot(map, timestampNs)
    }

    /**
     * Aplica un evento del sensor al estado. Devuelve `true` si el evento
     * proviene del sensor maestro (acelerómetro) y, por tanto, corresponde
     * un nuevo frame al consumidor.
     */
    fun onSensorEvent(event: SensorEvent): Boolean {
        val values = event.values
        return when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                slots[CsvSchema.IDX_ACC_X] = values[0]
                slots[CsvSchema.IDX_ACC_Y] = values[1]
                slots[CsvSchema.IDX_ACC_Z] = values[2]
                true // master clock
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val lx = values[0]; val ly = values[1]; val lz = values[2]
                slots[CsvSchema.IDX_LIN_X] = lx
                slots[CsvSchema.IDX_LIN_Y] = ly
                slots[CsvSchema.IDX_LIN_Z] = lz
                slots[CsvSchema.IDX_ACC_MAGNITUDE] = sqrt(lx * lx + ly * ly + lz * lz)
                false
            }
            Sensor.TYPE_GRAVITY -> {
                slots[CsvSchema.IDX_GRAV_X] = values[0]
                slots[CsvSchema.IDX_GRAV_Y] = values[1]
                slots[CsvSchema.IDX_GRAV_Z] = values[2]
                false
            }
            Sensor.TYPE_GYROSCOPE -> {
                val wx = values[0]; val wy = values[1]; val wz = values[2]
                slots[CsvSchema.IDX_GYRO_X] = wx
                slots[CsvSchema.IDX_GYRO_Y] = wy
                slots[CsvSchema.IDX_GYRO_Z] = wz
                slots[CsvSchema.IDX_GYRO_MAGNITUDE] = sqrt(wx * wx + wy * wy + wz * wz)
                false
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                slots[CsvSchema.IDX_ROT_YAW] = toDegrees(orientationAngles[0])
                slots[CsvSchema.IDX_ROT_PITCH] = toDegrees(orientationAngles[1])
                slots[CsvSchema.IDX_ROT_ROLL] = toDegrees(orientationAngles[2])
                false
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val heading = (toDegrees(atan2(values[1], values[0])) + 360f) % 360f
                slots[CsvSchema.IDX_MAG_HEADING] = heading
                false
            }
            else -> false
        }
    }

    /**
     * Construye un [SensorFrame] con el estado actual. Copia el array — el
     * frame resultante es inmutable desde el punto de vista del consumidor.
     */
    fun buildFrame(timestampNs: Long): SensorFrame {
        return SensorFrame(
            timestampNs = timestampNs,
            values = slots.copyOf(),
        )
    }

    private fun toDegrees(radians: Float): Float =
        (radians * 57.29577951308232f) // 180/PI
}
