package com.example.scantest.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.scantest.domain.model.Condition
import com.example.scantest.domain.model.Criterion
import com.example.scantest.domain.model.CustomMovement
import com.example.scantest.domain.model.OutputAction
import com.example.scantest.domain.model.SensorType
import java.util.UUID

// Se define el estado inicial del formulario
data class MovementState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val criteria: List<Criterion> = emptyList(),
    val isActive: Boolean = true
)

// --- COMPONENTES PRINCIPALES ---

@Composable
fun AddEditMovementDialog(
    initialMovement: CustomMovement? = null, // Null si es nuevo
    onDismiss: () -> Unit,
    onSave: (CustomMovement) -> Unit
) {
    // 1. Manejo del Estado del Formulario
    var state by remember {
        mutableStateOf(
            initialMovement?.let {
                MovementState(it.id, it.name, it.criteria, it.isActive)
            } ?: MovementState()
        )
    }

    // Estado para mostrar/ocultar el diálogo de añadir criterio
    var showCriterionDialog by remember { mutableStateOf(false) }

    // Función de validación básica
    val isSaveEnabled = state.name.isNotBlank() && state.criteria.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialMovement == null) "Añadir Nuevo Movimiento" else "Editar Movimiento") },
        text = {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                // Campo 1: Nombre del Movimiento
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { state = state.copy(name = it) },
                    label = { Text("Nombre del Movimiento") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Campo 3: Lista de Criterios (Header y Lista)
                CriterionListHeader(
                    criteriaCount = state.criteria.size,
                    onAddClicked = { showCriterionDialog = true }
                )

                // Lista de criterios definidos
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp) // Limitar la altura para evitar desbordamiento
                ) {
                    items(state.criteria) { criterion ->
                        CriterionListItem(
                            criterion = criterion,
                            onDelete = {
                                state = state.copy(criteria = state.criteria.minus(criterion))
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CustomMovement(
                            id = state.id,
                            name = state.name,
                            criteria = state.criteria,
                            isActive = state.isActive
                        )
                    )
                },
                enabled = isSaveEnabled
            ) {
                Text(if (initialMovement == null) "Guardar" else "Actualizar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )

    // Diálogo para añadir un criterio (aparece al pulsar el botón '+')
    if (showCriterionDialog) {
        AddCriterionDialog(
            onDismiss = { showCriterionDialog = false },
            onSave = { newCriterion ->
                state = state.copy(criteria = state.criteria + newCriterion)
                showCriterionDialog = false
            }
        )
    }
}

// --- COMPONENTES AUXILIARES DEL FORMULARIO ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionDropdown(
    selectedAction: OutputAction,
    onActionSelected: (OutputAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            readOnly = true,
            value = selectedAction.name.replace('_', ' '),
            onValueChange = { },
            label = { Text("Acción a Ejecutar") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            OutputAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.name.replace('_', ' ')) },
                    onClick = {
                        onActionSelected(action)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
fun CriterionListHeader(criteriaCount: Int, onAddClicked: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Criterios de Sensor ($criteriaCount)",
            style = MaterialTheme.typography.titleMedium
        )
        IconButton(onClick = onAddClicked) {
            Icon(Icons.Default.Add, contentDescription = "Añadir Criterio")
        }
    }
}

@Composable
fun CriterionListItem(criterion: Criterion, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val displayValue = if (criterion.condition == Condition.BETWEEN) {
            "Entre ${criterion.minValue} y ${criterion.maxValue}"
        } else {
            "${criterion.condition.name.replace('_', ' ')} ${criterion.minValue}"
        }

        Text(
            text = "${criterion.sensor.name.replace('_', ' ')}: $displayValue",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
        }
    }
}

// --- DIÁLOGO PARA AÑADIR UN CRITERIO INDIVIDUAL ---

@Composable
fun AddCriterionDialog(
    onDismiss: () -> Unit,
    onSave: (Criterion) -> Unit
) {
    // Estado interno del nuevo criterio
    var sensor by remember { mutableStateOf(SensorType.ACCELERATION_MAGNITUDE) }
    var condition by remember { mutableStateOf(Condition.GREATER_THAN) }
    var minValueText by remember { mutableStateOf("0.0") }
    var maxValueText by remember { mutableStateOf("0.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Definir Criterio") },
        text = {
            Column {
                // 1. Selector de SensorType
                SensorDropdown(
                    selectedSensor = sensor,
                    onSensorSelected = { sensor = it }
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 2. Selector de Condition
                ConditionDropdown(
                    selectedCondition = condition,
                    onConditionSelected = { condition = it }
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 3. Campos de Valor (Mínimo / Máximo)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Valor Mínimo (o Valor Único)
                    OutlinedTextField(
                        value = minValueText,
                        onValueChange = { minValueText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(if (condition == Condition.BETWEEN) "Valor Mínimo" else "Valor") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    // Valor Máximo (solo si es BETWEEN)
                    if (condition == Condition.BETWEEN) {
                        OutlinedTextField(
                            value = maxValueText,
                            onValueChange = { maxValueText = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Valor Máximo") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val minValue = minValueText.toFloatOrNull() ?: 0f
                    val maxValue = if (condition == Condition.BETWEEN) maxValueText.toFloatOrNull() else null

                    if (minValueText.isNotBlank() && minValue != null && (condition != Condition.BETWEEN || maxValue != null)) {
                        onSave(Criterion(sensor, condition, minValue, maxValue))
                    }
                }
            ) {
                Text("Añadir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDropdown(
    selectedSensor: SensorType,
    onSensorSelected: (SensorType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            readOnly = true,
            value = selectedSensor.name.replace('_', ' '),
            onValueChange = { },
            label = { Text("Tipo de Sensor") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SensorType.entries.forEach { sensor ->
                DropdownMenuItem(
                    text = { Text(sensor.name.replace('_', ' ')) },
                    onClick = {
                        onSensorSelected(sensor)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionDropdown(
    selectedCondition: Condition,
    onConditionSelected: (Condition) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            readOnly = true,
            value = selectedCondition.name.replace('_', ' '),
            onValueChange = { },
            label = { Text("Condición") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Condition.entries.forEach { condition ->
                DropdownMenuItem(
                    text = { Text(condition.name.replace('_', ' ')) },
                    onClick = {
                        onConditionSelected(condition)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}