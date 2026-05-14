package com.sarmidev.imuflux.domain.usecase

import com.sarmidev.imuflux.domain.model.CustomMovement
import com.sarmidev.imuflux.domain.repository.MovementRepository
import javax.inject.Inject

class SaveMovementUseCase @Inject constructor(
    private val movementRepository: MovementRepository
) {
    suspend operator fun invoke(movement: CustomMovement) {
        movementRepository.saveMovement(movement)
    }
}