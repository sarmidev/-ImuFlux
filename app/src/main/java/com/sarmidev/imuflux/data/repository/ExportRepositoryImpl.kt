package com.sarmidev.imuflux.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.sarmidev.imuflux.data.storage.SessionFileManager
import com.sarmidev.imuflux.domain.model.SessionSummary
import com.sarmidev.imuflux.domain.repository.ExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Export streaming a partir de los chunks ya escritos en disco. Nunca carga
 * una sesión entera en memoria — todo es I/O buffered.
 */
@Singleton
class ExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionFileManager: SessionFileManager,
) : ExportRepository {

    override suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        sessionFileManager.listSessions()
    }

    override suspend fun exportSessionAsSingleCsv(
        sessionId: String,
        destinationUriString: String,
    ): Long = withContext(Dispatchers.IO) {
        val chunks = sessionFileManager.listChunks(sessionId)
        if (chunks.isEmpty()) throw IOException("La sesión $sessionId no tiene chunks")
        val destUri = destinationUriString.toUri()
        val out: OutputStream = context.contentResolver.openOutputStream(destUri)
            ?: throw IOException("No se pudo abrir el stream de salida")
        out.buffered(BUFFER_BYTES).use { sink ->
            var isFirstChunk = true
            for (chunk in chunks) {
                chunk.inputStream().buffered(BUFFER_BYTES).use { src ->
                    val reader = BufferedReader(InputStreamReader(src, Charsets.UTF_8), BUFFER_BYTES)
                    var firstLine = true
                    reader.forEachLine { line ->
                        // Saltar cabeceras excepto en el primer chunk.
                        if (firstLine) {
                            firstLine = false
                            if (isFirstChunk) {
                                sink.write(line.toByteArray(Charsets.UTF_8))
                                sink.write(NEWLINE)
                            }
                        } else {
                            sink.write(line.toByteArray(Charsets.UTF_8))
                            sink.write(NEWLINE)
                        }
                    }
                }
                isFirstChunk = false
            }
            sink.flush()
        }
        // La cantidad exacta de bytes escritos no está disponible desde OutputStream;
        // devolvemos el tamaño aproximado como la suma de los chunks (sobreestima
        // por las cabeceras extra saltadas, pero es suficiente para UI).
        chunks.sumOf { it.length() }
    }

    override suspend fun exportSessionAsZip(
        sessionId: String,
        destinationUriString: String,
    ): Long = withContext(Dispatchers.IO) {
        val dir = sessionFileManager.sessionDir(sessionId)
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
        if (files.isEmpty()) throw IOException("La sesión $sessionId está vacía")
        val destUri = destinationUriString.toUri()
        val out: OutputStream = context.contentResolver.openOutputStream(destUri)
            ?: throw IOException("No se pudo abrir el stream de salida")
        val counting = CountingOutputStream(out.buffered(BUFFER_BYTES))
        ZipOutputStream(counting).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry("$sessionId/${file.name}"))
                file.inputStream().buffered(BUFFER_BYTES).use { it.copyTo(zip, BUFFER_BYTES) }
                zip.closeEntry()
            }
        }
        counting.count
    }

    override suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        sessionFileManager.deleteSession(sessionId)
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count += 1L
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    companion object {
        private const val BUFFER_BYTES: Int = 64 * 1024
        private val NEWLINE: ByteArray = "\n".toByteArray(Charsets.UTF_8)
    }
}
