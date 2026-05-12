package com.example.scantest.domain.repository

import com.example.scantest.data.analysis.InspectionResult
import com.example.scantest.domain.model.SessionMetadata

interface AnalysisRemoteRepository {
    suspend fun uploadAnalysis(
        metadata: SessionMetadata,
        result: InspectionResult,
    )
}
