package com.example.scantest.data.repository

import com.example.scantest.domain.model.CustomMovement
import com.example.scantest.domain.repository.MovementRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MovementRepositoryImpl @Inject constructor() : MovementRepository {

    private val _movements = MutableStateFlow<List<CustomMovement>>(emptyList())

    override fun getMovements(): Flow<List<CustomMovement>> = _movements.asStateFlow()

    override suspend fun saveMovement(movement: CustomMovement) {
        _movements.update { currentList ->
            val mutableList = currentList.toMutableList()
            val existingIndex = mutableList.indexOfFirst { it.id == movement.id }

            if (existingIndex != -1) {
                mutableList[existingIndex] = movement
            } else {
                val movementWithId = if (movement.id.isBlank()) {
                    movement.copy(id = UUID.randomUUID().toString())
                } else {
                    movement
                }
                mutableList.add(movementWithId)
            }
            mutableList
        }
    }

    override suspend fun toggleMovementActive(movementId: String, isActive: Boolean) {
        _movements.update { currentList ->
            currentList.map {
                if (it.id == movementId) it.copy(isActive = isActive) else it
            }
        }
    }

    override suspend fun deleteMovement(movementId: String) {
        _movements.update { currentList ->
            currentList.filter { it.id != movementId }
        }
    }
}