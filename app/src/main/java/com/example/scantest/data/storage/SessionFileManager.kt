package com.example.scantest.data.storage

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.scantest.domain.model.SessionMetadata
import com.example.scantest.domain.model.SessionSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestiona el árbol de sesiones en almacenamiento interno:
 *
 * ```
 * filesDir/sessions/
 *     <session_id>/
 *         metadata.json
 *         session.lock        (presente mientras la sesión está activa)
 *         chunk_000.csv
 *         chunk_001.csv
 *         ...
 * ```
 *
 * Ofrece API síncrona pensada para ser llamada desde `Dispatchers.IO`.
 */
@Singleton
class SessionFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Raíz de todas las sesiones. Se crea bajo demanda. */
    val sessionsRoot: File
        get() = File(context.filesDir, SESSIONS_DIR).also { it.mkdirs() }

    /** Genera un nuevo id basado en el reloj de pared y crea el directorio. */
    fun newSessionId(): String {
        val fmt = SimpleDateFormat(SESSION_ID_PATTERN, Locale.US)
        return fmt.format(Date())
    }

    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    fun chunkFile(sessionId: String, index: Int): File =
        File(sessionDir(sessionId), String.format(Locale.US, "chunk_%03d.csv", index))

    fun lockFile(sessionId: String): File = File(sessionDir(sessionId), LOCK_FILE)

    fun metadataFile(sessionId: String): File = File(sessionDir(sessionId), METADATA_FILE)

    /** Crea el directorio de la sesión (si no existe) y deja el lock file. */
    fun beginSession(sessionId: String) {
        val dir = sessionDir(sessionId)
        dir.mkdirs()
        runCatching { lockFile(sessionId).createNewFile() }
            .onFailure { Log.w(TAG, "No pude crear lock file para $sessionId", it) }
    }

    /** Elimina el lock file: la sesión ya no está activa. */
    fun endSession(sessionId: String) {
        runCatching { lockFile(sessionId).delete() }
    }

    /**
     * Comprueba si hay espacio libre suficiente en el almacenamiento interno.
     * Devuelve `true` si hay al menos [minBytes] libres.
     */
    fun hasFreeSpace(minBytes: Long): Boolean {
        return context.filesDir.usableSpace >= minBytes
    }

    /** Serializa y escribe `metadata.json`. */
    fun writeMetadata(metadata: SessionMetadata) {
        val json = JSONObject().apply {
            put("session_id", metadata.sessionId)
            put("started_at_wall_ms", metadata.startedAtWallMs)
            put("started_at_boot_ns", metadata.startedAtBootNs)
            metadata.endedAtWallMs?.let { put("ended_at_wall_ms", it) }
            metadata.endedAtBootNs?.let { put("ended_at_boot_ns", it) }
            put("device", JSONObject().apply {
                put("model", metadata.deviceModel)
                put("manufacturer", metadata.deviceManufacturer)
                put("sdk", metadata.sdkInt)
            })
            put("app_version", metadata.appVersion)
            put("sensors", JSONArray().apply {
                metadata.sensors.forEach { s ->
                    put(JSONObject().apply {
                        put("type", s.type)
                        put("name", s.name)
                        put("vendor", s.vendor)
                        put("resolution", s.resolution.toDouble())
                        put("fifo_max_event_count", s.fifoMaxEventCount)
                        put("min_delay_us", s.minDelayUs)
                    })
                }
            })
            put("columns", JSONArray(metadata.columns))
            put("chunk_duration_ms", metadata.chunkDurationMs)
            put("chunk_max_bytes", metadata.chunkMaxBytes)
            metadata.resumeOf?.let { put("resume_of", it) }
        }
        metadataFile(metadata.sessionId).writeText(json.toString(2))
    }

    /** Actualiza sólo `ended_at_*` en el metadata ya existente. */
    fun markSessionEnded(sessionId: String, endedAtWallMs: Long, endedAtBootNs: Long) {
        val file = metadataFile(sessionId)
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        json.put("ended_at_wall_ms", endedAtWallMs)
        json.put("ended_at_boot_ns", endedAtBootNs)
        file.writeText(json.toString(2))
    }

    /** Lista todas las sesiones presentes en disco, ordenadas de más reciente a más antigua. */
    fun listSessions(): List<SessionSummary> {
        val root = sessionsRoot
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { summarize(dir) }.getOrNull()
        }.sortedByDescending { it.startedAtWallMs }
    }

    private fun summarize(dir: File): SessionSummary {
        val sessionId = dir.name
        val metaFile = File(dir, METADATA_FILE)
        val chunkFiles = dir.listFiles { f ->
            f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".csv")
        } ?: emptyArray()
        val totalBytes = chunkFiles.sumOf { it.length() }
        var startedAtWallMs = dir.lastModified()
        var endedAtWallMs: Long? = null
        if (metaFile.exists()) {
            val json = JSONObject(metaFile.readText())
            startedAtWallMs = json.optLong("started_at_wall_ms", startedAtWallMs)
            if (json.has("ended_at_wall_ms")) endedAtWallMs = json.optLong("ended_at_wall_ms")
        }
        val durationMs = endedAtWallMs?.let { it - startedAtWallMs }
            ?: (System.currentTimeMillis() - startedAtWallMs)
        return SessionSummary(
            sessionId = sessionId,
            startedAtWallMs = startedAtWallMs,
            durationMs = durationMs,
            chunkCount = chunkFiles.size,
            totalBytes = totalBytes,
            isActive = File(dir, LOCK_FILE).exists(),
        )
    }

    /** Devuelve los chunks ordenados (chunk_000, chunk_001, ...). */
    fun listChunks(sessionId: String): List<File> {
        val dir = sessionDir(sessionId)
        val chunks = dir.listFiles { f ->
            f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".csv")
        } ?: return emptyList()
        return chunks.sortedBy { it.name }
    }

    /**
     * Cierra todas las sesiones que tienen `session.lock` presente en disco.
     * Se llama al arrancar el proceso tras un kill inesperado del sistema (LMK),
     * cuando `onDestroy()` no llegó a ejecutarse y el lock no se borró.
     *
     * Para cada sesión huérfana:
     * 1. Escribe `ended_at_wall_ms` / `ended_at_boot_ns` en `metadata.json`
     *    usando la fecha de modificación del último chunk (mejor aproximación
     *    al momento real de la última escritura), o la hora actual si no hay chunks.
     * 2. Borra el `session.lock`.
     *
     * @return número de sesiones cerradas.
     */
    fun closeOrphanedSessions(): Int {
        val root = sessionsRoot
        val dirs = root.listFiles { f -> f.isDirectory } ?: return 0
        var closed = 0
        for (dir in dirs) {
            val lock = File(dir, LOCK_FILE)
            if (!lock.exists()) continue
            val sessionId = dir.name
            // Mejor aproximación del último dato escrito: fecha del chunk más reciente.
            val lastChunkMs = dir.listFiles { f ->
                f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".csv")
            }?.maxByOrNull { it.lastModified() }?.lastModified()
                ?: System.currentTimeMillis()
            runCatching {
                markSessionEnded(
                    sessionId = sessionId,
                    endedAtWallMs = lastChunkMs,
                    endedAtBootNs = 0L, // desconocido tras reinicio de proceso
                )
                endSession(sessionId)
            }.onSuccess {
                Log.w(TAG, "Sesión huérfana cerrada: $sessionId (ended≈${lastChunkMs})")
                closed++
            }.onFailure {
                Log.e(TAG, "No se pudo cerrar sesión huérfana: $sessionId", it)
            }
        }
        return closed
    }

    /** Borra la sesión entera. */
    fun deleteSession(sessionId: String): Boolean {
        val dir = sessionDir(sessionId)
        return dir.deleteRecursively()
    }

    /** Construye un descriptor de dispositivo para `metadata.json`. */
    fun buildDeviceInfoTriplet(): Triple<String, String, Int> =
        Triple(Build.MODEL ?: "unknown", Build.MANUFACTURER ?: "unknown", Build.VERSION.SDK_INT)

    companion object {
        private const val TAG = "SessionFileManager"
        private const val SESSIONS_DIR = "sessions"
        private const val METADATA_FILE = "metadata.json"
        private const val LOCK_FILE = "session.lock"
        private const val SESSION_ID_PATTERN = "yyyyMMdd_HHmmss"
    }
}
