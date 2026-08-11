package com.sarmidev.imuflux.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.sarmidev.imuflux.backoffice.BackofficeApp
import com.sarmidev.imuflux.backoffice.config.FirebaseConfigBridge
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    FirebaseConfigBridge.apiKey = GeneratedFirebaseConfig.apiKey
    FirebaseConfigBridge.projectId = GeneratedFirebaseConfig.projectId
    FirebaseConfigBridge.source = GeneratedFirebaseConfig.source

    ComposeViewport(document.body!!) {
        BackofficeApp()
    }
}
