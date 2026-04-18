package com.example.scantest.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Programa el "latido externo" del watchdog: una alarma exacta cada
 * [INTERVAL_MS] que despertará al [WatchdogReceiver] incluso en Doze.
 *
 * Justificación de `setExactAndAllowWhileIdle`:
 *  - `setRepeating` en Android 4.4+ es **inexacto**: puede diferirse horas.
 *  - `setAndAllowWhileIdle` es inexacto (Android ajusta la hora real).
 *  - `setExactAndAllowWhileIdle` es **la única** que garantiza precisión
 *    (± segundos) y funciona con la pantalla apagada y Doze activo.
 *
 * En Android 12+ (`SCHEDULE_EXACT_ALARM`) la app debe haber declarado el
 * permiso en el manifest o el usuario haberlo concedido. Nosotros declaramos
 * también `USE_EXACT_ALARM` (API 33+) que se concede automáticamente por ser
 * una app cuyo caso de uso cae en los permitidos ("data collection").
 *
 * Las alarmas exactas modernas **no son repeating**: hay que reprogramar la
 * siguiente desde el propio receiver tras cada disparo. Lo hace
 * [WatchdogReceiver] llamando a [schedule] al final de cada ejecución.
 */
@Singleton
class WatchdogScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule() {
        val pi = buildPendingIntent()
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                Log.w(TAG, "No podemos programar alarmas exactas (permiso denegado); usando inexacta")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            }
            Log.d(TAG, "Watchdog programado en ${INTERVAL_MS / 1000}s")
        } catch (t: SecurityException) {
            // Algún OEM lanza SecurityException aunque canScheduleExactAlarms diga true.
            Log.w(TAG, "SecurityException programando alarma exacta — degradamos a inexacta", t)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            }
        }
    }

    fun cancel() {
        alarmManager.cancel(buildPendingIntent())
        Log.d(TAG, "Watchdog cancelado")
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_TICK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "WatchdogScheduler"
        private const val REQUEST_CODE = 0x1A7C
        /** 10 min: latencia máxima de detección de muerte del servicio. */
        const val INTERVAL_MS: Long = 10L * 60L * 1000L
    }
}
