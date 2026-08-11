package com.sarmidev.imuflux.backoffice.config

import java.io.File
import java.util.Properties

/**
 * Loads [BackofficeFirebaseConfig] from, in priority order:
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
actual object PlatformFirebaseConfigLoader {

    const val ENV_API_KEY = "IMUFLUX_FIREBASE_API_KEY"
    const val ENV_PROJECT_ID = "IMUFLUX_FIREBASE_PROJECT_ID"
    const val ENV_REMOTE_URL = "IMUFLUX_REMOTE_COMMANDS_URL"
    const val LOCAL_REMOTE_URL_KEY = "remoteCommands.url"
    const val DEFAULT_REGION = "europe-west1"
    const val FUNCTION_NAME = "sendRemoteRecordingCommand"

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

    actual fun load(): FirebaseConfigResult = load(
        env = System::getenv,
        localProperties = File("desktopApp/local.properties"),
        googleServicesJson = File("app/google-services.json"),
    )

    fun load(
        env: (String) -> String?,
        localProperties: File = File("desktopApp/local.properties"),
        googleServicesJson: File = File("app/google-services.json"),
    ): FirebaseConfigResult {
        val envApiKey = env(ENV_API_KEY)?.trim().orEmpty()
        val envProjectId = env(ENV_PROJECT_ID)?.trim().orEmpty()
        if (envApiKey.isNotEmpty() && envProjectId.isNotEmpty()) {
            return FirebaseConfigResult.Loaded(
                BackofficeFirebaseConfig(envApiKey, envProjectId, "environment variables"),
            )
        }

        readLocalProperties(localProperties)?.let { return FirebaseConfigResult.Loaded(it) }
        readGoogleServices(googleServicesJson)?.let { return FirebaseConfigResult.Loaded(it) }
        return FirebaseConfigResult.Missing(SETUP_INSTRUCTIONS)
    }

    actual fun resolveRemoteCommandsUrl(projectId: String): String =
        resolveRemoteCommandsUrl(
            projectId = projectId,
            env = System::getenv,
            localProperties = File("desktopApp/local.properties"),
        )

    fun resolveRemoteCommandsUrl(
        projectId: String,
        env: (String) -> String?,
        localProperties: File = File("desktopApp/local.properties"),
    ): String {
        env(ENV_REMOTE_URL)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        readLocalRemoteUrl(localProperties)?.let { return it }
        return "https://$DEFAULT_REGION-$projectId.cloudfunctions.net/$FUNCTION_NAME"
    }

    private fun readLocalProperties(file: File): BackofficeFirebaseConfig? {
        if (!file.exists()) return null
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return null }
        val apiKey = props.getProperty("firebase.apiKey")?.trim().orEmpty()
        val projectId = props.getProperty("firebase.projectId")?.trim().orEmpty()
        if (apiKey.isEmpty() || projectId.isEmpty()) return null
        return BackofficeFirebaseConfig(apiKey, projectId, file.path)
    }

    private fun readLocalRemoteUrl(file: File): String? {
        if (!file.exists()) return null
        val props = Properties()
        runCatching { file.inputStream().use(props::load) }.getOrElse { return null }
        return props.getProperty(LOCAL_REMOTE_URL_KEY)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun readGoogleServices(file: File): BackofficeFirebaseConfig? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrElse { return null }
        val projectId = PROJECT_ID_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val apiKey = API_KEY_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (apiKey.isEmpty() || projectId.isEmpty()) return null
        return BackofficeFirebaseConfig(apiKey, projectId, "${file.path} (dev fallback)")
    }

    private val PROJECT_ID_REGEX = Regex("\"project_id\"\\s*:\\s*\"([^\"]+)\"")
    private val API_KEY_REGEX = Regex("\"current_key\"\\s*:\\s*\"([^\"]+)\"")
}
