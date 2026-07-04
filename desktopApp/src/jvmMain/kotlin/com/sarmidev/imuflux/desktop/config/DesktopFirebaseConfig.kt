package com.sarmidev.imuflux.desktop.config

import java.io.File
import java.util.Properties

/**
 * Resolved Firebase project configuration for the desktop admin app.
 *
 * Only the **Web API key** and **project id** are needed — the desktop app
 * authenticates as a normal admin user through the Firebase Identity Toolkit
 * REST API and never embeds a service account.
 */
data class DesktopFirebaseConfig(
    val apiKey: String,
    val projectId: String,
    /** Where the values were resolved from, for diagnostics/UX. */
    val source: String,
)

/**
 * Loads [DesktopFirebaseConfig] from, in priority order:
 *
 *  1. Environment variables `IMUFLUX_FIREBASE_API_KEY` / `IMUFLUX_FIREBASE_PROJECT_ID`.
 *  2. A local, non-versioned `desktopApp/local.properties` file with keys
 *     `firebase.apiKey` and `firebase.projectId`.
 *  3. Development fallback: the app's `app/google-services.json` (read-only, never
 *     modified). This is only meant to smooth local development on the same machine.
 *
 * No credentials are ever committed: `desktopApp/local.properties` is git-ignored
 * and `google-services.json` is only read as a last-resort dev convenience.
 */
object DesktopFirebaseConfigLoader {

    const val ENV_API_KEY = "IMUFLUX_FIREBASE_API_KEY"
    const val ENV_PROJECT_ID = "IMUFLUX_FIREBASE_PROJECT_ID"

    /** Human-readable guidance shown when configuration is missing. */
    val SETUP_INSTRUCTIONS: String = """
        Firebase configuration missing.

        Configure it with either:

        1) Environment variables (recommended):
             export $ENV_API_KEY="your-web-api-key"
             export $ENV_PROJECT_ID="your-project-id"

        2) A local file desktopApp/local.properties (git-ignored):
             firebase.apiKey=your-web-api-key
             firebase.projectId=your-project-id
    """.trimIndent()

    sealed interface Result {
        data class Loaded(val config: DesktopFirebaseConfig) : Result
        data class Missing(val message: String) : Result
    }

    fun load(
        env: (String) -> String? = System::getenv,
        localProperties: File = File("desktopApp/local.properties"),
        googleServicesJson: File = File("app/google-services.json"),
    ): Result {
        // 1) Environment variables.
        val envApiKey = env(ENV_API_KEY)?.trim().orEmpty()
        val envProjectId = env(ENV_PROJECT_ID)?.trim().orEmpty()
        if (envApiKey.isNotEmpty() && envProjectId.isNotEmpty()) {
            return Result.Loaded(
                DesktopFirebaseConfig(envApiKey, envProjectId, "environment variables"),
            )
        }

        // 2) Local, non-versioned properties file.
        readLocalProperties(localProperties)?.let { return Result.Loaded(it) }

        // 3) Development fallback: reuse the Android app's google-services.json.
        readGoogleServices(googleServicesJson)?.let { return Result.Loaded(it) }

        return Result.Missing(SETUP_INSTRUCTIONS)
    }

    private fun readLocalProperties(file: File): DesktopFirebaseConfig? {
        if (!file.exists()) return null
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return null }
        val apiKey = props.getProperty("firebase.apiKey")?.trim().orEmpty()
        val projectId = props.getProperty("firebase.projectId")?.trim().orEmpty()
        if (apiKey.isEmpty() || projectId.isEmpty()) return null
        return DesktopFirebaseConfig(apiKey, projectId, file.path)
    }

    private fun readGoogleServices(file: File): DesktopFirebaseConfig? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrElse { return null }
        val projectId = PROJECT_ID_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val apiKey = API_KEY_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (apiKey.isEmpty() || projectId.isEmpty()) return null
        return DesktopFirebaseConfig(apiKey, projectId, "${file.path} (dev fallback)")
    }

    private val PROJECT_ID_REGEX = Regex("\"project_id\"\\s*:\\s*\"([^\"]+)\"")
    private val API_KEY_REGEX = Regex("\"current_key\"\\s*:\\s*\"([^\"]+)\"")
}
