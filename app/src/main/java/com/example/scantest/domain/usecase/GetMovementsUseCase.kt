package com.example.scantest.domain.usecase

import com.example.scantest.domain.CustomMovement
import com.example.scantest.domain.repository.MovementRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMovementsUseCase @Inject constructor(
    private val movementRepository: MovementRepository
) {
    operator fun invoke(): Flow<List<CustomMovement>> {
        return movementRepository.getMovements()
    }
}