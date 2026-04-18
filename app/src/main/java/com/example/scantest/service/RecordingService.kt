package com.example.scantest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.scantest.MainActivity
import com.example.scantest.data.storage.SessionFileManager
import com.example.scantest.recording.RecordingEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Servicio foreground que mantiene viva la sesión de grabación incluso con
 * la pantalla apagada.
 *
 * Diseño minimalista:
 * - **Sólo** gestiona ciclo de vida (start/stop), la notificación persistente
 *   y el `PARTIAL_WAKE_LOCK`. Toda la lógica de captura y escritura vive en
 *   [RecordingEngine].
 * - `foregroundServiceType = dataSync` (Android 14+): expresa la intención
 *   real ("sincronización continua de datos del dispositivo"). Se elimina el
 *   tipo `mediaPlayback` y el truco del `AudioTrack` silencioso.
 * - `START_STICKY`: si el sistema mata el proceso, Android volverá a arrancar
 *   el servicio con el último intent.
 * - Al reiniciarse tras un kill del LMK (intent == null), cierra automáticamente
 *   todas las sesiones huérfanas que hayan quedado con `session.lock` abierto.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var recordingEngine: RecordingEngine
    @Inject lateinit var sessionFileManager: SessionFileManager
    @Inject lateinit var recordingIntentStore: RecordingIntentStore
    @Inject lateinit var watchdogScheduler: WatchdogScheduler

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resumeOf = intent?.getStringExtra(EXTRA_RESUME_OF)
        when (intent?.action) {
            ACTION_START -> startServiceInternal(resumeOf = resumeOf)
            ACTION_STOP -> stopServiceInternal()
            else -> {
                // Sistema relanzó el servicio tras un kill (START_STICKY, intent == null).
                Log.w(TAG, "Servicio relanzado por el sistema — evaluando auto-resume")
                handleSystemRestart()
            }
        }
        LiveServiceRegistry.markAlive()
        return START_STICKY
    }

    /**
     * Se ejecuta cuando el sistema (o el watchdog) relanza el servicio tras
     * un kill. Decide si hay que **auto-reanudar** la grabación:
     *  1. El usuario tenía intención de grabar (`RecordingIntentStore`).
     *  2. Hay una sesión con lock y heartbeat reciente (< 15 min).
     *
     * Si se cumplen ambas, cerramos limpiamente la huérfana y arrancamos
     * otra sesión encadenada por `resume_of`. Si no, simplemente limpiamos
     * huérfanas estancadas.
     */
    private fun handleSystemRestart() {
        if (recordingEngine.isRecording.value) return // idempotencia: ya grabando
        val wantsRecording = recordingIntentStore.isRecordingIntended()
        val orphan = sessionFileManager.findRecentOrphan()

        if (wantsRecording && orphan != null) {
            val ageS = orphan.ageMs / 1000
            Log.w(
                TAG,
                "Auto-resume: sesión ${orphan.sessionId} murió hace ${ageS}s — " +
                    "cerrando huérfana y arrancando continuación (resume_of=${orphan.sessionId})",
            )
            // Cerramos la vieja con minIdleMs=0 para forzar el cierre aunque
            // el último heartbeat sea muy reciente: es que acabamos de decidir
            // reanudarla, así que hay que marcarla como ended.
            sessionFileManager.closeOrphanedSessions(minIdleMs = 0L)
            startServiceInternal(resumeOf = orphan.sessionId)
        } else {
            val closed = sessionFileManager.closeOrphanedSessions()
            if (closed > 0) {
                Log.w(TAG, "Limpieza tras reinicio: $closed sesiones huérfanas cerradas")
            }
        }
    }

    private fun startServiceInternal(resumeOf: String? = null) {
        createNotificationChannel()
        val notification = buildNotification()
        startForegroundCompat(notification)
        acquireWakeLock()
        recordingIntentStore.markRecordingStarted()
        watchdogScheduler.schedule()
        recordingEngine.start(resumeOf = resumeOf)
    }

    private fun stopServiceInternal() {
        recordingEngine.stop()
        releaseWakeLock()
        recordingIntentStore.markRecordingStopped()
        watchdogScheduler.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        if (!lock.isHeld) {
            lock.acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grabación IMU activa")
            .setContentText("Capturando sensores a 100 Hz. La app puede grabar con la pantalla apagada.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Grabación de sensores",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Notificación persistente mientras la grabación IMU está activa."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (recordingEngine.isRecording.value) {
            runCatching { recordingEngine.stop() }
        }
        LiveServiceRegistry.markDead()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.example.scantest.action.START_RECORDING"
        const val ACTION_STOP = "com.example.scantest.action.STOP_RECORDING"
        /** Extra opcional para pasar a [ACTION_START] el id de la sesión previa
         *  de la que ésta es continuación (auto-resume tras kill). */
        const val EXTRA_RESUME_OF = "com.example.scantest.extra.RESUME_OF"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "imuflux_recording"
        private const val WAKELOCK_TAG = "ImuFlux::RecordingWakeLock"
        /** 9 horas: cubre jornada completa con margen. */
        private const val WAKELOCK_TIMEOUT_MS: Long = 9L * 60L * 60L * 1000L
    }
}
