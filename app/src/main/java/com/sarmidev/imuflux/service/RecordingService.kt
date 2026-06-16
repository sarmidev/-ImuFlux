package com.sarmidev.imuflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sarmidev.imuflux.MainActivity
import com.sarmidev.imuflux.data.storage.SessionFileManager
import com.sarmidev.imuflux.recording.RecordingEngine
import com.sarmidev.imuflux.recording.RecordingWakeLockHolder
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
 * - `foregroundServiceType = specialUse` (Android 14+ / API 34+): evita el límite
 *   acumulativo de 6 h que Android 15 impone a `dataSync`. En Android 10-13 el código
 *   cae a `DATA_SYNC`, que no tiene ese límite en esas versiones.
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
    @Inject lateinit var sessionConfigStore: SessionConfigStore
    @Inject lateinit var watchdogScheduler: WatchdogScheduler
    @Inject lateinit var wakeLockHolder: RecordingWakeLockHolder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resumeOf = intent?.getStringExtra(EXTRA_RESUME_OF)
        val forklift = intent?.getStringExtra(EXTRA_FORKLIFT)
            ?: sessionConfigStore.getForklift()
        val warehouse = intent?.getStringExtra(EXTRA_WAREHOUSE)
            ?: sessionConfigStore.getWarehouse()
        when (intent?.action) {
            ACTION_START -> startServiceInternal(
                resumeOf = resumeOf,
                forkliftModel = forklift,
                warehouse = warehouse,
            )
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
            // Cuenta esta resurrección en la cadena. El nuevo session heredará
            // el contador actualizado vía `RecordingEngine.start(resumeOf=…)`.
            runCatching { sessionFileManager.incrementResurrectionCount(orphan.sessionId) }
                .onFailure { Log.w(TAG, "incrementResurrectionCount falló", it) }
            // Cerramos la vieja con minIdleMs=0 para forzar el cierre aunque
            // el último heartbeat sea muy reciente: es que acabamos de decidir
            // reanudarla, así que hay que marcarla como ended.
            sessionFileManager.closeOrphanedSessions(minIdleMs = 0L)
            startServiceInternal(
                resumeOf = orphan.sessionId,
                forkliftModel = sessionConfigStore.getForklift(),
                warehouse = sessionConfigStore.getWarehouse(),
            )
        } else {
            val closed = sessionFileManager.closeOrphanedSessions()
            if (closed > 0) {
                Log.w(TAG, "Limpieza tras reinicio: $closed sesiones huérfanas cerradas")
            }
        }
    }

    private fun startServiceInternal(
        resumeOf: String? = null,
        forkliftModel: String = "",
        warehouse: String = "",
    ) {
        createNotificationChannel()
        val notification = buildNotification()
        startForegroundCompat(notification)
        acquireWakeLock()
        recordingIntentStore.markRecordingStarted()
        watchdogScheduler.schedule()
        recordingEngine.start(
            resumeOf = resumeOf,
            forkliftModel = forkliftModel,
            warehouse = warehouse,
            toroId = sessionConfigStore.getToroId(),
        )
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
        when {
            // Android 14+ (API 34): specialUse no tiene el límite de 6 h de Android 15.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            // Android 10-13 (API 29-33): specialUse no existe; dataSync funciona sin límite.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        wakeLockHolder.acquireOrRenew(WAKELOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLockHolder.release()
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
        const val ACTION_START = "com.sarmidev.imuflux.action.START_RECORDING"
        const val ACTION_STOP = "com.sarmidev.imuflux.action.STOP_RECORDING"
        /** Extra opcional para pasar a [ACTION_START] el id de la sesión previa
         *  de la que ésta es continuación (auto-resume tras kill). */
        const val EXTRA_RESUME_OF = "com.sarmidev.imuflux.extra.RESUME_OF"
        /** Extras opcionales: modelo de toro y almacén seleccionados por el usuario. */
        const val EXTRA_FORKLIFT = "com.sarmidev.imuflux.extra.FORKLIFT"
        const val EXTRA_WAREHOUSE = "com.sarmidev.imuflux.extra.WAREHOUSE"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "imuflux_recording"
        /**
         * Timeout del wake-lock en cada `acquire`. Se re-adquiere desde el
         * heartbeat del [RecordingEngine] cada pocos segundos, así que con 2 h
         * sobra: es un margen de seguridad contra kills bruscos que dejen el
         * lock "zombi"; si la app muere y nadie libera, el sistema lo soltará
         * en ≤ 2 h.
         */
        const val WAKELOCK_TIMEOUT_MS: Long = 2L * 60L * 60L * 1000L
    }
}
