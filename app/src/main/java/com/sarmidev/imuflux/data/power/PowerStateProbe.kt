package com.sarmidev.imuflux.data.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Muestrea el estado de energía del sistema en el momento de la llamada.
 *
 * Se usa para **correlacionar** caídas de la tasa de muestreo y huecos de
 * grabación con las políticas de ahorro de energía del fabricante (Doze,
 * pantalla apagada, exención de optimización de batería). El sensor hub de
 * One UI, por ejemplo, reduce la frecuencia a la mitad con la pantalla
 * apagada; sin esta instrumentación no había forma de demostrarlo desde el
 * CSV a posteriori.
 *
 * Todas las lecturas son baratas (consultas al sistema, sin I/O) y seguras de
 * llamar desde cualquier hilo. Nunca se invoca desde el hot path de sensores.
 */
@Singleton
class PowerStateProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Snapshot(
        /** `true` si la app está exenta de la optimización de batería. */
        val batteryOptimizationIgnored: Boolean,
        /** `true` si el sistema está en Doze (idle profundo). */
        val deviceIdleMode: Boolean,
        /** `true` si la pantalla está encendida/interactiva. */
        val screenInteractive: Boolean,
        /** `true` si el dispositivo está cargando (AC/USB/wireless). */
        val charging: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("battery_optimization_ignored", batteryOptimizationIgnored)
            put("device_idle_mode", deviceIdleMode)
            put("screen_interactive", screenInteractive)
            put("charging", charging)
        }
    }

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** `true` si la app está exenta de la optimización de batería del sistema. */
    fun isIgnoringBatteryOptimizations(): Boolean =
        runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)

    fun snapshot(): Snapshot = Snapshot(
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
        deviceIdleMode = runCatching { powerManager.isDeviceIdleMode }.getOrDefault(false),
        screenInteractive = runCatching { powerManager.isInteractive }.getOrDefault(true),
        charging = isCharging(),
    )

    private fun isCharging(): Boolean = runCatching {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)
}
