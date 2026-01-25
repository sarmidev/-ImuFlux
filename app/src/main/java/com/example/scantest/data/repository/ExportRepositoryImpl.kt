package com.example.scantest.data.repository

import android.content.Context
import com.example.scantest.domain.SensorData
import com.example.scantest.domain.SensorType
import com.example.scantest.domain.repository.ExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import androidx.core.net.toUri

class ExportRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ExportRepository {

    override suspend fun exportSensorData(data: List<SensorData>, uriString: String) {
        withContext(Dispatchers.IO) {
            val uri = uriString.toUri()
            
            // Abrir stream y escribir
            context.contentResolver.openOutputStream(uri)?.bufferedWriter().use { out ->
                if (out == null) throw IOException("No se pudo abrir el stream de salida para $uriString")

                // 1. Obtener lista ordenada de nombres de sensores para la cabecera
                val sensorNames = SensorType.entries.map { it.name }

                // Escribir cabecera
                out.write("timestamp,${sensorNames.joinToString(",")}")
                out.newLine()

                // 2. Agrupar datos por timestamp (Pivotar tabla)
                val dataByTimestamp = data.groupBy { it.timestamp }

                // 3. Iterar ordenadamente por tiempo
                for (timestamp in dataByTimestamp.keys.sorted()) {
                    val sensorValuesForTimestamp = dataByTimestamp[timestamp]
                    // Mapa rápido para buscar valor por nombre de sensor
                    val valueMap = sensorValuesForTimestamp?.associate { it.name to it.value.toString() }

                    // Mapear cada columna de la cabecera a su valor (o vacío si no existe)
                    val rowValues = sensorNames.map { name ->
                        valueMap?.getOrDefault(name, "")
                    }

                    // Escribir fila
                    out.write("$timestamp,${rowValues.joinToString(",")}")
                    out.newLine()
                }
            }
        }
    }
}