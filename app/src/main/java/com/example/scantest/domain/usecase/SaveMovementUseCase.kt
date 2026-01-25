package com.example.scantest.domain.usecase

import com.example.scantest.domain.CustomMovement
import com.example.scantest.domain.repository.MovementRepository
import javax.inject.Inject

class SaveMovementUseCase @Inject constructor(
    private val movementRepository: MovementRepository
) {
    suspend operator fun invoke(movement: CustomMovement) {
        movementRepository.saveMovement(movement)
    }
}