package com.example.scantest.domain.repository

import com.example.scantest.data.analysis.InspectionResult
import com.example.scantest.domain.model.DeviceRankingEntry
import com.example.scantest.domain.model.SessionMetadata

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
