package com.example.scantest.domain.model

/**
 * Una "fila" del pipeline de grabación. Estructura plana y eficiente:
 *
 * - [timestampNs]: monotónico en nanosegundos tomado de `SensorEvent.timestamp`
 *   (sensor maestro). Es el único timestamp autoritativo: preciso sub-ms,
 *   alineado al reloj hardware del sensor.
 * - [values]: `FloatArray` de tamaño [com.example.scantest.data.storage.CsvSchema.SLOT_COUNT]
 *   en el orden fijado por ese schema. Los sensores no disponibles contienen `Float.NaN`.
 *
 * No es `data class` para evitar los métodos sintéticos (equals/hashCode) que
 * podrían dar a pensar que se puede comparar por contenido a 100 Hz.
 */
class SensorFrame(
    val timestampNs: Long,
    val values: FloatArray,
)
