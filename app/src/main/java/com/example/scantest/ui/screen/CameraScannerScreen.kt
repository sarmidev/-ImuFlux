package com.example.scantest.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.scantest.ui.viewmodel.ScanViewModel
import com.scandit.datacapture.core.ui.DataCaptureView
import androidx.lifecycle.viewmodel.compose.viewModel
// 1. Importar el Overlay correcto
import com.scandit.datacapture.barcode.batch.ui.overlay.BarcodeBatchBasicOverlay

@Composable
fun ScanditScannerScreen(
    viewModel: ScanViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            viewModel.onPermissionResult(isGranted)
        }
    )

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startCamera()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopCamera()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                factory = { ctx ->
                    // 2. Usar DataCaptureView
                    val captureView = DataCaptureView.newInstance(ctx, viewModel.dataCaptureContext)

                    // 3. Usar BarcodeBatchBasicOverlay
                    val overlay = BarcodeBatchBasicOverlay.newInstance(
                        viewModel.barcodeBatch, // Pasar la instancia de BarcodeBatch
                        captureView
                    )
                    captureView.addOverlay(overlay)

                    captureView
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0x80000000))
                    .padding(16.dp)
            ) {
                Text(text = "Última lectura:", style = MaterialTheme.typography.bodySmall, color = Color.White)
                Text(text = "${uiState.distance}", style = MaterialTheme.typography.bodyLarge, color = Color.White)

                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = { viewModel.onStartStopClick() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(if (uiState.isRecording) "Parar Grabación" else "Empezar a Grabar")
            }

            if (uiState.showSaveDialog) {
                SaveScanDialog(
                    onConfirm = { filename ->
                        viewModel.onSaveScanData(filename)
                    },
                    onDismiss = {
                        viewModel.onDismissSaveDialog()
                    }
                )
            }
        }
    }
}

@Composable
private fun SaveScanDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("scans_${System.currentTimeMillis()}.csv") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar grabación") },
        text = {
            Column {
                Text("Introduce un nombre para el archivo:")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}