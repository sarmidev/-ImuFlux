package com.example.scantest.service

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Almacena la configuración contextual de la sesión (modelo de toro y almacén)
 * que el usuario introduce antes de grabar.
 *
 * Responsabilidades:
 *  - Persistir el **último** valor seleccionado para pre-rellenar el siguiente
 *    arranque de la app.
 *  - Mantener una lista corta de **valores recientes** como sugerencias para
 *    facilitar la reutilización (hasta [MAX_RECENTS] por campo).
 *  - Ser la fuente de verdad que [RecordingService.handleSystemRestart] consulta
 *    cuando el sistema relanza el servicio tras un kill: así el auto-resume
 *    mantiene el mismo contexto que la sesión original sin depender de que el
 *    watchdog/boot receiver pase los extras.
 *
 * Nota: la lista de recientes usa `|` como separador y la sanitización en
 * [CsvSchema.sanitizeCsvValue] ya elimina comas y saltos de línea. Añadimos
 * además un filtro del pipe para no romper la propia serialización.
 */
@Singleton
class SessionConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getForklift(): String = prefs.getString(KEY_FORKLIFT, "").orEmpty()
    fun getWarehouse(): String = prefs.getString(KEY_WAREHOUSE, "").orEmpty()

    /** Establece el valor actual y lo promueve al principio de la lista de recientes. */
    fun setForklift(value: String) {
        val clean = normalize(value)
        prefs.edit()
            .putString(KEY_FORKLIFT, clean)
            .putString(KEY_RECENT_FORKLIFTS, pushRecent(getRecentForklifts(), clean))
            .apply()
    }

    fun setWarehouse(value: String) {
        val clean = normalize(value)
        prefs.edit()
            .putString(KEY_WAREHOUSE, clean)
            .putString(KEY_RECENT_WAREHOUSES, pushRecent(getRecentWarehouses(), clean))
            .apply()
    }

    fun getRecentForklifts(): List<String> = readRecents(KEY_RECENT_FORKLIFTS)
    fun getRecentWarehouses(): List<String> = readRecents(KEY_RECENT_WAREHOUSES)

    /** Hay configuración mínima válida para iniciar una grabación. */
    fun isReady(): Boolean = getForklift().isNotBlank() && getWarehouse().isNotBlank()

    private fun readRecents(key: String): List<String> =
        prefs.getString(key, "").orEmpty()
            .split('|')
            .filter { it.isNotBlank() }

    private fun pushRecent(current: List<String>, value: String): String {
        if (value.isBlank()) return current.joinToString("|")
        val merged = (listOf(value) + current.filterNot { it.equals(value, ignoreCase = true) })
            .take(MAX_RECENTS)
        return merged.joinToString("|")
    }

    private fun normalize(raw: String): String =
        raw.replace('|', ' ')
            .replace(',', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

    companion object {
        private const val PREFS_NAME = "imuflux_session_config"
        private const val KEY_FORKLIFT = "current_forklift"
        private const val KEY_WAREHOUSE = "current_warehouse"
        private const val KEY_RECENT_FORKLIFTS = "recent_forklifts"
        private const val KEY_RECENT_WAREHOUSES = "recent_warehouses"
        private const val MAX_RECENTS = 8
    }
}
