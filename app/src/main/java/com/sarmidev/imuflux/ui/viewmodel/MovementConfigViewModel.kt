package com.sarmidev.imuflux.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sarmidev.imuflux.domain.model.CustomMovement
import com.sarmidev.imuflux.domain.usecase.GetMovementsUseCase
import com.sarmidev.imuflux.domain.usecase.SaveMovementUseCase
import com.sarmidev.imuflux.domain.usecase.ToggleMovementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovementConfigViewModel @Inject constructor(
    private val getMovementsUseCase: GetMovementsUseCase,
    private val saveMovementUseCase: SaveMovementUseCase,
    private val toggleMovementUseCase: ToggleMovementUseCase
) : ViewModel() {

    val movements: StateFlow<List<CustomMovement>> = getMovementsUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleMovementActive(movementId: String, isActive: Boolean) {
        viewModelScope.launch {
            toggleMovementUseCase(movementId, isActive)
        }
    }

    fun saveMovement(movement: CustomMovement) {
        viewModelScope.launch {
            saveMovementUseCase(movement)
        }
    }
}