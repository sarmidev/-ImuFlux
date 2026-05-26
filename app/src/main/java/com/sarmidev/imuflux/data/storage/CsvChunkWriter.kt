package com.sarmidev.imuflux.data.storage

import android.os.SystemClock
import android.util.Log
import com.sarmidev.imuflux.domain.model.SensorFrame
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Escritor CSV streaming con rotación automática por tiempo o tamaño.
 *
 * **Propiedad clave**: cada chunk es **autocontenido** (contiene cabecera
 * completa). El orden de columnas es el definido en [CsvSchema].
 *
 * No es thread-safe: se espera que todas las llamadas ocurran desde el
 * mismo hilo (el `Dispatchers.IO.limitedParallelism(1)` del RecordingEngine).
 * Con eso se evita cualquier `synchronized` en el hot path de escritura.
 *
 * Formato numérico:
 * - 4 decimales fijos, sin uso de `Locale` (siempre `.` como separador).
 * - `NaN` → campo vacío (`,,`).
 * - `Infinity` → campo vacío (defensivo).
 *
 * Rotación:
 * - Al superar [chunkDurationMs] **desde el primer frame del chunk actual**.
 * - O al superar [chunkMaxBytes].
 */
class CsvChunkWriter(
    private val sessionFileManager: SessionFileManager,
    private val sessionId: String,
    forkliftModel: String,
    warehouse: String,
    deviceModel: String,
    private val chunkDurationMs: Long = DEFAULT_CHUNK_DURATION_MS,
    private val chunkMaxBytes: Long = DEFAULT_CHUNK_MAX_BYTES,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val onChunkRotated: ((rotatedChunkIndex: Int) -> Unit)? = null,
) : Closeable {

    /**
     * Sufijo estático `,forklift_model,warehouse,device_model` pre-sanitizado
     * y pre-serializado que se anexa a cada fila. Construirlo una única vez
     * evita allocations y llamadas a `replace` en el hot path (a 100 Hz durante
     * 8 h son ≈ 2.9 M filas).
     */
    private val rowSuffix: String = run {
        val forklift = CsvSchema.sanitizeCsvValue(forkliftModel)
        val wh = CsvSchema.sanitizeCsvValue(warehouse)
        val dev = CsvSchema.sanitizeCsvValue(deviceModel)
        ",$forklift,$wh,$dev"
    }

    private var currentChunkIndex: Int = 0
    private var currentFile: File? = null
    private var currentOutputStream: FileOutputStream? = null
    private var currentWriter: BufferedWriter? = null
    private var currentChunkBytes: Long = 0L
    private var chunkStartBootMs: Long = 0L
    private var lastFlushBootMs: Long = 0L

    private var totalFramesWritten: Long = 0L
    private var totalBytesWritten: Long = 0L

    /** Buffer reutilizable para formatear cada fila. Máx ≈ 18 columnas × 12 chars. */
    private val lineBuffer: StringBuilder = StringBuilder(256)

    init {
        openNextChunk()
    }

    /** Índice 0-based del chunk que se está escribiendo actualmente. */
    val chunkIndex: Int get() = currentChunkIndex

    /** Total de frames (filas) escritos por este writer. */
    val framesWritten: Long get() = totalFramesWritten

    /** Total de bytes escritos (antes de flush → aproximado; útil para UI). */
    val bytesWritten: Long get() = totalBytesWritten

    /** Escribe una fila. Realiza rotación y flush periódico si procede. */
    fun writeFrame(frame: SensorFrame) {
        val writer = currentWriter ?: error("Writer cerrado")
        lineBuffer.setLength(0)
        lineBuffer.append(frame.timestampNs)
        val values = frame.values
        for (i in CsvSchema.CSV_SLOT_INDICES) {
            lineBuffer.append(',')
            val v = values[i]
            if (v.isFinite()) appendFloat4(lineBuffer, v)
        }
        lineBuffer.append(rowSuffix)
        lineBuffer.append('\n')
        val lineLen = lineBuffer.length
        writer.append(lineBuffer)
        currentChunkBytes += lineLen
        totalBytesWritten += lineLen
        totalFramesWritten += 1

        val nowBootMs = SystemClock.elapsedRealtime()
        if (nowBootMs - lastFlushBootMs >= flushIntervalMs) {
            runCatching {
                writer.flush()
                currentOutputStream?.fd?.takeIf { it.valid() }?.sync()
            }.onFailure { Log.w(TAG, "flush/fd.sync falló", it) }
            lastFlushBootMs = nowBootMs
        }

        val shouldRotateByTime = nowBootMs - chunkStartBootMs >= chunkDurationMs
        val shouldRotateBySize = currentChunkBytes >= chunkMaxBytes
        if (shouldRotateByTime || shouldRotateBySize) {
            rotate()
        }
    }

    /** Cierra el writer activo. Idempotente. */
    override fun close() {
        val writer = currentWriter
        val stream = currentOutputStream
        currentWriter = null
        currentOutputStream = null
        runCatching {
            writer?.flush()
            stream?.fd?.takeIf { it.valid() }?.sync()
            writer?.close()
        }.onFailure { Log.w(TAG, "close: flush/close falló", it) }
    }

    private fun rotate() {
        val rotatedIndex = currentChunkIndex
        close()
        currentChunkIndex += 1
        openNextChunk()
        runCatching { onChunkRotated?.invoke(rotatedIndex) }
            .onFailure { Log.w(TAG, "onChunkRotated callback falló", it) }
    }

    private fun openNextChunk() {
        val file = sessionFileManager.chunkFile(sessionId, currentChunkIndex)
        currentFile = file
        val stream = FileOutputStream(file, /* append = */ false)
        currentOutputStream = stream
        val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), WRITE_BUFFER_BYTES)
        writer.write(CsvSchema.HEADER_LINE)
        writer.newLine()
        currentWriter = writer
        currentChunkBytes = CsvSchema.HEADER_LINE.length + 1L
        totalBytesWritten += currentChunkBytes
        val nowBootMs = SystemClock.elapsedRealtime()
        chunkStartBootMs = nowBootMs
        lastFlushBootMs = nowBootMs
        Log.i(TAG, "Abierto chunk #$currentChunkIndex → ${file.name}")
    }

    /**
     * Formateo alloc-free de `Float` con 4 decimales fijos, usando enteros.
     *
     * Precisión suficiente para IMU (resolución típica de acelerómetros gama
     * media ≈ 0.002 m/s²; 4 decimales = 0.0001). No escribe el separador.
     */
    private fun appendFloat4(sb: StringBuilder, value: Float) {
        val scaled = Math.round(value * 10_000.0)
        if (scaled < 0) {
            sb.append('-')
            appendPositiveFixed(sb, -scaled)
        } else {
            appendPositiveFixed(sb, scaled)
        }
    }

    private fun appendPositiveFixed(sb: StringBuilder, intValue: Long) {
        val whole = intValue / 10_000L
        val frac = intValue % 10_000L
        sb.append(whole).append('.')
        if (frac < 1000L) sb.append('0')
        if (frac < 100L) sb.append('0')
        if (frac < 10L) sb.append('0')
        sb.append(frac)
    }

    companion object {
        private const val TAG = "CsvChunkWriter"
        /** Buffer del BufferedWriter: 64 KB → reduce syscalls. */
        private const val WRITE_BUFFER_BYTES: Int = 64 * 1024
        /** 5 minutos. */
        const val DEFAULT_CHUNK_DURATION_MS: Long = 5L * 60_000L
        /** 20 MiB. */
        const val DEFAULT_CHUNK_MAX_BYTES: Long = 20L * 1024L * 1024L
        /** Flush cada 1 s (≈ 100 frames a 100 Hz). */
        const val DEFAULT_FLUSH_INTERVAL_MS: Long = 1_000L
    }
}
