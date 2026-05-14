package com.sarmidev.imuflux.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Rearma el [WatchdogScheduler] tras un reboot del dispositivo, **sólo** si
 * el usuario había marcado intención de grabar antes del reboot.
 *
 * No auto-arranca el servicio aquí: delegamos en el propio watchdog para
 * que, pasados unos minutos, detecte la situación (servicio muerto +
 * intención viva + huérfana con heartbeat relativamente reciente) y actúe.
 * Así unificamos la lógica en un único sitio y evitamos duplicarla.
 *
 * Nota: tras reboot, el heartbeat de la última sesión puede ser demasiado
 * viejo (> 15 min) y en ese caso el watchdog no reanudará. Es comportamiento
 * correcto: un reboot completo es un evento lo bastante disruptivo como
 * para que no tenga sentido "reanudar" una grabación 30 min después.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var recordingIntentStore: RecordingIntentStore
    @Inject lateinit var watchdogScheduler: WatchdogScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (!recordingIntentStore.isRecordingIntended()) {
            Log.d(TAG, "Reboot sin intención de grabar — no rearmamos watchdog")
            return
        }
        Log.w(TAG, "Reboot detectado con intención de grabar activa — rearmando watchdog")
        runCatching { watchdogScheduler.schedule() }
            .onFailure { Log.e(TAG, "No se pudo rearmar watchdog tras boot", it) }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
