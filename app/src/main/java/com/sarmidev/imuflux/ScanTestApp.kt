package com.sarmidev.imuflux

import android.app.Application
import android.util.Log
import com.sarmidev.imuflux.data.diagnostics.DeviceIdentityProvider
import com.sarmidev.imuflux.data.diagnostics.FcmTokenRegistrar
import com.sarmidev.imuflux.data.storage.SessionFileManager
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
 *    proceso — **independiente** del [com.sarmidev.imuflux.service.RecordingService].
 *
 *    Esta limpieza adicional es crítica porque en muchos fabricantes (MIUI,
 *    EMUI, ColorOS, OneUI, etc.) Android mata nuestro proceso tras varias
 *    horas y **no relanza el servicio aunque sea START_STICKY**. En ese
 *    escenario, cuando el usuario abre la app, esta `onCreate` se ejecuta
 *    y cierra cualquier sesión que haya quedado con `session.lock` abierto.
 *
 *    Se ejecuta en [Dispatchers.IO] para no bloquear el arranque de la UI.
 *
 * 3. Realizar el sign-in anónimo de Firebase en el arranque para que
 *    [DeviceIdentityProvider.deviceId] esté resuelto antes de que empiece
 *    cualquier grabación o escritura de diagnósticos.
 */
@HiltAndroidApp
class ScanTestApp : Application() {

    @Inject lateinit var sessionFileManager: SessionFileManager
    @Inject lateinit var deviceIdentityProvider: DeviceIdentityProvider
    @Inject lateinit var fcmTokenRegistrar: FcmTokenRegistrar

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Sign in anonymously early so the Firebase UID is cached before
            // any recording or diagnostics write is attempted. Idempotent: if
            // Firebase Auth already has a persisted anonymous user this
            // returns immediately without a network round-trip.
            runCatching { deviceIdentityProvider.ensureSignedIn() }
                .onFailure { Log.w(TAG, "Startup anonymous sign-in failed: ${it.message}") }
            // Once the anonymous UID is resolved, register/refresh the FCM token
            // on the device's diagnostics document so the admin dashboard can
            // push remote recording commands. Fire-and-forget and failure-tolerant.
            runCatching { fcmTokenRegistrar.registerCurrentToken() }
                .onFailure { Log.w(TAG, "Startup FCM token registration failed: ${it.message}") }
        }
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
