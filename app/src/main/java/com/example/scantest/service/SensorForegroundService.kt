package com.example.scantest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.scantest.MainActivity
import com.example.scantest.data.manager.SensorDataManager
import com.example.scantest.domain.usecase.EvaluateMovementUseCase
import com.example.scantest.domain.usecase.GetMovementsUseCase
import com.example.scantest.domain.usecase.GetSensorDataUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SensorForegroundService : Service() {

    @Inject lateinit var sensorDataManager: SensorDataManager
    @Inject lateinit var getSensorDataUseCase: GetSensorDataUseCase
    @Inject lateinit var getMovementsUseCase: GetMovementsUseCase
    @Inject lateinit var evaluateMovementUseCase: EvaluateMovementUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectionJob: Job? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    
    // TRUCO DE AUDIO SILENCIOSO
    private var audioTrack: AudioTrack? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sensor_channel"
        private const val WAKELOCK_TAG = "ScanTest::SensorRecordingWakeLock"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
        }
        return START_STICKY
    }

    private fun startService() {
        createNotificationChannel()
        val notification = buildNotification()
        
        // Usamos MEDIA_PLAYBACK type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        acquireWakeLock()
        
        // INICIAMOS EL AUDIO SILENCIOSO
        playSilentAudio()

        sensorDataManager.setRecording(true)
        startCollection()
    }

    private fun stopService() {
        sensorDataManager.setRecording(false)
        collectionJob?.cancel()
        
        // PARAMOS AUDIO
        stopSilentAudio()
        
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    // --- Lógica "Nuclear" de Audio ---
    private fun playSilentAudio() {
        if (audioTrack != null) return

        try {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate, 
                AudioFormat.CHANNEL_OUT_MONO, 
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            // Generar silencio (buffer de ceros)
            val silentData = ByteArray(bufferSize)
            audioTrack?.write(silentData, 0, silentData.size)
            
            // Loop infinito
            audioTrack?.setLoopPoints(0, silentData.size / 2, -1)
            
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopSilentAudio() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioTrack = null
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 60 * 1000L) 
        }
    }
    
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun startCollection() {
        collectionJob?.cancel()
        collectionJob = serviceScope.launch {
            combine(
                getSensorDataUseCase(),
                getMovementsUseCase()
            ) { snapshot, movements ->
                Pair(snapshot, movements)
            }.collect { (snapshot, movements) ->
                if (snapshot.values.isNotEmpty()) {
                    val activeMovements = movements.filter { it.isActive }
                    val result = evaluateMovementUseCase(snapshot.values, activeMovements)
                    
                    sensorDataManager.updateSnapshot(snapshot)
                    sensorDataManager.setDetectedMovement(result)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, SensorForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grabando Sensores (Modo Activo)")
            .setContentText("Grabación ininterrumpida activa.")
            .setSmallIcon(android.R.drawable.stat_sys_headset) // Icono de auriculares para indicar audio
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensor Recording Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSilentAudio()
        releaseWakeLock()
        serviceScope.cancel()
    }
}