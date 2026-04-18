package com.example.scantest.domain.usecase

import com.example.scantest.domain.repository.ExportRepository
import javax.inject.Inject

class DeleteSessionUseCase @Inject constructor(
    private val exportRepository: ExportRepository,
) {
    suspend operator fun invoke(sessionId: String): Boolean =
        exportRepository.deleteSession(sessionId)
}
