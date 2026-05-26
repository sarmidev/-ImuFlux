package com.sarmidev.imuflux.data.upload

import android.util.Log
import com.sarmidev.imuflux.data.storage.SessionFileManager
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registro ligero de qué chunks de cada sesión se han subido con éxito.
 *
 * Persiste un archivo `uploaded_chunks.txt` por sesión con una línea por
 * chunk confirmado (e.g. `chunk_000`). Operaciones atómicas de append
 * y lectura; no necesita locks porque se usa siempre desde el scope
 * del [ChunkUploader] (un solo consumidor de cola).
 */
@Singleton
class UploadTracker @Inject constructor(
    private val sessionFileManager: SessionFileManager,
) {

    fun markUploaded(sessionId: String, chunkIndex: Int) {
        val file = markerFile(sessionId)
        runCatching {
            file.appendText(chunkName(chunkIndex) + "\n")
        }.onFailure { Log.w(TAG, "No pude marcar chunk $chunkIndex como subido", it) }
    }

    fun isUploaded(sessionId: String, chunkIndex: Int): Boolean {
        return chunkName(chunkIndex) in uploadedSet(sessionId)
    }

    /**
     * Devuelve los archivos de chunk que aún no se han subido, ordenados
     * por nombre (chunk_000, chunk_001, ...).
     */
    fun pendingChunks(sessionId: String): List<File> {
        val uploaded = uploadedSet(sessionId)
        return sessionFileManager.listChunks(sessionId)
            .filter { it.nameWithoutExtension !in uploaded }
    }

    private fun markerFile(sessionId: String): File =
        File(sessionFileManager.sessionDir(sessionId), MARKER_FILE)

    private fun uploadedSet(sessionId: String): Set<String> {
        val file = markerFile(sessionId)
        if (!file.exists()) return emptySet()
        return runCatching {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }

    companion object {
        private const val TAG = "UploadTracker"
        private const val MARKER_FILE = "uploaded_chunks.txt"

        fun chunkName(index: Int): String =
            String.format(Locale.US, "chunk_%03d", index)
    }
}
