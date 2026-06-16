package com.sarmidev.imuflux.domain.usecase

import com.sarmidev.imuflux.domain.model.CustomMovement
import com.sarmidev.imuflux.domain.repository.MovementRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMovementsUseCase @Inject constructor(
    private val movementRepository: MovementRepository
) {
    operator fun invoke(): Flow<List<CustomMovement>> {
        return movementRepository.getMovements()
    }
}