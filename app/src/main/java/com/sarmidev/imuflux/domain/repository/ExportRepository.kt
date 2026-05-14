package com.sarmidev.imuflux.domain.repository

import com.sarmidev.imuflux.domain.model.SessionSummary

interface ExportRepository {

    /** Lista resumen de sesiones existentes en almacenamiento interno. */
    suspend fun listSessions(): List<SessionSummary>

    /**
     * Copia todos los chunks de la sesión al URI destino (fichero CSV único,
     * concatenando chunks y omitiendo la cabecera a partir del segundo).
     *
     * @return número total de bytes escritos al destino.
     */
    suspend fun exportSessionAsSingleCsv(sessionId: String, destinationUriString: String): Long

    /**
     * Empaqueta toda la sesión (chunks + metadata) en un ZIP escrito al URI
     * destino. Es la opción recomendada para sesiones largas (preserva los
     * límites de chunk y el metadata).
     *
     * @return número total de bytes del ZIP escrito.
     */
    suspend fun exportSessionAsZip(sessionId: String, destinationUriString: String): Long

    /** Borra la sesión completa (directorio y chunks). */
    suspend fun deleteSession(sessionId: String): Boolean
}
