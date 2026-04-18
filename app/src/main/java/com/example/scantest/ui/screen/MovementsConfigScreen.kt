package com.example.scantest.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.scantest.ui.AddEditMovementDialog
import com.example.scantest.domain.model.CustomMovement
import com.example.scantest.ui.viewmodel.MovementConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementManagerScreen(
    viewModel: MovementConfigViewModel
) {

    var showAddEditDialog by remember { mutableStateOf(false) }
    var movementToEdit by remember { mutableStateOf<CustomMovement?>(null) } // null -> Add movement  //  != null -> Edit movement
    // Use collectAsState for StateFlow
    val movements by viewModel.movements.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Configuración de Movimientos") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    movementToEdit = null
                    showAddEditDialog = true
                },
                content = { Icon(Icons.Filled.Add, contentDescription = "Añadir Movimiento") }
            )
        }
    ) { paddingValues ->

        if (movements.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay movimientos definidos. ¡Usa el '+' para añadir uno!")
            }
        } else {
            LazyColumn(contentPadding = paddingValues) {
                items(movements.size, key = { movements[it].id }) { index ->
                    MovementListItem(
                        movement = movements[index],
                        onToggleActive = viewModel::toggleMovementActive,
                        onEditClicked = {
                            movementToEdit = movements[index]
                            showAddEditDialog = true
                        }
                    )
                    Divider()
                }
            }
        }
    }

    if (showAddEditDialog) {
        AddEditMovementDialog(
            initialMovement = movementToEdit,
            onDismiss = { showAddEditDialog = false },
            onSave = {
                viewModel.saveMovement(it)
                showAddEditDialog = false
            }
        )
    }
}

@Composable
fun MovementListItem(
    movement: CustomMovement,
    onToggleActive: (String, Boolean) -> Unit,
    onEditClicked: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditClicked)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Nombre y detalles del Movimiento
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = movement.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = " (${movement.criteria.size} Criterios)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = movement.isActive,
            onCheckedChange = { isChecked ->
                onToggleActive(movement.id, isChecked)
            },
            modifier = Modifier.wrapContentSize()
        )
    }
}