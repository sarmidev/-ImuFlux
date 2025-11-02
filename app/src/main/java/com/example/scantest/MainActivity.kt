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
import androidx.compose.ui.platform.LocalContext
import com.example.scantest.domain.SensorMonitor
import com.example.scantest.ui.screen.MovementManagerScreen
import com.example.scantest.ui.screen.ScanditScannerScreen
import com.example.scantest.ui.screen.SimpleMovementMonitorScreen
import com.example.scantest.ui.viewmodel.MovementConfigViewModel
import com.example.scantest.ui.viewmodel.SensorsViewModel
import com.scandit.datacapture.core.common.geometry.Quadrilateral
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val moduleId = mutableIntStateOf(0)
        setContent {
            val viewmodel = remember { MovementConfigViewModel() }
            val context = LocalContext.current
            val detectionViewmodel = remember {
                SensorsViewModel( application, SensorMonitor(context), viewmodel)
            }
            Column {
                Row {
                    Button(onClick = {
                        moduleId.intValue = 0
                    }) {
                        Text("Camara")
                    }
                    Button(onClick = {
                        moduleId.intValue = 1
                    }) {
                        Text("Movimientos")
                    }
                    Button(onClick = {
                        moduleId.intValue = 2
                        detectionViewmodel.collectAndEvaluate()
                    }) {
                        Text("Sensor")
                    }
                }
                when (moduleId.intValue) {
                    0 -> {
                        ScanditScannerScreen()
                    }
                    1 -> {
                        MovementManagerScreen(viewmodel)
                    }
                    2 -> {
                        SimpleMovementMonitorScreen(detectionViewmodel)
                    }
                }

            }
        }
    }
}

fun estimateDistance(corners: Quadrilateral, markerSizeMeters: Float): Float {
    val focalPx = 1220f
    val widthPx = hypot(
        (corners.topRight.x - corners.topLeft.x).toDouble(),
        (corners.topRight.y - corners.topLeft.y).toDouble()
    )
    return (focalPx * markerSizeMeters / widthPx).toFloat()
}
