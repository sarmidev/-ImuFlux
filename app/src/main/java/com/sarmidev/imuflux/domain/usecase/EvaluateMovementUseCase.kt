package com.sarmidev.imuflux.domain.usecase

import com.sarmidev.imuflux.domain.model.Condition
import com.sarmidev.imuflux.domain.model.CustomMovement
import com.sarmidev.imuflux.domain.model.SensorType
import javax.inject.Inject

/**
 * Evalúa la lista de movimientos activos contra un snapshot de sensores
 * y devuelve el primero que cumple TODOS sus criterios (AND), o `null`.
 *
 * Cero allocations; diseñado para ejecutarse sobre el flujo throttled de UI
 * (10 Hz) — no se usa en el hot path de grabación a disco.
 */
class EvaluateMovementUseCase @Inject constructor() {

    operator fun invoke(
        sensorValues: Map<SensorType, Float>,
        movements: List<CustomMovement>,
    ): CustomMovement? {
        for (movement in movements) {
            var allCriteriaMet = true
            for (criterion in movement.criteria) {
                val currentValue = sensorValues[criterion.sensor]
                if (currentValue == null) {
                    allCriteriaMet = false
                    break
                }
                val conditionIsMet = when (criterion.condition) {
                    Condition.GREATER_THAN -> currentValue > criterion.minValue
                    Condition.LESS_THAN -> currentValue < criterion.minValue
                    Condition.BETWEEN -> {
                        val maxValue = criterion.maxValue
                        maxValue != null &&
                            currentValue >= criterion.minValue &&
                            currentValue <= maxValue
                    }
                }
                if (!conditionIsMet) {
                    allCriteriaMet = false
                    break
                }
            }
            if (allCriteriaMet) return movement
        }
        return null
    }
}
