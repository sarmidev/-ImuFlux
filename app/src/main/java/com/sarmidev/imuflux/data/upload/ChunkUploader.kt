package com.sarmidev.imuflux.data.upload

import android.util.Log
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsLogger
import com.sarmidev.imuflux.data.diagnostics.ImuDiagnosticsAggregator
import com.sarmidev.imuflux.data.storage.SessionFileManager
import com.sarmidev.imuflux.service.SessionConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Motor de subida de chunks CSV comprimidos al endpoint de ingesta.
 *
 * Diseño:
 * - Corre en `Dispatchers.IO` (pool general), **totalmente aislado** del
 *   `Dispatchers.IO.limitedParallelism(1)` de [RecordingEngine] que escribe
 *   los chunks. Así, timeouts de red o latencia de compresión gzip jamás
 *   afectan al hot path de grabación a 100 Hz.
 * - Cola interna con capacidad 64 y política `DROP_OLDEST`: si se acumulan
 *   más tareas de las procesables, las más antiguas se descartan (serán
 *   reintentadas por [enqueueAllPending] al parar la sesión).
 * - Reintentos con backoff exponencial (10 s, 30 s, 90 s).
 * - Marca cada chunk subido en [UploadTracker] para idempotencia.
 *
 * Ciclo de vida: [start] al iniciar grabación, [stop] al detenerla.
 * No tiene estado propio más allá de la coroutine; el estado duradero
 * vive en [UploadTracker] (fichero por sesión en disco).
 */
@Singleton
class ChunkUploader @Inject constructor(
    private val sessionFileManager: SessionFileManager,
    private val uploadTracker: UploadTracker,
    private val sessionConfigStore: SessionConfigStore,
    private val diagnosticsAggregator: ImuDiagnosticsAggregator,
    private val diagnosticsLogger: DiagnosticsLogger,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processorJob: Job? = null

    private val uploadChannel = Channel<UploadTask>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Arranca el procesador de cola. Idempotente (cancela el anterior). */
    fun start() {
        processorJob?.cancel()
        processorJob = uploadScope.launch { processQueue() }
        Log.i(TAG, "Upload processor arrancado")
    }

    /** Detiene el procesador. Los chunks pendientes quedan en disco para retry. */
    fun stop() {
        processorJob?.cancel()
        processorJob = null
        Log.i(TAG, "Upload processor detenido")
    }

    /**
     * Encola un chunk recién rotado (ya cerrado en disco) para subir.
     * Operación no bloqueante (~µs) — segura desde el hilo de I/O de grabación.
     */
    fun enqueueChunk(sessionId: String, chunkIndex: Int) {
        val task = UploadTask(sessionId, chunkIndex, attempt = 0)
        uploadChannel.trySend(task)
        Log.d(TAG, "Encolado chunk $chunkIndex de sesión $sessionId")
    }

    /**
     * Encola todos los chunks de [sessionId] que aún no se hayan subido.
     * Útil al detener la sesión (subir el último chunk + reintentar fallidos)
     * o al auto-resumir tras un kill.
     */
    fun enqueueAllPending(sessionId: String) {
        uploadScope.launch {
            val pending = uploadTracker.pendingChunks(sessionId)
            for (chunk in pending) {
                val index = extractChunkIndex(chunk.name)
                if (index >= 0) enqueueChunk(sessionId, index)
            }
            if (pending.isNotEmpty()) {
                Log.i(TAG, "Encolados ${pending.size} chunks pendientes de $sessionId")
            }
        }
    }

    private suspend fun processQueue() {
        for (task in uploadChannel) {
            // Performance trace around one upload attempt (isolated upload pool,
            // never the recording hot path).
            val success = diagnosticsLogger.trace(DiagnosticsLogger.TRACE_CHUNK_UPLOAD) {
                uploadChunk(task)
            }
            if (!success && task.attempt < MAX_RETRIES) {
                val backoffMs = RETRY_BASE_MS * 3.0.pow(task.attempt).toLong()
                Log.d(TAG, "Reintento #${task.attempt + 1} en ${backoffMs / 1000}s")
                delay(backoffMs)
                uploadChannel.trySend(task.copy(attempt = task.attempt + 1))
            }
        }
    }

    private fun uploadChunk(task: UploadTask): Boolean {
        if (uploadTracker.isUploaded(task.sessionId, task.chunkIndex)) return true

        val chunkFile = sessionFileManager.chunkFile(task.sessionId, task.chunkIndex)
        if (!chunkFile.exists()) {
            Log.w(TAG, "Chunk ${task.chunkIndex} no existe en disco, se omite")
            return true
        }

        val forkliftId = sessionConfigStore.getForkliftId()
        if (forkliftId.isEmpty()) {
            Log.w(TAG, "forkliftId vacío — no se puede subir chunk")
            return false
        }

        var gzFile: File? = null
        try {
            gzFile = File(
                chunkFile.parentFile,
                String.format(Locale.US, "chunk_%03d.csv.gz", task.chunkIndex),
            )
            compressGzip(chunkFile, gzFile)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // TODO: Rename ingestion API field from toro_id to forkliftId when backend is updated.
                .addFormDataPart("toro_id", forkliftId)
                .addFormDataPart(
                    "file",
                    gzFile.name,
                    gzFile.asRequestBody(MEDIA_TYPE_GZIP),
                )
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                return if (response.isSuccessful) {
                    uploadTracker.markUploaded(task.sessionId, task.chunkIndex)
                    Log.i(
                        TAG,
                        "Chunk ${task.chunkIndex} subido OK " +
                            "(${gzFile.length() / 1024} KB gz, sesión ${task.sessionId})",
                    )
                    // Diagnostics: aggregated upload state (non-blocking, own thread).
                    diagnosticsAggregator.onUploadSuccess(task.sessionId, task.chunkIndex)
                    true
                } else {
                    Log.w(
                        TAG,
                        "Upload chunk ${task.chunkIndex} falló: HTTP ${response.code} " +
                            "${response.message}",
                    )
                    diagnosticsAggregator.onUploadFailure(
                        task.sessionId,
                        task.chunkIndex,
                        "HTTP ${response.code}",
                    )
                    false
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Upload chunk ${task.chunkIndex} falló por red/IO", e)
            diagnosticsAggregator.onUploadFailure(task.sessionId, task.chunkIndex, e.message)
            return false
        } finally {
            gzFile?.delete()
        }
    }

    private fun compressGzip(input: File, output: File) {
        input.inputStream().buffered(BUFFER_SIZE).use { src ->
            GZIPOutputStream(output.outputStream().buffered(BUFFER_SIZE)).use { gzOut ->
                src.copyTo(gzOut, bufferSize = BUFFER_SIZE)
            }
        }
    }

    companion object {
        private const val TAG = "ChunkUploader"
        private const val UPLOAD_URL =
            "https://torotrack-ingestion-worker.jgallegoweb.workers.dev/upload"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 10_000L
        private const val BUFFER_SIZE = 65_536
        private val MEDIA_TYPE_GZIP = "application/gzip".toMediaType()

        fun extractChunkIndex(fileName: String): Int {
            val match = Regex("""chunk_(\d+)""").find(fileName) ?: return -1
            return match.groupValues[1].toIntOrNull() ?: -1
        }
    }
}

private data class UploadTask(
    val sessionId: String,
    val chunkIndex: Int,
    val attempt: Int,
)
