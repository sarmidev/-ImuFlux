package com.example.scantest.ui.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.scantest.domain.CustomMovement
import java.util.UUID


class MovementConfigViewModel {
    // Lista mutable de movimientos para que Compose pueda observar los cambios
    private val _movements: MutableState<List<CustomMovement>> = mutableStateOf(
        mutableListOf()
    )
    val movements: State<List<CustomMovement>> = _movements

    fun toggleMovementActive(movementId: String, isActive: Boolean) {
        // Lógica para actualizar la lista y guardar en la base de datos...
        // Aquí simulamos la actualización del estado:
        val currentList = movements.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == movementId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(isActive = isActive)
            (movements as MutableState<List<CustomMovement>>).value = currentList
        }
    }

    fun saveMovement(movement: CustomMovement) {

        val currentList = _movements.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == movement.id }

        if (existingIndex != -1) {
            // Case 1: Update movement
            currentList[existingIndex] = movement
            println("Movimiento actualizado: ${movement.name}")
        } else {
            // Case 2: Add movement
            val movementWithId = movement.copy(id = UUID.randomUUID().toString())
            currentList.add(movementWithId)
            println("Movimiento nuevo guardado: ${movementWithId.name}")
        }

        // Actualizar el estado para notificar a la UI
        _movements.value = currentList
    }

}