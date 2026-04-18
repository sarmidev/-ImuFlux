package com.example.scantest

import android.app.Application
import android.util.Log
import com.example.scantest.data.storage.SessionFileManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application class para Hilt. Responsabilidades mínimas:
 *
 * 1. Ofrecer un contenedor Hilt a todo el proceso.
 * 2. Ejecutar una limpieza defensiva de sesiones huérfanas al arrancar el
 *    proceso — **independiente** del [com.example.scantest.service.RecordingService].
 *
 *    Esta limpieza adicional es crítica porque en muchos fabricantes (MIUI,
 *    EMUI, ColorOS, OneUI, etc.) Android mata nuestro proceso tras varias
 *    horas y **no relanza el servicio aunque sea START_STICKY**. En ese
 *    escenario, cuando el usuario abre la app, esta `onCreate` se ejecuta
 *    y cierra cualquier sesión que haya quedado con `session.lock` abierto.
 *
 *    Se ejecuta en [Dispatchers.IO] para no bloquear el arranque de la UI.
 */
@HiltAndroidApp
class ScanTestApp : Application() {

    @Inject lateinit var sessionFileManager: SessionFileManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching {
                val closed = sessionFileManager.closeOrphanedSessions()
                if (closed > 0) {
                    Log.w(
                        TAG,
                        "Al arrancar el proceso se cerraron $closed sesiones huérfanas " +
                            "(probable kill OEM / LMK / thermal en la sesión anterior).",
                    )
                }
            }.onFailure { Log.e(TAG, "Fallo al limpiar huérfanas en Application.onCreate", it) }
        }
    }

    companion object {
        private const val TAG = "ScanTestApp"
    }
}
