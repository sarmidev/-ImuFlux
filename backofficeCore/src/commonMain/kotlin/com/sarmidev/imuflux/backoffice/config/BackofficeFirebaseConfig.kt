package com.sarmidev.imuflux.backoffice.config

/**
 * Resolved Firebase project configuration for the backoffice admin app.
 *
 * Only the **Web API key** and **project id** are needed — the app authenticates
 * as a normal admin user through the Firebase Identity Toolkit REST API and never
 * embeds a service account.
 */
data class BackofficeFirebaseConfig(
    val apiKey: String,
    val projectId: String,
    /** Where the values were resolved from, for diagnostics/UX. */
    val source: String,
)

sealed interface FirebaseConfigResult {
    data class Loaded(val config: BackofficeFirebaseConfig) : FirebaseConfigResult
    data class Missing(val message: String) : FirebaseConfigResult
}

/**
 * Injectable bridge so wasm can receive Firebase config from the web host
 * without a circular dependency on `:webApp`.
 */
object FirebaseConfigBridge {
    var apiKey: String = ""
    var projectId: String = ""
    var source: String = "unset"
}

expect object PlatformFirebaseConfigLoader {
    fun load(): FirebaseConfigResult
    fun resolveRemoteCommandsUrl(projectId: String): String
}
