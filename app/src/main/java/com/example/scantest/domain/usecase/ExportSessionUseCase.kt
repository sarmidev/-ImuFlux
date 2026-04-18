package com.example.scantest.domain.usecase

import com.example.scantest.domain.repository.ExportRepository
import javax.inject.Inject

class ExportSessionUseCase @Inject constructor(
    private val exportRepository: ExportRepository,
) {
    enum class Format { SINGLE_CSV, ZIP }

    suspend operator fun invoke(
        sessionId: String,
        destinationUriString: String,
        format: Format,
    ): Long = when (format) {
        Format.SINGLE_CSV -> exportRepository.exportSessionAsSingleCsv(sessionId, destinationUriString)
        Format.ZIP -> exportRepository.exportSessionAsZip(sessionId, destinationUriString)
    }
}
