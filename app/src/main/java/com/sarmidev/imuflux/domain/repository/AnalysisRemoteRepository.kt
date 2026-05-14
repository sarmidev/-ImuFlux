package com.sarmidev.imuflux.domain.repository

import com.sarmidev.imuflux.data.analysis.InspectionResult
import com.sarmidev.imuflux.domain.model.DeviceRankingEntry
import com.sarmidev.imuflux.domain.model.SessionMetadata

interface AnalysisRemoteRepository {
    suspend fun uploadAnalysis(
        metadata: SessionMetadata,
        result: InspectionResult,
    )

    /**
     * Fetches the global device ranking from Firestore `deviceModels`, ordered by
     * compatibility score descending. Returns at most [limit] entries.
     */
    suspend fun fetchDeviceRanking(limit: Long = 100): List<DeviceRankingEntry>
}
