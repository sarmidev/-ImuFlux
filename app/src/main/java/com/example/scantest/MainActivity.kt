package com.example.scantest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.scantest.ui.screen.MovementManagerScreen
import com.example.scantest.ui.screen.SimpleMovementMonitorScreen
import com.example.scantest.ui.viewmodel.MovementConfigViewModel
import com.example.scantest.ui.viewmodel.SensorsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val moduleId = mutableIntStateOf(1) // Default to Movements
        setContent {
            // Hilt injections
            val movementConfigViewModel: MovementConfigViewModel = hiltViewModel()
            val sensorsViewModel: SensorsViewModel = hiltViewModel()

            Column {
                Row {
                    /*
                    Button(onClick = {
                        moduleId.intValue = 0
                    }) {
                        Text("Camara")
                    }
                    */
                    Button(onClick = {
                        moduleId.intValue = 1
                    }) {
                        Text("Movimientos")
                    }
                    Button(onClick = {
                        moduleId.intValue = 2
                        sensorsViewModel.collectAndEvaluate()
                    }) {
                        Text("Sensor")
                    }
                }
                when (moduleId.intValue) {
                    /*
                    0 -> {
                        ScanditScannerScreen()
                    }
                    */
                    1 -> {
                        MovementManagerScreen(movementConfigViewModel)
                    }
                    2 -> {
                        SimpleMovementMonitorScreen(sensorsViewModel)
                    }
                }

            }
        }
    }
}