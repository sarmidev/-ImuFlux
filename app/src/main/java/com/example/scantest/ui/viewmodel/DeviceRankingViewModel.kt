package com.example.scantest.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.scantest.domain.model.DeviceRankingEntry
import com.example.scantest.domain.repository.AnalysisRemoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceRankingUiState(
    val isLoading: Boolean = false,
    val entries: List<DeviceRankingEntry> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DeviceRankingViewModel @Inject constructor(
    private val analysisRemoteRepository: AnalysisRemoteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceRankingUiState())
    val uiState: StateFlow<DeviceRankingUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        load()
    }

    private fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { analysisRemoteRepository.fetchDeviceRanking() }
                .onSuccess { entries ->
                    _uiState.update { it.copy(isLoading = false, entries = entries) }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message ?: "Error al cargar el ranking",
                        )
                    }
                }
        }
    }
}
