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
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var recordingEngine: RecordingEngine

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServiceInternal()
            ACTION_STOP -> stopServiceInternal()
            else -> Log.i(TAG, "onStartCommand sin acción — servicio reiniciado por el sistema")
        }
        return START_STICKY
    }

    private fun startServiceInternal() {
        createNotificationChannel()
        val notification = buildNotification()
        startForegroundCompat(notification)
        acquireWakeLock()
        recordingEngine.start()
    }

    private fun stopServiceInternal() {
        recordingEngine.stop()
        releaseWakeLock()
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
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.example.scantest.action.START_RECORDING"
        const val ACTION_STOP = "com.example.scantest.action.STOP_RECORDING"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "imuflux_recording"
        private const val WAKELOCK_TAG = "ImuFlux::RecordingWakeLock"
        /** 9 horas: cubre jornada completa con margen. */
        private const val WAKELOCK_TIMEOUT_MS: Long = 9L * 60L * 60L * 1000L
    }
}
