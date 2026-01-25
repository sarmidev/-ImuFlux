package com.example.scantest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.scantest.MainActivity
import com.example.scantest.R
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

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sensor_channel"
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
        
        // Android 14 requiere especificar el tipo si se declara en manifest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        sensorDataManager.setRecording(true)
        startCollection()
    }

    private fun stopService() {
        sensorDataManager.setRecording(false)
        collectionJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
                    // 1. Evaluar movimiento
                    val activeMovements = movements.filter { it.isActive }
                    val result = evaluateMovementUseCase(snapshot.values, activeMovements)
                    
                    // 2. Actualizar Manager (esto actualiza UI y guarda en buffer)
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
        
        // Stop action intent
        val stopIntent = Intent(this, SensorForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grabando Sensores")
            .setContentText("La recolección de datos está activa en segundo plano.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de tener un icono válido o usa uno del sistema
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
        serviceScope.cancel()
    }
}