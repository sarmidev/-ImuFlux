package com.sarmidev.imuflux.backoffice.config

/**
 * Wasm loads Firebase config from [FirebaseConfigBridge], which the web host
 * (`:webApp`) populates from generated build-time values before starting the UI.
 */
actual object PlatformFirebaseConfigLoader {

    private const val DEFAULT_REGION = "europe-west1"
    private const val FUNCTION_NAME = "sendRemoteRecordingCommand"

    private val SETUP_INSTRUCTIONS: String = """
        Firebase configuration missing.

        For the web backoffice, set environment variables at build time:
             IMUFLUX_FIREBASE_API_KEY
             IMUFLUX_FIREBASE_PROJECT_ID

        Or provide desktopApp/local.properties with firebase.apiKey / firebase.projectId
        before running the Gradle generateFirebaseConfig task.
    """.trimIndent()

    actual fun load(): FirebaseConfigResult {
        val apiKey = FirebaseConfigBridge.apiKey.trim()
        val projectId = FirebaseConfigBridge.projectId.trim()
        if (apiKey.isEmpty() || projectId.isEmpty()) {
            return FirebaseConfigResult.Missing(SETUP_INSTRUCTIONS)
        }
        return FirebaseConfigResult.Loaded(
            BackofficeFirebaseConfig(
                apiKey = apiKey,
                projectId = projectId,
                source = FirebaseConfigBridge.source.ifBlank { "FirebaseConfigBridge" },
            ),
        )
    }

    actual fun resolveRemoteCommandsUrl(projectId: String): String =
        "https://$DEFAULT_REGION-$projectId.cloudfunctions.net/$FUNCTION_NAME"
}
