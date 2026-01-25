package com.example.scantest.domain.usecase

import  com.example.scantest.domain.Condition
import com.example.scantest.domain.CustomMovement
import com.example.scantest.domain.SensorType
import javax.inject.Inject

class EvaluateMovementUseCase @Inject constructor() {

    operator fun invoke(
        sensorValues: Map<SensorType, Float>,
        movements: List<CustomMovement>
    ): CustomMovement? {

        for (movement in movements) {
            var allCriteriaMet = true

            // Comprueba cada Criterio dentro de la regla actual
            for (criterion in movement.criteria) {

                // 1. Obtener el valor actual del sensor que la regla está evaluando
                val currentValue = sensorValues[criterion.sensor]

                // Si el valor del sensor no está disponible, esta regla no se puede cumplir.
                if (currentValue == null) {
                    allCriteriaMet = false
                    break // Pasa a la siguiente regla de movimiento
                }

                // 2. Aplicar la condición lógica definida por el usuario
                val conditionIsMet = when (criterion.condition) {
                    Condition.GREATER_THAN -> currentValue > criterion.minValue
                    Condition.LESS_THAN -> currentValue < criterion.minValue
                    Condition.BETWEEN -> {
                        val maxValue = criterion.maxValue
                        if (maxValue != null) {
                            currentValue >= criterion.minValue && currentValue <= maxValue
                        } else {
                            false
                        }
                    }
                }

                // Si un solo criterio no se cumple, la regla completa falla
                if (!conditionIsMet) {
                    allCriteriaMet = false
                    break // Pasa a la siguiente regla de movimiento
                }
            }

            // 3. Si se llegó hasta aquí y la bandera es true, ¡el movimiento fue detectado!
            if (allCriteriaMet) {
                return movement
            }
        }

        return null
    }
}