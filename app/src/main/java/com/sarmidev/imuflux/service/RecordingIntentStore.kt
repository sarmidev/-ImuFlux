package com.sarmidev.imuflux.service

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistencia ligera de la "intención de grabar" del usuario.
 *
 * Semántica: `true` entre `Start` (usuario pulsa grabar) y `Stop` (usuario
 * pulsa parar). Si el proceso muere entre medias, al relanzarse veremos
 * `true` y sabremos que tenemos permiso para auto-reanudar la grabación.
 *
 * Nunca debe ponerse a `true` desde el sistema (watchdog, boot receiver,
 * auto-resume) — sólo desde una acción explícita del usuario. Esto evita
 * que la app arranque grabaciones fantasma tras reinicios o actualizaciones.
 *
 * Uso de `SharedPreferences` (no DataStore): API síncrona mínima, el coste
 * de un arranque lazy de DataStore es desproporcionado para un único boolean.
 */
@Singleton
class RecordingIntentStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Llamar cuando el usuario inicia grabación. */
    fun markRecordingStarted() {
        prefs.edit().putBoolean(KEY_INTENT, true).apply()
    }

    /** Llamar cuando el usuario para grabación (acción explícita). */
    fun markRecordingStopped() {
        prefs.edit().putBoolean(KEY_INTENT, false).apply()
    }

    /**
     * @return `true` si el usuario había iniciado grabación y no la ha
     *   parado explícitamente.
     */
    fun isRecordingIntended(): Boolean = prefs.getBoolean(KEY_INTENT, false)

    companion object {
        private const val PREFS_NAME = "imuflux_recording_intent"
        private const val KEY_INTENT = "recording_intent"
    }
}
