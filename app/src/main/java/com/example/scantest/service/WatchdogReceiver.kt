package com.example.scantest.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.scantest.data.storage.SessionFileManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Red de seguridad contra kills OEM que ignoran `START_STICKY`.
 *
 * Lógica en cada disparo (cada 10 min):
 *  1. Si [LiveServiceRegistry.isAlive] es `true` y [SessionFileManager]
 *     no reporta huérfanas recientes → todo bien, sólo reprogramamos.
 *  2. Si el usuario NO había marcado intención de grabar → idle; reprogramamos.
 *  3. Si había intención y el servicio está muerto o hay huérfana reciente →
 *     arrancamos [RecordingService] con `ACTION_START` + `EXTRA_RESUME_OF`
 *     apuntando a la sesión previa. `RecordingService` se encarga de cerrar
 *     la huérfana y crear la continuación.
 *  4. Siempre, al final, reprogramamos la siguiente alarma.
 *
 * El receiver está anotado con `@AndroidEntryPoint` para permitir inyección
 * Hilt de `SessionFileManager` y `RecordingIntentStore`.
 */
@AndroidEntryPoint
class WatchdogReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionFileManager: SessionFileManager
    @Inject lateinit var recordingIntentStore: RecordingIntentStore
    @Inject lateinit var watchdogScheduler: WatchdogScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TICK) return
        try {
            evaluateAndAct(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Fallo inesperado en watchdog tick", t)
        } finally {
            // Siempre reprogramamos — incluso si hay fallo arriba.
            runCatching { watchdogScheduler.schedule() }
        }
    }

    private fun evaluateAndAct(context: Context) {
        val wantsRecording = recordingIntentStore.isRecordingIntended()
        val alive = LiveServiceRegistry.isAlive()
        val orphan = sessionFileManager.findRecentOrphan()

        Log.d(
            TAG,
            "tick: wantsRecording=$wantsRecording alive=$alive orphan=${orphan?.sessionId}",
        )

        if (!wantsRecording) {
            // Usuario no quiere estar grabando → si hay huérfanas estancadas
            // aprovechamos para cerrarlas (defensa en profundidad).
            sessionFileManager.closeOrphanedSessions()
            return
        }

        if (alive) {
            // El servicio dice que está vivo; asumimos que sigue grabando.
            // Si además hay orphan (no debería) es un caso raro — no tocar.
            return
        }

        // Servicio muerto y el usuario quería grabar → relanzar.
        val resumeOf = orphan?.sessionId
        if (resumeOf != null) {
            // Sella el contador en la sesión huérfana. El RecordingEngine al
            // crear la continuación heredará ese contador como inicio, de modo
            // que `watchdog_resurrections` sea acumulativo a lo largo de toda
            // la cadena de resurrecciones (indicador de dispositivo hostil).
            runCatching { sessionFileManager.incrementResurrectionCount(resumeOf) }
                .onFailure { Log.w(TAG, "incrementResurrectionCount($resumeOf) falló", it) }
        }
        val startIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            if (resumeOf != null) putExtra(RecordingService.EXTRA_RESUME_OF, resumeOf)
        }
        Log.w(
            TAG,
            "Servicio muerto pero el usuario quería grabar — relanzando " +
                (resumeOf?.let { "como continuación de $it" } ?: "como sesión nueva"),
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }.onFailure { Log.e(TAG, "No se pudo relanzar RecordingService", it) }
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
        const val ACTION_TICK = "com.example.scantest.action.WATCHDOG_TICK"
    }
}
