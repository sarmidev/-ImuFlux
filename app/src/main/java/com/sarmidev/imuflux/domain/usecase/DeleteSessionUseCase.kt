package com.sarmidev.imuflux.domain.usecase

import com.sarmidev.imuflux.domain.repository.ExportRepository
import javax.inject.Inject

class DeleteSessionUseCase @Inject constructor(
    private val exportRepository: ExportRepository,
) {
    suspend operator fun invoke(sessionId: String): Boolean =
        exportRepository.deleteSession(sessionId)
}
