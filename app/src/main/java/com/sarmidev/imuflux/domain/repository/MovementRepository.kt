package com.sarmidev.imuflux.domain.repository

import com.sarmidev.imuflux.domain.model.CustomMovement
import kotlinx.coroutines.flow.Flow

interface MovementRepository {
    fun getMovements(): Flow<List<CustomMovement>>
    suspend fun saveMovement(movement: CustomMovement)
    suspend fun toggleMovementActive(movementId: String, isActive: Boolean)
    suspend fun deleteMovement(movementId: String)
}
