package com.sarmidev.imuflux.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sarmidev.imuflux.backoffice.BackofficeApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ImuFlux Diagnostics",
        state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
    ) {
        BackofficeApp()
    }
}
