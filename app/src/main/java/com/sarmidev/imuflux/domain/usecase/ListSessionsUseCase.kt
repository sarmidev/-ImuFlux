package com.sarmidev.imuflux.domain.usecase

import com.sarmidev.imuflux.domain.model.SessionSummary
import com.sarmidev.imuflux.domain.repository.ExportRepository
import javax.inject.Inject

class ListSessionsUseCase @Inject constructor(
    private val exportRepository: ExportRepository,
) {
    suspend operator fun invoke(): List<SessionSummary> = exportRepository.listSessions()
}
