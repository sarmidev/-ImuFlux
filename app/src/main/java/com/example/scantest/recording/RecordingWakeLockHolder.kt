package com.example.scantest.recording

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contenedor singleton del `PARTIAL_WAKE_LOCK` usado mientras hay grabación.
 *
 * Separa la gestión del lock del servicio que lo solicita: así tanto
 * [com.example.scantest.service.RecordingService] (al arrancar/parar) como
 * [RecordingEngine] (desde su heartbeat) pueden pedir que el lock esté vivo
 * sin depender uno del otro ni duplicar objetos.
 *
 * ## Por qué re-adquirir en cada heartbeat
 *
 * El sistema cancela los `acquire(timeoutMs)` cuando vence el timeout. Si
 * grabamos más tiempo que el timeout configurado, a partir de ese punto la
 * CPU podría entrar en Doze pese a estar "grabando". Para evitarlo sin
 * regalar un lock zombi en caso de kill brusco del proceso, el patrón es:
 *
 *  - Timeout corto (ej. 2 h) en cada `acquire`.
 *  - Renovación periódica desde el heartbeat (30 s) mientras siga activo.
 *
 * Si el proceso muere y por algún motivo no se llama a [release], el timeout
 * del sistema liberará el lock en el peor caso ~2 h después — manejable.
 *
 * No es thread-safe: se espera que todas las llamadas vengan del hilo IO
 * dedicado del engine o del main thread del servicio, que nunca solapan en
 * un mismo instante.
 */
@Singleton
class RecordingWakeLockHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Adquiere el lock si no está creado y (re)arma su timeout a [timeoutMs].
     * Llamadas repetidas son idempotentes: extienden el timeout sin liberar
     * el lock entre medias.
     *
     * Nota: la API `WakeLock.acquire(timeout)` re-arma el contador de
     * timeout desde cero en cada llamada. No es necesario `release` previo.
     */
    fun acquireOrRenew(timeoutMs: Long) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_LOCK).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        runCatching { lock.acquire(timeoutMs) }
            .onFailure { Log.w(TAG, "acquire(${timeoutMs}ms) falló", it) }
    }

    /** Libera el lock si está tomado. Idempotente. */
    fun release() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
        }.onFailure { Log.w(TAG, "release falló", it) }
    }

    /** Sólo para inspección en logs. */
    fun isHeld(): Boolean = wakeLock?.isHeld == true

    companion object {
        private const val TAG = "RecordingWakeLock"
        private const val TAG_LOCK = "ImuFlux::RecordingWakeLock"
    }
}
