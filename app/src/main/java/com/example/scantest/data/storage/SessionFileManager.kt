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

    /**
     * Escribe `last_heartbeat_ms` en el metadata: marca de "estoy vivo" que el
     * [RecordingEngine] actualiza periódicamente durante la grabación. Si el
     * proceso muere bruscamente (LMK, kill OEM, térmica, reboot) este valor
     * queda como la última hora conocida y se usa como `ended_at_wall_ms`
     * aproximado cuando se cierra la sesión huérfana.
     *
     * Escritura tolerante a fallos: no lanza excepción si el archivo no
     * existe (nunca debería ocurrir, pero la grabación no debe abortar por
     * un error de disco puntual).
     */
    fun writeHeartbeat(sessionId: String, wallMs: Long) {
        val file = metadataFile(sessionId)
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            json.put("last_heartbeat_ms", wallMs)
            file.writeText(json.toString(2))
        }.onFailure { Log.w(TAG, "heartbeat falló para $sessionId", it) }
    }

    /**
     * Lee `last_heartbeat_ms` del metadata si existe. `null` si no está o
     * no se puede parsear.
     */
    fun readLastHeartbeatMs(sessionId: String): Long? {
        val file = metadataFile(sessionId)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.has("last_heartbeat_ms")) json.optLong("last_heartbeat_ms") else null
        }.getOrNull()
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
        var resumeOf: String? = null
        if (metaFile.exists()) {
            val json = JSONObject(metaFile.readText())
            startedAtWallMs = json.optLong("started_at_wall_ms", startedAtWallMs)
            if (json.has("ended_at_wall_ms")) endedAtWallMs = json.optLong("ended_at_wall_ms")
            if (json.has("resume_of") && !json.isNull("resume_of")) {
                resumeOf = json.optString("resume_of").takeIf { it.isNotEmpty() }
            }
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
            resumeOf = resumeOf,
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
     * Candidato a reanudación: sesión con lock presente y último latido
     * reciente. Usado por el auto-resume para reanudar grabación tras un
     * kill del sistema.
     */
    data class OrphanCandidate(
        val sessionId: String,
        val lastAliveMs: Long,
        val ageMs: Long,
    )

    /**
     * Busca la sesión huérfana con heartbeat/chunk más reciente, **si** su
     * antigüedad está por debajo de [maxAgeMs].
     *
     * - `null` si no hay huérfanas o la más reciente es demasiado vieja.
     * - Si hay varias, se devuelve la de `lastAliveMs` mayor (la última viva).
     *
     * Esto lo usan el [com.example.scantest.service.RecordingService] al
     * relanzarse y el watchdog para decidir si vale la pena auto-reanudar.
     * Si la última señal de vida fue hace > 15 min asumimos que el usuario
     * ya no espera una reanudación transparente.
     */
    fun findRecentOrphan(maxAgeMs: Long = DEFAULT_MAX_ORPHAN_RESUME_AGE_MS): OrphanCandidate? {
        val root = sessionsRoot
        val dirs = root.listFiles { f -> f.isDirectory } ?: return null
        val now = System.currentTimeMillis()
        var best: OrphanCandidate? = null
        for (dir in dirs) {
            val lock = File(dir, LOCK_FILE)
            if (!lock.exists()) continue
            val sessionId = dir.name
            val heartbeatMs = readLastHeartbeatMs(sessionId)
            val lastChunkMs = dir.listFiles { f ->
                f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".csv")
            }?.maxByOrNull { it.lastModified() }?.lastModified()
            val lastAliveMs = maxOf(heartbeatMs ?: 0L, lastChunkMs ?: 0L)
            if (lastAliveMs <= 0L) continue
            val age = now - lastAliveMs
            if (age > maxAgeMs) continue
            if (best == null || lastAliveMs > best.lastAliveMs) {
                best = OrphanCandidate(sessionId, lastAliveMs, age)
            }
        }
        return best
    }

    /**
     * Cierra todas las sesiones con `session.lock` presente en disco que
     * lleven estancadas al menos [minIdleMs] milisegundos.
     *
     * "Estancada" = la referencia de último latido (heartbeat del metadata
     * o mtime del chunk más reciente) tiene una antigüedad ≥ [minIdleMs].
     * Esto evita cerrar por error una grabación que acaba de arrancar y
     * todavía no ha escrito.
     *
     * Para cada sesión huérfana detectada:
     *  1. Escribe `ended_at_wall_ms` / `ended_at_boot_ns` en `metadata.json`
     *     usando el heartbeat si existe, o el mtime del último chunk.
     *  2. Borra el `session.lock`.
     *
     * Se llama en tres puntos (defensa en profundidad):
     *  - [RecordingService.onStartCommand] cuando `intent == null` (relanzado por
     *    el sistema tras un kill).
     *  - `Application.onCreate` (arranque del proceso por cualquier causa).
     *  - [listSessions] (al abrir la pantalla de sesiones, limpieza on-the-fly).
     *
     * @param minIdleMs tiempo mínimo sin actividad para considerar estancada.
     *   Default 60 s: suficiente para no confundir con una pausa transitoria.
     * @return número de sesiones cerradas.
     */
    fun closeOrphanedSessions(minIdleMs: Long = DEFAULT_ORPHAN_MIN_IDLE_MS): Int {
        val root = sessionsRoot
        val dirs = root.listFiles { f -> f.isDirectory } ?: return 0
        val now = System.currentTimeMillis()
        var closed = 0
        for (dir in dirs) {
            val lock = File(dir, LOCK_FILE)
            if (!lock.exists()) continue
            val sessionId = dir.name

            val heartbeatMs = readLastHeartbeatMs(sessionId)
            val lastChunkMs = dir.listFiles { f ->
                f.isFile && f.name.startsWith("chunk_") && f.name.endsWith(".csv")
            }?.maxByOrNull { it.lastModified() }?.lastModified()
            // Referencia temporal: la más reciente entre heartbeat y mtime de chunk.
            val lastAliveMs = maxOf(heartbeatMs ?: 0L, lastChunkMs ?: 0L)
                .takeIf { it > 0L }
                ?: lock.lastModified()

            if (now - lastAliveMs < minIdleMs) {
                // Grabación reciente — no tocar, puede estar vivita.
                continue
            }

            runCatching {
                markSessionEnded(
                    sessionId = sessionId,
                    endedAtWallMs = lastAliveMs,
                    endedAtBootNs = 0L, // desconocido tras reinicio de proceso
                )
                endSession(sessionId)
            }.onSuccess {
                val idleMin = (now - lastAliveMs) / 60_000
                val cause = when {
                    heartbeatMs != null -> "heartbeat hace ${idleMin} min"
                    lastChunkMs != null -> "último chunk hace ${idleMin} min"
                    else -> "sin referencia temporal"
                }
                Log.w(
                    TAG,
                    "Sesión huérfana cerrada: $sessionId " +
                        "(ended≈$lastAliveMs, $cause — probable kill del sistema)",
                )
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
        /** Tiempo sin actividad para considerar una sesión huérfana. */
        const val DEFAULT_ORPHAN_MIN_IDLE_MS: Long = 60_000L
        /** Edad máxima del último heartbeat para que valga auto-reanudar. */
        const val DEFAULT_MAX_ORPHAN_RESUME_AGE_MS: Long = 15L * 60_000L
    }
}
