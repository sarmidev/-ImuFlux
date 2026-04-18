package com.example.scantest.domain.usecase

import com.example.scantest.domain.repository.MovementRepository

import javax.inject.Inject

class ToggleMovementUseCase @Inject constructor(
    private val movementRepository: MovementRepository
) {
    suspend operator fun invoke(movementId: String, isActive: Boolean) {
        movementRepository.toggleMovementActive(movementId, isActive)
    }
}