package com.example.scantest.domain.usecase

import com.example.scantest.domain.model.SessionSummary
import com.example.scantest.domain.repository.ExportRepository
import javax.inject.Inject

class ListSessionsUseCase @Inject constructor(
    private val exportRepository: ExportRepository,
) {
    suspend operator fun invoke(): List<SessionSummary> = exportRepository.listSessions()
}
